package com.tx5dr.bridge

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.Closeable
import java.io.File

private const val UNIX_SUN_PATH_LIMIT = 100

class AndroidUnixSocketServer(private val socketFile: File) : Closeable {
    private val binder = LocalSocket(LocalSocket.SOCKET_STREAM)
    private val server: LocalServerSocket

    init {
        socketFile.parentFile?.mkdirs()
        socketFile.delete()
        require(socketFile.absolutePath.toByteArray(Charsets.UTF_8).size < UNIX_SUN_PATH_LIMIT) {
            "Unix socket path is too long: ${socketFile.absolutePath}"
        }
        binder.bind(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        server = LocalServerSocket(binder.fileDescriptor)
    }

    fun accept(): LocalSocket = server.accept()

    override fun close() {
        runCatching { server.close() }
        runCatching { binder.close() }
        runCatching { socketFile.delete() }
    }
}
