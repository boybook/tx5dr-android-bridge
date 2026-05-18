package com.tx5dr.bridge

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlin.math.max
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object BridgeRuntime {
    private const val TAG = "RuntimeManager"
    private const val DEFAULT_MANIFEST_URL = "https://dl.tx5dr.com/tx-5dr/android-runtime/nightly/latest.json"
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(BridgeStatus) -> Unit>()

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private var prootProcess: Process? = null
    private var healthRunning = false
    private var status = BridgeStatus()

    lateinit var paths: RuntimePaths
        private set

    fun init(context: Context) {
        app = context.applicationContext
        prefs = app.getSharedPreferences("bridge", Context.MODE_PRIVATE)
        paths = RuntimePaths(app.filesDir, File(app.applicationInfo.nativeLibraryDir))
        paths.ensureDirs()
        NetworkAccessProvider.startWatching(app, paths.androidNetworkAccessFile)
        updateStatus(detectInitialStatus())
    }

    fun addStatusListener(listener: (BridgeStatus) -> Unit) {
        listeners.add(listener)
        listener(status)
    }

    fun removeStatusListener(listener: (BridgeStatus) -> Unit) {
        listeners.remove(listener)
    }

    fun getManifestUrl(): String = prefs.getString("manifestUrl", DEFAULT_MANIFEST_URL) ?: DEFAULT_MANIFEST_URL

    fun setManifestUrl(url: String) {
        prefs.edit().putString("manifestUrl", url.trim()).apply()
    }

    fun getAdminToken(): String? {
        val candidates = listOf(
            File(paths.dataDir, "config/.admin-token"),
            File(paths.dataDir, ".admin-token")
        )
        return candidates.firstNotNullOfOrNull { file ->
            try {
                file.readText().trim().takeIf { it.isNotEmpty() }
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun refreshNetworkAccess(): NetworkAccessProvider.Snapshot {
        return NetworkAccessProvider.writeSnapshot(app, paths.androidNetworkAccessFile)
    }

    fun installOrUpdate() {
        executor.execute {
            try {
                LogBus.i(TAG, "Install/update started")
                updateStatus(status.copy(runtimeState = RuntimeState.Installing, error = null, progress = "Preparing base runtime"))
                ensureBaseRuntime()
                installTx5drRelease(getManifestUrl())
                updateStatus(detectInitialStatus().copy(error = null, progress = "Install/update complete"))
            } catch (error: Throwable) {
                LogBus.e(TAG, "Install/update failed", error)
                updateStatus(status.copy(runtimeState = RuntimeState.Error, error = error.message))
            }
        }
    }

    fun start() {
        executor.execute {
            if (prootProcess?.isAlive == true) return@execute
            try {
                ensureBaseRuntime()
                refreshNetworkAccess()
                val missing = missingReleaseFiles(paths.currentLink)
                require(missing.isEmpty()) { "TX-5DR is not installed or is incomplete (${missing.joinToString(", ")}). Install from manifest first." }
                ensureProotVisibleCurrentLink()
                ensureHostRuntimeEnvironment()
                updateStatus(status.copy(runtimeState = RuntimeState.Starting, error = null))
                val process = newProotProcess(buildRuntimeCommand())
                    .directory(paths.workDir)
                    .redirectErrorStream(true)
                    .start()
                prootProcess = process
                pipeOutput(process, "proot")
                LogBus.i(TAG, "Started PRoot runtime")
                updateStatus(status.copy(runtimeState = RuntimeState.Running))
                startHealthLoop()
                val exitCode = process.waitFor()
                LogBus.w(TAG, "PRoot runtime exited with code $exitCode")
                prootProcess = null
                if (status.runtimeState != RuntimeState.Stopping) {
                    updateStatus(status.copy(runtimeState = RuntimeState.Stopped, serverHealthy = false, webHealthy = false))
                }
            } catch (error: Throwable) {
                LogBus.e(TAG, "Runtime start failed", error)
                prootProcess = null
                updateStatus(status.copy(runtimeState = RuntimeState.Error, serverHealthy = false, webHealthy = false, error = error.message))
            }
        }
    }

    fun stop() {
        executor.execute {
            updateStatus(status.copy(runtimeState = RuntimeState.Stopping))
            val process = prootProcess
            if (process != null && process.isAlive) {
                process.destroy()
                if (!process.waitFor(8, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            }
            prootProcess = null
            healthRunning = false
            updateStatus(status.copy(runtimeState = RuntimeState.Stopped, serverHealthy = false, webHealthy = false))
        }
    }

    fun startLinuxMicSide() {
        executor.execute {
            try {
                ensureBaseRuntime()
                ensureHostRuntimeEnvironment()
                val cmd = buildProotBaseCommand() + listOf("/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "PULSE_SERVER=tcp:127.0.0.1:4718", "/bin/bash", "-lc", MIC_SCRIPT)
                val p = newProotProcess(cmd).redirectErrorStream(true).start()
                pipeOutput(p, "mic-linux")
                LogBus.i(TAG, "Started Linux mic injector side")
            } catch (error: Throwable) {
                LogBus.e(TAG, "Linux mic side failed", error)
            }
        }
    }

    fun stopLinuxMicSide() {
        executor.execute {
            try {
                ensureHostRuntimeEnvironment()
                val cmd = buildProotBaseCommand() + listOf("/usr/bin/env", "-i", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "/bin/bash", "-lc", "pkill -f tx5dr-android-mic-injector || true; pactl list short modules | awk '/AndroidSink|AndroidMic/ {print \\$1}' | xargs -r -n1 pactl unload-module || true")
                newProotProcess(cmd).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
            } catch (error: Throwable) {
                LogBus.e(TAG, "Stop Linux mic side failed", error)
            }
        }
    }

    private fun detectInitialStatus(): BridgeStatus {
        val installed = isReleaseUsable(paths.currentLink)
        val version = prefs.getString("installedVersion", null)
        return BridgeStatus(runtimeState = if (installed) RuntimeState.Installed else RuntimeState.NotInstalled, installedVersion = version)
    }

    private fun ensureBaseRuntime() {
        if (!paths.rootfsReady.exists()) {
            val archive = paths.cacheDir.resolve("rootfs-debian13-arm64.tgz")
            logProgress("Copying embedded Debian 13 rootfs asset")
            copyAsset("rootfs/rootfs-debian13-arm64.tgz", archive, executable = false)
            logProgress("Extracting rootfs archive; this can take several minutes")
            val progress = ExtractionProgress("Extracting rootfs", everyEntries = 5000, everyMs = 3000)
            TarZstExtractor.extract(archive, paths.rootfsDir, { name -> progress.onEntry(name) }, paths.zstdFile)
            paths.rootfsReady.writeText(System.currentTimeMillis().toString())
            logProgress("Base Debian rootfs ready (${progress.count} entries)")
        } else {
            LogBus.i(TAG, "Base Debian rootfs already installed")
        }
    }

    private fun installTx5drRelease(manifestUrl: String) {
        logProgress("Fetching release manifest: $manifestUrl")
        val manifest = JSONObject(downloadText(manifestUrl))
        LogBus.i(TAG, "Manifest parsed: version=${manifest.optString("version", "unknown")} channel=${manifest.optString("channel", "unknown")}")
        val asset = selectAndroidAsset(manifest)
        val name = asset.getString("name")
        val url = asset.optString("url_cn", asset.optString("url", asset.optString("url_oss")))
        val sha256 = asset.getString("sha256").lowercase()
        val expectedSize = asset.optLong("size", -1L)
        require(url.startsWith("https://") || url.startsWith("http://")) { "Invalid artifact URL in manifest" }
        val archive = paths.cacheDir.resolve(name)
        LogBus.i(TAG, "Selected artifact: $name (${formatBytes(expectedSize)}), sha256=$sha256")
        downloadFile(url, archive)
        if (expectedSize > 0 && archive.length() != expectedSize) {
            error("Downloaded size mismatch: expected ${formatBytes(expectedSize)}, got ${formatBytes(archive.length())}")
        }
        logProgress("Verifying sha256: $name")
        val actual = sha256(archive)
        require(actual == sha256) { "sha256 mismatch: expected $sha256, got $actual" }
        logProgress("sha256 verified")
        val version = manifest.optString("version", manifest.optString("commit", "unknown"))
        val releaseDir = paths.releasesDir.resolve(version.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val tmpDir = paths.releasesDir.resolve(".${releaseDir.name}.tmp")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        logProgress("Extracting TX-5DR release ${releaseDir.name}")
        val progress = ExtractionProgress("Extracting TX-5DR", everyEntries = 5000, everyMs = 3000)
        TarZstExtractor.extract(archive, tmpDir, { entry -> progress.onEntry(entry) }, zstdHelper = paths.zstdFile)
        logProgress("Extracted TX-5DR release ${releaseDir.name} (${progress.count} entries)")
        val missing = missingReleaseFiles(tmpDir)
        require(missing.isEmpty()) { "Extracted TX-5DR release is missing expected runtime files: ${missing.joinToString(", ")}" }
        releaseDir.deleteRecursively()
        logProgress("Activating release ${releaseDir.name}")
        require(tmpDir.renameTo(releaseDir)) { "Failed to move release into place" }
        switchCurrent(releaseDir)
        LogBus.i(TAG, "Current release points to ${releaseDir.absolutePath}")
        prefs.edit().putString("installedVersion", version).apply()
        logProgress("Installed TX-5DR release $version")
    }

    private fun selectAndroidAsset(manifest: JSONObject): JSONObject {
        val assets = manifest.getJSONArray("assets")
        findAsset(assets, platformOnly = true, packageTypes = setOf("tar.gz"))?.let { return it }
        findAsset(assets, platformOnly = false, packageTypes = setOf("tar.gz"))?.let { return it }
        findAsset(assets, platformOnly = true, packageTypes = setOf("tar.zst"))?.let { return it }
        findAsset(assets, platformOnly = false, packageTypes = setOf("tar.zst"))?.let { return it }
        error("Manifest has no android arm64 tar.gz/tar.zst asset")
    }

    private fun findAsset(assets: org.json.JSONArray, platformOnly: Boolean, packageTypes: Set<String>): JSONObject? {
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (platformOnly && (asset.optString("platform") != "android" || asset.optString("arch") != "arm64")) continue
            val packageType = asset.optString("package_type")
            val name = asset.optString("name")
            if ("tar.gz" in packageTypes && (packageType == "tar.gz" || name.endsWith(".tar.gz") || name.endsWith(".tgz"))) return asset
            if ("tar.zst" in packageTypes && (packageType == "tar.zst" || name.endsWith(".tar.zst"))) return asset
        }
        return null
    }

    private fun switchCurrent(releaseDir: File) {
        deletePathOrSymlink(paths.currentLink)
        try {
            java.nio.file.Files.createSymbolicLink(paths.currentLink.toPath(), File("releases/${releaseDir.name}").toPath())
        } catch (_: Throwable) {
            paths.currentLink.mkdirs()
            releaseDir.copyRecursively(paths.currentLink, overwrite = true)
        }
    }

    private fun ensureProotVisibleCurrentLink() {
        if (!java.nio.file.Files.isSymbolicLink(paths.currentLink.toPath())) return
        val target = try {
            java.nio.file.Files.readSymbolicLink(paths.currentLink.toPath())
        } catch (_: Throwable) {
            return
        }
        if (!target.isAbsolute) return
        val releaseDir = paths.currentLink.canonicalFile
        if (!releaseDir.exists() || releaseDir.parentFile?.canonicalPath != paths.releasesDir.canonicalPath) return
        LogBus.i(TAG, "Rewriting current symlink for PRoot visibility: ${releaseDir.name}")
        switchCurrent(releaseDir)
    }

    private fun isReleaseUsable(releaseDir: File): Boolean {
        return missingReleaseFiles(releaseDir).isEmpty()
    }

    private fun missingReleaseFiles(releaseDir: File): List<String> {
        if (!releaseDir.exists()) return listOf(releaseDir.absolutePath)
        return listOf(
            "packages/server/dist" to File(releaseDir, "packages/server/dist").exists(),
            "packages/client-tools/src/proxy.js" to File(releaseDir, "packages/client-tools/src/proxy.js").isFile,
            "packages/web/dist" to File(releaseDir, "packages/web/dist").exists()
        ).filterNot { it.second }.map { it.first }
    }

    private fun deletePathOrSymlink(path: File) {
        try {
            if (java.nio.file.Files.isSymbolicLink(path.toPath())) {
                java.nio.file.Files.deleteIfExists(path.toPath())
                return
            }
        } catch (_: Throwable) {
        }
        path.deleteRecursively()
    }

    private fun buildRuntimeCommand(): List<String> {
        val script = """
set -e
mkdir -p /opt/tx5dr-data/logs /opt/tx5dr-data/runtime /tmp/pulse
export HOME=/root
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export NODE_ENV=production
export PORT=4000
export HOST=127.0.0.1
export TX5DR_RUNTIME_FLAVOR=android-bridge
export TX5DR_SERVER_HOST=127.0.0.1
export TX5DR_NETWORK_ACCESS_FILE=/opt/tx5dr-data/runtime/android-network-access.json
export TX5DR_DATA_DIR=/opt/tx5dr-data
export TX5DR_CONFIG_DIR=/opt/tx5dr-data/config
export TX5DR_LOGS_DIR=/opt/tx5dr-data/logs
export TX5DR_CACHE_DIR=/opt/tx5dr-data/cache
export PULSE_SERVER=tcp:127.0.0.1:4718
export TX5DR_AUDIO_BACKEND=pulse
pulseaudio --exit-idle-time=-1 --daemonize=yes --log-target=stderr --load='module-native-protocol-tcp auth-ip-acl=127.0.0.1 port=4718 auth-anonymous=1' || true
cd /opt/tx5dr/current
node /opt/tx5dr/current/packages/server/dist/scripts/server-launcher.js /opt/tx5dr/current/packages/server/dist/index.js 2>&1 | sed -u 's/^/[server] /' &
server_pid=$!
PORT=8076 HOST=0.0.0.0 TARGET=http://127.0.0.1:4000 STATIC_DIR=/opt/tx5dr/current/packages/web/dist TX5DR_CLIENT_TOOLS_READY_FILE=/opt/tx5dr-data/runtime/client-tools-ready.json TX5DR_CLIENT_TOOLS_LOG_FILE=/opt/tx5dr-data/logs/client-tools.log node packages/client-tools/src/proxy.js 2>&1 | sed -u 's/^/[client-tools] /' &
client_pid=$!
trap 'kill ${'$'}server_pid ${'$'}client_pid 2>/dev/null || true; wait || true; exit 0' TERM INT
wait -n ${'$'}server_pid ${'$'}client_pid
""".trimIndent()
        return buildProotBaseCommand() + listOf("/usr/bin/env", "-i", "/bin/bash", "-lc", script)
    }

    private fun buildProotBaseCommand(): List<String> = listOf(
        paths.prootFile.absolutePath,
        "--rootfs=${paths.rootfsDir.absolutePath}",
        "--pwd=/",
        "--bind=${paths.dataDir.absolutePath}:/opt/tx5dr-data",
        "--bind=${paths.txDir.absolutePath}:/opt/tx5dr",
        "--bind=/proc:/proc",
        "--bind=/dev:/dev",
        "--bind=/sys:/sys",
        "--kill-on-exit",
        "--link2symlink"
    )

    private fun newProotProcess(command: List<String>): ProcessBuilder {
        val processBuilder = ProcessBuilder(command)
        val env = processBuilder.environment()
        val existingLdPath = env["LD_LIBRARY_PATH"].orEmpty()
        env["LD_LIBRARY_PATH"] = listOf(paths.hostLibDir.absolutePath, paths.nativeLibDir.absolutePath, existingLdPath)
            .filter { it.isNotBlank() }
            .joinToString(":")
        env["PROOT_LOADER"] = paths.prootLoader64.absolutePath
        env["PROOT_LOADER_32"] = paths.prootLoader32.absolutePath
        env["PROOT_TMP_DIR"] = paths.prootTmpDir.absolutePath
        return processBuilder
    }

    private fun ensureHostRuntimeEnvironment() {
        paths.ensureDirs()
        require(paths.prootFile.exists()) { "Missing bundled PRoot executable: ${paths.prootFile.absolutePath}. Run tools/fetch-proot.sh and rebuild the APK." }
        require(paths.tallocLib.exists()) { "Missing bundled PRoot dependency libtalloc.so. Run tools/fetch-proot.sh and rebuild the APK." }
        require(paths.prootLoader64.exists()) { "Missing bundled PRoot loader: ${paths.prootLoader64.absolutePath}. Run tools/fetch-proot.sh and rebuild the APK." }
        require(paths.prootLoader32.exists()) { "Missing bundled PRoot 32-bit loader: ${paths.prootLoader32.absolutePath}. Run tools/fetch-proot.sh and rebuild the APK." }
        ensureVersionedLibrary("libtalloc.so.2", paths.tallocLib)
        if (paths.zstdLib.exists()) ensureVersionedLibrary("libzstd.so.1", paths.zstdLib)
    }

    private fun ensureVersionedLibrary(name: String, target: File) {
        val link = File(paths.hostLibDir, name)
        val currentTarget = try {
            java.nio.file.Files.readSymbolicLink(link.toPath()).toString()
        } catch (_: Throwable) {
            null
        }
        if (link.exists() && currentTarget == target.absolutePath) return
        try {
            if (java.nio.file.Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                java.nio.file.Files.delete(link.toPath())
            }
        } catch (_: Throwable) {
            link.delete()
        }
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (_: Throwable) {
            target.copyTo(link, overwrite = true)
        }
    }

    private fun copyAsset(assetName: String, target: File, executable: Boolean) {
        target.parentFile?.mkdirs()
        try {
            app.assets.open(assetName).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
        } catch (error: Throwable) {
            throw IllegalStateException("Missing required APK asset: $assetName. Run tools/build-rootfs.sh and tools/fetch-proot.sh first.", error)
        }
        target.setReadable(true, false)
        target.setWritable(true, true)
        target.setExecutable(executable, false)
    }

    private fun downloadText(url: String): String {
        val conn = openHttp(url, connectTimeoutMs = 15000, readTimeoutMs = 30000)
        try {
            val code = conn.responseCode
            val length = conn.contentLengthLong
            LogBus.i(TAG, "Manifest HTTP $code, contentLength=${formatBytes(length)}, contentType=${conn.contentType ?: "unknown"}")
            if (code !in 200..299) throw httpError("manifest", url, conn, code)
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            LogBus.i(TAG, "Manifest downloaded: ${formatBytes(text.toByteArray().size.toLong())}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFile(url: String, target: File) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()
        val conn = openHttp(url, connectTimeoutMs = 20000, readTimeoutMs = 120000)
        try {
            val code = conn.responseCode
            val total = conn.contentLengthLong
            LogBus.i(TAG, "Artifact HTTP $code, contentLength=${formatBytes(total)}, contentType=${conn.contentType ?: "unknown"}")
            if (code !in 200..299) throw httpError("artifact", url, conn, code)

            val startedAt = System.currentTimeMillis()
            var downloaded = 0L
            var lastLogAt = 0L
            var lastLogBytes = 0L
            var lastPercent = -1
            logProgress("Downloading ${target.name}: 0 B/${formatBytes(total)}")
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(1024 * 256)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else -1
                        val shouldLog = when {
                            total > 0 && percent >= 0 && percent >= lastPercent + 2 -> true
                            downloaded - lastLogBytes >= 8L * 1024L * 1024L -> true
                            now - lastLogAt >= 2000L -> true
                            else -> false
                        }
                        if (shouldLog) {
                            lastPercent = max(lastPercent, percent)
                            lastLogAt = now
                            lastLogBytes = downloaded
                            val elapsedSeconds = max(1L, (now - startedAt) / 1000L)
                            val speed = downloaded / elapsedSeconds
                            logProgress("Downloading ${target.name}: ${formatBytes(downloaded)}/${formatBytes(total)}${formatPercent(percent)} at ${formatBytes(speed)}/s")
                        }
                    }
                }
            }
            target.delete()
            require(part.renameTo(target)) { "Failed to move downloaded file into cache" }
            logProgress("Download complete: ${target.name} (${formatBytes(target.length())})")
        } finally {
            conn.disconnect()
            if (part.exists() && !target.exists()) part.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        var processed = 0L
        var lastLogAt = 0L
        var lastPercent = -1
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
                processed += n
                val now = System.currentTimeMillis()
                val percent = if (total > 0) ((processed * 100) / total).toInt() else -1
                if ((percent >= 0 && percent >= lastPercent + 10) || now - lastLogAt >= 3000L) {
                    lastPercent = max(lastPercent, percent)
                    lastLogAt = now
                    logProgress("Verifying sha256: ${formatBytes(processed)}/${formatBytes(total)}${formatPercent(percent)}")
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun openHttp(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TX-5DR-Android-Bridge/0.1")
        }
    }

    private fun httpError(kind: String, url: String, conn: HttpURLConnection, code: Int): IllegalStateException {
        val body = try {
            (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText().take(500) }.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        return IllegalStateException("HTTP $code while downloading $kind: $url${if (body.isBlank()) "" else " body=${body.replace('\n', ' ')}"}")
    }

    private fun logProgress(message: String) {
        LogBus.i(TAG, message)
        updateStatus(status.copy(progress = message))
    }

    private class ExtractionProgress(
        private val label: String,
        private val everyEntries: Int,
        private val everyMs: Long,
    ) {
        var count = 0
            private set
        private var lastLogCount = 0
        private var lastLogAt = 0L

        fun onEntry(name: String) {
            count += 1
            val now = System.currentTimeMillis()
            val entryIntervalReached = count - lastLogCount >= everyEntries
            val timeIntervalReached = now - lastLogAt >= everyMs && count != lastLogCount
            if (entryIntervalReached || timeIntervalReached) {
                lastLogCount = count
                lastLogAt = now
                logProgress("$label: $count entries; latest ${compactPath(name)}")
            }
        }

        private fun compactPath(path: String): String {
            if (path.length <= 96) return path
            return "..." + path.takeLast(93)
        }
    }

    private fun formatPercent(percent: Int): String = if (percent >= 0) " ($percent%)" else ""

    private fun formatBytes(value: Long): String {
        if (value < 0) return "unknown"
        val units = arrayOf("B", "KiB", "MiB", "GiB")
        var size = value.toDouble()
        var unit = 0
        while (size >= 1024.0 && unit < units.lastIndex) {
            size /= 1024.0
            unit += 1
        }
        return if (unit == 0) "${value} ${units[unit]}" else String.format(java.util.Locale.US, "%.1f %s", size, units[unit])
    }

    private fun pipeOutput(process: Process, prefix: String) {
        Thread {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { LogBus.i(prefix, it) } }
        }.start()
    }

    private fun startHealthLoop() {
        if (healthRunning) return
        healthRunning = true
        Thread {
            while (healthRunning && prootProcess?.isAlive == true) {
                val server = isHealthy("http://127.0.0.1:4000/api/hello")
                val web = isHealthy("http://127.0.0.1:8076/")
                updateStatus(status.copy(serverHealthy = server, webHealthy = web))
                Thread.sleep(3000)
            }
        }.start()
    }

    private fun isHealthy(url: String): Boolean = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 1000
        conn.readTimeout = 1500
        conn.responseCode in 200..399
    } catch (_: Throwable) { false }

    private fun updateStatus(next: BridgeStatus) {
        status = next
        main.post { listeners.forEach { it(next) } }
    }

    data class RuntimePaths(val base: File, val nativeLibDir: File) {
        val workDir = File(base, "runtime")
        val cacheDir = File(workDir, "cache")
        val hostLibDir = File(workDir, "host-libs")
        val prootTmpDir = File(workDir, "proot-tmp")
        val rootfsDir = File(workDir, "rootfs")
        val dataDir = File(workDir, "tx5dr-data")
        val androidNetworkAccessFile = File(dataDir, "runtime/android-network-access.json")
        val txDir = File(workDir, "tx5dr")
        val releasesDir = File(txDir, "releases")
        val currentLink = File(txDir, "current")
        val prootFile = File(nativeLibDir, "libproot_exec.so")
        val prootLoader64 = File(nativeLibDir, "libproot_loader.so")
        val prootLoader32 = File(nativeLibDir, "libproot_loader32.so")
        val tallocLib = File(nativeLibDir, "libtalloc.so")
        val zstdFile = File(nativeLibDir, "libzstd_exec.so")
        val zstdLib = File(nativeLibDir, "libzstd.so")
        val rootfsReady = File(rootfsDir, ".tx5dr-rootfs-ready")
        fun ensureDirs() {
            listOf(workDir, cacheDir, hostLibDir, prootTmpDir, rootfsDir, dataDir, releasesDir).forEach { it.mkdirs() }
        }
    }

    private const val MIC_SCRIPT = """
set -e
export PULSE_SERVER=tcp:127.0.0.1:4718
pactl load-module module-null-sink sink_name=AndroidSink sink_properties=device.description=Android_Audio_Stream || true
pactl load-module module-remap-source master=AndroidSink.monitor source_name=AndroidMic source_properties=device.description=Android_Virtual_Mic || true
pkill -f tx5dr-android-mic-injector || true
nohup tx5dr-android-mic-injector 127.0.0.1 4719 AndroidSink >/opt/tx5dr-data/logs/mic-injector.log 2>&1 &
"""
}
