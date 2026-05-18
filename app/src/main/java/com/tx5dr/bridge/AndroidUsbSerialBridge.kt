package com.tx5dr.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import com.bg7yoz.ft8cn.serialport.UsbSerialPort
import com.bg7yoz.ft8cn.serialport.UsbSerialProber
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

object AndroidUsbSerialBridge {
    private const val TAG = "UsbSerialBridge"
    private const val ACTION_USB_PERMISSION = "com.tx5dr.bridge.USB_PERMISSION"
    private const val BRIDGE_PORT = 4721
    private const val READ_TIMEOUT_MS = 200
    private const val WRITE_TIMEOUT_MS = 2000
    private const val FRAME_DATA = 1
    private const val FRAME_CONFIG = 2
    private const val FRAME_CONTROL = 3
    private const val FRAME_STATUS = 4

    private val listeners = CopyOnWriteArrayList<(UsbSerialStatus) -> Unit>()
    @Volatile private var status = UsbSerialStatus()
    @Volatile private var running = false
    private var app: Context? = null
    private var devicesFile: File? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var port: UsbSerialPort? = null
    private var ioThread: Thread? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            LogBus.i(TAG, "USB serial permission result: granted=$granted")
            if (granted) start(context, devicesFile ?: BridgeRuntime.paths.androidSerialDevicesFile) else update(status.copy(state = "permission-denied", error = "USB permission denied"))
        }
    }

    fun init(context: Context, targetFile: File) {
        app = context.applicationContext
        devicesFile = targetFile
        registerReceiver(app!!)
        refreshDevices(app!!, targetFile)
    }

    fun addListener(listener: (UsbSerialStatus) -> Unit) {
        listeners.add(listener)
        listener(status)
    }

    fun removeListener(listener: (UsbSerialStatus) -> Unit) {
        listeners.remove(listener)
    }

    fun refreshDevices(context: Context? = null, targetFile: File? = null): UsbSerialStatus {
        val context = context ?: app ?: return status
        val targetFile = targetFile ?: devicesFile ?: BridgeRuntime.paths.androidSerialDevicesFile
        val manager = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        var pathIndex = 0
        val devices = drivers.flatMap { driver ->
            driver.ports.mapIndexed { portNum, _ ->
                val virtualIndex = pathIndex++
                val d = driver.device
                AndroidSerialDevice(
                    deviceId = d.deviceId,
                    vendorId = d.vendorId,
                    productId = d.productId,
                    portNum = portNum,
                    path = "/opt/tx5dr-data/android-dev/ttyUSB$virtualIndex",
                    name = buildString {
                        append(d.productName ?: d.deviceName ?: "USB Serial")
                        append(" #$portNum")
                    },
                    granted = manager.hasPermission(d),
                )
            }
        }
        writeDevicesFile(targetFile, devices, running)
        update(status.copy(devices = devices, error = null))
        return status
    }

    fun start(context: Context? = null, targetFile: File? = null) {
        val context = context ?: app ?: return
        val targetFile = targetFile ?: devicesFile ?: BridgeRuntime.paths.androidSerialDevicesFile
        val appContext = context.applicationContext
        app = appContext
        devicesFile = targetFile
        registerReceiver(appContext)
        if (running) return
        val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
        if (driver == null) {
            refreshDevices(appContext, targetFile)
            update(status.copy(state = "no-device", error = "No supported USB serial device"))
            return
        }
        if (!manager.hasPermission(driver.device)) {
            update(status.copy(state = "permission-required", error = null))
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName), flags)
            manager.requestPermission(driver.device, pi)
            return
        }
        val connection = manager.openDevice(driver.device)
        if (connection == null) {
            update(status.copy(state = "error", error = "Unable to open USB serial device"))
            return
        }
        try {
            val selectedPort = driver.ports.first()
            selectedPort.open(connection)
            selectedPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = selectedPort
            running = true
            refreshDevices(appContext, targetFile)
            writeDevicesFile(targetFile, status.devices, true)
            update(status.copy(state = "starting", activePath = "/opt/tx5dr-data/android-dev/ttyUSB0", error = null))
            thread(name = "tx5dr-usb-serial-server") { serverLoop(selectedPort) }
        } catch (error: Throwable) {
            try { connection.close() } catch (_: Throwable) {}
            LogBus.e(TAG, "USB serial bridge start failed", error)
            update(status.copy(state = "error", error = error.message))
        }
    }

    fun stop() {
        running = false
        try { clientSocket?.close() } catch (_: Throwable) {}
        try { serverSocket?.close() } catch (_: Throwable) {}
        try { port?.close() } catch (_: Throwable) {}
        port = null
        ioThread?.join(1500)
        writeDevicesFile(devicesFile ?: BridgeRuntime.paths.androidSerialDevicesFile, status.devices, false)
        update(status.copy(state = "stopped", activePath = null))
    }

    private fun serverLoop(serialPort: UsbSerialPort) {
        try {
            ServerSocket(BRIDGE_PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                serverSocket = server
                LogBus.i(TAG, "USB serial bridge waiting on 127.0.0.1:$BRIDGE_PORT")
                update(status.copy(state = "waiting-helper"))
                server.accept().use { socket ->
                    clientSocket = socket
                    update(status.copy(state = "connected"))
                    LogBus.i(TAG, "Linux serial PTY helper connected")
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    ioThread = thread(name = "tx5dr-usb-serial-read") {
                        val buffer = ByteArray(4096)
                        while (running && !socket.isClosed) {
                            try {
                                val n = serialPort.read(buffer, READ_TIMEOUT_MS)
                                if (n > 0) writeFrame(output, FRAME_DATA, buffer.copyOf(n))
                            } catch (_: java.net.SocketException) {
                                break
                            } catch (error: Throwable) {
                                if (running) LogBus.w(TAG, "USB serial read failed: ${error.message}")
                            }
                        }
                    }
                    while (running && !socket.isClosed) {
                        val type = input.read()
                        if (type < 0) break
                        val length = input.readInt()
                        require(length in 0..1048576) { "Invalid serial frame length $length" }
                        val payload = ByteArray(length)
                        input.readFully(payload)
                        when (type) {
                            FRAME_DATA -> serialPort.write(payload, WRITE_TIMEOUT_MS)
                            FRAME_CONFIG -> handleConfig(serialPort, payload)
                            FRAME_CONTROL -> handleControl(serialPort, payload)
                            FRAME_STATUS -> LogBus.i(TAG, "serial helper status: ${payload.decodeToString()}")
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (running) {
                LogBus.e(TAG, "USB serial bridge server failed", error)
                update(status.copy(state = "error", error = error.message))
            }
        } finally {
            clientSocket = null
            serverSocket = null
            if (running) update(status.copy(state = "disconnected"))
        }
    }

    private fun handleConfig(serialPort: UsbSerialPort, payload: ByteArray) {
        try {
            val json = JSONObject(payload.decodeToString())
            val baud = json.optInt("baud", 9600).coerceAtLeast(1)
            val dataBits = json.optInt("dataBits", 8).coerceIn(5, 8)
            val stopBits = when (json.optInt("stopBits", 1)) {
                2 -> UsbSerialPort.STOPBITS_2
                3 -> UsbSerialPort.STOPBITS_1_5
                else -> UsbSerialPort.STOPBITS_1
            }
            val parity = when (json.optString("parity", "none")) {
                "odd" -> UsbSerialPort.PARITY_ODD
                "even" -> UsbSerialPort.PARITY_EVEN
                "mark" -> UsbSerialPort.PARITY_MARK
                "space" -> UsbSerialPort.PARITY_SPACE
                else -> UsbSerialPort.PARITY_NONE
            }
            serialPort.setParameters(baud, dataBits, stopBits, parity)
            LogBus.i(TAG, "USB serial config baud=$baud dataBits=$dataBits stopBits=${json.optInt("stopBits", 1)} parity=${json.optString("parity", "none")}")
        } catch (error: Throwable) {
            LogBus.w(TAG, "Invalid serial config frame: ${error.message}")
        }
    }

    private fun handleControl(serialPort: UsbSerialPort, payload: ByteArray) {
        try {
            val json = JSONObject(payload.decodeToString())
            if (json.has("dtr")) serialPort.setDTR(json.optBoolean("dtr"))
            if (json.has("rts")) serialPort.setRTS(json.optBoolean("rts"))
            LogBus.i(TAG, "USB serial control dtr=${json.opt("dtr")} rts=${json.opt("rts")}")
        } catch (error: Throwable) {
            LogBus.w(TAG, "Invalid serial control frame: ${error.message}")
        }
    }

    private fun writeFrame(output: DataOutputStream, type: Int, payload: ByteArray) = synchronized(output) {
        output.write(type)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    private fun writeDevicesFile(target: File, devices: List<AndroidSerialDevice>, bridgeRunning: Boolean) {
        val root = JSONObject()
            .put("updatedAt", System.currentTimeMillis())
            .put("bridgePort", BRIDGE_PORT)
            .put("running", bridgeRunning)
        val arr = JSONArray()
        // The current PoC opens one Android USB serial port and one PTY helper.
        // Keep extra discovered ports visible in the Android UI, but do not
        // advertise unbacked ttyUSB paths to Hamlib yet.
        devices.take(1).forEach { d ->
            arr.put(JSONObject()
                .put("path", d.path)
                .put("manufacturer", "Android USB Host")
                .put("serialNumber", "device-${d.deviceId}-port-${d.portNum}")
                .put("pnpId", "usb-${d.vendorId}-${d.productId}-${d.portNum}")
                .put("locationId", d.deviceId.toString())
                .put("productId", d.productId.toString(16).padStart(4, '0'))
                .put("vendorId", d.vendorId.toString(16).padStart(4, '0'))
                .put("name", d.name)
                .put("granted", d.granted))
        }
        root.put("ports", arr)
        try {
            target.parentFile?.mkdirs()
            target.writeText(root.toString(2))
        } catch (error: Throwable) {
            LogBus.w(TAG, "Failed to write serial devices file: ${error.message}")
        }
    }

    private fun registerReceiver(context: Context) {
        try {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(permissionReceiver, filter)
        } catch (_: Throwable) {
            // Already registered.
        }
    }

    private fun update(next: UsbSerialStatus) {
        status = next
        listeners.forEach { it(next) }
        LogBus.i(TAG, "USB serial state=${next.state}, devices=${next.devices.size}, active=${next.activePath ?: "--"}${next.error?.let { ", error=$it" } ?: ""}")
    }
}

data class AndroidSerialDevice(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val portNum: Int,
    val path: String,
    val name: String,
    val granted: Boolean,
)

data class UsbSerialStatus(
    val state: String = "stopped",
    val devices: List<AndroidSerialDevice> = emptyList(),
    val activePath: String? = null,
    val error: String? = null,
)
