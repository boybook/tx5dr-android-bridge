package com.tx5dr.bridge.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.tx5dr.bridge.BridgeStatus
import com.tx5dr.bridge.RuntimeState

internal data class StatusVisual(
    val title: String,
    val subtitle: String,
    val chip: String,
    val icon: ImageVector,
    val color: Color,
    val busy: Boolean = false,
)

@Composable
internal fun statusVisual(status: BridgeStatus): StatusVisual {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    return when {
        status.error != null || status.runtimeState == RuntimeState.Error -> StatusVisual(
            title = "需要处理",
            subtitle = status.error?.takeIf { it.isNotBlank() } ?: "后台服务遇到异常，请查看诊断信息。",
            chip = "异常",
            icon = Icons.Filled.Error,
            color = scheme.error,
        )
        status.serverHealthy && status.webHealthy -> StatusVisual(
            title = "可以开始使用",
            subtitle = "后台服务已就绪，可进入 TX-5DR。",
            chip = "已就绪",
            icon = Icons.Filled.CheckCircle,
            color = scheme.primary,
        )
        status.runtimeState == RuntimeState.NotInstalled -> StatusVisual(
            title = "需要安装引擎",
            subtitle = "首次使用需要下载并安装 Android 运行环境。",
            chip = "未安装",
            icon = Icons.Filled.RadioButtonUnchecked,
            color = scheme.primary,
        )
        status.runtimeState == RuntimeState.Installing -> StatusVisual(
            title = "正在安装引擎",
            subtitle = status.progress ?: "正在下载、校验并准备运行环境。",
            chip = "安装中",
            icon = Icons.Filled.HourglassTop,
            color = scheme.primary,
            busy = true,
        )
        status.runtimeState == RuntimeState.Starting || status.runtimeState == RuntimeState.Running -> StatusVisual(
            title = "正在准备服务",
            subtitle = status.progress ?: "正在启动后台服务并等待健康检查。",
            chip = "启动中",
            icon = Icons.Filled.HourglassTop,
            color = scheme.primary,
            busy = true,
        )
        status.runtimeState == RuntimeState.Stopping -> StatusVisual(
            title = "正在停止服务",
            subtitle = status.progress ?: "正在关闭后台进程。",
            chip = "停止中",
            icon = Icons.Filled.HourglassTop,
            color = scheme.primary,
            busy = true,
        )
        status.runtimeState == RuntimeState.Installed || status.runtimeState == RuntimeState.Stopped -> StatusVisual(
            title = "服务尚未启动",
            subtitle = "运行环境已安装，可以启动后台服务。",
            chip = "已安装",
            icon = Icons.Filled.Warning,
            color = scheme.tertiary,
        )
        else -> StatusVisual(
            title = "正在准备",
            subtitle = status.progress ?: "请稍候。",
            chip = status.runtimeState.name,
            icon = Icons.Filled.HourglassTop,
            color = scheme.primary,
            busy = true,
        )
    }
}

internal fun bridgeLabel(state: String): String = when (state) {
    "streaming", "connected" -> "已连接"
    "permission-required" -> "需要授权"
    "permission-denied" -> "权限被拒绝"
    "no-device" -> "未检测到"
    "starting", "waiting-helper" -> "启动中"
    "disconnected" -> "已断开"
    "stopped" -> "已停止"
    "error" -> "异常"
    else -> state.ifBlank { "未知" }
}

internal fun formatBytes(value: Long): String {
    if (value < 0) return "unknown"
    val units = arrayOf("B", "KiB", "MiB", "GiB")
    var size = value.toDouble()
    var unit = 0
    while (size >= 1024.0 && unit < units.lastIndex) {
        size /= 1024.0
        unit += 1
    }
    return if (unit == 0) "$value ${units[unit]}" else "%.1f %s".format(java.util.Locale.US, size, units[unit])
}
