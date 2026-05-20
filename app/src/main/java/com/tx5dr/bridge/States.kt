package com.tx5dr.bridge

enum class RuntimeState { NotInstalled, Installing, Installed, Starting, Running, Stopping, Stopped, Error }
enum class RuntimePhase {
    Idle,
    PreparingRuntime,
    StartingBridges,
    StartingSupervisor,
    WaitingServer,
    WaitingWeb,
    Healthy,
    Stopping,
    Exited,
    Error,
}
enum class MicBridgeState { PermissionRequired, Stopped, Starting, Streaming, Error }

enum class InstallProgressStage {
    Preparing,
    CopyingBase,
    ExtractingBase,
    FetchingManifest,
    Downloading,
    Verifying,
    ExtractingRelease,
    Activating,
    Complete,
}

data class InstallProgress(
    val stage: InstallProgressStage,
    val artifactName: String? = null,
    val bytesDone: Long = -1L,
    val bytesTotal: Long = -1L,
    val bytesPerSecond: Long = -1L,
    val entriesDone: Int = -1,
    val latestEntry: String? = null,
) {
    val fraction: Float?
        get() = if (bytesTotal > 0 && bytesDone >= 0) (bytesDone.toDouble() / bytesTotal.toDouble()).coerceIn(0.0, 1.0).toFloat() else null
}

data class BridgeStatus(
    val runtimeState: RuntimeState = RuntimeState.NotInstalled,
    val runtimePhase: RuntimePhase = RuntimePhase.Idle,
    val runtimeDetail: String? = null,
    val runtimeAbiStatus: RuntimeAbiStatus = RuntimeAbiStatus(),
    val startedAtMs: Long? = null,
    val lastExitCode: Int? = null,
    val lastExitReason: String? = null,
    val micState: MicBridgeState = MicBridgeState.Stopped,
    val serverHealthy: Boolean = false,
    val webHealthy: Boolean = false,
    val clientToolsHealthy: Boolean = false,
    val installedVersion: String? = null,
    val progress: String? = null,
    val installProgress: InstallProgress? = null,
    val error: String? = null,
)

data class RuntimeAbiStatus(
    val supported: Boolean = true,
    val requiredAbi: String = "arm64-v8a",
    val supportedAbis: String = "",
    val supported64BitAbis: String = "",
    val nativeLibraryDir: String = "",
    val zygote: String = "",
)

data class ReleasePreview(
    val version: String,
    val name: String,
    val sizeBytes: Long,
    val url: String,
)


data class ExternalDataStatus(
    val rootPath: String? = null,
    val dataPath: String? = null,
    val logsPath: String? = null,
    val pluginsPath: String? = null,
    val pluginDataPath: String? = null,
    val external: Boolean = false,
    val fallbackReason: String? = null,
    val migrationSummary: String? = null,
)
