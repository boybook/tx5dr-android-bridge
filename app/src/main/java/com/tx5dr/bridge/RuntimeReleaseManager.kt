package com.tx5dr.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max

class RuntimeChannelEmptyException : IllegalStateException("update_channel_empty")
private class ReleaseHttpException(val statusCode: Int, message: String) : IllegalStateException(message)

object RuntimeReleaseManager {
    private const val TAG = "RuntimeRelease"
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var activeDownload: HttpURLConnection? = null
    fun cancelInstall() { cancelled.set(true); activeDownload?.disconnect() }
    fun wasCancelled(): Boolean = cancelled.get()
    private fun checkCancelled() { if (cancelled.get()) throw java.util.concurrent.CancellationException("Update cancelled") }
    private val previews = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun validatedManifest(url: String, text: String): JSONObject {
        val manifest = JSONObject(text)
        require(manifest.getString("product") == "android-runtime") { "Wrong update product" }
        val channel = manifest.getString("channel")
        require(channel in setOf("release", "nightly")) { "Invalid channel" }
        if (url.contains("/release/") || url.contains("/nightly/")) require(url.contains("/$channel/")) { "Channel mismatch" }
        require(manifest.optInt("schema_version", 1) == 1) { "Unsupported manifest schema" }
        require(manifest.getString("version").matches(Regex("[0-9]+[.][0-9]+[.][0-9]+([-+][A-Za-z0-9.+-]+)?"))) { "Invalid version" }
        require(manifest.getString("commit").matches(Regex("[a-fA-F0-9]{7,40}"))) { "Invalid commit" }
        require(manifest.optLong("minimum_android_version_code", 0) <= BuildConfig.VERSION_CODE) { "Update the Android app before installing this runtime" }
        val asset = selectAndroidAsset(manifest)
        require(asset.getString("name").matches(Regex("[A-Za-z0-9._+-]+"))) { "Invalid asset name" }
        require(asset.getString("sha256").matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid checksum" }
        require(asset.getLong("size") > 0) { "Invalid asset size" }
        return manifest
    }
    private val ROOTFS_REQUIRED_FILES = listOf(
        "etc/os-release",
        "usr/bin/node",
        "usr/local/bin/tx5dr-android-serial-pty",
    )

    fun ensureBaseRuntime(context: Context, paths: RuntimePaths, progress: (InstallProgress) -> Unit) {
        val expectedRootfsSha = readAssetTextOrNull(context, "rootfs/rootfs-debian13-arm64.tgz.sha256")
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (!isBaseRuntimeCurrent(paths, expectedRootfsSha)) {
            progress(InstallProgress(InstallProgressStage.Preparing))
            paths.rootfsDir.deleteRecursively()
            paths.rootfsDir.mkdirs()
            val archive = paths.cacheDir.resolve("rootfs-debian13-arm64.tgz")
            progress(InstallProgress(InstallProgressStage.CopyingBase, artifactName = archive.name))
            copyAsset(context, "rootfs/rootfs-debian13-arm64.tgz", archive, executable = false)
            progress(InstallProgress(InstallProgressStage.ExtractingBase, artifactName = archive.name, entriesDone = 0))
            val extraction = ExtractionProgress(InstallProgressStage.ExtractingBase, archive.name, everyEntries = 5000, everyMs = 3000, progress)
            TarZstExtractor.extract(archive, paths.rootfsDir, { name -> extraction.onEntry(name) }, paths.zstdFile)
            require(isDebian13Rootfs(paths.rootfsDir)) {
                "Base runtime archive is not Debian 13 (trixie)"
            }
            paths.rootfsReady.writeText(expectedRootfsSha.ifBlank { System.currentTimeMillis().toString() })
            progress(InstallProgress(InstallProgressStage.ExtractingBase, artifactName = archive.name, entriesDone = extraction.count))
        } else {
            LogBus.i(TAG, "Base Debian rootfs already installed")
        }
    }

    fun installTx5drRelease(paths: RuntimePaths, manifestUrl: String, expectedVersion: String, beforeActivate: () -> Unit, progress: (InstallProgress) -> Unit): String {
        cancelled.set(false)
        progress(InstallProgress(InstallProgressStage.FetchingManifest))
        val manifest = validatedManifest(manifestUrl, previews.remove(manifestUrl) ?: error("Preview this release again before installing"))
        require(manifest.getString("version") == expectedVersion) { "Update target changed; preview again" }
        LogBus.i(TAG, "Manifest parsed: version=${manifest.optString("version", "unknown")} channel=${manifest.optString("channel", "unknown")}")
        val asset = selectAndroidAsset(manifest)
        val name = asset.getString("name")
        val url = asset.optString("url_cn", asset.optString("url", asset.optString("url_oss")))
        val sha256 = asset.getString("sha256").lowercase()
        val expectedSize = asset.optLong("size", -1L)
        require(url.startsWith("https://")) { "Invalid artifact URL in manifest" }
        val archive = paths.cacheDir.resolve(name)
        LogBus.i(TAG, "Selected artifact: $name (${formatBytes(expectedSize)}), sha256=$sha256")
        try { downloadFile(url, archive, progress) } catch (error: Throwable) {
            checkCancelled()
            val alternate = asset.optString("url_github", asset.optString("url_global"))
            if (!alternate.startsWith("https://") || alternate == url) throw error
            downloadFile(alternate, archive, progress)
        }
        if (expectedSize > 0 && archive.length() != expectedSize) {
            error("Downloaded size mismatch: expected ${formatBytes(expectedSize)}, got ${formatBytes(archive.length())}")
        }
        progress(InstallProgress(InstallProgressStage.Verifying, artifactName = name, bytesDone = 0, bytesTotal = archive.length()))
        val actual = sha256(archive, progress)
        require(actual == sha256) { "sha256 mismatch: expected $sha256, got $actual" }
        progress(InstallProgress(InstallProgressStage.Verifying, artifactName = name, bytesDone = archive.length(), bytesTotal = archive.length()))

        val version = manifest.optString("version", manifest.optString("commit", "unknown"))
        val releaseDir = paths.releasesDir.resolve(version.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" + System.currentTimeMillis())
        val tmpDir = paths.releasesDir.resolve(".${releaseDir.name}.tmp")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        progress(InstallProgress(InstallProgressStage.ExtractingRelease, artifactName = releaseDir.name, entriesDone = 0))
        val extraction = ExtractionProgress(InstallProgressStage.ExtractingRelease, releaseDir.name, everyEntries = 5000, everyMs = 3000, progress)
        TarZstExtractor.extract(archive, tmpDir, { entry -> checkCancelled(); extraction.onEntry(entry) }, zstdHelper = paths.zstdFile)
        progress(InstallProgress(InstallProgressStage.ExtractingRelease, artifactName = releaseDir.name, entriesDone = extraction.count))
        val missing = missingReleaseFiles(tmpDir)
        require(missing.isEmpty()) { "Extracted TX-5DR release is missing expected runtime files: ${missing.joinToString(", ")}" }
        checkCancelled()
        progress(InstallProgress(InstallProgressStage.Activating, artifactName = releaseDir.name))
        beforeActivate()
        require(tmpDir.renameTo(releaseDir)) { "Failed to move release into place" }
        switchCurrent(paths, releaseDir)
        LogBus.i(TAG, "Current release points to ${releaseDir.absolutePath}")
        progress(InstallProgress(InstallProgressStage.Complete, artifactName = version))
        return version
    }

    fun fetchReleasePreview(manifestUrl: String): ReleasePreview {
        val text = try { downloadText(manifestUrl) } catch (error: Throwable) {
            val channel = when {
                manifestUrl == "https://dl.tx5dr.com/tx-5dr/android-runtime/nightly/latest.json" -> "nightly"
                manifestUrl == "https://dl.tx5dr.com/tx-5dr/android-runtime/release/latest.json" -> "release"
                else -> throw error
            }
            try {
                downloadText(if (channel == "nightly") "https://github.com/boybook/tx-5dr/releases/download/nightly-android-runtime/latest.json" else "https://github.com/boybook/tx-5dr/releases/latest/download/android-runtime-manifest.json")
            } catch (fallback: Throwable) {
                if (error is ReleaseHttpException && error.statusCode == 404 && fallback is ReleaseHttpException && fallback.statusCode == 404) throw RuntimeChannelEmptyException()
                throw error
            }
        }
        val manifest = validatedManifest(manifestUrl, text)
        previews[manifestUrl] = text
        val asset = selectAndroidAsset(manifest)
        return ReleasePreview(
            version = manifest.optString("version", manifest.optString("commit", "unknown")),
            name = asset.optString("name", "TX-5DR Android runtime"),
            sizeBytes = asset.optLong("size", -1L),
            url = asset.optString("url_cn", asset.optString("url", asset.optString("url_oss"))),
        )
    }

    fun ensureProotVisibleCurrentLink(paths: RuntimePaths) {
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
        switchCurrent(paths, releaseDir)
    }

    fun isReleaseUsable(releaseDir: File): Boolean = missingReleaseFiles(releaseDir).isEmpty()

    fun missingReleaseFiles(releaseDir: File): List<String> {
        if (!releaseDir.exists()) return listOf(releaseDir.absolutePath)
        return listOf(
            "packages/server/dist" to File(releaseDir, "packages/server/dist").exists(),
            "packages/client-tools/src/proxy.js" to File(releaseDir, "packages/client-tools/src/proxy.js").isFile,
            "packages/web/dist" to File(releaseDir, "packages/web/dist").exists(),
        ).filterNot { it.second }.map { it.first }
    }

    private fun isBaseRuntimeCurrent(paths: RuntimePaths, expectedRootfsSha: String): Boolean {
        if (!paths.rootfsReady.exists()) return false
        if (expectedRootfsSha.isNotBlank()) {
            val installed = try { paths.rootfsReady.readText().trim() } catch (_: Throwable) { "" }
            if (installed != expectedRootfsSha) return false
        }
        return ROOTFS_REQUIRED_FILES.all { File(paths.rootfsDir, it).exists() } && isDebian13Rootfs(paths.rootfsDir)
    }

    private fun isDebian13Rootfs(rootfsDir: File): Boolean {
        val fields = runCatching {
            File(rootfsDir, "etc/os-release").readLines()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    val key = line.substring(0, separator)
                    val value = line.substring(separator + 1).trim().trim('"')
                    key to value
                }
                .toMap()
        }.getOrNull() ?: return false
        return fields["ID"] == "debian" && fields["VERSION_ID"] == "13" &&
            fields["VERSION_CODENAME"] == "trixie"
    }

    private fun selectAndroidAsset(manifest: JSONObject): JSONObject {
        val assets = manifest.getJSONArray("assets")
        findAsset(assets, platformOnly = true, packageTypes = setOf("tar.gz"))?.let { return it }
        findAsset(assets, platformOnly = true, packageTypes = setOf("tar.zst"))?.let { return it }
        error("Manifest has no android arm64 tar.gz/tar.zst asset")
    }

    private fun findAsset(assets: JSONArray, platformOnly: Boolean, packageTypes: Set<String>): JSONObject? {
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

    fun switchCurrent(paths: RuntimePaths, releaseDir: File) {
        require(releaseDir.canonicalFile.parentFile == paths.releasesDir.canonicalFile) { "Invalid runtime directory" }
        val next = File(paths.txDir, "current.next")
        deletePathOrSymlink(next)
        java.nio.file.Files.createSymbolicLink(next.toPath(), File("releases/${releaseDir.name}").toPath())
        if (paths.currentLink.exists() && !java.nio.file.Files.isSymbolicLink(paths.currentLink.toPath())) {
            require(paths.currentLink.renameTo(File(paths.releasesDir, "legacy-" + System.currentTimeMillis()))) { "Cannot preserve legacy runtime" }
        }
        java.nio.file.Files.move(next.toPath(), paths.currentLink.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
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

    private fun copyAsset(context: Context, assetName: String, target: File, executable: Boolean) {
        target.parentFile?.mkdirs()
        try {
            context.assets.open(assetName).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
        } catch (error: Throwable) {
            throw IllegalStateException("Missing required APK asset: $assetName. Run tools/build-rootfs.sh and tools/fetch-proot.sh first.", error)
        }
        target.setReadable(true, false)
        target.setWritable(true, true)
        target.setExecutable(executable, false)
    }

    private fun readAssetTextOrNull(context: Context, assetName: String): String? = try {
        context.assets.open(assetName).bufferedReader().use { it.readText() }
    } catch (_: Throwable) {
        null
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

    private fun downloadFile(url: String, target: File, progress: (InstallProgress) -> Unit) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()
        val conn = openHttp(url, connectTimeoutMs = 20000, readTimeoutMs = 120000)
        activeDownload = conn
        try {
            checkCancelled()
            val code = conn.responseCode
            val total = conn.contentLengthLong
            LogBus.i(TAG, "Artifact HTTP $code, contentLength=${formatBytes(total)}, contentType=${conn.contentType ?: "unknown"}")
            if (code !in 200..299) throw httpError("artifact", url, conn, code)

            val startedAt = System.currentTimeMillis()
            var downloaded = 0L
            var lastLogAt = 0L
            var lastLogBytes = 0L
            var lastPercent = -1
            progress(InstallProgress(InstallProgressStage.Downloading, artifactName = target.name, bytesDone = 0, bytesTotal = total))
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(1024 * 256)
                    while (true) {
                        checkCancelled()
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
                            progress(InstallProgress(InstallProgressStage.Downloading, artifactName = target.name, bytesDone = downloaded, bytesTotal = total, bytesPerSecond = speed))
                        }
                    }
                }
            }
            target.delete()
            require(part.renameTo(target)) { "Failed to move downloaded file into cache" }
            progress(InstallProgress(InstallProgressStage.Downloading, artifactName = target.name, bytesDone = target.length(), bytesTotal = total))
        } finally {
            activeDownload = null
            conn.disconnect()
            if (part.exists() && !target.exists()) part.delete()
        }
    }

    private fun sha256(file: File, progress: (InstallProgress) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        var processed = 0L
        var lastLogAt = 0L
        var lastPercent = -1
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                checkCancelled()
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
                processed += n
                val now = System.currentTimeMillis()
                val percent = if (total > 0) ((processed * 100) / total).toInt() else -1
                if ((percent >= 0 && percent >= lastPercent + 10) || now - lastLogAt >= 3000L) {
                    lastPercent = max(lastPercent, percent)
                    lastLogAt = now
                    progress(InstallProgress(InstallProgressStage.Verifying, artifactName = file.name, bytesDone = processed, bytesTotal = total))
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
        return ReleaseHttpException(code, "HTTP $code while downloading $kind: $url${if (body.isBlank()) "" else " body=${body.replace('\n', ' ')}"}")
    }

    private class ExtractionProgress(
        private val stage: InstallProgressStage,
        private val artifactName: String,
        private val everyEntries: Int,
        private val everyMs: Long,
        private val progress: (InstallProgress) -> Unit,
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
                progress(InstallProgress(stage, artifactName = artifactName, entriesDone = count, latestEntry = compactPath(name)))
            }
        }

        private fun compactPath(path: String): String {
            if (path.length <= 96) return path
            return "..." + path.takeLast(93)
        }
    }

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
}
