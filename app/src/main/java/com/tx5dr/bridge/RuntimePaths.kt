package com.tx5dr.bridge

import java.io.File

data class ExternalRootSelection(val root: File, val external: Boolean, val fallbackReason: String? = null)

data class RuntimePaths(val base: File, val nativeLibDir: File, val externalRootSelection: ExternalRootSelection) {
    val workDir = File(base, "runtime")
    val cacheDir = File(workDir, "cache")
    val hostLibDir = File(workDir, "host-libs")
    val prootTmpDir = File(workDir, "proot-tmp")
    val rootfsDir = File(workDir, "rootfs")
    val dataDir = File(workDir, "tx5dr-data")
    val externalUserRootDir = externalRootSelection.root
    val externalUserDataDir = File(externalUserRootDir, "data")
    val externalLogsDir = File(externalUserRootDir, "logs")
    val externalPluginsDir = File(externalUserRootDir, "plugins")
    val externalPluginDataDir = File(externalUserRootDir, "plugin-data")
    val externalMigrationMarker = File(dataDir, "runtime/external-data-migration.json")
    val androidNetworkAccessFile = File(dataDir, "runtime/android-network-access.json")
    val androidSerialDevicesFile = File(dataDir, "runtime/android-serial-devices.json")
    val androidAudioDevicesFile = File(dataDir, "runtime/android-audio-devices.json")
    val serverReadyFile = File(dataDir, "runtime/server-ready.json")
    val clientToolsReadyFile = File(dataDir, "runtime/client-tools-ready.json")
    val resolvConfFile = File(dataDir, "runtime/resolv.conf")
    val socketsDir = File(dataDir, "runtime/sockets")
    val txDir = File(workDir, "tx5dr")
    val releasesDir = File(txDir, "releases")
    val currentLink = File(txDir, "current")
    val prootFile = File(nativeLibDir, "libproot_exec.so")
    val prootLoader64 = File(nativeLibDir, "libproot_loader.so")
    val prootLoader32 = File(nativeLibDir, "libproot_loader32.so")
    val tallocLib = File(nativeLibDir, "libtalloc.so")
    val zstdFile = File(nativeLibDir, "libzstd_exec.so")
    val zstdLib = File(nativeLibDir, "libzstd.so")
    val rootfsReady = File(rootfsDir, ".tx5dr-rootfs-ready")

    fun ensureDirs() {
        listOf(
            workDir, cacheDir, hostLibDir, prootTmpDir, rootfsDir, dataDir, releasesDir,
            externalUserRootDir, externalUserDataDir, externalLogsDir, externalPluginsDir, externalPluginDataDir, socketsDir,
        ).forEach { it.mkdirs() }
        androidNetworkAccessFile.parentFile?.mkdirs()
        externalMigrationMarker.parentFile?.mkdirs()
    }

    fun externalDataStatus(): ExternalDataStatus = ExternalDataStatus(
        rootPath = externalUserRootDir.absolutePath,
        dataPath = externalUserDataDir.absolutePath,
        logsPath = externalLogsDir.absolutePath,
        pluginsPath = externalPluginsDir.absolutePath,
        pluginDataPath = externalPluginDataDir.absolutePath,
        external = externalRootSelection.external,
        fallbackReason = externalRootSelection.fallbackReason,
        migrationSummary = runCatching {
            externalMigrationMarker.takeIf { it.isFile }?.readText()?.take(500)
        }.getOrNull(),
    )
}
