package com.tx5dr.bridge.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.tx5dr.bridge.BridgeStatus
import com.tx5dr.bridge.R
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
            title = stringResource(R.string.runtime_needs_attention),
            subtitle = status.error?.takeIf { it.isNotBlank() } ?: stringResource(R.string.runtime_error_subtitle),
            chip = stringResource(R.string.runtime_error_chip),
            icon = Icons.Filled.Error,
            color = scheme.error,
        )
        status.serverHealthy && status.webHealthy -> StatusVisual(
            title = stringResource(R.string.runtime_ready_title),
            subtitle = stringResource(R.string.runtime_ready_subtitle),
            chip = stringResource(R.string.runtime_running_chip),
            icon = Icons.Filled.CheckCircle,
            color = scheme.primary,
        )
        status.runtimeState == RuntimeState.NotInstalled -> StatusVisual(
            title = stringResource(R.string.runtime_install_required),
            subtitle = stringResource(R.string.runtime_install_subtitle),
            chip = stringResource(R.string.runtime_not_installed_chip),
            icon = Icons.Filled.RadioButtonUnchecked,
            color = scheme.primary,
        )
        status.runtimeState == RuntimeState.Installing -> StatusVisual(
            title = stringResource(R.string.runtime_installing),
            subtitle = status.progress ?: stringResource(R.string.runtime_installing_subtitle),
            chip = stringResource(R.string.runtime_installing_chip),
            icon = Icons.Filled.HourglassTop,
            color = scheme.primary,
            busy = true,
        )
        status.runtimeState == RuntimeState.Starting || status.runtimeState == RuntimeState.Running -> StatusVisual(
            title = stringResource(R.string.runtime_starting),
            subtitle = status.progress ?: stringResource(R.string.runtime_starting_subtitle),
            chip = stringResource(R.string.runtime_starting_chip),
            icon = Icons.Filled.HourglassTop,
            color = scheme.primary,
            busy = true,
        )
        status.runtimeState == RuntimeState.Stopping -> StatusVisual(
            title = stringResource(R.string.runtime_stopping),
            subtitle = status.progress ?: stringResource(R.string.runtime_stopping_subtitle),
            chip = stringResource(R.string.runtime_stopping_chip),
            icon = Icons.Filled.HourglassTop,
            color = scheme.primary,
            busy = true,
        )
        status.runtimeState == RuntimeState.Installed || status.runtimeState == RuntimeState.Stopped -> StatusVisual(
            title = stringResource(R.string.runtime_not_started),
            subtitle = stringResource(R.string.runtime_installed_subtitle),
            chip = stringResource(R.string.runtime_installed_chip),
            icon = Icons.Filled.Warning,
            color = scheme.tertiary,
        )
        else -> StatusVisual(
            title = stringResource(R.string.runtime_preparing),
            subtitle = status.progress ?: stringResource(R.string.runtime_wait),
            chip = status.runtimeState.name,
            icon = Icons.Filled.HourglassTop,
            color = scheme.primary,
            busy = true,
        )
    }
}

@Composable
internal fun bridgeLabel(state: String): String = when (state) {
    "streaming", "connected" -> stringResource(R.string.bridge_state_connected)
    "permission-required" -> stringResource(R.string.bridge_state_permission_required)
    "permission-denied" -> stringResource(R.string.bridge_state_permission_denied)
    "no-device" -> stringResource(R.string.bridge_state_no_device)
    "starting", "waiting-helper" -> stringResource(R.string.bridge_state_starting)
    "disconnected" -> stringResource(R.string.bridge_state_disconnected)
    "stopped" -> stringResource(R.string.bridge_state_stopped)
    "error" -> stringResource(R.string.bridge_state_error)
    else -> state.ifBlank { stringResource(R.string.bridge_state_unknown) }
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
