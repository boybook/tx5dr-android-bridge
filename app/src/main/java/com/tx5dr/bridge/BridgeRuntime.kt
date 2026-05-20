package com.tx5dr.bridge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.nio.file.LinkOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object BridgeRuntime {
    private const val TAG = "RuntimeManager"
    private const val DEFAULT_MANIFEST_URL = "https://dl.tx5dr.com/tx-5dr/android-runtime/nightly/latest.json"
    private const val STARTUP_HEALTH_TIMEOUT_MS = 60_000L
    private const val STOP_TIMEOUT_SECONDS = 8L
    private val executor = Executors.newSingleThreadExecutor()
    private val processMonitorExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(BridgeStatus) -> Unit>()

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private var prootProcess: Process? = null
    private var audioBridgeProcess: Process? = null
    private val serialPtyProcesses = mutableMapOf<String, Process>()
    private var healthRunning = false
    private var stopRequested = false
    private var status = BridgeStatus()

    lateinit var paths: RuntimePaths
        private set

    fun init(context: Context) {
        app = context.applicationContext
        prefs = app.getSharedPreferences("bridge", Context.MODE_PRIVATE)
        val externalUserRoot = RuntimeExternalData.selectExternalUserRoot(app)
        paths = RuntimePaths(app.filesDir, File(app.applicationInfo.nativeLibraryDir), externalUserRoot)
        paths.ensureDirs()
        RuntimeExternalData.migrateIfNeeded(paths)
        NetworkAccessProvider.startWatching(app, paths.androidNetworkAccessFile, paths.resolvConfFile)
        AndroidUsbAudioBridge.init(app, paths.androidAudioDevicesFile)
        AndroidUsbAudioBridge.startWatchingDevices(app)
        AndroidUsbSerialBridge.init(app, paths.androidSerialDevicesFile)
        registerUsbHotplugReceiver()
        updateStatus(detectInitialStatus())
    }

    fun addStatusListener(listener: (BridgeStatus) -> Unit) {
        listeners.add(listener)
        runCatching { listener(status) }.onFailure { Log.w(TAG, "Status listener failed", it) }
    }

    fun removeStatusListener(listener: (BridgeStatus) -> Unit) {
        listeners.remove(listener)
    }

    fun getManifestUrl(): String = prefs.getString("manifestUrl", DEFAULT_MANIFEST_URL) ?: DEFAULT_MANIFEST_URL

    fun setManifestUrl(url: String) {
        prefs.edit().putString("manifestUrl", url.trim()).apply()
    }

    fun getPreference(key: String, defaultValue: Boolean): Boolean = prefs.getBoolean(key, defaultValue)

    fun setPreference(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getIntPreference(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)

    fun setIntPreference(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getStringPreference(key: String, defaultValue: String): String = prefs.getString(key, defaultValue) ?: defaultValue

    fun setStringPreference(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun appContext(): Context = app

    fun getAudioBufferTargetMs(): Int =
        coerceAudioBufferTargetMs(getIntPreference(PREF_AUDIO_BUFFER_TARGET_MS, DEFAULT_AUDIO_BUFFER_TARGET_MS))

    fun setAudioBufferTargetMs(value: Int): Int {
        val target = coerceAudioBufferTargetMs(value)
        setIntPreference(PREF_AUDIO_BUFFER_TARGET_MS, target)
        executor.execute {
            runCatching { AndroidUsbAudioBridge.restartIfRunning(app) }
                .onFailure { error -> LogBus.e(TAG, "Audio bridge restart after buffer change failed", error) }
        }
        return target
    }

    fun snapshotStatus(): BridgeStatus = status

    fun externalDataStatus(): ExternalDataStatus = paths.externalDataStatus()

    fun bootstrap() {
        executor.execute {
            try {
                LogBus.i(TAG, "Bootstrap started")
                val abiStatus = RuntimeCompatibility.snapshot(paths.nativeLibDir)
                if (!abiStatus.supported) {
                    val reason = RuntimeCompatibility.unsupportedReason(abiStatus)
                    LogBus.e(TAG, reason)
                    updateStatus(detectInitialStatus().copy(runtimePhase = RuntimePhase.Error, runtimeDetail = reason, error = reason))
                    return@execute
                }
                refreshNetworkAccess()
                if (getPreference(PREF_AUTO_START_BRIDGES, true)) startBridgesInternal()
                val initial = detectInitialStatus()
                val runtimeState = if (prootProcess?.isAlive == true) RuntimeState.Running else initial.runtimeState
                updateStatus(status.copy(
                    runtimeState = runtimeState,
                    runtimePhase = if (runtimeState == RuntimeState.Running) RuntimePhase.WaitingServer else initial.runtimePhase,
                    installedVersion = initial.installedVersion,
                    error = null,
                ))
                if (runtimeState == RuntimeState.Installed && getPreference(PREF_AUTO_START_RUNTIME, true)) {
                    main.post { start() }
                }
            } catch (error: Throwable) {
                LogBus.e(TAG, "Bootstrap failed", error)
                updateStatus(status.copy(runtimeState = RuntimeState.Error, runtimePhase = RuntimePhase.Error, error = error.message))
            }
        }
    }

    fun startBridges() {
        executor.execute { startBridgesInternal() }
    }

    fun stopBridges() {
        executor.execute { stopBridgesInternal() }
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
        return NetworkAccessProvider.writeSnapshot(app, paths.androidNetworkAccessFile, paths.resolvConfFile)
    }

    fun installOrUpdate() {
        executor.execute {
            try {
                LogBus.i(TAG, "Install/update started")
                RuntimeCompatibility.requireSupported(paths.nativeLibDir)
                updateStatus(status.copy(
                    runtimeState = RuntimeState.Installing,
                    runtimePhase = RuntimePhase.PreparingRuntime,
                    error = null,
                    progress = "Preparing base runtime",
                    installProgress = InstallProgress(InstallProgressStage.Preparing),
                ))
                RuntimeReleaseManager.ensureBaseRuntime(app, paths, ::logProgress)
                val version = RuntimeReleaseManager.installTx5drRelease(paths, getManifestUrl(), ::logProgress)
                prefs.edit().putString("installedVersion", version).apply()
                updateStatus(detectInitialStatus().copy(error = null, progress = "Install/update complete", installProgress = null))
            } catch (error: Throwable) {
                LogBus.e(TAG, "Install/update failed", error)
                updateStatus(status.copy(runtimeState = RuntimeState.Error, runtimePhase = RuntimePhase.Error, error = error.message, installProgress = null))
            }
        }
    }

    fun fetchReleasePreview(manifestUrl: String, callback: (ReleasePreview?, String?) -> Unit) {
        executor.execute {
            try {
                val preview = RuntimeReleaseManager.fetchReleasePreview(manifestUrl)
                main.post { callback(preview, null) }
            } catch (error: Throwable) {
                main.post { callback(null, error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    fun start() {
        executor.execute {
            if (prootProcess?.isAlive == true) return@execute
            try {
                stopRequested = false
                healthRunning = false
                RuntimeCompatibility.requireSupported(paths.nativeLibDir)
                updateStatus(status.copy(
                    runtimeState = RuntimeState.Starting,
                    runtimePhase = RuntimePhase.PreparingRuntime,
                    runtimeDetail = "Preparing runtime",
                    serverHealthy = false,
                    webHealthy = false,
                    clientToolsHealthy = false,
                    error = null,
                    progress = "Preparing runtime",
                    installProgress = null,
                    startedAtMs = null,
                    lastExitCode = null,
                    lastExitReason = null,
                ))
                cleanupStaleRuntimeProcesses("before start")
                RuntimeReleaseManager.ensureBaseRuntime(app, paths, ::logProgress)
                refreshNetworkAccess()
                if (getPreference(PREF_AUTO_START_BRIDGES, true)) {
                    updateStatus(status.copy(runtimePhase = RuntimePhase.StartingBridges, runtimeDetail = "Starting hardware bridges", progress = "Starting hardware bridges"))
                    startBridgesInternal()
                }
                val missing = RuntimeReleaseManager.missingReleaseFiles(paths.currentLink)
                require(missing.isEmpty()) { "TX-5DR is not installed or is incomplete (${missing.joinToString(", ")}). Install from manifest first." }
                RuntimeReleaseManager.ensureProotVisibleCurrentLink(paths)
                ensureHostRuntimeEnvironment()
                RuntimeProcessSupport.resetReadyFiles(paths)
                updateStatus(status.copy(runtimeState = RuntimeState.Starting, runtimePhase = RuntimePhase.StartingSupervisor, runtimeDetail = "Starting service process", error = null, progress = "Starting service process"))
                val process = newProotProcess(RuntimeProcessSupport.buildRuntimeCommand(buildProotBaseCommand()))
                    .directory(paths.workDir)
                    .redirectErrorStream(true)
                    .start()
                prootProcess = process
                pipeOutput(process, "proot")
                LogBus.i(TAG, "Started PRoot runtime")
                val now = System.currentTimeMillis()
                updateStatus(status.copy(runtimeState = RuntimeState.Running, runtimePhase = RuntimePhase.WaitingServer, runtimeDetail = "Waiting for TX-5DR API", progress = null, startedAtMs = now))
                startHealthLoop(process, now)
                startSerialPtyProcess()
                monitorRuntimeProcess(process)
            } catch (error: Throwable) {
                LogBus.e(TAG, "Runtime start failed", error)
                prootProcess = null
                healthRunning = false
                stopAuxiliaryProcesses()
                cleanupStaleRuntimeProcesses("after start failure")
                updateStatus(status.copy(
                    runtimeState = RuntimeState.Error,
                    runtimePhase = RuntimePhase.Error,
                    runtimeDetail = "Runtime start failed",
                    serverHealthy = false,
                    webHealthy = false,
                    clientToolsHealthy = false,
                    error = error.message,
                ))
            }
        }
    }

    fun stop() {
        stopRequested = true
        healthRunning = false
        updateStatus(status.copy(
            runtimeState = RuntimeState.Stopping,
            runtimePhase = RuntimePhase.Stopping,
            runtimeDetail = "Stopping background processes",
            serverHealthy = false,
            webHealthy = false,
            clientToolsHealthy = false,
            progress = "Stopping background processes",
            error = null,
        ))
        executor.execute {
            val process = prootProcess
            if (process != null && process.isAlive) {
                process.destroy()
                if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            }
            prootProcess = null
            stopAuxiliaryProcesses()
            cleanupStaleRuntimeProcesses("after stop")
            updateStatus(status.copy(
                runtimeState = RuntimeState.Stopped,
                runtimePhase = RuntimePhase.Idle,
                runtimeDetail = null,
                serverHealthy = false,
                webHealthy = false,
                clientToolsHealthy = false,
                progress = null,
            ))
        }
    }

    private fun startBridgesInternal() {
        AndroidUsbAudioBridge.startIfPermitted(app)
        val serialStarted = AndroidUsbSerialBridge.startAuto(app, paths.androidSerialDevicesFile)
        if (serialStarted) startSerialPtyProcess()
        LogBus.i(TAG, "Bridge bootstrap complete")
    }

    private fun stopBridgesInternal() {
        AndroidUsbAudioBridge.stop()
        AndroidUsbSerialBridge.stop()
        stopSerialPtyProcess()
        LogBus.i(TAG, "Bridge stop complete")
    }

    private fun registerUsbHotplugReceiver() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                if (action != UsbManager.ACTION_USB_DEVICE_ATTACHED && action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                LogBus.i(TAG, "USB hotplug event: $action")
                executor.execute {
                    runCatching {
                        AndroidUsbAudioBridge.refreshDevices(app)
                        AndroidUsbSerialBridge.refreshDevices(app, paths.androidSerialDevicesFile)
                        if (getPreference(PREF_AUTO_START_BRIDGES, true)) startBridgesInternal()
                        startSerialPtyProcess()
                    }.onFailure { error ->
                        LogBus.e(TAG, "USB hotplug handling failed", error)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") app.registerReceiver(receiver, filter)
        } catch (error: Throwable) {
            LogBus.w(TAG, "Unable to register USB hotplug receiver: ${error.message}")
        }
    }

    fun startLinuxMicSide() {
        startLinuxAudioSide()
    }

    fun startLinuxAudioSide() {
        executor.execute {
            LogBus.i(TAG, "Linux audio bridge side is handled by TX-5DR Android Unix socket backend")
        }
    }

    fun stopLinuxMicSide() {
        stopLinuxAudioSide()
    }

    fun stopLinuxAudioSide() {
        executor.execute {
            try {
                audioBridgeProcess?.destroy()
                audioBridgeProcess = null
                ensureHostRuntimeEnvironment()
                val cmd = buildProotBaseCommand() + listOf("/usr/bin/env", "-i", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "/bin/bash", "-lc", "true")
                newProotProcess(cmd).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
            } catch (error: Throwable) {
                LogBus.e(TAG, "Stop Linux audio side failed", error)
            }
        }
    }

    fun startLinuxSerialSide() {
        executor.execute {
            try {
                startSerialPtyProcess()
            } catch (error: Throwable) {
                LogBus.e(TAG, "Linux serial PTY side failed", error)
            }
        }
    }

    fun stopLinuxSerialSide() {
        executor.execute {
            stopSerialPtyProcess()
        }
    }

    private fun detectInitialStatus(): BridgeStatus {
        val installed = RuntimeReleaseManager.isReleaseUsable(paths.currentLink)
        val version = prefs.getString("installedVersion", null)
        return BridgeStatus(
            runtimeState = if (installed) RuntimeState.Installed else RuntimeState.NotInstalled,
            runtimePhase = RuntimePhase.Idle,
            runtimeAbiStatus = RuntimeCompatibility.snapshot(paths.nativeLibDir),
            installedVersion = version,
        )
    }

    private fun cleanupStaleRuntimeProcesses(reason: String) {
        serialPtyProcesses.clear()
        RuntimeProcessSupport.cleanupStaleProcesses(paths, reason)
    }

    private fun startSerialPtyProcess() {
        val serialDevices = AndroidUsbSerialBridge.snapshotStatus().devices
            .filter { it.granted && it.active }
            .sortedBy { it.virtualIndex }
        val activePaths = serialDevices.map { it.path }.toSet()
        serialPtyProcesses.filterKeys { it !in activePaths }.values.forEach { it.destroy() }
        serialPtyProcesses.keys.removeAll { it !in activePaths }
        if (serialDevices.isEmpty()) return
        RuntimeReleaseManager.ensureBaseRuntime(app, paths, ::logProgress)
        ensureHostRuntimeEnvironment()
        serialDevices.forEach { device ->
            val existing = serialPtyProcesses[device.path]
            if (existing?.isAlive == true) return@forEach
            serialPtyProcesses.remove(device.path)
            val script = """
set -e
mkdir -p /opt/tx5dr-data/android-dev /opt/tx5dr-data/logs /opt/tx5dr-data/runtime/sockets
exec tx5dr-android-serial-pty ${device.path} ${device.bridgeSocket}
""".trimIndent()
            val process = newProotProcess(buildProotBaseCommand() + listOf("/usr/bin/env", "-i", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "/bin/bash", "-lc", script))
                .redirectErrorStream(true)
                .start()
            serialPtyProcesses[device.path] = process
            pipeOutput(process, "serial-pty-${device.virtualIndex}")
            LogBus.i(TAG, "Started Linux serial PTY helper for ${device.path} on ${device.bridgeSocket}")
            monitorAuxiliaryProcess("Linux serial PTY helper ${device.path}", process) {
                if (serialPtyProcesses[device.path] == process) serialPtyProcesses.remove(device.path)
            }
        }
    }

    private fun stopAuxiliaryProcesses() {
        audioBridgeProcess?.destroy()
        audioBridgeProcess = null
        stopSerialPtyProcess()
    }

    private fun stopSerialPtyProcess() {
        val processes = serialPtyProcesses.values.toList()
        serialPtyProcesses.clear()
        processes.forEach { it.destroy() }
    }

    private fun buildProotBaseCommand(): List<String> = listOf(
        paths.prootFile.absolutePath,
        "--rootfs=${paths.rootfsDir.absolutePath}",
        "--pwd=/",
        "--bind=${paths.dataDir.absolutePath}:/opt/tx5dr-data",
        "--bind=${paths.externalUserRootDir.absolutePath}:/opt/tx5dr-user",
        "--bind=${paths.txDir.absolutePath}:/opt/tx5dr",
        "--bind=${paths.resolvConfFile.absolutePath}:/etc/resolv.conf",
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
        RuntimeCompatibility.requireSupported(paths.nativeLibDir)
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

    private fun logProgress(progress: InstallProgress) {
        val message = describeInstallProgress(progress)
        LogBus.i(TAG, message)
        updateStatus(status.copy(progress = message, installProgress = progress))
    }

    private fun describeInstallProgress(progress: InstallProgress): String = when (progress.stage) {
        InstallProgressStage.Preparing -> "Preparing runtime install"
        InstallProgressStage.CopyingBase -> "Copying embedded Debian rootfs asset"
        InstallProgressStage.ExtractingBase -> "Extracting rootfs: ${progress.entriesDone.coerceAtLeast(0)} entries"
        InstallProgressStage.FetchingManifest -> "Fetching release manifest"
        InstallProgressStage.Downloading -> "Downloading ${progress.artifactName.orEmpty()}: ${formatBytesForLog(progress.bytesDone)}/${formatBytesForLog(progress.bytesTotal)}"
        InstallProgressStage.Verifying -> "Verifying sha256: ${formatBytesForLog(progress.bytesDone)}/${formatBytesForLog(progress.bytesTotal)}"
        InstallProgressStage.ExtractingRelease -> "Extracting TX-5DR: ${progress.entriesDone.coerceAtLeast(0)} entries"
        InstallProgressStage.Activating -> "Activating release ${progress.artifactName.orEmpty()}"
        InstallProgressStage.Complete -> "Installed TX-5DR release ${progress.artifactName.orEmpty()}"
    }

    private fun formatBytesForLog(value: Long): String {
        if (value < 0) return "unknown"
        val units = arrayOf("B", "KiB", "MiB", "GiB")
        var size = value.toDouble()
        var unit = 0
        while (size >= 1024.0 && unit < units.lastIndex) {
            size /= 1024.0
            unit += 1
        }
        return if (unit == 0) "$value ${units[unit]}" else String.format(java.util.Locale.US, "%.1f %s", size, units[unit])
    }

    private fun pipeOutput(process: Process, prefix: String) {
        Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { LogBus.i(prefix, it) }
                }
            } catch (error: Throwable) {
                if (process.isAlive) {
                    LogBus.w(TAG, "Output pipe for $prefix stopped unexpectedly: ${error.message}")
                }
            }
        }.start()
    }

    private fun monitorRuntimeProcess(process: Process) {
        processMonitorExecutor.execute {
            val exitCode = process.waitFor()
            LogBus.w(TAG, "PRoot runtime exited with code $exitCode")
            if (prootProcess == process) {
                prootProcess = null
                healthRunning = false
                stopAuxiliaryProcesses()
                cleanupStaleRuntimeProcesses("runtime exit")
                if (stopRequested || status.runtimeState == RuntimeState.Stopping) {
                    updateStatus(status.copy(
                        runtimeState = RuntimeState.Stopped,
                        runtimePhase = RuntimePhase.Idle,
                        runtimeDetail = null,
                        serverHealthy = false,
                        webHealthy = false,
                        clientToolsHealthy = false,
                        progress = null,
                        lastExitCode = exitCode,
                    ))
                } else if (status.runtimeState != RuntimeState.Error) {
                    val reason = "Service process exited with code $exitCode"
                    updateStatus(status.copy(
                        runtimeState = RuntimeState.Error,
                        runtimePhase = RuntimePhase.Exited,
                        runtimeDetail = reason,
                        serverHealthy = false,
                        webHealthy = false,
                        clientToolsHealthy = false,
                        error = reason,
                        lastExitCode = exitCode,
                        lastExitReason = reason,
                    ))
                }
            }
        }
    }

    private fun monitorAuxiliaryProcess(label: String, process: Process, onExit: () -> Unit) {
        Thread {
            val exitCode = process.waitFor()
            LogBus.w(TAG, "$label exited with code $exitCode")
            onExit()
        }.start()
    }

    private fun startHealthLoop(process: Process, startedAtMs: Long) {
        if (healthRunning) return
        healthRunning = true
        Thread {
            while (healthRunning && !stopRequested && prootProcess == process && process.isAlive) {
                val server = RuntimeProcessSupport.isHealthy("http://127.0.0.1:4000/api/hello")
                val clientTools = RuntimeProcessSupport.isHealthy("http://127.0.0.1:8076/")
                val webApi = server && RuntimeProcessSupport.isHealthy("http://127.0.0.1:8076/api/hello")
                val readyDetail = RuntimeProcessSupport.readyDetail(paths)
                if (stopRequested || status.runtimeState == RuntimeState.Stopping) break
                val next = when {
                    server && webApi -> status.copy(
                        runtimeState = RuntimeState.Running,
                        runtimePhase = RuntimePhase.Healthy,
                        runtimeDetail = readyDetail ?: "TX-5DR is ready",
                        serverHealthy = true,
                        webHealthy = true,
                        clientToolsHealthy = clientTools,
                        progress = null,
                        error = null,
                    )
                    server -> status.copy(
                        runtimeState = RuntimeState.Running,
                        runtimePhase = RuntimePhase.WaitingWeb,
                        runtimeDetail = readyDetail ?: "TX-5DR API is ready; waiting for page proxy",
                        serverHealthy = true,
                        webHealthy = false,
                        clientToolsHealthy = clientTools,
                        progress = readyDetail ?: "Waiting for page proxy",
                    )
                    clientTools -> status.copy(
                        runtimeState = RuntimeState.Running,
                        runtimePhase = RuntimePhase.WaitingServer,
                        runtimeDetail = readyDetail ?: "Page proxy is ready; waiting for TX-5DR API",
                        serverHealthy = false,
                        webHealthy = false,
                        clientToolsHealthy = true,
                        progress = readyDetail ?: "Waiting for TX-5DR API",
                    )
                    else -> status.copy(
                        runtimeState = RuntimeState.Running,
                        runtimePhase = RuntimePhase.WaitingServer,
                        runtimeDetail = readyDetail ?: "Service process is running; waiting for health checks",
                        serverHealthy = false,
                        webHealthy = false,
                        clientToolsHealthy = false,
                        progress = readyDetail ?: "Waiting for health checks",
                    )
                }
                if (!stopRequested && status.runtimeState != RuntimeState.Stopping) updateStatus(next)
                if (!server && System.currentTimeMillis() - startedAtMs > STARTUP_HEALTH_TIMEOUT_MS) {
                    failUnresponsiveRuntime(process, readyDetail ?: "TX-5DR API did not respond within 60 seconds")
                    break
                }
                Thread.sleep(3000)
            }
        }.start()
    }

    private fun failUnresponsiveRuntime(process: Process, reason: String) {
        executor.execute {
            if (prootProcess != process || stopRequested) return@execute
            LogBus.e(TAG, "Runtime health timeout: $reason")
            updateStatus(status.copy(
                runtimeState = RuntimeState.Error,
                runtimePhase = RuntimePhase.Error,
                runtimeDetail = reason,
                serverHealthy = false,
                webHealthy = false,
                clientToolsHealthy = status.clientToolsHealthy,
                error = reason,
                lastExitReason = reason,
            ))
            process.destroy()
            if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
            prootProcess = null
            healthRunning = false
            stopAuxiliaryProcesses()
            cleanupStaleRuntimeProcesses("health timeout")
        }
    }

    private fun updateStatus(next: BridgeStatus) {
        val current = next.copy(runtimeAbiStatus = RuntimeCompatibility.snapshot(paths.nativeLibDir))
        status = current
        main.post {
            listeners.forEach { listener ->
                runCatching { listener(current) }.onFailure { Log.w(TAG, "Status listener failed", it) }
            }
        }
    }



    const val PREF_AUTO_START_RUNTIME = "autoStartRuntime"
    const val PREF_AUTO_START_BRIDGES = "autoStartBridges"
    const val PREF_AUTO_OPEN_WEBVIEW = "autoOpenWebView"
    const val PREF_SERVICE_ONLY_MODE = "serviceOnlyMode"
    const val PREF_KEEP_ALIVE_ENABLED = "keepAliveEnabled"
    const val PREF_AUDIO_BUFFER_TARGET_MS = "audioBufferTargetMs"
    const val DEFAULT_AUDIO_BUFFER_TARGET_MS = 60

    fun coerceAudioBufferTargetMs(value: Int): Int = when (value) {
        10, 20, 30, 40, 60, 100 -> value
        else -> DEFAULT_AUDIO_BUFFER_TARGET_MS
    }
}
