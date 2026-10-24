package com.tx5dr.bridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** APK updates never change the selected runtime channel. Android owns installation. */
object ApkUpdates {
    fun check(activity: Activity) {
        Thread {
            try {
                var found: JSONObject? = null
                var missing = 0
                var failure: Throwable? = null
                for (url in listOf("https://dl.tx5dr.com/tx-5dr/android-bridge/release/latest.json", "https://github.com/boybook/tx5dr-android-bridge/releases/latest/download/android-bridge-manifest.json")) {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000; connection.readTimeout = 30000
                    try {
                        if (connection.responseCode == 404) { missing++; continue }
                        check(connection.responseCode == 200) { "APK manifest HTTP ${connection.responseCode}" }
                        found = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        break
                    } catch (error: Throwable) { failure = error } finally { connection.disconnect() }
                }
                val manifest = found ?: if (missing == 2) error(activity.getString(R.string.apk_no_release)) else throw (failure ?: IllegalStateException("APK manifest unavailable"))
                require(manifest.getString("product") == "android-bridge" && manifest.getString("channel") == "release")
                require(manifest.getString("package_name") == activity.packageName)
                val current = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                if (manifest.getLong("version_code") <= current.longVersionCode) {
                    activity.runOnUiThread { AlertDialog.Builder(activity).setMessage(R.string.apk_current).setPositiveButton(android.R.string.ok, null).show() }
                    return@Thread
                }
                val assets = manifest.getJSONArray("assets")
                val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.getString("package_type") == "apk" && it.getString("arch") == "arm64" }
                activity.runOnUiThread {
                    AlertDialog.Builder(activity).setTitle(R.string.apk_update)
                        .setMessage(activity.getString(R.string.apk_confirm, current.versionName, manifest.getString("version")))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ -> download(activity, manifest, asset) }.show()
                }
            } catch (error: Throwable) { showError(activity, error) }
        }.start()
    }

    private fun download(activity: Activity, manifest: JSONObject, asset: JSONObject) {
        Thread {
            try {
                val url = asset.optString("url_cn", asset.getString("url"))
                require(url.startsWith("https://"))
                val directory = File(activity.cacheDir, "apk-updates").apply { mkdirs() }
                val apk = File(directory, "tx5dr-${manifest.getLong("version_code")}.apk")
                val candidates = listOf(url, asset.optString("url_github", asset.optString("url_global"))).filter { it.startsWith("https://") }.distinct()
                var downloaded = false
                var downloadFailure: Throwable? = null
                for (candidateUrl in candidates) {
                    val connection = URL(candidateUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000; connection.readTimeout = 120000
                    try {
                        check(connection.responseCode == 200)
                        connection.inputStream.use { input -> apk.outputStream().use { input.copyTo(it) } }
                        downloaded = true; break
                    } catch (error: Throwable) { downloadFailure = error } finally { connection.disconnect() }
                }
                if (!downloaded) throw (downloadFailure ?: IllegalStateException("APK download failed"))
                val digest = MessageDigest.getInstance("SHA-256")
                apk.inputStream().use { input -> val buffer = ByteArray(262144); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                require(apk.length() == asset.getLong("size") && hash == asset.getString("sha256").lowercase()) { "APK integrity check failed" }
                val pm = activity.packageManager
                val installed = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val candidate = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Invalid APK")
                require(candidate.packageName == activity.packageName && candidate.longVersionCode == manifest.getLong("version_code") && candidate.longVersionCode > installed.longVersionCode) { "APK version or package mismatch" }
                val installedSigners = installed.signingInfo!!.apkContentsSigners.map { it.toCharsString() }.toSet()
                require(candidate.signingInfo!!.apkContentsSigners.map { it.toCharsString() }.toSet() == installedSigners) { "APK signing identity mismatch" }
                activity.runOnUiThread {
                    if (!pm.canRequestPackageInstalls()) {
                        AlertDialog.Builder(activity).setMessage(R.string.apk_permission).setPositiveButton(android.R.string.ok) { _, _ ->
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                        }.show()
                    } else {
                        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
                        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                }
            } catch (error: Throwable) { showError(activity, error) }
        }.start()
    }
    private fun showError(activity: Activity, error: Throwable) {
        activity.runOnUiThread { AlertDialog.Builder(activity).setTitle(R.string.apk_update).setMessage(error.message).setPositiveButton(android.R.string.ok, null).show() }
    }
}
