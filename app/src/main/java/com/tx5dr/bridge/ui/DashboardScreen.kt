@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.tx5dr.bridge.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tx5dr.bridge.AudioRoute
import com.tx5dr.bridge.BridgeStatus
import com.tx5dr.bridge.R
import com.tx5dr.bridge.ReleasePreview
import com.tx5dr.bridge.RuntimeState
import com.tx5dr.bridge.UsbAudioDevice
import com.tx5dr.bridge.UsbAudioStatus
import com.tx5dr.bridge.UsbSerialStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    bridgeStatus: BridgeStatus,
    usbAudioStatus: UsbAudioStatus,
    usbSerialStatus: UsbSerialStatus,
    logs: String,
    lanUrls: List<String>,
    manifestUrl: String,
    autoOpenWebView: Boolean,
    keepAliveEnabled: Boolean,
    showInstallDialog: Boolean,
    releasePreview: ReleasePreview?,
    releasePreviewError: String?,
    showDiagnosticsSheet: Boolean,
    controlSystemBars: Boolean = true,
    onInstallClick: () -> Unit,
    onConfirmInstall: () -> Unit,
    onDismissInstallDialog: () -> Unit,
    onStartRuntime: () -> Unit,
    onStopRuntime: () -> Unit,
    onOpenWebView: () -> Unit,
    onAuthorizeAudio: () -> Unit,
    onSetAudioInputRoute: (String) -> Unit,
    onSetAudioOutputRoute: (String) -> Unit,
    onStartSerial: () -> Unit,
    onSetKeepAlive: (Boolean) -> Unit,
    onRefreshBridges: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onDismissDiagnostics: () -> Unit,
    onCopyText: (String) -> Unit,
    onRefreshLan: () -> Unit,
    onManifestUrlChange: (String) -> Unit,
    onAutoOpenWebViewChange: (Boolean) -> Unit,
    onServiceOnlyModeChange: (Boolean) -> Unit,
) {
    var detailSheet by remember { mutableStateOf<DetailSheet?>(null) }

    Tx5drTheme(controlSystemBars = controlSystemBars) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                "TX-5DR",
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
                            )
                        },
                        navigationIcon = {
                            Image(
                                painter = painterResource(R.drawable.tx5dr_logo_full),
                                contentDescription = null,
                                modifier = Modifier.padding(start = 12.dp).size(36.dp),
                            )
                        },
                        actions = {
                            IconButton(onClick = onShowDiagnostics) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_diagnostics_settings))
                            }
                        },
                    )
                },
            ) { padding ->
                AtmosphereBox(Modifier.fillMaxSize().padding(padding)) {
                    DashboardContent(
                        bridgeStatus = bridgeStatus,
                        usbAudioStatus = usbAudioStatus,
                        usbSerialStatus = usbSerialStatus,
                        lanUrls = lanUrls,
                        keepAliveEnabled = keepAliveEnabled,
                        updateAvailable = hasRuntimeUpdate(bridgeStatus, releasePreview),
                        onInstallClick = onInstallClick,
                        onStartRuntime = onStartRuntime,
                        onStopRuntime = onStopRuntime,
                        onOpenWebView = onOpenWebView,
                        onShowDiagnostics = onShowDiagnostics,
                        onCopyText = onCopyText,
                        onAudioClick = { detailSheet = DetailSheet.Audio },
                        onSerialClick = { detailSheet = DetailSheet.Serial },
                        onAuthorizeAudio = onAuthorizeAudio,
                        onSetAudioInputRoute = onSetAudioInputRoute,
                        onSetAudioOutputRoute = onSetAudioOutputRoute,
                        onSetKeepAlive = onSetKeepAlive,
                        onRefreshBridges = onRefreshBridges,
                        onOpenBatterySettings = onOpenBatterySettings,
                    )
                }
            }

            if (showInstallDialog) {
                InstallRuntimeDialog(
                    runtimeState = bridgeStatus.runtimeState,
                    manifestUrl = manifestUrl,
                    releasePreview = releasePreview,
                    releasePreviewError = releasePreviewError,
                    onConfirm = onConfirmInstall,
                    onDismiss = onDismissInstallDialog,
                )
            }

            if (showDiagnosticsSheet) {
                DiagnosticsSheet(
                    bridgeStatus = bridgeStatus,
                    logs = logs,
                    manifestUrl = manifestUrl,
                    autoOpenWebView = autoOpenWebView,
                    onDismiss = onDismissDiagnostics,
                    onManifestUrlChange = onManifestUrlChange,
                    onAutoOpenWebViewChange = onAutoOpenWebViewChange,
                    onServiceOnlyModeChange = onServiceOnlyModeChange,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onRefreshLan = onRefreshLan,
                    onInstallClick = onInstallClick,
                )
            }

            when (detailSheet) {
                DetailSheet.Audio -> AudioDetailSheet(
                    status = usbAudioStatus,
                    onDismiss = { detailSheet = null },
                    onAuthorizeAudio = onAuthorizeAudio,
                    onSetInputRoute = onSetAudioInputRoute,
                    onSetOutputRoute = onSetAudioOutputRoute,
                    onShowDiagnostics = {
                        detailSheet = null
                        onShowDiagnostics()
                    },
                )
                DetailSheet.Serial -> SerialDetailSheet(
                    status = usbSerialStatus,
                    onDismiss = { detailSheet = null },
                    onStartSerial = onStartSerial,
                    onShowDiagnostics = {
                        detailSheet = null
                        onShowDiagnostics()
                    },
                )
                null -> Unit
            }
        }
    }
}

@Composable
private fun AtmosphereBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.background(
            Brush.verticalGradient(
                listOf(
                    scheme.primaryContainer.copy(alpha = 0.42f),
                    scheme.background,
                    scheme.background,
                ),
            ),
        ),
    ) {
        content()
    }
}

@Composable
private fun DashboardContent(
    bridgeStatus: BridgeStatus,
    usbAudioStatus: UsbAudioStatus,
    usbSerialStatus: UsbSerialStatus,
    lanUrls: List<String>,
    keepAliveEnabled: Boolean,
    updateAvailable: Boolean,
    onInstallClick: () -> Unit,
    onStartRuntime: () -> Unit,
    onStopRuntime: () -> Unit,
    onOpenWebView: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onCopyText: (String) -> Unit,
    onAudioClick: () -> Unit,
    onSerialClick: () -> Unit,
    onAuthorizeAudio: () -> Unit,
    onSetAudioInputRoute: (String) -> Unit,
    onSetAudioOutputRoute: (String) -> Unit,
    onSetKeepAlive: (Boolean) -> Unit,
    onRefreshBridges: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wideLayout = maxWidth >= 720.dp
        if (wideLayout) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Column(
                    Modifier
                        .weight(0.92f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    HeroStatusPanel(
                        status = bridgeStatus,
                        onInstallClick = onInstallClick,
                        onStartRuntime = onStartRuntime,
                        onStopRuntime = onStopRuntime,
                        onOpenWebView = onOpenWebView,
                        onShowDiagnostics = onShowDiagnostics,
                        updateAvailable = updateAvailable,
                    )
                }
                Column(
                    Modifier
                        .weight(1.08f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (usbAudioStatus.needsRecordAudioPermission()) {
                        AudioPermissionNotice(
                            denied = usbAudioStatus.state == "permission-denied",
                            onAuthorizeAudio = onAuthorizeAudio,
                        )
                    }
                    ServiceAccessStrip(
                        healthy = bridgeStatus.webHealthy,
                        lanUrls = lanUrls,
                        onCopyText = onCopyText,
                    )
                    HardwareDock(
                        audioStatus = usbAudioStatus,
                        serialStatus = usbSerialStatus,
                        keepAliveEnabled = keepAliveEnabled,
                        onAudioClick = onAudioClick,
                        onSerialClick = onSerialClick,
                        onAuthorizeAudio = onAuthorizeAudio,
                        onKeepAliveChange = onSetKeepAlive,
                        onRefreshBridges = onRefreshBridges,
                        onOpenBatterySettings = onOpenBatterySettings,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                HeroStatusPanel(
                    status = bridgeStatus,
                    onInstallClick = onInstallClick,
                    onStartRuntime = onStartRuntime,
                    onStopRuntime = onStopRuntime,
                    onOpenWebView = onOpenWebView,
                    onShowDiagnostics = onShowDiagnostics,
                    updateAvailable = updateAvailable,
                )
                if (usbAudioStatus.needsRecordAudioPermission()) {
                    AudioPermissionNotice(
                        denied = usbAudioStatus.state == "permission-denied",
                        onAuthorizeAudio = onAuthorizeAudio,
                    )
                }
                ServiceAccessStrip(
                    healthy = bridgeStatus.webHealthy,
                    lanUrls = lanUrls,
                    onCopyText = onCopyText,
                )
                HardwareDock(
                    audioStatus = usbAudioStatus,
                    serialStatus = usbSerialStatus,
                    keepAliveEnabled = keepAliveEnabled,
                    onAudioClick = onAudioClick,
                    onSerialClick = onSerialClick,
                    onAuthorizeAudio = onAuthorizeAudio,
                    onKeepAliveChange = onSetKeepAlive,
                    onRefreshBridges = onRefreshBridges,
                    onOpenBatterySettings = onOpenBatterySettings,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun Modifier.tx5drSoftShadow(elevation: Dp, shape: Shape): Modifier =
    shadow(
        elevation = elevation,
        shape = shape,
        ambientColor = Color.Black.copy(alpha = 0.06f),
        spotColor = Color.Black.copy(alpha = 0.09f),
    )

@Composable
private fun HeroStatusPanel(
    status: BridgeStatus,
    onInstallClick: () -> Unit,
    onStartRuntime: () -> Unit,
    onStopRuntime: () -> Unit,
    onOpenWebView: () -> Unit,
    onShowDiagnostics: () -> Unit,
    updateAvailable: Boolean,
) {
    val visual = statusVisual(status)
    val title = when {
        status.serverHealthy && status.webHealthy -> stringResource(R.string.runtime_service_running)
        status.runtimeState == RuntimeState.NotInstalled -> stringResource(R.string.runtime_install_required)
        status.runtimeState == RuntimeState.Installing -> stringResource(R.string.runtime_installing)
        status.runtimeState == RuntimeState.Starting || status.runtimeState == RuntimeState.Running -> stringResource(R.string.runtime_starting)
        status.runtimeState == RuntimeState.Stopping -> stringResource(R.string.runtime_stopping)
        status.runtimeState == RuntimeState.Error || status.error != null -> stringResource(R.string.runtime_needs_attention)
        else -> stringResource(R.string.runtime_not_started)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(visual.icon, contentDescription = null, tint = visual.color, modifier = Modifier.size(44.dp))
        Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)

        when {
            visual.busy -> {
                Text(visual.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(Modifier.fillMaxWidth(0.72f))
            }
            status.runtimeState == RuntimeState.Error || status.error != null -> {
                Text(
                    status.error?.takeIf { it.isNotBlank() } ?: stringResource(R.string.see_diagnostics),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            status.runtimeState == RuntimeState.NotInstalled -> {
                Text(stringResource(R.string.first_use_install_runtime), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                visual.busy -> {
                    Button(onClick = {}, enabled = false, modifier = Modifier.widthIn(min = 156.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_start_service))
                    }
                    UpdateTextButton(onClick = {}, enabled = false, updateAvailable = updateAvailable)
                }
                status.runtimeState == RuntimeState.NotInstalled -> {
                    Button(onClick = onInstallClick, modifier = Modifier.widthIn(min = 168.dp)) {
                        Icon(Icons.Filled.PowerSettingsNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_install_engine))
                    }
                }
                status.serverHealthy && status.webHealthy -> {
                    Button(onClick = onOpenWebView, modifier = Modifier.widthIn(min = 176.dp)) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_enter_tx5dr))
                    }
                    IconButton(onClick = onStopRuntime) { Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.action_stop_service)) }
                }
                status.runtimeState == RuntimeState.Error || status.error != null -> {
                    Button(onClick = onStartRuntime, modifier = Modifier.widthIn(min = 144.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_retry))
                    }
                    TextButton(onClick = onShowDiagnostics) { Text(stringResource(R.string.action_diagnostics)) }
                }
                else -> {
                    Button(onClick = onStartRuntime, modifier = Modifier.widthIn(min = 156.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_start_service))
                    }
                    UpdateTextButton(onClick = onInstallClick, updateAvailable = updateAvailable)
                }
            }
        }
    }
}

@Composable
private fun UpdateTextButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    updateAvailable: Boolean,
) {
    BadgedBox(
        badge = {
            if (updateAvailable) {
                Badge(containerColor = MaterialTheme.colorScheme.error)
            }
        },
    ) {
        TextButton(onClick = onClick, enabled = enabled) { Text(stringResource(R.string.action_update)) }
    }
}

@Composable
private fun ServiceAccessStrip(
    healthy: Boolean,
    lanUrls: List<String>,
    onCopyText: (String) -> Unit,
) {
    if (!healthy && lanUrls.isEmpty()) return
    val cardShape = MaterialTheme.shapes.extraLarge
    Card(
        modifier = Modifier.fillMaxWidth().tx5drSoftShadow(10.dp, cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        ListItem(
            leadingContent = { Icon(Icons.Filled.Lan, contentDescription = null) },
            headlineContent = { Text(if (healthy) stringResource(R.string.access_title_ready) else stringResource(R.string.access_title_waiting)) },
            supportingContent = {
                Text(
                    lanUrls.firstOrNull() ?: stringResource(R.string.access_fallback),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                if (lanUrls.isNotEmpty()) {
                    IconButton(onClick = { onCopyText(lanUrls.first()) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.cd_copy_address))
                    }
                }
            },
        )
    }
}

@Composable
private fun HardwareDock(
    audioStatus: UsbAudioStatus,
    serialStatus: UsbSerialStatus,
    keepAliveEnabled: Boolean,
    onAudioClick: () -> Unit,
    onSerialClick: () -> Unit,
    onAuthorizeAudio: () -> Unit,
    onKeepAliveChange: (Boolean) -> Unit,
    onRefreshBridges: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.radio_connection), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefreshBridges) { Text(stringResource(R.string.action_refresh)) }
        }
        val cardShape = MaterialTheme.shapes.extraLarge
        Card(
            Modifier.fillMaxWidth().tx5drSoftShadow(12.dp, cardShape),
            shape = cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column {
                HardwareListItem(
                    icon = Icons.Filled.GraphicEq,
                    title = stringResource(R.string.audio_route),
                    state = audioStatus.state,
                    supporting = when {
                        audioStatus.needsRecordAudioPermission() -> stringResource(R.string.mic_permission_required)
                        else -> audioRouteSummary(audioStatus)
                    },
                    onClick = onAudioClick,
                )
                HorizontalDivider()
                HardwareListItem(
                    icon = Icons.Filled.Usb,
                    title = stringResource(R.string.usb_serial),
                    state = usbSerialState(serialStatus),
                    supporting = serialStatus.activePath ?: serialStatus.devices.firstOrNull()?.name ?: stringResource(R.string.serial_auto_detect),
                    onClick = onSerialClick,
                )
                HorizontalDivider()
                ListItem(
                    leadingContent = { Icon(Icons.Filled.BatterySaver, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.keep_alive_mode)) },
                    supportingContent = { Text(stringResource(R.string.keep_alive_subtitle)) },
                    trailingContent = { Switch(checked = keepAliveEnabled, onCheckedChange = onKeepAliveChange) },
                )
                if (keepAliveEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = onOpenBatterySettings) { Text(stringResource(R.string.go_battery_optimization_settings)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioPermissionNotice(
    denied: Boolean,
    onAuthorizeAudio: () -> Unit,
) {
    val cardShape = MaterialTheme.shapes.extraLarge
    Card(
        modifier = Modifier.fillMaxWidth().tx5drSoftShadow(8.dp, cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (denied) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = if (denied) Icons.Filled.Error else Icons.Filled.Info,
                contentDescription = null,
                tint = if (denied) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (denied) stringResource(R.string.mic_permission_denied_title) else stringResource(R.string.mic_permission_allow_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (denied) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.mic_permission_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (denied) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = onAuthorizeAudio) { Text(if (denied) stringResource(R.string.action_go_allow) else stringResource(R.string.action_allow)) }
        }
    }
}

@Composable
private fun HardwareListItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    state: String,
    supporting: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { StateChip(state) },
    )
}

@Composable
private fun StateChip(state: String) {
    AssistChip(onClick = {}, label = { Text(bridgeLabel(state)) })
}

@Composable
private fun InstallRuntimeDialog(
    runtimeState: RuntimeState,
    manifestUrl: String,
    releasePreview: ReleasePreview?,
    releasePreviewError: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.PowerSettingsNew, contentDescription = null) },
        title = { Text(if (runtimeState == RuntimeState.NotInstalled) stringResource(R.string.install_dialog_title) else stringResource(R.string.install_update_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.install_dialog_body))
                when {
                    releasePreview != null -> {
                        Text(stringResource(R.string.release_version, releasePreview.version))
                        Text(stringResource(R.string.release_size, formatBytes(releasePreview.sizeBytes)))
                    }
                    releasePreviewError != null -> Text(stringResource(R.string.release_preview_error, releasePreviewError), color = MaterialTheme.colorScheme.error)
                    else -> Text(stringResource(R.string.release_preview_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(manifestUrl, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.action_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsSheet(
    bridgeStatus: BridgeStatus,
    logs: String,
    manifestUrl: String,
    autoOpenWebView: Boolean,
    onDismiss: () -> Unit,
    onManifestUrlChange: (String) -> Unit,
    onAutoOpenWebViewChange: (Boolean) -> Unit,
    onServiceOnlyModeChange: (Boolean) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRefreshLan: () -> Unit,
    onInstallClick: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.diagnostics_settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            bridgeStatus.error?.let {
                Card(Modifier.fillMaxWidth()) {
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        headlineContent = { Text(stringResource(R.string.recent_error)) },
                        supportingContent = { Text(it) },
                    )
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                SettingsSwitch(
                    title = stringResource(R.string.auto_enter_tx5dr),
                    subtitle = stringResource(R.string.auto_enter_tx5dr_subtitle),
                    checked = autoOpenWebView,
                    onCheckedChange = { enabled ->
                        onAutoOpenWebViewChange(enabled)
                        onServiceOnlyModeChange(!enabled)
                    },
                )
            }
            OutlinedTextField(
                value = manifestUrl,
                onValueChange = onManifestUrlChange,
                label = { Text(stringResource(R.string.manifest_url)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefreshLan) { Text(stringResource(R.string.refresh_lan)) }
                OutlinedButton(onClick = onOpenBatterySettings) { Text(stringResource(R.string.battery_optimization_settings)) }
                OutlinedButton(onClick = onInstallClick) { Text(stringResource(R.string.install_update)) }
            }
            Text(stringResource(R.string.runtime_logs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Surface(Modifier.fillMaxWidth().height(360.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
                Text(
                    logs.ifBlank { stringResource(R.string.no_logs) },
                    modifier = Modifier.padding(14.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsSwitch(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioDetailSheet(
    status: UsbAudioStatus,
    onDismiss: () -> Unit,
    onAuthorizeAudio: () -> Unit,
    onSetInputRoute: (String) -> Unit,
    onSetOutputRoute: (String) -> Unit,
    onShowDiagnostics: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.audio_route),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                StateChip(status.state)
            }
            AudioRouteSection(
                title = stringResource(R.string.input_route),
                activeText = stringResource(R.string.active_input_format, displayAudioDeviceName(status.activeInputDevice)),
                selectedRoute = status.selectedInputRoute,
                routes = listOf(AudioRoute.AUTO, AudioRoute.USB, AudioRoute.BUILTIN_MIC),
                onSelect = onSetInputRoute,
            )
            AudioRouteSection(
                title = stringResource(R.string.output_route),
                activeText = stringResource(R.string.active_output_format, displayAudioDeviceName(status.activeOutputDevice)),
                selectedRoute = status.selectedOutputRoute,
                routes = listOf(AudioRoute.AUTO, AudioRoute.USB, AudioRoute.BUILTIN_SPEAKER),
                onSelect = onSetOutputRoute,
            )
            if (usesBuiltinAudio(status)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.phone_audio_notice),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onAuthorizeAudio) { Text(if (status.state == "streaming") stringResource(R.string.audio_reconnect) else stringResource(R.string.authorize_start)) }
                OutlinedButton(onClick = onShowDiagnostics) { Text(stringResource(R.string.related_logs)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AudioRouteSection(
    title: String,
    activeText: String,
    selectedRoute: String,
    routes: List<String>,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(activeText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            routes.forEachIndexed { index, route ->
                SegmentedButton(
                    selected = selectedRoute == route,
                    onClick = { onSelect(route) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = routes.size),
                ) {
                    Text(audioRouteSegmentLabel(route), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SerialDetailSheet(
    status: UsbSerialStatus,
    onDismiss: () -> Unit,
    onStartSerial: () -> Unit,
    onShowDiagnostics: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.usb_serial), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            StateChip(usbSerialState(status))
            DeviceList(stringResource(R.string.devices), status.devices.map { it.name })
            status.activePath?.let { Text(stringResource(R.string.path_format, it), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStartSerial) { Text(if (status.state == "connected") stringResource(R.string.serial_reconnect) else stringResource(R.string.authorize_start)) }
                OutlinedButton(onClick = onShowDiagnostics) { Text(stringResource(R.string.related_logs)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DeviceList(title: String, values: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        if (values.isEmpty()) {
            Text(stringResource(R.string.no_device_detected), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            values.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun audioRouteSummary(status: UsbAudioStatus): String {
    val input = status.activeInputDevice ?: preferredInputDevice(status)
    val output = status.activeOutputDevice ?: preferredOutputDevice(status)
    return stringResource(
        R.string.audio_route_summary,
        displayAudioDeviceName(input),
        displayAudioDeviceName(output),
    )
}

@Composable
private fun displayAudioDeviceName(device: UsbAudioDevice?): String {
    if (device == null) return stringResource(R.string.no_device_detected)
    return when (device.kind) {
        AudioRoute.BUILTIN_MIC -> stringResource(R.string.phone_microphone)
        AudioRoute.BUILTIN_SPEAKER -> stringResource(R.string.phone_speaker)
        else -> device.name
    }
}

@Composable
private fun audioRouteSegmentLabel(route: String): String = when (route) {
    AudioRoute.AUTO -> stringResource(R.string.audio_route_auto)
    AudioRoute.USB -> stringResource(R.string.usb_sound_card)
    AudioRoute.BUILTIN_MIC -> stringResource(R.string.audio_route_mic_short)
    AudioRoute.BUILTIN_SPEAKER -> stringResource(R.string.audio_route_speaker_short)
    else -> route
}

private fun usesBuiltinAudio(status: UsbAudioStatus): Boolean =
    status.selectedInputRoute == AudioRoute.BUILTIN_MIC ||
        status.selectedOutputRoute == AudioRoute.BUILTIN_SPEAKER ||
        status.activeInputDevice?.kind == AudioRoute.BUILTIN_MIC ||
        status.activeOutputDevice?.kind == AudioRoute.BUILTIN_SPEAKER

private fun preferredInputDevice(status: UsbAudioStatus): UsbAudioDevice? = when (status.selectedInputRoute) {
    AudioRoute.USB -> status.inputDevices.firstOrNull { it.kind == AudioRoute.USB }
    AudioRoute.BUILTIN_MIC -> status.inputDevices.firstOrNull { it.kind == AudioRoute.BUILTIN_MIC }
    else -> status.inputDevices.firstOrNull { it.kind == AudioRoute.USB }
        ?: status.inputDevices.firstOrNull { it.kind == AudioRoute.BUILTIN_MIC }
}

private fun preferredOutputDevice(status: UsbAudioStatus): UsbAudioDevice? = when (status.selectedOutputRoute) {
    AudioRoute.USB -> status.outputDevices.firstOrNull { it.kind == AudioRoute.USB }
    AudioRoute.BUILTIN_SPEAKER -> status.outputDevices.firstOrNull { it.kind == AudioRoute.BUILTIN_SPEAKER }
    else -> status.outputDevices.firstOrNull { it.kind == AudioRoute.USB }
        ?: status.outputDevices.firstOrNull { it.kind == AudioRoute.BUILTIN_SPEAKER }
}

private fun usbSerialState(status: UsbSerialStatus): String = when {
    status.state == "starting" && status.activePath != null -> "waiting-helper"
    else -> status.state
}

private fun hasRuntimeUpdate(status: BridgeStatus, preview: ReleasePreview?): Boolean {
    val installed = status.installedVersion?.trim().orEmpty()
    val latest = preview?.version?.trim().orEmpty()
    return installed.isNotEmpty() && latest.isNotEmpty() && installed != latest
}

private fun UsbAudioStatus.needsRecordAudioPermission(): Boolean =
    state == "permission-required" || state == "permission-denied"

private enum class DetailSheet { Audio, Serial }
