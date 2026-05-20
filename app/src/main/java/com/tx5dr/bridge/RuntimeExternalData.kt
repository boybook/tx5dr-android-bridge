package com.tx5dr.bridge

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object RuntimeExternalData {
    private const val TAG = "RuntimeExternalData"

    fun selectExternalUserRoot(context: Context): ExternalRootSelection {
        val candidates = buildList {
            context.externalMediaDirs?.forEach { dir -> if (dir != null) add(File(dir, "TX-5DR")) }
            context.getExternalFilesDirs(null)?.forEach { dir -> if (dir != null) add(File(dir, "TX-5DR")) }
        }
        for (candidate in candidates) {
            try {
                candidate.mkdirs()
                if (candidate.isDirectory && candidate.canWrite()) {
                    return ExternalRootSelection(candidate, external = true)
                }
            } catch (_: Throwable) {
            }
        }
        return ExternalRootSelection(
            root = File(File(context.filesDir, "runtime/tx5dr-data"), "user"),
            external = false,
            fallbackReason = "Android external media directory is not writable; using app-private storage.",
        )
    }

    fun migrateIfNeeded(paths: RuntimePaths) {
        val marker = paths.externalMigrationMarker
        val previousRoot = runCatching {
            marker.takeIf { it.isFile }?.readText()?.let { JSONObject(it).optString("root").takeIf { root -> root.isNotBlank() } }
        }.getOrNull()
        if (previousRoot == paths.externalUserRootDir.absolutePath) return

        val moved = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val legacyMappings = listOf(
            File(paths.dataDir, "logbook") to File(paths.externalUserDataDir, "logbook"),
            File(paths.dataDir, "voice-keyer") to File(paths.externalUserDataDir, "voice-keyer"),
            File(paths.dataDir, "cw-keyer") to File(paths.externalUserDataDir, "cw-keyer"),
            File(paths.dataDir, "plugins") to paths.externalPluginsDir,
            File(paths.dataDir, "plugin-data") to paths.externalPluginDataDir,
            File(paths.dataDir, "logs") to paths.externalLogsDir,
        )
        val previousRootMappings = previousRoot?.let { root ->
            listOf(
                File(root, "data") to paths.externalUserDataDir,
                File(root, "logs") to paths.externalLogsDir,
                File(root, "plugins") to paths.externalPluginsDir,
                File(root, "plugin-data") to paths.externalPluginDataDir,
            )
        }.orEmpty()

        for ((source, target) in legacyMappings + previousRootMappings) {
            migratePath(source, target, source.name, moved, skipped, errors)
        }
        paths.dataDir.listFiles()?.forEach { source ->
            val lower = source.name.lowercase()
            if (source.isFile && (lower.endsWith(".adi") || lower.endsWith(".adif"))) {
                migratePath(source, File(paths.externalUserDataDir, source.name), source.name, moved, skipped, errors)
            }
        }
        writeMarker(paths, moved, skipped, errors)
    }

    private fun migratePath(
        source: File,
        target: File,
        name: String,
        moved: MutableList<String>,
        skipped: MutableList<String>,
        errors: MutableList<String>,
    ) {
        if (!source.exists()) return
        if (targetHasUserContent(target)) {
            skipped += name
            return
        }
        runCatching {
            copyRecursivelySafely(source, target)
            moved += name
        }.onFailure { error ->
            errors += "$name: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun writeMarker(paths: RuntimePaths, moved: List<String>, skipped: List<String>, errors: List<String>) {
        val summary = JSONObject()
            .put("external", paths.externalRootSelection.external)
            .put("root", paths.externalUserRootDir.absolutePath)
            .put("moved", moved.joinToString(","))
            .put("skipped", skipped.joinToString(","))
            .put("errors", errors.joinToString(","))
            .put("createdAt", System.currentTimeMillis())
            .toString()
        runCatching {
            paths.externalMigrationMarker.parentFile?.mkdirs()
            paths.externalMigrationMarker.writeText(summary)
        }.onFailure { error -> LogBus.w(TAG, "Failed to write external data migration marker: ${error.message}") }
        if (errors.isEmpty()) {
            LogBus.i(TAG, "External user data ready at ${paths.externalUserRootDir.absolutePath}; migrated=${moved.joinToString(",")}")
        } else {
            LogBus.w(TAG, "External user data migration finished with errors: ${errors.joinToString("; ")}")
        }
    }

    private fun targetHasUserContent(target: File): Boolean {
        if (!target.exists()) return false
        if (target.isFile) return true
        return target.list()?.isNotEmpty() == true
    }

    private fun copyRecursivelySafely(source: File, target: File) {
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { child -> copyRecursivelySafely(child, File(target, child.name)) }
        } else {
            target.parentFile?.mkdirs()
            source.inputStream().use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
        }
    }
}
