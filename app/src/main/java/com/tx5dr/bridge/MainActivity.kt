package com.tx5dr.bridge

import android.Manifest
import android.annotation.SuppressLint
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
import android.provider.Settings
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.tx5dr.bridge.ui.DashboardScreen
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var bridgeStatus by mutableStateOf(BridgeStatus())
    private var usbAudioStatus by mutableStateOf(UsbAudioStatus())
    private var usbSerialStatus by mutableStateOf(UsbSerialStatus())
    private var logs by mutableStateOf("")
    private var lanUrls by mutableStateOf<List<String>>(emptyList())
    private var manifestUrl by mutableStateOf("")
    private var autoOpenWebView by mutableStateOf(true)
    private var serviceOnlyMode by mutableStateOf(false)
    private var keepAliveEnabled by mutableStateOf(false)
    private var showInstallDialog by mutableStateOf(false)
    private var releasePreview by mutableStateOf<ReleasePreview?>(null)
    private var releasePreviewError by mutableStateOf<String?>(null)
    private var showDiagnosticsSheet by mutableStateOf(false)
    private var webVisible by mutableStateOf(false)
    private var webSuppressedForSession by mutableStateOf(false)
    private var micWanted = false
    private lateinit var rootContainer: FrameLayout
    private var nativeWebView: WebView? = null

    private val statusListener: (BridgeStatus) -> Unit = { status ->
        bridgeStatus = status
        if (status.serverHealthy && status.webHealthy && autoOpenWebView && !serviceOnlyMode && !webVisible && !webSuppressedForSession) {
            openWebView()
        }
    }
    private val logListener: (String) -> Unit = { logs = it }
    private val usbAudioListener: (UsbAudioStatus) -> Unit = { usbAudioStatus = it }
    private val usbSerialListener: (UsbSerialStatus) -> Unit = { usbSerialStatus = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPreferences()
        manifestUrl = BridgeRuntime.getManifestUrl()
        refreshLanUrls()
        bridgeStatus = BridgeRuntime.snapshotStatus()
        logs = LogBus.snapshot()
        rootContainer = FrameLayout(this)
        val composeView = ComposeView(this).apply {
            setContent {
                DashboardScreen(
                    bridgeStatus = bridgeStatus,
                    usbAudioStatus = usbAudioStatus,
                    usbSerialStatus = usbSerialStatus,
                    logs = logs,
                    lanUrls = lanUrls,
                    manifestUrl = manifestUrl,
                    autoOpenWebView = autoOpenWebView,
                    keepAliveEnabled = keepAliveEnabled,
                    showInstallDialog = showInstallDialog,
                    releasePreview = releasePreview,
                    releasePreviewError = releasePreviewError,
                    showDiagnosticsSheet = showDiagnosticsSheet,
                    controlSystemBars = !webVisible,
                    onInstallClick = { prepareInstallDialog() },
                    onConfirmInstall = { showInstallDialog = false; startInstall() },
                    onDismissInstallDialog = { showInstallDialog = false },
                    onStartRuntime = { startRuntime() },
                    onStopRuntime = { stopRuntime() },
                    onOpenWebView = { openWebView() },
                    onAuthorizeAudio = { authorizeAudio() },
                    onStartSerial = { startSerialWithPermission() },
                    onSetKeepAlive = { setKeepAlive(it) },
                    onOpenBatterySettings = { openBatterySettings() },
                    onRefreshBridges = { BridgeService.start(this@MainActivity, BridgeService.ACTION_START_BRIDGES) },
                    onShowDiagnostics = { showDiagnosticsSheet = true },
                    onDismissDiagnostics = { showDiagnosticsSheet = false },
                    onCopyText = { copyToClipboard(it) },
                    onRefreshLan = { refreshLanUrls() },
                    onManifestUrlChange = { manifestUrl = it },
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
        AndroidUsbAudioBridge.addListener(usbAudioListener)
        AndroidUsbSerialBridge.addListener(usbSerialListener)
        AndroidUsbAudioBridge.refreshDevices(this)
        AndroidUsbSerialBridge.refreshDevices(this, BridgeRuntime.paths.androidSerialDevicesFile)
        requestNotificationPermissionIfNeeded()
        BridgeService.start(this, BridgeService.ACTION_BOOTSTRAP)
        LogBus.i("Tx5drBridge", "MainActivity created")
    }

    override fun onResume() {
        super.onResume()
        loadPreferences()
        refreshLanUrls()
    }

    override fun onDestroy() {
        destroyNativeWebView()
        BridgeRuntime.removeStatusListener(statusListener)
        LogBus.removeListener(logListener)
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
    }

    private fun openWebView() {
        val token = BridgeRuntime.getAdminToken()
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
            if (BuildConfig.DEBUG) {
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        LogBus.i(
                            "WebView",
                            "${consoleMessage.messageLevel()} ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} ${consoleMessage.message()}"
                        )
                        return true
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val requestUrl = request.url
                    if (isAllowedWebUrl(requestUrl)) return false
                    LogBus.w("WebView", "Blocked external navigation: $requestUrl")
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, requestUrl)) }
                    return true
                }

                override fun onPageCommitVisible(view: WebView, url: String) {
                    syncWebViewTheme(view)
                    installWebChromeProbe(view)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    LogBus.i("WebView", "Page finished: ${url.substringBefore('?')}")
                    syncWebViewTheme(view)
                    installWebChromeProbe(view)
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

    @Suppress("DEPRECATION")
    private fun configureWebViewTheme(settings: WebSettings) {
        val darkMode = isSystemDarkMode()
        if (Build.VERSION.SDK_INT >= 33) {
            settings.isAlgorithmicDarkeningAllowed = false
        } else if (Build.VERSION.SDK_INT >= 29) {
            settings.forceDark = WebSettings.FORCE_DARK_OFF
        }
        LogBus.i("WebView", "System theme for WebView: ${if (darkMode) "dark" else "light"}")
    }

    private fun syncWebViewTheme(webView: WebView) {
        val theme = if (isSystemDarkMode()) "dark" else "light"
        val js = """
            (function() {
              const theme = ${JSONObject.quote(theme)};
              try { localStorage.setItem('tx5dr-theme-mode', theme); } catch (error) {}
              const root = document.documentElement;
              const body = document.body;
              if (root) {
                root.classList.remove('light', 'dark');
                root.classList.add(theme);
                root.style.colorScheme = theme;
              }
              if (body) {
                body.classList.remove('light', 'dark', 'text-foreground', 'bg-background');
                body.classList.add(theme, 'text-foreground', 'bg-background');
              }
              let meta = document.querySelector('meta[name="color-scheme"]');
              if (!meta) {
                meta = document.createElement('meta');
                meta.name = 'color-scheme';
                document.head && document.head.appendChild(meta);
              }
              meta && meta.setAttribute('content', theme);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
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

    private fun startRuntime() {
        BridgeRuntime.setManifestUrl(manifestUrl)
        BridgeService.start(this, BridgeService.ACTION_START_RUNTIME)
    }

    private fun stopRuntime() {
        BridgeService.start(this, BridgeService.ACTION_STOP_RUNTIME)
    }

    private fun authorizeAudio() {
        if (!AndroidUsbAudioBridge.hasRecordPermission(this)) {
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
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO && micWanted) {
            micWanted = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) AndroidUsbAudioBridge.start(this) else LogBus.w("AudioBridge", "Microphone permission denied")
        }
    }

    private fun copyToClipboard(value: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("TX-5DR", value))
        LogBus.i("Tx5drBridge", "Copied to clipboard: $value")
    }

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
        private const val REQ_POST_NOTIFICATIONS = 1002
        private const val WEB_PORT = 8076
        private const val WEB_URL = "http://127.0.0.1:8076"
        private const val WEB_CHROME_BRIDGE = "Tx5drAndroidChrome"
    }
}
