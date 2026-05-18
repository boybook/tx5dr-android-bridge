package com.tx5dr.bridge

enum class RuntimeState { NotInstalled, Installing, Installed, Starting, Running, Stopping, Stopped, Error }
enum class MicBridgeState { PermissionRequired, Stopped, Starting, Streaming, Error }

data class BridgeStatus(
    val runtimeState: RuntimeState = RuntimeState.NotInstalled,
    val micState: MicBridgeState = MicBridgeState.Stopped,
    val serverHealthy: Boolean = false,
    val webHealthy: Boolean = false,
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
