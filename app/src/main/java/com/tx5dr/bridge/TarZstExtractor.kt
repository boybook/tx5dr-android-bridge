package com.tx5dr.bridge

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream

object TarZstExtractor {
    fun extract(archive: File, destination: File, progress: (String) -> Unit = {}, zstdHelper: File? = null) {
        destination.mkdirs()
        if (archive.name.endsWith(".tar.gz") || archive.name.endsWith(".tgz")) {
            GZIPInputStream(BufferedInputStream(FileInputStream(archive))).use { gzip ->
                extractTarStream(TarArchiveInputStream(gzip), destination, progress)
            }
            return
        }
        if (archive.name.endsWith(".tar.zst")) {
            val helper = zstdHelper ?: File(archive.parentFile, "zstd-arm64")
            if (helper.exists()) {
                extractWithProcess(helper, archive, destination)
                return
            }
        }
        throw IllegalStateException("Unsupported archive or missing helper: ${archive.name}")
    }

    private fun extractTarStream(tar: TarArchiveInputStream, destination: File, progress: (String) -> Unit) {
        tar.use {
            while (true) {
                val entry = it.nextTarEntry ?: break
                val name = entry.name.removePrefix("./")
                if (name.isBlank()) continue
                val target = safeTarget(destination, name)
                progress(name)
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        try {
                            Files.deleteIfExists(target.toPath())
                            Files.createSymbolicLink(target.toPath(), File(entry.linkName).toPath())
                        } catch (_: Throwable) {
                            target.writeText(entry.linkName)
                        }
                    }
                    entry.isLink -> {
                        val linkTarget = safeTarget(destination, entry.linkName.removePrefix("./"))
                        target.parentFile?.mkdirs()
                        try {
                            Files.createLink(target.toPath(), linkTarget.toPath())
                        } catch (_: Throwable) {
                            if (linkTarget.exists()) Files.copy(linkTarget.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> it.copyTo(out) }
                        applyMode(target, entry.mode)
                    }
                }
            }
        }
    }

    private fun extractWithProcess(zstdBinary: File, archive: File, destination: File) {
        val command = "${zstdBinary.absolutePath} -dc ${shellQuote(archive.absolutePath)} | tar -xpf - -C ${shellQuote(destination.absolutePath)}"
        val processBuilder = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true)
        processBuilder.environment()["LD_LIBRARY_PATH"] = zstdBinary.parentFile?.absolutePath.orEmpty()
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) throw IllegalStateException("tar.zst extraction failed with exit $exit: $output")
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\''") + "'"

    private fun safeTarget(root: File, name: String): File {
        val target = File(root, name).canonicalFile
        val canonicalRoot = root.canonicalFile
        require(target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator)) { "Unsafe tar entry: $name" }
        return target
    }

    private fun applyMode(file: File, mode: Int) {
        file.setReadable(true, mode and 0x004 == 0)
        file.setWritable(mode and 0x080 != 0, mode and 0x002 == 0)
        file.setExecutable(mode and 0x049 != 0, mode and 0x001 == 0)
    }
}
