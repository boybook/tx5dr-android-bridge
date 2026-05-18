package com.tx5dr.bridge

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var manifestInput: EditText
    private lateinit var logText: TextView
    private lateinit var webView: WebView
    private lateinit var micButton: Button
    private var micWanted = false

    private val statusListener: (BridgeStatus) -> Unit = { runOnUiThread { renderStatus(it) } }
    private lateinit var logScroll: ScrollView

    private val logListener: (String) -> Unit = {
        runOnUiThread {
            logText.text = it
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
    private val micListener: (MicBridgeState) -> Unit = { runOnUiThread { micButton.text = if (it == MicBridgeState.Streaming || it == MicBridgeState.Starting) "Stop Mic Bridge" else "Start Mic Bridge" } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        BridgeRuntime.addStatusListener(statusListener)
        LogBus.addListener(logListener)
        MicBridge.addListener(micListener)
        LogBus.i("Tx5drBridge", "MainActivity created")
    }

    override fun onDestroy() {
        BridgeRuntime.removeStatusListener(statusListener)
        LogBus.removeListener(logListener)
        MicBridge.removeListener(micListener)
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 18, 18, 18) }
        statusText = TextView(this).apply { textSize = 14f; typeface = Typeface.MONOSPACE }
        manifestInput = EditText(this).apply {
            hint = "Manifest URL"
            setSingleLine(false)
            minLines = 2
            setText(BridgeRuntime.getManifestUrl())
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val installButton = Button(this).apply { text = "Install/Update"; setOnClickListener { saveManifest(); LogBus.i("Tx5drBridge", "Install/Update requested"); BridgeService.start(this@MainActivity, BridgeService.ACTION_INSTALL) } }
        val startButton = Button(this).apply { text = "Start"; setOnClickListener { saveManifest(); LogBus.i("Tx5drBridge", "Start requested"); BridgeService.start(this@MainActivity, BridgeService.ACTION_START_RUNTIME) } }
        val stopButton = Button(this).apply { text = "Stop"; setOnClickListener { LogBus.i("Tx5drBridge", "Stop requested"); BridgeService.start(this@MainActivity, BridgeService.ACTION_STOP_RUNTIME) } }
        micButton = Button(this).apply { text = "Start Mic Bridge"; setOnClickListener { toggleMic() } }
        listOf(installButton, startButton, stopButton, micButton).forEach { buttons.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }
        val webButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        webButtons.addView(Button(this).apply { text = "Open Web"; setOnClickListener { webView.visibility = View.VISIBLE; webView.loadUrl("http://127.0.0.1:8076") } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        webButtons.addView(Button(this).apply { text = "Hide Web"; setOnClickListener { webView.visibility = View.GONE } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        logText = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logText) }

        root.addView(TextView(this).apply { text = "TX-5DR Android Bridge PoC"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD })
        root.addView(statusText)
        root.addView(manifestInput)
        root.addView(buttons)
        root.addView(webButtons)
        root.addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.1f).apply { webView.visibility = View.GONE })
        root.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun saveManifest() {
        BridgeRuntime.setManifestUrl(manifestInput.text.toString())
    }

    private fun renderStatus(status: BridgeStatus) {
        statusText.text = buildString {
            append("Runtime: ${status.runtimeState}")
            append(" | Server: ${if (status.serverHealthy) "ok" else "--"}")
            append(" | Web: ${if (status.webHealthy) "ok" else "--"}")
            append("\nVersion: ${status.installedVersion ?: "not installed"}")
            if (status.progress != null) append("\nProgress: ${status.progress}")
            if (status.error != null) append("\nError: ${status.error}")
        }
    }

    private fun toggleMic() {
        if (!MicBridge.hasPermission(this)) {
            micWanted = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
            return
        }
        if (micButton.text.toString().startsWith("Stop")) MicBridge.stop() else MicBridge.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO && micWanted) {
            micWanted = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) MicBridge.start(this) else LogBus.w("AudioBridge", "Microphone permission denied")
        }
    }

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
    }
}
