package com.tx5dr.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.bg7yoz.ft8cn.serialport.UsbSerialDriver
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

object AndroidUsbSerialBridge {
    private const val TAG = "UsbSerialBridge"
    private const val ACTION_USB_PERMISSION = "com.tx5dr.bridge.USB_PERMISSION"
    private const val BASE_BRIDGE_PORT = 4721
    private const val READ_TIMEOUT_MS = 200
    private const val WRITE_TIMEOUT_MS = 2000
    private const val FRAME_DATA = 1
    private const val FRAME_CONFIG = 2
    private const val FRAME_CONTROL = 3
    private const val FRAME_STATUS = 4

    private val listeners = CopyOnWriteArrayList<(UsbSerialStatus) -> Unit>()
    private val sessions = mutableMapOf<Int, SerialSession>()
    private val deviceIndexByKey = mutableMapOf<String, Int>()
    private val portErrors = mutableMapOf<Int, String>()
    private val pendingPermissionDeviceIds = mutableSetOf<Int>()
    private val deniedDeviceIds = mutableSetOf<Int>()
    private var nextVirtualIndex = 0

    @Volatile private var status = UsbSerialStatus()
    @Volatile private var wanted = false
    private var app: Context? = null
    private var devicesFile: File? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            device?.let {
                pendingPermissionDeviceIds.remove(it.deviceId)
                if (!granted) deniedDeviceIds.add(it.deviceId)
            }
            LogBus.i(TAG, "USB serial permission result: device=${device?.deviceId ?: "--"}, granted=$granted")
            if (!granted) updateFromCurrentDevices("permission-denied", "USB permission denied")
            startInternal(context, devicesFile ?: BridgeRuntime.paths.androidSerialDevicesFile, requestPermissions = true, resetDenied = false)
        }
    }

    @Synchronized
    fun init(context: Context, targetFile: File) {
        app = context.applicationContext
        devicesFile = targetFile
        registerReceiver(app!!)
        refreshDevices(app!!, targetFile)
    }

    fun addListener(listener: (UsbSerialStatus) -> Unit) {
        listeners.add(listener)
        runCatching { listener(status) }.onFailure { Log.w(TAG, "USB serial listener failed", it) }
    }

    fun removeListener(listener: (UsbSerialStatus) -> Unit) {
        listeners.remove(listener)
    }

    fun snapshotStatus(): UsbSerialStatus = status

    @Synchronized
    fun refreshDevices(context: Context? = null, targetFile: File? = null): UsbSerialStatus {
        val context = context ?: app ?: return status
        val targetFile = targetFile ?: devicesFile ?: BridgeRuntime.paths.androidSerialDevicesFile
        val devices = discoverPorts(context.applicationContext).map { it.device }
        stopStaleSessions(devices)
        updateFromDevices(targetFile, devices, preferredState = null, preferredError = null)
        return status
    }

    @Synchronized
    fun start(context: Context? = null, targetFile: File? = null) {
        startInternal(context, targetFile, requestPermissions = true, resetDenied = true)
    }

    @Synchronized
    fun startIfPermitted(context: Context? = null, targetFile: File? = null): Boolean {
        return startInternal(context, targetFile, requestPermissions = false, resetDenied = false)
    }

    @Synchronized
    fun stop() {
        wanted = false
        pendingPermissionDeviceIds.clear()
        val activeSessions = sessions.values.toList()
        sessions.clear()
        activeSessions.forEach { it.stop() }
        val target = devicesFile ?: BridgeRuntime.paths.androidSerialDevicesFile
        val devices = (app?.let { discoverPorts(it).map { candidate -> candidate.device } } ?: status.devices).map { it.copy(active = false, connected = false) }
        writeDevicesFile(target, devices, bridgeRunning = false)
        update(status.copy(state = "stopped", devices = devices, activePath = null, mappedCount = 0, error = null))
    }

    @Synchronized
    private fun startInternal(
        context: Context? = null,
        targetFile: File? = null,
        requestPermissions: Boolean,
        resetDenied: Boolean,
    ): Boolean {
        val context = context ?: app ?: return false
        val targetFile = targetFile ?: devicesFile ?: BridgeRuntime.paths.androidSerialDevicesFile
        val appContext = context.applicationContext
        app = appContext
        devicesFile = targetFile
        registerReceiver(appContext)
        wanted = true
        if (resetDenied) deniedDeviceIds.clear()

        val candidates = discoverPorts(appContext)
        if (candidates.isEmpty()) {
            stopStaleSessions(emptyList())
            updateFromDevices(targetFile, emptyList(), preferredState = "no-device", preferredError = if (requestPermissions) "No supported USB serial device" else null)
            return false
        }

        stopStaleSessions(candidates.map { it.device })
        var startedOrRunning = sessions.isNotEmpty()
        val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val ungrantedDevices = candidates
            .map { it.driver.device }
            .distinctBy { it.deviceId }
            .filter { !manager.hasPermission(it) && it.deviceId !in deniedDeviceIds }

        candidates.filter { it.device.granted }.forEach { candidate ->
            if (sessions[candidate.device.virtualIndex]?.isAlive == true) return@forEach
            startSession(manager, candidate)?.let { session ->
                sessions[candidate.device.virtualIndex] = session
                session.serverThread = thread(name = "tx5dr-usb-serial-server-${candidate.device.virtualIndex}") { serverLoop(session) }
                startedOrRunning = true
            }
        }

        val devicesAfterStart = discoverPorts(appContext).map { it.device }
        if (requestPermissions) requestNextPermission(appContext, ungrantedDevices)
        updateFromDevices(targetFile, devicesAfterStart, preferredState = null, preferredError = null)
        return startedOrRunning
    }

    private fun startSession(manager: UsbManager, candidate: SerialPortCandidate): SerialSession? {
        val connection = manager.openDevice(candidate.driver.device)
        if (connection == null) {
            portErrors[candidate.device.virtualIndex] = "Unable to open USB serial device"
            return null
        }
        return try {
            val selectedPort = candidate.port
            selectedPort.open(connection)
            selectedPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            val session = SerialSession(candidate.device, selectedPort, connection)
            portErrors.remove(candidate.device.virtualIndex)
            LogBus.i(TAG, "Starting USB serial bridge ${candidate.device.path} on 127.0.0.1:${candidate.device.bridgePort}")
            session
        } catch (error: Throwable) {
            try { candidate.port.close() } catch (_: Throwable) {}
            try { connection.close() } catch (_: Throwable) {}
            portErrors[candidate.device.virtualIndex] = error.message ?: error.javaClass.simpleName
            LogBus.e(TAG, "USB serial bridge ${candidate.device.path} start failed", error)
            null
        }
    }

    private fun requestNextPermission(context: Context, devices: List<UsbDevice>) {
        val device = devices.firstOrNull { it.deviceId !in pendingPermissionDeviceIds } ?: return
        updateFromCurrentDevices("permission-required", null)
        pendingPermissionDeviceIds.add(device.deviceId)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        LogBus.i(TAG, "Requesting USB serial permission for device=${device.deviceId}")
        (context.getSystemService(Context.USB_SERVICE) as UsbManager).requestPermission(device, pi)
    }

    private fun serverLoop(session: SerialSession) {
        try {
            ServerSocket(session.device.bridgePort, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                session.serverSocket = server
                LogBus.i(TAG, "USB serial bridge waiting on 127.0.0.1:${session.device.bridgePort} for ${session.device.path}")
                updateFromCurrentDevices("waiting-helper", null)
                server.accept().use { socket ->
                    session.clientSocket = socket
                    session.connected = true
                    updateFromCurrentDevices("connected", null)
                    LogBus.i(TAG, "Linux serial PTY helper connected for ${session.device.path}")
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    session.readThread = thread(name = "tx5dr-usb-serial-read-${session.device.virtualIndex}") {
                        val buffer = ByteArray(4096)
                        while (session.running && !socket.isClosed) {
                            try {
                                val n = session.port.read(buffer, READ_TIMEOUT_MS)
                                if (n > 0) writeFrame(output, FRAME_DATA, buffer.copyOf(n))
                            } catch (_: java.net.SocketException) {
                                break
                            } catch (error: Throwable) {
                                if (session.running) LogBus.w(TAG, "USB serial read failed for ${session.device.path}: ${error.message}")
                            }
                        }
                    }
                    while (session.running && !socket.isClosed) {
                        val type = input.read()
                        if (type < 0) break
                        val length = input.readInt()
                        require(length in 0..1048576) { "Invalid serial frame length $length" }
                        val payload = ByteArray(length)
                        input.readFully(payload)
                        when (type) {
                            FRAME_DATA -> session.port.write(payload, WRITE_TIMEOUT_MS)
                            FRAME_CONFIG -> handleConfig(session.port, payload, session.device.path)
                            FRAME_CONTROL -> handleControl(session.port, payload, session.device.path)
                            FRAME_STATUS -> LogBus.i(TAG, "serial helper status ${session.device.path}: ${payload.decodeToString()}")
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (session.running) {
                portErrors[session.device.virtualIndex] = error.message ?: error.javaClass.simpleName
                LogBus.e(TAG, "USB serial bridge server failed for ${session.device.path}", error)
            }
        } finally {
            session.connected = false
            synchronized(this) {
                if (sessions[session.device.virtualIndex] == session) sessions.remove(session.device.virtualIndex)
            }
            session.stop()
            if (wanted) updateFromCurrentDevices("disconnected", null)
        }
    }

    private fun handleConfig(serialPort: UsbSerialPort, payload: ByteArray, path: String) {
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
            LogBus.i(TAG, "USB serial config $path baud=$baud dataBits=$dataBits stopBits=${json.optInt("stopBits", 1)} parity=${json.optString("parity", "none")}")
        } catch (error: Throwable) {
            LogBus.w(TAG, "Invalid serial config frame for $path: ${error.message}")
        }
    }

    private fun handleControl(serialPort: UsbSerialPort, payload: ByteArray, path: String) {
        try {
            val json = JSONObject(payload.decodeToString())
            if (json.has("dtr")) serialPort.setDTR(json.optBoolean("dtr"))
            if (json.has("rts")) serialPort.setRTS(json.optBoolean("rts"))
            LogBus.i(TAG, "USB serial control $path dtr=${json.opt("dtr")} rts=${json.opt("rts")}")
        } catch (error: Throwable) {
            LogBus.w(TAG, "Invalid serial control frame for $path: ${error.message}")
        }
    }

    private fun writeFrame(output: DataOutputStream, type: Int, payload: ByteArray) = synchronized(output) {
        output.write(type)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    private fun discoverPorts(context: Context): List<SerialPortCandidate> {
        val manager = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        return drivers.flatMap { driver ->
            driver.ports.mapIndexed { portNum, port ->
                val device = driver.device
                val virtualIndex = virtualIndexFor(device, portNum)
                val session = sessions[virtualIndex]
                val bridgePort = BASE_BRIDGE_PORT + virtualIndex
                SerialPortCandidate(
                    driver = driver,
                    port = port,
                    device = AndroidSerialDevice(
                        deviceId = device.deviceId,
                        vendorId = device.vendorId,
                        productId = device.productId,
                        portNum = portNum,
                        virtualIndex = virtualIndex,
                        bridgePort = bridgePort,
                        path = "/opt/tx5dr-data/android-dev/ttyUSB$virtualIndex",
                        name = buildString {
                            append(device.productName ?: device.deviceName ?: "USB Serial")
                            append(" #$portNum")
                        },
                        granted = manager.hasPermission(device),
                        active = session?.isAlive == true,
                        connected = session?.connected == true,
                        error = portErrors[virtualIndex],
                    ),
                )
            }
        }.sortedBy { it.device.virtualIndex }
    }

    private fun virtualIndexFor(device: UsbDevice, portNum: Int): Int {
        val key = "${device.deviceName}:${device.vendorId}:${device.productId}:$portNum"
        return deviceIndexByKey.getOrPut(key) { nextVirtualIndex++ }
    }

    private fun stopStaleSessions(devices: List<AndroidSerialDevice>) {
        val activeIndexes = devices.map { it.virtualIndex }.toSet()
        val stale = sessions.filterKeys { it !in activeIndexes }.values.toList()
        stale.forEach { session ->
            sessions.remove(session.device.virtualIndex)
            session.stop()
            portErrors.remove(session.device.virtualIndex)
        }
    }

    @Synchronized
    private fun updateFromCurrentDevices(preferredState: String?, preferredError: String?) {
        val context = app ?: return
        val target = devicesFile ?: BridgeRuntime.paths.androidSerialDevicesFile
        updateFromDevices(target, discoverPorts(context).map { it.device }, preferredState, preferredError)
    }

    @Synchronized
    private fun updateFromDevices(target: File, devices: List<AndroidSerialDevice>, preferredState: String?, preferredError: String?) {
        val mappedCount = devices.count { it.active }
        val connectedCount = devices.count { it.connected }
        val ungrantedCount = devices.count { !it.granted }
        val error = preferredError ?: devices.firstOrNull { it.error != null }?.error
        val state = preferredState ?: when {
            devices.isEmpty() -> "no-device"
            connectedCount > 0 -> "connected"
            mappedCount > 0 -> "waiting-helper"
            devices.any { it.deviceId in deniedDeviceIds } -> "permission-denied"
            ungrantedCount > 0 -> "permission-required"
            error != null -> "error"
            wanted -> "starting"
            else -> "stopped"
        }
        writeDevicesFile(target, devices, bridgeRunning = mappedCount > 0)
        update(status.copy(
            state = state,
            devices = devices,
            activePath = devices.firstOrNull { it.active }?.path,
            mappedCount = mappedCount,
            error = error,
        ))
    }

    private fun writeDevicesFile(target: File, devices: List<AndroidSerialDevice>, bridgeRunning: Boolean) {
        val root = JSONObject()
            .put("updatedAt", System.currentTimeMillis())
            .put("bridgePort", BASE_BRIDGE_PORT)
            .put("running", bridgeRunning)
        val arr = JSONArray()
        devices.forEach { d ->
            arr.put(JSONObject()
                .put("path", d.path)
                .put("manufacturer", "Android USB Host")
                .put("serialNumber", "device-${d.deviceId}-port-${d.portNum}")
                .put("pnpId", "usb-${d.vendorId}-${d.productId}-${d.portNum}")
                .put("locationId", d.deviceId.toString())
                .put("productId", d.productId.toString(16).padStart(4, '0'))
                .put("vendorId", d.vendorId.toString(16).padStart(4, '0'))
                .put("name", d.name)
                .put("granted", d.granted)
                .put("active", d.active)
                .put("connected", d.connected)
                .put("bridgePort", d.bridgePort)
                .put("error", d.error ?: JSONObject.NULL))
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
        listeners.forEach { listener ->
            runCatching { listener(next) }.onFailure { Log.w(TAG, "USB serial listener failed", it) }
        }
        LogBus.i(TAG, "USB serial state=${next.state}, devices=${next.devices.size}, mapped=${next.mappedCount}, active=${next.activePath ?: "--"}${next.error?.let { ", error=$it" } ?: ""}")
    }

    private data class SerialPortCandidate(
        val driver: UsbSerialDriver,
        val port: UsbSerialPort,
        val device: AndroidSerialDevice,
    )

    private class SerialSession(
        val device: AndroidSerialDevice,
        val port: UsbSerialPort,
        val connection: UsbDeviceConnection,
    ) {
        @Volatile var running = true
        @Volatile var connected = false
        @Volatile var serverSocket: ServerSocket? = null
        @Volatile var clientSocket: Socket? = null
        @Volatile var serverThread: Thread? = null
        @Volatile var readThread: Thread? = null
        val isAlive: Boolean get() = running && serverThread?.isAlive == true

        fun stop() {
            running = false
            try { clientSocket?.close() } catch (_: Throwable) {}
            try { serverSocket?.close() } catch (_: Throwable) {}
            try { port.close() } catch (_: Throwable) {}
            try { connection.close() } catch (_: Throwable) {}
            if (Thread.currentThread() != readThread) readThread?.join(500)
            if (Thread.currentThread() != serverThread) serverThread?.join(1500)
        }
    }
}

data class AndroidSerialDevice(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val portNum: Int,
    val virtualIndex: Int,
    val bridgePort: Int,
    val path: String,
    val name: String,
    val granted: Boolean,
    val active: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
)

data class UsbSerialStatus(
    val state: String = "stopped",
    val devices: List<AndroidSerialDevice> = emptyList(),
    val activePath: String? = null,
    val mappedCount: Int = 0,
    val error: String? = null,
)
