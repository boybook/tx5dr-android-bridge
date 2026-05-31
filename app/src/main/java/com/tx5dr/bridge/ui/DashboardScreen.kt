@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.tx5dr.bridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tx5dr.bridge.AudioRoute
import com.tx5dr.bridge.BridgeStatus
import com.tx5dr.bridge.ExternalDataStatus
import com.tx5dr.bridge.R
import com.tx5dr.bridge.ReleasePreview
import com.tx5dr.bridge.RuntimeState
import com.tx5dr.bridge.UsbAudioDevice
import com.tx5dr.bridge.UsbAudioStatus
import com.tx5dr.bridge.UsbSerialStatus

private enum class RuntimeLogTab {
    Server,
    Web,
    Bridge,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    bridgeStatus: BridgeStatus,
    usbAudioStatus: UsbAudioStatus,
    usbSerialStatus: UsbSerialStatus,
    logs: String,
    lanUrls: List<String>,
    adminToken: String?,
    manifestUrl: String,
    autoStartRuntime: Boolean,
    autoOpenWebView: Boolean,
    audioBufferTargetMs: Int,
    keepAliveEnabled: Boolean,
    showInstallDialog: Boolean,
    releasePreview: ReleasePreview?,
    releasePreviewError: String?,
    showLogSheet: Boolean,
    showSettingsSheet: Boolean,
    notificationPermissionState: String,
    externalDataStatus: ExternalDataStatus,
    controlSystemBars: Boolean = true,
    onInstallClick: () -> Unit,
    onConfirmInstall: () -> Unit,
    onDismissInstallDialog: () -> Unit,
    onStartRuntime: () -> Unit,
    onStopRuntime: () -> Unit,
    onOpenWebView: () -> Unit,
    onAuthorizeAudio: () -> Unit,
    onStartSerial: () -> Unit,
    onSetKeepAlive: (Boolean) -> Unit,
    onRefreshBridges: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenDataDirectory: () -> Unit,
    onShowLogs: () -> Unit,
    onDismissLogs: () -> Unit,
    onShowSettings: () -> Unit,
    onDismissSettings: () -> Unit,
    onCopyText: (String) -> Unit,
    onRefreshLan: () -> Unit,
    onManifestUrlChange: (String) -> Unit,
    onAutoStartRuntimeChange: (Boolean) -> Unit,
    onAutoOpenWebViewChange: (Boolean) -> Unit,
    onAudioBufferTargetChange: (Int) -> Unit,
    onServiceOnlyModeChange: (Boolean) -> Unit,
) {
    var detailSheet by remember { mutableStateOf<DetailSheet?>(null) }
    var overflowExpanded by remember { mutableStateOf(false) }

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
                            Box {
                                IconButton(onClick = { overflowExpanded = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_actions))
                                }
                                DropdownMenu(
                                    expanded = overflowExpanded,
                                    onDismissRequest = { overflowExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.runtime_logs)) },
                                        onClick = {
                                            overflowExpanded = false
                                            onShowLogs()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.settings)) },
                                        onClick = {
                                            overflowExpanded = false
                                            onShowSettings()
                                        },
                                    )
                                }
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
                        adminToken = adminToken,
                        keepAliveEnabled = keepAliveEnabled,
                        updateAvailable = hasRuntimeUpdate(bridgeStatus, releasePreview),
                        onInstallClick = onInstallClick,
                        onStartRuntime = onStartRuntime,
                        onStopRuntime = onStopRuntime,
                        onOpenWebView = onOpenWebView,
                        onShowDiagnostics = onShowLogs,
                        onCopyText = onCopyText,
                        onAudioClick = { detailSheet = DetailSheet.Audio },
                        onSerialClick = { detailSheet = DetailSheet.Serial },
                        onAuthorizeAudio = onAuthorizeAudio,
                        onSetKeepAlive = onSetKeepAlive,
                        onRefreshBridges = onRefreshBridges,
                        onOpenBatterySettings = onOpenBatterySettings,
                    )
                }
            }

            if (showInstallDialog) {
                InstallRuntimeDialog(
                    bridgeStatus = bridgeStatus,
                    manifestUrl = manifestUrl,
                    releasePreview = releasePreview,
                    releasePreviewError = releasePreviewError,
                    onConfirm = onConfirmInstall,
                    onDismiss = onDismissInstallDialog,
                )
            }

            if (showLogSheet) {
                LogsSheet(
                    bridgeStatus = bridgeStatus,
                    logs = logs,
                    onDismiss = onDismissLogs,
                )
            }

            if (showSettingsSheet) {
                SettingsSheet(
                    bridgeStatus = bridgeStatus,
                    manifestUrl = manifestUrl,
                    autoStartRuntime = autoStartRuntime,
                    autoOpenWebView = autoOpenWebView,
                    notificationPermissionState = notificationPermissionState,
                    externalDataStatus = externalDataStatus,
                    onDismiss = onDismissSettings,
                    onManifestUrlChange = onManifestUrlChange,
                    onAutoStartRuntimeChange = onAutoStartRuntimeChange,
                    onAutoOpenWebViewChange = onAutoOpenWebViewChange,
                    onServiceOnlyModeChange = onServiceOnlyModeChange,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onOpenDataDirectory = onOpenDataDirectory,
                    onCopyText = onCopyText,
                    onRefreshLan = onRefreshLan,
                    onInstallClick = onInstallClick,
                )
            }

            when (detailSheet) {
                DetailSheet.Audio -> AudioDetailSheet(
                    status = usbAudioStatus,
                    audioBufferTargetMs = audioBufferTargetMs,
                    onDismiss = { detailSheet = null },
                    onAuthorizeAudio = onAuthorizeAudio,
                    onAudioBufferTargetChange = onAudioBufferTargetChange,
                    onShowDiagnostics = {
                        detailSheet = null
                        onShowLogs()
                    },
                )
                DetailSheet.Serial -> SerialDetailSheet(
                    status = usbSerialStatus,
                    onDismiss = { detailSheet = null },
                    onStartSerial = onStartSerial,
                    onCopyText = onCopyText,
                    onShowDiagnostics = {
                        detailSheet = null
                        onShowLogs()
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
    adminToken: String?,
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
                    AdminTokenCard(
                        healthy = bridgeStatus.webHealthy,
                        adminToken = adminToken,
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
                AdminTokenCard(
                    healthy = bridgeStatus.webHealthy,
                    adminToken = adminToken,
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

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(visual.icon, contentDescription = null, tint = visual.color, modifier = Modifier.size(44.dp))
        Text(visual.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        HeroStatusDetail(status = status, visual = visual)

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                !status.runtimeAbiStatus.supported -> {
                    Button(onClick = onShowDiagnostics, modifier = Modifier.widthIn(min = 144.dp)) {
                        Icon(Icons.Filled.Info, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_diagnostics))
                    }
                }
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
private fun HeroStatusDetail(
    status: BridgeStatus,
    visual: StatusVisual,
) {
    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = visual.busy,
            enter = fadeIn() + expandVertically(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    visual.subtitle,
                    modifier = Modifier.animateContentSize(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val installFraction = if (status.runtimeState == RuntimeState.Installing) status.installProgress?.fraction else null
                if (installFraction != null) {
                    LinearProgressIndicator(
                        progress = { installFraction },
                        modifier = Modifier.fillMaxWidth(0.72f),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(0.72f))
                }
            }
        }

        AnimatedVisibility(
            visible = !visual.busy && status.runtimeState != RuntimeState.NotInstalled &&
                status.runtimeState != RuntimeState.Error && status.error == null,
            enter = fadeIn() + expandVertically(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                visual.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        AnimatedVisibility(
            visible = status.runtimeState == RuntimeState.Error || status.error != null,
            enter = fadeIn() + expandVertically(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                status.error?.takeIf { it.isNotBlank() } ?: stringResource(R.string.see_diagnostics),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        AnimatedVisibility(
            visible = status.runtimeState == RuntimeState.NotInstalled,
            enter = fadeIn() + expandVertically(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                stringResource(R.string.first_use_install_runtime),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun AdminTokenCard(
    healthy: Boolean,
    adminToken: String?,
    onCopyText: (String) -> Unit,
) {
    val token = adminToken?.takeIf { it.isNotBlank() }
    if (!healthy && token == null) return
    val ready = token != null
    val cardShape = MaterialTheme.shapes.extraLarge
    Card(
        modifier = Modifier.fillMaxWidth().tx5drSoftShadow(10.dp, cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        ListItem(
            leadingContent = { Icon(Icons.Filled.VpnKey, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.admin_token_title)) },
            supportingContent = {
                Text(
                    token?.let { maskAdminToken(it) } ?: stringResource(R.string.admin_token_waiting),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                IconButton(
                    onClick = { token?.let(onCopyText) },
                    enabled = ready,
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                }
            },
        )
    }
}

private fun maskAdminToken(token: String): String {
    if (token.length <= 8) return "***"
    val edge = minOf(6, token.length / 3)
    return "${token.take(edge)}***${token.takeLast(edge)}"
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
                    supporting = serialSummary(serialStatus),
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
        trailingContent = { StateChip(state, onClick = onClick) },
    )
}

@Composable
private fun StateChip(state: String, onClick: () -> Unit = {}) {
    AssistChip(onClick = onClick, label = { Text(bridgeLabel(state)) })
}

@Composable
private fun InstallRuntimeDialog(
    bridgeStatus: BridgeStatus,
    manifestUrl: String,
    releasePreview: ReleasePreview?,
    releasePreviewError: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.PowerSettingsNew, contentDescription = null) },
        title = { Text(if (bridgeStatus.runtimeState == RuntimeState.NotInstalled) stringResource(R.string.install_dialog_title) else stringResource(R.string.install_update_dialog_title)) },
        text = {
            val installedVersion = bridgeStatus.installedVersion?.trim().orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    releasePreview != null -> {
                        val remoteVersion = releasePreview.version.trim()
                        Text(
                            when {
                                installedVersion.isBlank() -> stringResource(R.string.install_dialog_body)
                                installedVersion == remoteVersion -> stringResource(R.string.install_dialog_reinstall_body)
                                else -> stringResource(R.string.install_dialog_update_body)
                            },
                        )
                        Text(stringResource(R.string.release_current_version, installedVersion.ifBlank { stringResource(R.string.release_not_installed) }))
                        Text(stringResource(R.string.release_remote_version, remoteVersion))
                        Text(stringResource(R.string.release_size, formatBytes(releasePreview.sizeBytes)))
                    }
                    releasePreviewError != null -> {
                        Text(stringResource(R.string.install_dialog_body))
                        Text(stringResource(R.string.release_current_version, installedVersion.ifBlank { stringResource(R.string.release_not_installed) }))
                        Text(stringResource(R.string.release_preview_error, releasePreviewError), color = MaterialTheme.colorScheme.error)
                    }
                    else -> {
                        Text(stringResource(R.string.install_dialog_body))
                        Text(stringResource(R.string.release_current_version, installedVersion.ifBlank { stringResource(R.string.release_not_installed) }))
                        Text(stringResource(R.string.release_preview_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
private fun LogsSheet(
    bridgeStatus: BridgeStatus,
    logs: String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val logScrollState = rememberScrollState()
        var selectedLogTab by remember { mutableStateOf(RuntimeLogTab.Server) }
        val filteredLines = remember(logs, selectedLogTab) {
            filterRuntimeLogLines(logs, selectedLogTab)
        }
        LaunchedEffect(selectedLogTab, filteredLines.size) {
            logScrollState.scrollTo(logScrollState.maxValue)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.runtime_logs), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RuntimeLogTab.values().forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = selectedLogTab == tab,
                        onClick = { selectedLogTab = tab },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = RuntimeLogTab.values().size),
                    ) {
                        Text(runtimeLogTabLabel(tab))
                    }
                }
            }
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
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.78f)
                    .heightIn(min = 460.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .verticalScroll(logScrollState),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val lines = filteredLines.ifEmpty { listOf(stringResource(R.string.no_logs)) }
                    lines.forEach { line ->
                        LogLine(line)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun runtimeLogTabLabel(tab: RuntimeLogTab): String = when (tab) {
    RuntimeLogTab.Server -> stringResource(R.string.runtime_log_tab_server)
    RuntimeLogTab.Web -> stringResource(R.string.runtime_log_tab_web)
    RuntimeLogTab.Bridge -> stringResource(R.string.runtime_log_tab_bridge)
}

private fun filterRuntimeLogLines(logs: String, tab: RuntimeLogTab): List<String> {
    val lines = logs.lines().filter { it.isNotBlank() }
    return lines.filter { line ->
        when (tab) {
            RuntimeLogTab.Server -> isServerLogLine(line)
            RuntimeLogTab.Web -> isWebLogLine(line)
            RuntimeLogTab.Bridge -> !isServerLogLine(line) && !isWebLogLine(line)
        }
    }
}

private fun isServerLogLine(line: String): Boolean =
    "[server]" in line || "[proot]" in line

private fun isWebLogLine(line: String): Boolean =
    "[WebView]" in line || "[WebNotification]" in line

@Composable
private fun LogLine(line: String) {
    Text(
        line.ifEmpty { " " },
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = logLineColor(line),
    )
}

@Composable
private fun logLineColor(line: String): Color {
    val upper = line.uppercase()
    return when {
        "[ERROR]" in upper || " ERROR " in upper || "EXCEPTION" in upper || "FAILED" in upper ->
            MaterialTheme.colorScheme.error
        "[WARN]" in upper || " WARN " in upper || "WARNING" in upper ->
            MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    bridgeStatus: BridgeStatus,
    manifestUrl: String,
    autoStartRuntime: Boolean,
    autoOpenWebView: Boolean,
    notificationPermissionState: String,
    externalDataStatus: ExternalDataStatus,
    onDismiss: () -> Unit,
    onManifestUrlChange: (String) -> Unit,
    onAutoStartRuntimeChange: (Boolean) -> Unit,
    onAutoOpenWebViewChange: (Boolean) -> Unit,
    onServiceOnlyModeChange: (Boolean) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenDataDirectory: () -> Unit,
    onCopyText: (String) -> Unit,
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
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            SettingsGroup {
                SettingsSwitch(
                    title = stringResource(R.string.auto_start_service),
                    subtitle = stringResource(R.string.auto_start_service_subtitle),
                    checked = autoStartRuntime,
                    onCheckedChange = onAutoStartRuntimeChange,
                )
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
            DataDirectorySettingsCard(
                status = externalDataStatus,
                onCopyText = onCopyText,
                onOpenDataDirectory = onOpenDataDirectory,
            )
            SettingsGroup(containerColor = MaterialTheme.colorScheme.surface) {
                SettingsAction(
                    icon = Icons.Filled.Lan,
                    title = stringResource(R.string.refresh_lan),
                    subtitle = stringResource(R.string.refresh_lan_subtitle),
                    onClick = onRefreshLan,
                )
                HorizontalDivider()
                SettingsAction(
                    icon = Icons.Filled.BatterySaver,
                    title = stringResource(R.string.battery_optimization_settings),
                    subtitle = stringResource(R.string.battery_optimization_subtitle),
                    onClick = onOpenBatterySettings,
                )
                HorizontalDivider()
                SettingsAction(
                    icon = Icons.Filled.Notifications,
                    title = stringResource(R.string.notification_permission_settings),
                    subtitle = when (notificationPermissionState) {
                        "granted" -> stringResource(R.string.notification_permission_granted)
                        "denied" -> stringResource(R.string.notification_permission_denied)
                        else -> stringResource(R.string.notification_permission_default)
                    },
                    onClick = onOpenNotificationSettings,
                )
                HorizontalDivider()
                SettingsAction(
                    icon = Icons.Filled.PowerSettingsNew,
                    title = stringResource(R.string.install_update),
                    subtitle = if (bridgeStatus.runtimeAbiStatus.supported) {
                        stringResource(R.string.install_update_subtitle)
                    } else {
                        stringResource(R.string.runtime_unsupported_subtitle)
                    },
                    onClick = onInstallClick,
                    enabled = bridgeStatus.runtimeAbiStatus.supported,
                )
            }
            OutlinedTextField(
                value = manifestUrl,
                onValueChange = onManifestUrlChange,
                label = { Text(stringResource(R.string.manifest_url)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AudioBufferSettingsCard(selectedMs: Int, onSelect: (Int) -> Unit) {
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.audio_buffer_target_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.audio_buffer_target_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AudioBufferChip(100, stringResource(R.string.audio_buffer_target_compatible), selectedMs, onSelect)
                AudioBufferChip(60, stringResource(R.string.audio_buffer_target_standard), selectedMs, onSelect)
                AudioBufferChip(40, stringResource(R.string.audio_buffer_target_low_latency), selectedMs, onSelect)
                AudioBufferChip(30, stringResource(R.string.audio_buffer_target_aggressive), selectedMs, onSelect)
                AudioBufferChip(20, stringResource(R.string.audio_buffer_target_extreme), selectedMs, onSelect)
                AudioBufferChip(10, stringResource(R.string.audio_buffer_target_experimental), selectedMs, onSelect)
            }
        }
    }
}

@Composable
private fun AudioBufferChip(valueMs: Int, label: String, selectedMs: Int, onSelect: (Int) -> Unit) {
    AssistChip(
        onClick = { onSelect(valueMs) },
        label = { Text("$label · ${valueMs}ms") },
        leadingIcon = if (valueMs == selectedMs) {
            { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
    )
}


@Composable
private fun DataDirectorySettingsCard(
    status: ExternalDataStatus,
    onCopyText: (String) -> Unit,
    onOpenDataDirectory: () -> Unit,
) {
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsCardHeader(
                icon = Icons.Filled.Folder,
                title = stringResource(R.string.data_directory_title),
                subtitle = if (status.external) stringResource(R.string.data_directory_external_subtitle)
                else stringResource(R.string.data_directory_internal_subtitle),
                action = {
                    IconButton(
                        enabled = !status.rootPath.isNullOrBlank(),
                        onClick = { status.rootPath?.let(onCopyText) },
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy_data_directory_path))
                    }
                },
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DataDirectoryPathRow(stringResource(R.string.data_directory_user_data), status.dataPath)
                DataDirectoryPathRow(stringResource(R.string.data_directory_logs), status.logsPath)
                DataDirectoryPathRow(stringResource(R.string.data_directory_plugins), status.pluginsPath)
                DataDirectoryPathRow(stringResource(R.string.data_directory_plugin_data), status.pluginDataPath)
            }
            status.fallbackReason?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(
                onClick = onOpenDataDirectory,
                modifier = Modifier.align(Alignment.Start),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.open_data_directory))
            }
        }
    }
}

@Composable
private fun SettingsCardHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action()
    }
}

@Composable
private fun DataDirectoryPathRow(label: String, path: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.width(76.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            path ?: stringResource(R.string.not_available),
            modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SettingsGroup(
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.fillMaxWidth()) {
            content()
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

@Composable
private fun SettingsAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.48f)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioDetailSheet(
    status: UsbAudioStatus,
    audioBufferTargetMs: Int,
    onDismiss: () -> Unit,
    onAuthorizeAudio: () -> Unit,
    onAudioBufferTargetChange: (Int) -> Unit,
    onShowDiagnostics: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val sheetScrollState = rememberScrollState()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(sheetScrollState)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.audio_route),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                StateChip(status.state)
            }
            Text(
                stringResource(R.string.audio_devices_available_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AudioBufferSettingsCard(
                selectedMs = audioBufferTargetMs,
                onSelect = onAudioBufferTargetChange,
            )
            AudioDeviceListSection(
                title = stringResource(R.string.audio_input_devices),
                emptyText = stringResource(R.string.no_input_devices_detected),
                devices = status.inputDevices,
            )
            AudioDeviceListSection(
                title = stringResource(R.string.audio_output_devices),
                emptyText = stringResource(R.string.no_output_devices_detected),
                devices = status.outputDevices,
            )
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
private fun AudioDeviceListSection(
    title: String,
    emptyText: String,
    devices: List<UsbAudioDevice>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (devices.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    devices.forEachIndexed { index, device ->
                        AudioDeviceRow(device)
                        if (index != devices.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioDeviceRow(device: UsbAudioDevice) {
    ListItem(
        headlineContent = { Text(displayAudioDeviceName(device), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                stringResource(R.string.audio_device_socket_format, device.socketPath),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(
                    if (device.connected) R.string.cd_audio_device_connected else R.string.cd_audio_device_ready
                ),
                tint = if (device.connected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SerialDetailSheet(
    status: UsbSerialStatus,
    onDismiss: () -> Unit,
    onStartSerial: () -> Unit,
    onCopyText: (String) -> Unit,
    onShowDiagnostics: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.usb_serial),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                StateChip(usbSerialState(status))
            }
            SerialDeviceList(status, onCopyText)
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
private fun SerialDeviceList(status: UsbSerialStatus, onCopyText: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.devices), style = MaterialTheme.typography.labelLarge)
        if (status.devices.isEmpty()) {
            Text(stringResource(R.string.no_device_detected), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    status.devices.forEachIndexed { index, device ->
                        ListItem(
                            headlineContent = { Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(stringResource(R.string.path_format, device.path), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(stringResource(R.string.serial_bridge_socket_format, device.bridgeSocket), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    device.error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                }
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SerialDeviceStatusIcon(device)
                                    IconButton(onClick = { onCopyText(device.path) }) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy_serial_path))
                                    }
                                }
                            },
                        )
                        if (index != status.devices.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SerialDeviceStatusIcon(device: com.tx5dr.bridge.AndroidSerialDevice) {
    when {
        device.error != null -> Icon(
            Icons.Filled.Error,
            contentDescription = bridgeLabel("error"),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        device.granted -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = stringResource(
                if (device.connected) R.string.cd_serial_device_connected else R.string.cd_serial_device_ready
            ),
            tint = if (device.connected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        else -> StateChip("permission-required")
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
    return stringResource(R.string.audio_device_count_summary, status.inputDevices.size, status.outputDevices.size)
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
private fun serialSummary(status: UsbSerialStatus): String = when {
    status.mappedCount > 1 -> stringResource(R.string.serial_mapped_count, status.mappedCount)
    status.activePath != null -> status.activePath
    status.devices.isNotEmpty() -> status.devices.first().name
    else -> stringResource(R.string.serial_auto_detect)
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
