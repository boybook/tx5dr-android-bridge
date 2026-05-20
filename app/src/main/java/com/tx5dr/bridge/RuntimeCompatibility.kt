package com.tx5dr.bridge

import android.os.Build
import java.io.File

object RuntimeCompatibility {
    const val REQUIRED_ABI = "arm64-v8a"

    fun snapshot(nativeLibraryDir: File? = null): RuntimeAbiStatus {
        val supported64BitAbis = Build.SUPPORTED_64_BIT_ABIS.toList()
        return RuntimeAbiStatus(
            supported = supported64BitAbis.contains(REQUIRED_ABI),
            requiredAbi = REQUIRED_ABI,
            supportedAbis = Build.SUPPORTED_ABIS.joinToString(", "),
            supported64BitAbis = supported64BitAbis.joinToString(", "),
            nativeLibraryDir = nativeLibraryDir?.absolutePath.orEmpty(),
            zygote = readSystemProperty("ro.zygote"),
        )
    }

    fun unsupportedReason(status: RuntimeAbiStatus): String =
        "Unsupported Android userspace ABI: supported=${status.supportedAbis.ifBlank { "unknown" }}, " +
            "64-bit=${status.supported64BitAbis.ifBlank { "none" }}; TX-5DR Android runtime requires $REQUIRED_ABI."

    fun requireSupported(nativeLibraryDir: File? = null) {
        val status = snapshot(nativeLibraryDir)
        require(status.supported) { unsupportedReason(status) }
    }

    private fun readSystemProperty(name: String): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod("get", String::class.java, String::class.java)
        method.invoke(null, name, "") as? String
    }.getOrNull().orEmpty()
}
