package com.tx5dr.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

object NetworkAccessProvider {
    private const val TAG = "NetworkAccess"
    private const val DEFAULT_WEB_PORT = 8076
    private const val POLL_INTERVAL_SECONDS = 5L
    private var watching = false
    private val FALLBACK_DNS_SERVERS = listOf("223.5.5.5", "119.29.29.29", "1.1.1.1")
    private var lastLoggedSummary: String? = null
    private var lastLoggedDnsSummary: String? = null
    private var lastSnapshotFingerprint: String? = null
    private var lastDnsFingerprint: String? = null
    private val poller: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Tx5drNetworkAccessPoll").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    data class Snapshot(
        val addresses: List<String>,
        val urls: List<String>,
        val updatedAt: Long,
        val dnsServers: List<String> = emptyList(),
    )

    private data class AddressCandidate(
        val ip: String,
        val interfaceName: String,
        val source: String,
        val priority: Int,
    )

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun startWatching(context: Context, target: File, resolvConfTarget: File? = null) {
        if (watching) return
        watching = true
        writeSnapshot(context, target, resolvConfTarget)
        startPolling(context.applicationContext, target, resolvConfTarget)
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    writeSnapshot(context, target, resolvConfTarget)
                }

                override fun onLost(network: Network) {
                    writeSnapshot(context, target, resolvConfTarget)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    writeSnapshot(context, target, resolvConfTarget)
                }
            }
            val request = NetworkRequest.Builder().clearCapabilities().build()
            cm.registerNetworkCallback(request, callback)
        } catch (error: Throwable) {
            LogBus.w(TAG, "Network callback registration failed: ${error.message}")
        }
    }

    private fun startPolling(context: Context, target: File, resolvConfTarget: File?) {
        poller.scheduleWithFixedDelay(
            {
                runCatching { writeSnapshot(context, target, resolvConfTarget) }
                    .onFailure { LogBus.w(TAG, "LAN polling refresh failed: ${it.message}") }
            },
            POLL_INTERVAL_SECONDS,
            POLL_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
        LogBus.i(TAG, "LAN address polling enabled (${POLL_INTERVAL_SECONDS}s)")
    }

    fun writeSnapshot(context: Context, target: File, resolvConfTarget: File? = null, webPort: Int = DEFAULT_WEB_PORT): Snapshot {
        val candidates = try {
            collectLanIpv4Candidates(context)
        } catch (error: Throwable) {
            LogBus.w(TAG, "LAN address discovery failed: ${error.message}")
            emptyList()
        }
        val addresses = candidates.map { it.ip }.distinct()
        val dnsServers = try {
            collectDnsServers(context)
        } catch (error: Throwable) {
            LogBus.w(TAG, "DNS server discovery failed: ${error.message}")
            FALLBACK_DNS_SERVERS
        }
        val updatedAt = System.currentTimeMillis()
        val root = JSONObject()
            .put("hostname", "android")
            .put("webPort", webPort)
            .put("updatedAt", updatedAt)
        val jsonAddresses = JSONArray()
        val visibleCandidates = candidates.distinctBy { it.ip }
        visibleCandidates.forEach { candidate ->
            jsonAddresses.put(
                JSONObject()
                    .put("ip", candidate.ip)
                    .put("interface", candidate.interfaceName)
                    .put("source", candidate.source)
            )
        }
        root.put("addresses", jsonAddresses)
        val urls = addresses.map { "http://$it:$webPort" }
        val snapshot = Snapshot(addresses, urls, updatedAt, dnsServers)
        val fingerprint = visibleCandidates.joinToString("|") { "${it.ip},${it.interfaceName},${it.source}" }
        val changed = fingerprint != lastSnapshotFingerprint
        if (changed || !target.exists()) {
            try {
                target.parentFile?.mkdirs()
                target.writeText(root.toString(2))
            } catch (error: Throwable) {
                LogBus.w(TAG, "Failed to write LAN access file: ${error.message}")
            }
        }
        if (changed) {
            lastSnapshotFingerprint = fingerprint
            logSnapshot(urls, candidates)
            notifyListeners(snapshot)
        }
        if (resolvConfTarget != null) {
            writeResolvConf(resolvConfTarget, dnsServers)
        }
        return snapshot
    }


    private fun writeResolvConf(target: File, dnsServers: List<String>) {
        val effectiveServers = dnsServers.ifEmpty { FALLBACK_DNS_SERVERS }
        val content = buildString {
            effectiveServers.take(3).forEach { append("nameserver ").append(it).append('\n') }
            append("options timeout:2 attempts:2\n")
        }
        val fingerprint = effectiveServers.take(3).joinToString(",")
        if (fingerprint != lastDnsFingerprint || !target.exists()) {
            try {
                target.parentFile?.mkdirs()
                target.writeText(content)
                lastDnsFingerprint = fingerprint
            } catch (error: Throwable) {
                LogBus.w(TAG, "Failed to write PRoot DNS config: ${error.message}")
                return
            }
        }

        val detail = "PRoot DNS servers: ${effectiveServers.take(3).joinToString(", ")}"
        if (detail != lastLoggedDnsSummary) {
            lastLoggedDnsSummary = detail
            LogBus.i(TAG, detail)
        }
    }

    @Suppress("DEPRECATION")
    private fun collectDnsServers(context: Context): List<String> {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return FALLBACK_DNS_SERVERS
        val networks = buildList {
            cm.activeNetwork?.let { add(it) }
            cm.allNetworks.forEach { network -> if (!contains(network)) add(network) }
        }
        val servers = networks.flatMap { network ->
            val capabilities = cm.getNetworkCapabilities(network)
            val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val isActive = network == cm.activeNetwork
            if (!isActive && !hasInternet) return@flatMap emptyList()
            cm.getLinkProperties(network)?.dnsServers.orEmpty()
        }
            .mapNotNull { normalizeDnsAddress(it) }
            .distinct()
        return servers.ifEmpty { FALLBACK_DNS_SERVERS }
    }

    private fun normalizeDnsAddress(address: InetAddress): String? {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return null
        val host = address.hostAddress.orEmpty().substringBefore('%')
        return host.takeIf { it.isNotBlank() }
    }

    private fun notifyListeners(snapshot: Snapshot) {
        listeners.forEach { listener ->
            runCatching { listener(snapshot) }
                .onFailure { LogBus.w(TAG, "Network listener failed: ${it.message}") }
        }
    }

    private fun logSnapshot(urls: List<String>, candidates: List<AddressCandidate>) {
        val detail = if (urls.isEmpty()) {
            "LAN URLs unavailable"
        } else {
            val sources = candidates.distinctBy { it.ip }.joinToString(", ") { "${it.ip}/${it.interfaceName}/${it.source}" }
            "LAN URLs: ${urls.joinToString(", ")} ($sources)"
        }
        if (detail != lastLoggedSummary) {
            lastLoggedSummary = detail
            LogBus.i(TAG, detail)
        }
    }

    private fun collectLanIpv4Candidates(context: Context): List<AddressCandidate> {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val candidates = mutableListOf<AddressCandidate>()
        if (cm != null) {
            candidates += collectFromConnectivityManager(cm)
        }
        candidates += collectFromNetworkInterfaces()
        return candidates
            .filterNot { isCellularInterface(it.interfaceName) }
            .filter { isUsableLanIpv4(it.ip) }
            .sortedWith(compareByDescending<AddressCandidate> { it.priority }.thenBy { it.interfaceName }.thenBy { it.ip })
            .distinctBy { it.ip }
    }

    @Suppress("DEPRECATION")
    private fun collectFromConnectivityManager(cm: ConnectivityManager): List<AddressCandidate> {
        return cm.allNetworks.flatMap { network ->
            val linkProperties = cm.getLinkProperties(network) ?: return@flatMap emptyList()
            val capabilities = cm.getNetworkCapabilities(network)
            val interfaceName = linkProperties.interfaceName.orEmpty()
            val source = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_USB) == true -> "usb"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "vpn"
                else -> "network"
            }
            linkProperties.linkAddresses
                .mapNotNull { it.address as? Inet4Address }
                .map { it.hostAddress.orEmpty() }
                .map { ip -> AddressCandidate(ip, interfaceName, source, connectivityPriority(source, interfaceName, ip)) }
        }
    }

    private fun collectFromNetworkInterfaces(): List<AddressCandidate> {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        return interfaces.flatMap { networkInterface ->
            val interfaceName = networkInterface.name.orEmpty()
            if (!isUsableInterface(networkInterface, interfaceName)) return@flatMap emptyList()
            networkInterface.inetAddresses.toList()
                .mapNotNull { it as? Inet4Address }
                .map { it.hostAddress.orEmpty() }
                .map { ip -> AddressCandidate(ip, interfaceName, interfaceSource(interfaceName, ip), interfacePriority(interfaceName, ip)) }
        }
    }

    private fun isUsableInterface(networkInterface: NetworkInterface, interfaceName: String): Boolean {
        if (interfaceName.isBlank()) return false
        if (networkInterface.isLoopback || !networkInterface.isUp) return false
        return true
    }

    private fun connectivityPriority(source: String, interfaceName: String, ip: String): Int {
        val interfaceScore = interfacePriority(interfaceName, ip)
        val sourceScore = when (source) {
            "usb" -> 96
            "wifi" -> 86
            "ethernet" -> 82
            "cellular", "vpn" -> -100
            else -> 50
        }
        return maxOf(interfaceScore, sourceScore)
    }

    private fun interfacePriority(interfaceName: String, ip: String): Int {
        val name = interfaceName.lowercase(Locale.US)
        val gatewayLike = ip.endsWith(".1") || ip.endsWith(".129")
        return when {
            isCellularInterface(name) -> -100
            isHotspotInterfaceName(name) -> if (gatewayLike) 120 else 112
            name.startsWith("wlan") -> if (gatewayLike) 108 else 86
            name.startsWith("rndis") || name.startsWith("usb") -> 96
            name.startsWith("br") || name.contains("bridge") || name.contains("tether") -> 94
            name.startsWith("eth") -> 82
            else -> 40
        }
    }

    private fun interfaceSource(interfaceName: String, ip: String): String {
        val name = interfaceName.lowercase(Locale.US)
        val gatewayLike = ip.endsWith(".1") || ip.endsWith(".129")
        return when {
            isHotspotInterfaceName(name) -> "hotspot"
            name.startsWith("wlan") && gatewayLike -> "hotspot"
            name.startsWith("wlan") -> "wifi"
            name.startsWith("rndis") || name.startsWith("usb") -> "usb-tether"
            name.startsWith("br") || name.contains("bridge") || name.contains("tether") -> "tether"
            name.startsWith("eth") -> "ethernet"
            else -> "interface"
        }
    }

    private fun isHotspotInterfaceName(name: String): Boolean {
        if (name.startsWith("ap") || name.contains("softap") || name.startsWith("swlan")) return true
        if (name.matches(Regex("wlan[1-9][0-9]*"))) return true
        return false
    }

    private fun isCellularInterface(interfaceName: String): Boolean {
        val name = interfaceName.lowercase(Locale.US)
        return name.startsWith("rmnet") ||
            name.contains("rmnet") ||
            name.startsWith("ccmni") ||
            name.startsWith("pdp") ||
            name.startsWith("wwan") ||
            name.startsWith("cell") ||
            name.startsWith("clat") ||
            name.startsWith("v4-rmnet") ||
            name.startsWith("tun") ||
            name.startsWith("ipsec") ||
            name.startsWith("ppp")
    }

    private fun isUsableLanIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val nums = parts.mapNotNull { it.toIntOrNull() }
        if (nums.size != 4 || nums.any { it !in 0..255 }) return false
        if (nums[0] == 0 || nums[0] == 127 || nums[0] == 255) return false
        if (nums[0] == 169 && nums[1] == 254) return false
        if (nums[0] == 100 && nums[1] in 64..127) return false
        return isPrivateLanIpv4(nums)
    }

    private fun isPrivateLanIpv4(nums: List<Int>): Boolean {
        if (nums[0] == 10) return true
        if (nums[0] == 172 && nums[1] in 16..31) return true
        if (nums[0] == 192 && nums[1] == 168) return true
        return false
    }
}
