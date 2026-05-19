package com.tx5dr.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.tx5dr.bridge.ui.DashboardScreen
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var bridgeStatus by mutableStateOf(BridgeStatus())
    private var usbAudioStatus by mutableStateOf(UsbAudioStatus())
    private var usbSerialStatus by mutableStateOf(UsbSerialStatus())
    private var logs by mutableStateOf("")
    private var lanUrls by mutableStateOf<List<String>>(emptyList())
    private var adminToken by mutableStateOf<String?>(null)
    private var manifestUrl by mutableStateOf("")
    private var autoOpenWebView by mutableStateOf(true)
    private var serviceOnlyMode by mutableStateOf(false)
    private var keepAliveEnabled by mutableStateOf(false)
    private var showInstallDialog by mutableStateOf(false)
    private var releasePreview by mutableStateOf<ReleasePreview?>(null)
    private var releasePreviewError by mutableStateOf<String?>(null)
    private var showLogSheet by mutableStateOf(false)
    private var showSettingsSheet by mutableStateOf(false)
    private var notificationPermissionState by mutableStateOf("default")
    private var webVisible by mutableStateOf(false)
    private var webSuppressedForSession by mutableStateOf(false)
    private var micWanted = false
    private lateinit var rootContainer: FrameLayout
    private var nativeWebView: WebView? = null
    private var webNotificationBridge: AndroidWebNotificationBridge? = null
    private var pendingFilePathCallback: ValueCallback<Array<Uri>>? = null
    private val webDownloadLock = Any()
    private var activeWebDownload: PendingWebDownload? = null
    private var pendingWebDownloadSave: PendingWebDownload? = null
    private var lastReleasePreviewUrl: String? = null
    private var lastReleasePreviewAtMs: Long = 0L
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingFilePathCallback ?: return@registerForActivityResult
        pendingFilePathCallback = null
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
            null
        }
        LogBus.i("WebView", "File chooser returned ${uris?.size ?: 0} file(s)")
        callback.onReceiveValue(uris)
    }
    private val downloadSaveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val download = pendingWebDownloadSave ?: return@registerForActivityResult
        pendingWebDownloadSave = null
        if (result.resultCode != Activity.RESULT_OK || result.data?.data == null) {
            LogBus.i("WebView", "Download save cancelled: ${download.fileName}")
            download.cleanup()
            return@registerForActivityResult
        }
        val target = result.data?.data!!
        runCatching {
            contentResolver.openOutputStream(target)?.use { output ->
                download.file.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Unable to open selected file for writing")
        }.onSuccess {
            LogBus.i("WebView", "Saved download ${download.fileName}: ${formatBytes(download.bytesWritten)}")
        }.onFailure { error ->
            LogBus.w("WebView", "Failed to save download ${download.fileName}: ${error.message}")
        }
        download.cleanup()
    }

    private val statusListener: (BridgeStatus) -> Unit = { status ->
        runOnUiThread {
            bridgeStatus = status
            refreshAdminToken()
            if (status.serverHealthy && status.webHealthy && autoOpenWebView && !serviceOnlyMode && !webVisible && !webSuppressedForSession) {
                openWebView()
            }
        }
    }
    private val logListener: (String) -> Unit = { text -> runOnUiThread { logs = text } }
    private val usbAudioListener: (UsbAudioStatus) -> Unit = { status -> runOnUiThread { usbAudioStatus = status } }
    private val usbSerialListener: (UsbSerialStatus) -> Unit = { status -> runOnUiThread { usbSerialStatus = status } }
    private val networkListener: (NetworkAccessProvider.Snapshot) -> Unit = { snapshot ->
        runOnUiThread {
            lanUrls = snapshot.urls
            refreshAdminToken()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPreferences()
        manifestUrl = BridgeRuntime.getManifestUrl()
        refreshLanUrls()
        refreshAdminToken()
        bridgeStatus = BridgeRuntime.snapshotStatus()
        logs = LogBus.snapshot()
        checkRemoteVersion(force = true)
        rootContainer = FrameLayout(this)
        val composeView = ComposeView(this).apply {
            setContent {
                DashboardScreen(
                    bridgeStatus = bridgeStatus,
                    usbAudioStatus = usbAudioStatus,
                    usbSerialStatus = usbSerialStatus,
                    logs = logs,
                    lanUrls = lanUrls,
                    adminToken = adminToken,
                    manifestUrl = manifestUrl,
                    autoOpenWebView = autoOpenWebView,
                    keepAliveEnabled = keepAliveEnabled,
                    showInstallDialog = showInstallDialog,
                    releasePreview = releasePreview,
                    releasePreviewError = releasePreviewError,
                    showLogSheet = showLogSheet,
                    showSettingsSheet = showSettingsSheet,
                    notificationPermissionState = notificationPermissionState,
                    controlSystemBars = !webVisible,
                    onInstallClick = { prepareInstallDialog() },
                    onConfirmInstall = { showInstallDialog = false; startInstall() },
                    onDismissInstallDialog = { showInstallDialog = false },
                    onStartRuntime = { startRuntime() },
                    onStopRuntime = { stopRuntime() },
                    onOpenWebView = { openWebView() },
                    onAuthorizeAudio = { authorizeAudio() },
                    onSetAudioInputRoute = { AndroidUsbAudioBridge.setInputRoute(this@MainActivity, it) },
                    onSetAudioOutputRoute = { AndroidUsbAudioBridge.setOutputRoute(this@MainActivity, it) },
                    onStartSerial = { startSerialWithPermission() },
                    onSetKeepAlive = { setKeepAlive(it) },
                    onOpenBatterySettings = { openBatterySettings() },
                    onOpenNotificationSettings = { openNotificationSettings() },
                    onRefreshBridges = { BridgeService.start(this@MainActivity, BridgeService.ACTION_START_BRIDGES) },
                    onShowLogs = { showLogSheet = true },
                    onDismissLogs = { showLogSheet = false },
                    onShowSettings = { showSettingsSheet = true },
                    onDismissSettings = { showSettingsSheet = false },
                    onCopyText = { copyToClipboard(it) },
                    onRefreshLan = { refreshLanUrls() },
                    onManifestUrlChange = {
                        manifestUrl = it
                        releasePreview = null
                        releasePreviewError = null
                    },
                    onAutoOpenWebViewChange = { setBooleanPreference(BridgeRuntime.PREF_AUTO_OPEN_WEBVIEW, it) },
                    onServiceOnlyModeChange = { value ->
                        setBooleanPreference(BridgeRuntime.PREF_SERVICE_ONLY_MODE, value)
                        if (value) releaseWebView()
                    },
                )
            }
        }
        rootContainer.addView(composeView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(rootContainer)
        BridgeRuntime.addStatusListener(statusListener)
        LogBus.addListener(logListener)
        NetworkAccessProvider.addListener(networkListener)
        AndroidUsbAudioBridge.addListener(usbAudioListener)
        AndroidUsbSerialBridge.addListener(usbSerialListener)
        AndroidUsbAudioBridge.refreshDevices(this)
        AndroidUsbSerialBridge.refreshDevices(this, BridgeRuntime.paths.androidSerialDevicesFile)
        BridgeService.start(this, BridgeService.ACTION_BOOTSTRAP)
        handleLaunchIntent(intent)
        LogBus.i("Tx5drBridge", "MainActivity created")
    }

    override fun onResume() {
        super.onResume()
        loadPreferences()
        updateNotificationPermissionState()
        refreshLanUrls()
        refreshAdminToken()
        AndroidUsbAudioBridge.refreshDevices(this)
        AndroidUsbSerialBridge.refreshDevices(this, BridgeRuntime.paths.androidSerialDevicesFile)
        if (AndroidUsbAudioBridge.hasRecordPermission(this) && BridgeRuntime.getPreference(BridgeRuntime.PREF_AUTO_START_BRIDGES, true)) {
            AndroidUsbAudioBridge.startIfPermitted(this)
        }
        checkRemoteVersion()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onDestroy() {
        destroyNativeWebView()
        BridgeRuntime.removeStatusListener(statusListener)
        LogBus.removeListener(logListener)
        NetworkAccessProvider.removeListener(networkListener)
        AndroidUsbAudioBridge.removeListener(usbAudioListener)
        AndroidUsbSerialBridge.removeListener(usbSerialListener)
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (webVisible) {
            nativeWebView?.let { webView ->
                if (webView.canGoBack()) {
                    webView.goBack()
                    return
                }
            }
            releaseWebView()
            return
        }
        super.onBackPressed()
    }

    private fun loadPreferences() {
        autoOpenWebView = BridgeRuntime.getPreference(BridgeRuntime.PREF_AUTO_OPEN_WEBVIEW, true)
        serviceOnlyMode = BridgeRuntime.getPreference(BridgeRuntime.PREF_SERVICE_ONLY_MODE, false)
        keepAliveEnabled = BridgeRuntime.getPreference(BridgeRuntime.PREF_KEEP_ALIVE_ENABLED, false)
    }

    private fun setBooleanPreference(key: String, value: Boolean) {
        BridgeRuntime.setPreference(key, value)
        loadPreferences()
    }

    private fun refreshLanUrls() {
        lanUrls = BridgeRuntime.refreshNetworkAccess().urls
        refreshAdminToken()
    }

    private fun refreshAdminToken() {
        adminToken = BridgeRuntime.getAdminToken()
    }

    private fun openWebView() {
        refreshAdminToken()
        val token = adminToken
        if (token == null) {
            LogBus.w("Tx5drBridge", "Admin token not found; wait until TX-5DR server is ready before opening WebView")
            return
        }
        webSuppressedForSession = false
        webVisible = true
        LogBus.i("Tx5drBridge", "Opening WebView with admin token")
        showNativeWebView(
            Uri.parse(WEB_URL)
                .buildUpon()
                .appendQueryParameter("auth_token", token)
                .build()
                .toString()
        )
    }

    private fun releaseWebView() {
        if (!webVisible) return
        LogBus.i("Tx5drBridge", "Releasing WebView and returning to service status")
        webSuppressedForSession = true
        webVisible = false
        destroyNativeWebView()
        refreshLanUrls()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showNativeWebView(url: String) {
        destroyNativeWebView()
        val webView = WebView(this).apply {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            configureWebViewTheme(settings)
            addJavascriptInterface(WebChromeBridge(), WEB_CHROME_BRIDGE)
            addJavascriptInterface(WebDownloadBridge(), WEB_DOWNLOAD_BRIDGE)
            val notificationBridge = AndroidWebNotificationBridge(this@MainActivity, this, url)
            webNotificationBridge = notificationBridge
            addJavascriptInterface(notificationBridge, WEB_NOTIFICATION_BRIDGE)
            webChromeClient = createWebChromeClient()
            setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, contentLength ->
                handleWebViewDownload(downloadUrl, userAgent, contentDisposition, mimeType, contentLength)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    notificationBridge.updateUrl(url)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val requestUrl = request.url
                    if (isAllowedWebUrl(requestUrl)) return false
                    LogBus.w("WebView", "Blocked external navigation: $requestUrl")
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, requestUrl)) }
                    return true
                }

                override fun onPageCommitVisible(view: WebView, url: String) {
                    notificationBridge.updateUrl(url)
                    installWebChromeProbe(view)
                    installWebDownloadBridge(view)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    notificationBridge.updateUrl(url)
                    LogBus.i("WebView", "Page finished: ${url.substringBefore('?')}")
                    installWebChromeProbe(view)
                    installWebDownloadBridge(view)
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    LogBus.w("WebView", "HTTP ${errorResponse.statusCode} ${request.url}")
                }
            }
            isFocusable = true
            isFocusableInTouchMode = true
            loadUrl(url)
        }
        applyWebStatusBarFallback()
        rootContainer.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        nativeWebView = webView
    }

    private fun createWebChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG) {
                LogBus.i(
                    "WebView",
                    "${consoleMessage.messageLevel()} ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} ${consoleMessage.message()}"
                )
            }
            return BuildConfig.DEBUG
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            pendingFilePathCallback?.onReceiveValue(null)
            pendingFilePathCallback = filePathCallback
            val intent = runCatching { fileChooserParams.createIntent() }
                .getOrElse { error ->
                    LogBus.w("WebView", "Failed to create file chooser intent: ${error.message}")
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                }
                .apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            return try {
                fileChooserLauncher.launch(intent)
                true
            } catch (error: ActivityNotFoundException) {
                LogBus.w("WebView", "No Android file picker available: ${error.message}")
                pendingFilePathCallback = null
                filePathCallback.onReceiveValue(null)
                true
            } catch (error: RuntimeException) {
                LogBus.w("WebView", "Unable to open Android file picker: ${error.message}")
                pendingFilePathCallback = null
                filePathCallback.onReceiveValue(null)
                true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureWebViewTheme(settings: WebSettings) {
        val darkMode = isSystemDarkMode()
        if (Build.VERSION.SDK_INT >= 33) {
            // Let the TX-5DR web app stay in its default "system" mode via
            // prefers-color-scheme; do not algorithmically rewrite page colors.
            settings.isAlgorithmicDarkeningAllowed = false
        } else if (Build.VERSION.SDK_INT >= 29) {
            settings.forceDark = WebSettings.FORCE_DARK_AUTO
        }
        LogBus.i("WebView", "System theme for WebView: ${if (darkMode) "dark" else "light"}")
    }

    private fun installWebChromeProbe(webView: WebView) {
        val js = """
            (function() {
              if (window.__tx5drAndroidChromeProbeInstalled) {
                window.__tx5drAndroidChromeProbeSample && window.__tx5drAndroidChromeProbeSample();
                return;
              }
              window.__tx5drAndroidChromeProbeInstalled = true;
              function usableBackground(color) {
                if (!color || color === 'transparent') return false;
                const rgba = color.match(/rgba?\(([^)]+)\)/);
                if (!rgba) return true;
                const parts = rgba[1].split(',').map((part) => part.trim());
                if (parts.length >= 4 && Number(parts[3]) < 0.35) return false;
                return !(Number(parts[0]) === 0 && Number(parts[1]) === 0 && Number(parts[2]) === 0 && parts.length >= 4 && Number(parts[3]) === 0);
              }
              function readColorAt(x, y) {
                let element = document.elementFromPoint(Math.max(1, Math.min(window.innerWidth - 1, x)), Math.max(1, y));
                while (element) {
                  const style = window.getComputedStyle(element);
                  if (usableBackground(style.backgroundColor)) return style.backgroundColor;
                  element = element.parentElement;
                }
                const body = document.body ? window.getComputedStyle(document.body).backgroundColor : null;
                if (usableBackground(body)) return body;
                const root = document.documentElement ? window.getComputedStyle(document.documentElement).backgroundColor : null;
                if (usableBackground(root)) return root;
                const meta = document.querySelector('meta[name="theme-color"]');
                return meta ? meta.getAttribute('content') : null;
              }
              let pending = false;
              window.__tx5drAndroidChromeProbeSample = function() {
                if (pending) return;
                pending = true;
                window.requestAnimationFrame(function() {
                  pending = false;
                  try {
                    const topColor = readColorAt(window.innerWidth / 2, 1) || readColorAt(8, 1) || readColorAt(window.innerWidth - 8, 1);
                    const bottomY = Math.max(1, window.innerHeight - 2);
                    const bottomColor = readColorAt(window.innerWidth / 2, bottomY) || readColorAt(8, bottomY) || readColorAt(window.innerWidth - 8, bottomY);
                    if (window.$WEB_CHROME_BRIDGE) {
                      if (topColor) window.$WEB_CHROME_BRIDGE.setStatusBarColor(topColor);
                      if (bottomColor) window.$WEB_CHROME_BRIDGE.setNavigationBarColor(bottomColor);
                    }
                  } catch (error) {}
                });
              };
              document.addEventListener('scroll', window.__tx5drAndroidChromeProbeSample, true);
              window.addEventListener('resize', window.__tx5drAndroidChromeProbeSample);
              document.addEventListener('transitionend', window.__tx5drAndroidChromeProbeSample, true);
              document.addEventListener('touchend', window.__tx5drAndroidChromeProbeSample, true);
              try {
                new MutationObserver(window.__tx5drAndroidChromeProbeSample)
                  .observe(document.documentElement, { attributes: true, childList: true, subtree: true, attributeFilter: ['class', 'style'] });
              } catch (error) {}
              window.__tx5drAndroidChromeProbeSample();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun installWebDownloadBridge(webView: WebView) {
        val js = """
            (function() {
              if (window.__tx5drAndroidDownloadBridgeInstalled) return;
              window.__tx5drAndroidDownloadBridgeInstalled = true;
              const bridge = window.$WEB_DOWNLOAD_BRIDGE;
              if (!bridge) return;
              const maxBytes = $MAX_WEB_DOWNLOAD_BYTES;
              const chunkBytes = 512 * 1024;
              function fallbackName(name) {
                return (name && String(name).trim()) || 'tx5dr-download';
              }
              function readSliceAsBase64(slice) {
                return new Promise(function(resolve, reject) {
                  const reader = new FileReader();
                  reader.onerror = function() { reject(reader.error || new Error('read failed')); };
                  reader.onload = function() {
                    const result = String(reader.result || '');
                    const comma = result.indexOf(',');
                    resolve(comma >= 0 ? result.slice(comma + 1) : result);
                  };
                  reader.readAsDataURL(slice);
                });
              }
              async function saveBlob(fileName, blob) {
                if (!blob) throw new Error('empty blob');
                if (blob.size > maxBytes) throw new Error('download too large: ' + blob.size);
                const mimeType = blob.type || 'application/octet-stream';
                const id = bridge.beginDownload(fallbackName(fileName), mimeType, String(blob.size));
                if (!id) throw new Error('native download rejected');
                try {
                  for (let offset = 0; offset < blob.size; offset += chunkBytes) {
                    const base64 = await readSliceAsBase64(blob.slice(offset, Math.min(offset + chunkBytes, blob.size)));
                    if (!bridge.appendDownloadChunk(id, base64)) throw new Error('native chunk rejected');
                  }
                  if (!bridge.finishDownload(id)) throw new Error('native download finish rejected');
                } catch (error) {
                  try { bridge.cancelDownload(id); } catch (_) {}
                  throw error;
                }
              }
              document.addEventListener('click', function(event) {
                const anchor = event.target && event.target.closest ? event.target.closest('a[download]') : null;
                if (!anchor) return;
                const href = anchor.href || '';
                if (!href.startsWith('blob:') && !href.startsWith('data:')) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                (async function() {
                  try {
                    const response = await fetch(href);
                    const blob = await response.blob();
                    await saveBlob(anchor.getAttribute('download'), blob);
                  } catch (error) {
                    console.error('[TX-5DR Android] download bridge failed', error);
                  }
                })();
              }, true);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun handleWebViewDownload(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        val value = url?.takeIf { it.isNotBlank() } ?: return
        if (value.startsWith("blob:") || value.startsWith("data:")) {
            LogBus.w("WebView", "Ignoring ${value.substringBefore(':')} download listener event; JS bridge should handle it")
            return
        }
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return
        if (uri.scheme != "http" && uri.scheme != "https") {
            LogBus.w("WebView", "Unsupported download URL: $value")
            return
        }
        val fileName = sanitizeFileName(URLUtil.guessFileName(value, contentDisposition, mimeType))
        val request = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setDescription("TX-5DR")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
        if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(value)?.takeIf { it.isNotBlank() }?.let { cookie ->
            request.addRequestHeader("Cookie", cookie)
        }
        runCatching {
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
        }.onSuccess {
            LogBus.i("WebView", "Queued HTTP download $fileName (${formatBytes(contentLength)})")
        }.onFailure { error ->
            LogBus.w("WebView", "Failed to queue HTTP download $fileName: ${error.message}")
        }
    }

    private inner class WebChromeBridge {
        @JavascriptInterface
        fun setStatusBarColor(cssColor: String?) {
            if (!webVisible) return
            val color = parseCssColor(cssColor) ?: return
            runOnUiThread {
                if (webVisible) applyStatusBarColor(color)
            }
        }

        @JavascriptInterface
        fun setNavigationBarColor(cssColor: String?) {
            if (!webVisible) return
            val color = parseCssColor(cssColor) ?: return
            runOnUiThread {
                if (webVisible) applyNavigationBarColor(color)
            }
        }
    }

    private inner class WebDownloadBridge {
        @JavascriptInterface
        fun beginDownload(fileName: String?, mimeType: String?, totalBytes: String?): String {
            val expectedBytes = totalBytes?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            if (expectedBytes > MAX_WEB_DOWNLOAD_BYTES) {
                LogBus.w("WebView", "Download too large: ${formatBytes(expectedBytes)}")
                return ""
            }
            return synchronized(webDownloadLock) {
                if (pendingWebDownloadSave != null) {
                    LogBus.w("WebView", "Another download is waiting for a save location")
                    return@synchronized ""
                }
                activeWebDownload?.cleanup()
                val download = createPendingWebDownload(fileName, mimeType)
                activeWebDownload = download
                LogBus.i("WebView", "Receiving download ${download.fileName} (${formatBytes(expectedBytes)})")
                download.id
            }
        }

        @JavascriptInterface
        fun appendDownloadChunk(id: String?, base64Payload: String?): Boolean {
            val chunk = stripDataUrlPrefix(base64Payload)
            if (id.isNullOrBlank() || chunk.isBlank()) return false
            return synchronized(webDownloadLock) {
                val download = activeWebDownload?.takeIf { it.id == id } ?: return@synchronized false
                val decodedSize = estimateBase64DecodedBytes(chunk)
                if (download.bytesWritten + decodedSize > MAX_WEB_DOWNLOAD_BYTES) {
                    LogBus.w("WebView", "Download exceeded ${formatBytes(MAX_WEB_DOWNLOAD_BYTES)}: ${download.fileName}")
                    download.cleanup()
                    activeWebDownload = null
                    return@synchronized false
                }
                runCatching {
                    val bytes = Base64.decode(chunk, Base64.DEFAULT)
                    download.write(bytes)
                }.onFailure { error ->
                    LogBus.w("WebView", "Failed to append download chunk: ${error.message}")
                    download.cleanup()
                    activeWebDownload = null
                }.isSuccess
            }
        }

        @JavascriptInterface
        fun finishDownload(id: String?): Boolean {
            if (id.isNullOrBlank()) return false
            val download = synchronized(webDownloadLock) {
                val current = activeWebDownload?.takeIf { it.id == id } ?: return@synchronized null
                activeWebDownload = null
                current.finish()
                current
            } ?: return false
            launchWebDownloadSave(download)
            return true
        }

        @JavascriptInterface
        fun cancelDownload(id: String?) {
            synchronized(webDownloadLock) {
                val download = activeWebDownload?.takeIf { it.id == id } ?: return@synchronized
                activeWebDownload = null
                download.cleanup()
                LogBus.i("WebView", "Cancelled download ${download.fileName}")
            }
        }

        @JavascriptInterface
        fun saveBase64Download(fileName: String?, mimeType: String?, base64Payload: String?): Boolean {
            val chunk = stripDataUrlPrefix(base64Payload)
            if (chunk.isBlank()) return false
            if (estimateBase64DecodedBytes(chunk) > MAX_WEB_DOWNLOAD_BYTES) {
                LogBus.w("WebView", "Download too large for single-shot save")
                return false
            }
            val download = synchronized(webDownloadLock) {
                if (pendingWebDownloadSave != null) return@synchronized null
                activeWebDownload?.cleanup()
                activeWebDownload = null
                createPendingWebDownload(fileName, mimeType)
            } ?: return false
            return runCatching {
                download.write(Base64.decode(chunk, Base64.DEFAULT))
                download.finish()
                launchWebDownloadSave(download)
            }.onFailure { error ->
                LogBus.w("WebView", "Failed to receive download: ${error.message}")
                download.cleanup()
            }.isSuccess
        }
    }

    private data class PendingWebDownload(
        val id: String,
        val fileName: String,
        val mimeType: String,
        val file: File,
        private val output: FileOutputStream,
        var bytesWritten: Long = 0L,
    ) {
        fun write(bytes: ByteArray) {
            output.write(bytes)
            bytesWritten += bytes.size
        }

        fun finish() {
            output.flush()
            output.close()
        }

        fun cleanup() {
            runCatching { output.close() }
            runCatching { file.delete() }
        }
    }

    private fun createPendingWebDownload(fileName: String?, mimeType: String?): PendingWebDownload {
        val safeName = sanitizeFileName(fileName)
        val dir = File(cacheDir, "web-downloads").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}-$safeName")
        return PendingWebDownload(
            id = UUID.randomUUID().toString(),
            fileName = safeName,
            mimeType = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
            file = file,
            output = FileOutputStream(file),
        )
    }

    private fun launchWebDownloadSave(download: PendingWebDownload) {
        runOnUiThread {
            pendingWebDownloadSave?.cleanup()
            pendingWebDownloadSave = download
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = download.mimeType
                putExtra(Intent.EXTRA_TITLE, download.fileName)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            try {
                downloadSaveLauncher.launch(intent)
            } catch (error: RuntimeException) {
                LogBus.w("WebView", "Unable to open save picker: ${error.message}")
                pendingWebDownloadSave = null
                download.cleanup()
            }
        }
    }

    private fun stripDataUrlPrefix(value: String?): String {
        val text = value?.trim().orEmpty()
        val comma = text.indexOf(',')
        return if (text.startsWith("data:", ignoreCase = true) && comma >= 0) text.substring(comma + 1) else text
    }

    private fun estimateBase64DecodedBytes(base64: String): Long {
        val cleanLength = base64.count { !it.isWhitespace() }
        val padding = base64.takeLast(2).count { it == '=' }
        return ((cleanLength * 3L) / 4L - padding).coerceAtLeast(0L)
    }

    private fun sanitizeFileName(value: String?): String {
        val raw = value?.trim().takeUnless { it.isNullOrBlank() } ?: "tx5dr-download"
        val sanitized = raw.map { ch ->
            if (ch.code < 32 || ch == '\\' || ch == '/' || ch == ':' || ch == '*' || ch == '?' || ch == '"' || ch == '<' || ch == '>' || ch == '|') '_' else ch
        }.joinToString("").trim().take(160)
        return sanitized.ifBlank { "tx5dr-download" }
    }

    private fun formatBytes(value: Long): String {
        if (value < 0) return "unknown size"
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB")
        var size = value.toDouble() / 1024.0
        var unit = 0
        while (size >= 1024.0 && unit < units.lastIndex) {
            size /= 1024.0
            unit++
        }
        return String.format(java.util.Locale.US, "%.1f %s", size, units[unit])
    }

    private fun applyWebStatusBarFallback() {
        applyStatusBarColor(if (isSystemDarkMode()) Color.rgb(18, 17, 19) else Color.rgb(255, 255, 255))
        applyNavigationBarColor(if (isSystemDarkMode()) Color.rgb(37, 37, 40) else Color.rgb(255, 255, 255))
    }

    private fun applyStatusBarColor(color: Int) {
        window.statusBarColor = color
        @Suppress("DEPRECATION")
        var flags = window.decorView.systemUiVisibility
        @Suppress("DEPRECATION")
        flags = if (isLightColor(color)) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }

    private fun applyNavigationBarColor(color: Int) {
        window.navigationBarColor = color
        if (Build.VERSION.SDK_INT < 26) return
        @Suppress("DEPRECATION")
        var flags = window.decorView.systemUiVisibility
        @Suppress("DEPRECATION")
        flags = if (isLightColor(color)) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }

    private fun parseCssColor(cssColor: String?): Int? {
        val value = cssColor?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return runCatching {
            if (value.startsWith("#")) return@runCatching Color.parseColor(value)
            val match = Regex("""rgba?\(([^)]+)\)""").find(value) ?: return@runCatching null
            val parts = match.groupValues[1].split(',').map { it.trim() }
            if (parts.size < 3) return@runCatching null
            Color.rgb(parts[0].toFloat().toInt().coerceIn(0, 255), parts[1].toFloat().toInt().coerceIn(0, 255), parts[2].toFloat().toInt().coerceIn(0, 255))
        }.getOrNull()
    }

    private fun isLightColor(color: Int): Boolean {
        val red = Color.red(color) / 255.0
        val green = Color.green(color) / 255.0
        val blue = Color.blue(color) / 255.0
        val luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
        return luminance > 0.62
    }

    private fun isAllowedWebUrl(uri: Uri): Boolean {
        if (uri.scheme == "about") return true
        return uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == WEB_PORT
    }

    private fun isSystemDarkMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun destroyNativeWebView() {
        pendingFilePathCallback?.onReceiveValue(null)
        pendingFilePathCallback = null
        synchronized(webDownloadLock) {
            activeWebDownload?.cleanup()
            activeWebDownload = null
            pendingWebDownloadSave?.cleanup()
            pendingWebDownloadSave = null
        }
        webNotificationBridge = null
        nativeWebView?.let { view ->
            runCatching { rootContainer.removeView(view) }
            runCatching { view.stopLoading() }
            runCatching { view.loadUrl("about:blank") }
            runCatching { view.clearHistory() }
            runCatching { view.destroy() }
        }
        nativeWebView = null
    }

    private fun startInstall() {
        BridgeRuntime.setManifestUrl(manifestUrl)
        BridgeService.start(this, BridgeService.ACTION_INSTALL)
    }

    private fun prepareInstallDialog() {
        BridgeRuntime.setManifestUrl(manifestUrl)
        releasePreview = null
        releasePreviewError = null
        showInstallDialog = true
        BridgeRuntime.fetchReleasePreview(manifestUrl) { preview, error ->
            releasePreview = preview
            releasePreviewError = error
        }
    }

    private fun checkRemoteVersion(force: Boolean = false) {
        val url = manifestUrl.trim()
        if (url.isBlank()) return
        val now = System.currentTimeMillis()
        if (!force && lastReleasePreviewUrl == url && now - lastReleasePreviewAtMs < RELEASE_PREVIEW_MIN_INTERVAL_MS) return
        lastReleasePreviewUrl = url
        lastReleasePreviewAtMs = now
        BridgeRuntime.setManifestUrl(url)
        BridgeRuntime.fetchReleasePreview(url) { preview, error ->
            releasePreview = preview
            releasePreviewError = error
        }
    }

    private fun startRuntime() {
        BridgeRuntime.setManifestUrl(manifestUrl)
        BridgeService.start(this, BridgeService.ACTION_START_RUNTIME)
    }

    private fun stopRuntime() {
        BridgeService.start(this, BridgeService.ACTION_STOP_RUNTIME)
    }

    private fun authorizeAudio() {
        if (!AndroidUsbAudioBridge.hasRecordPermission(this)) {
            if (Build.VERSION.SDK_INT >= 23 &&
                usbAudioStatus.state == "permission-denied" &&
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            ) {
                LogBus.w("AudioBridge", "Microphone permission denied permanently; opening app settings")
                openAppSettings()
                return
            }
            micWanted = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
        } else {
            AndroidUsbAudioBridge.start(this)
        }
    }

    private fun startSerialWithPermission() {
        AndroidUsbSerialBridge.start(this, BridgeRuntime.paths.androidSerialDevicesFile)
        BridgeRuntime.startLinuxSerialSide()
    }

    private fun setKeepAlive(enabled: Boolean) {
        keepAliveEnabled = enabled
        BridgeService.start(
            this,
            Intent(this, BridgeService::class.java)
                .setAction(BridgeService.ACTION_SET_KEEPALIVE)
                .putExtra(BridgeService.EXTRA_ENABLED, enabled)
        )
    }

    private fun openBatterySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure {
            openAppSettings()
        }
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }.onFailure { openAppSettings() }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            updateNotificationPermissionState()
            webNotificationBridge?.onPermissionResult()
            return
        }
        if (requestCode == REQ_RECORD_AUDIO && micWanted) {
            micWanted = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                AndroidUsbAudioBridge.start(this)
            } else {
                LogBus.w("AudioBridge", "Microphone permission denied")
                AndroidUsbAudioBridge.markPermissionDenied()
            }
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_WEBVIEW) {
            webSuppressedForSession = false
            openWebView()
        }
    }

    private fun updateNotificationPermissionState() {
        notificationPermissionState = AndroidWebNotificationBridge.readPermissionState(this)
    }

    private fun copyToClipboard(value: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("TX-5DR", value))
        Toast.makeText(this, getString(R.string.copy_success), Toast.LENGTH_SHORT).show()
        LogBus.i("Tx5drBridge", "Copied to clipboard: $value")
    }

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
        const val REQ_POST_NOTIFICATIONS = 1002
        private const val WEB_PORT = 8076
        private const val WEB_URL = "http://127.0.0.1:8076"
        private const val WEB_CHROME_BRIDGE = "Tx5drAndroidChrome"
        private const val WEB_DOWNLOAD_BRIDGE = "Tx5drAndroidDownloads"
        private const val WEB_NOTIFICATION_BRIDGE = "Tx5drAndroidNotifications"
        const val ACTION_OPEN_WEBVIEW = "com.tx5dr.bridge.OPEN_WEBVIEW"
        private const val MAX_WEB_DOWNLOAD_BYTES = 128L * 1024L * 1024L
        private const val RELEASE_PREVIEW_MIN_INTERVAL_MS = 10 * 60 * 1000L
    }
}
