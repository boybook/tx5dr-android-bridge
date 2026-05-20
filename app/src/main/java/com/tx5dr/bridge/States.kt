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

data class BridgeStatus(
    val runtimeState: RuntimeState = RuntimeState.NotInstalled,
    val runtimePhase: RuntimePhase = RuntimePhase.Idle,
    val runtimeDetail: String? = null,
    val startedAtMs: Long? = null,
    val lastExitCode: Int? = null,
    val lastExitReason: String? = null,
    val micState: MicBridgeState = MicBridgeState.Stopped,
    val serverHealthy: Boolean = false,
    val webHealthy: Boolean = false,
    val clientToolsHealthy: Boolean = false,
    val installedVersion: String? = null,
    val progress: String? = null,
    val error: String? = null,
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
