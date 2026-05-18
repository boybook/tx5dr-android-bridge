package com.tx5dr.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address

object NetworkAccessProvider {
    private const val TAG = "NetworkAccess"
    private const val DEFAULT_WEB_PORT = 8076
    private var watching = false

    data class Snapshot(
        val addresses: List<String>,
        val urls: List<String>,
        val updatedAt: Long,
    )

    fun startWatching(context: Context, target: File) {
        if (watching) return
        watching = true
        writeSnapshot(context, target)
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    writeSnapshot(context, target)
                }

                override fun onLost(network: Network) {
                    writeSnapshot(context, target)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    writeSnapshot(context, target)
                }
            })
        } catch (error: Throwable) {
            LogBus.w(TAG, "Network callback registration failed: ${error.message}")
        }
    }

    fun writeSnapshot(context: Context, target: File, webPort: Int = DEFAULT_WEB_PORT): Snapshot {
        val addresses = try {
            collectLanIpv4Addresses(context)
        } catch (error: Throwable) {
            LogBus.w(TAG, "LAN address discovery failed: ${error.message}")
            emptyList()
        }
        val updatedAt = System.currentTimeMillis()
        val root = JSONObject()
            .put("hostname", "android")
            .put("webPort", webPort)
            .put("updatedAt", updatedAt)
        val jsonAddresses = JSONArray()
        addresses.forEach { ip ->
            jsonAddresses.put(JSONObject().put("ip", ip))
        }
        root.put("addresses", jsonAddresses)
        try {
            target.parentFile?.mkdirs()
            target.writeText(root.toString(2))
        } catch (error: Throwable) {
            LogBus.w(TAG, "Failed to write LAN access file: ${error.message}")
        }
        val urls = addresses.map { "http://$it:$webPort" }
        LogBus.i(TAG, if (urls.isEmpty()) "LAN URLs unavailable" else "LAN URLs: ${urls.joinToString(", ")}")
        return Snapshot(addresses, urls, updatedAt)
    }

    @Suppress("DEPRECATION")
    private fun collectLanIpv4Addresses(context: Context): List<String> {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val active = cm.activeNetwork
        val activeAddresses = active?.let { collectFromNetwork(cm, it) }.orEmpty()
        val addresses = if (activeAddresses.isNotEmpty()) {
            activeAddresses
        } else {
            cm.allNetworks.flatMap { collectFromNetwork(cm, it) }
        }
        return addresses.distinct()
    }

    private fun collectFromNetwork(cm: ConnectivityManager, network: Network): List<String> {
        val linkProperties = cm.getLinkProperties(network) ?: return emptyList()
        return linkProperties.linkAddresses
            .mapNotNull { it.address as? Inet4Address }
            .map { it.hostAddress.orEmpty() }
            .filter(::isUsableIpv4)
    }

    private fun isUsableIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val nums = parts.mapNotNull { it.toIntOrNull() }
        if (nums.size != 4 || nums.any { it !in 0..255 }) return false
        if (nums[0] == 0 || nums[0] == 127) return false
        if (nums[0] == 169 && nums[1] == 254) return false
        return true
    }
}
