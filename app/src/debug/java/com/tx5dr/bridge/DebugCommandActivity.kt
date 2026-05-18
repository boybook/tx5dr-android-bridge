package com.tx5dr.bridge

import android.app.Activity
import android.os.Bundle

class DebugCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDebugCommand()
        finish()
    }

    private fun handleDebugCommand() {
        val action = intent?.action.orEmpty()
        val manifestUrl = intent?.getStringExtra(EXTRA_MANIFEST_URL)?.trim().orEmpty()
        if (manifestUrl.isNotEmpty()) {
            BridgeRuntime.setManifestUrl(manifestUrl)
            LogBus.i(TAG, "Debug manifest URL set: $manifestUrl")
        }

        when (action) {
            ACTION_INSTALL -> {
                LogBus.i(TAG, "Debug install/update requested")
                BridgeService.start(this, BridgeService.ACTION_INSTALL)
            }
            ACTION_START -> {
                LogBus.i(TAG, "Debug start requested")
                BridgeService.start(this, BridgeService.ACTION_START_RUNTIME)
            }
            ACTION_STOP -> {
                LogBus.i(TAG, "Debug stop requested")
                BridgeService.start(this, BridgeService.ACTION_STOP_RUNTIME)
            }
            ACTION_START_MIC -> {
                LogBus.i(TAG, "Debug USB audio start requested")
                AndroidUsbAudioBridge.start(this)
            }
            ACTION_STOP_MIC -> {
                LogBus.i(TAG, "Debug USB audio stop requested")
                AndroidUsbAudioBridge.stop()
            }
            ACTION_START_USB_SERIAL -> {
                LogBus.i(TAG, "Debug USB serial start requested")
                AndroidUsbSerialBridge.start(this, BridgeRuntime.paths.androidSerialDevicesFile)
                BridgeRuntime.startLinuxSerialSide()
            }
            ACTION_STOP_USB_SERIAL -> {
                LogBus.i(TAG, "Debug USB serial stop requested")
                AndroidUsbSerialBridge.stop()
                BridgeRuntime.stopLinuxSerialSide()
            }
            ACTION_STATUS -> {
                LogBus.i(TAG, "Debug status requested; logcat tags: Tx5drBridge RuntimeManager AudioBridge UsbSerialBridge proot mic-linux serial-pty")
            }
            else -> LogBus.w(TAG, "Unknown debug action: ${action.ifBlank { "<empty>" }}")
        }
    }

    companion object {
        private const val TAG = "Tx5drBridge"
        const val EXTRA_MANIFEST_URL = "manifest_url"
        const val ACTION_INSTALL = "com.tx5dr.bridge.debug.INSTALL"
        const val ACTION_START = "com.tx5dr.bridge.debug.START"
        const val ACTION_STOP = "com.tx5dr.bridge.debug.STOP"
        const val ACTION_START_MIC = "com.tx5dr.bridge.debug.START_MIC"
        const val ACTION_STOP_MIC = "com.tx5dr.bridge.debug.STOP_MIC"
        const val ACTION_START_USB_SERIAL = "com.tx5dr.bridge.debug.START_USB_SERIAL"
        const val ACTION_STOP_USB_SERIAL = "com.tx5dr.bridge.debug.STOP_USB_SERIAL"
        const val ACTION_STATUS = "com.tx5dr.bridge.debug.STATUS"
    }
}
