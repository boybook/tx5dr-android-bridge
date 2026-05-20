package com.tx5dr.bridge

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object RuntimeProcessSupport {
    private const val TAG = "RuntimeProcess"

    fun resetReadyFiles(paths: RuntimePaths) {
        listOf(paths.serverReadyFile, paths.clientToolsReadyFile).forEach { file ->
            runCatching { file.delete() }
        }
    }

    fun buildRuntimeCommand(baseCommand: List<String>): List<String> {
        val script = """
set -e
mkdir -p /opt/tx5dr-data/config /opt/tx5dr-data/cache /opt/tx5dr-data/runtime /opt/tx5dr-data/android-dev /opt/tx5dr-user/data /opt/tx5dr-user/logs /opt/tx5dr-user/plugins /opt/tx5dr-user/plugin-data
rm -f /opt/tx5dr-data/runtime/server-ready.json /opt/tx5dr-data/runtime/client-tools-ready.json
export HOME=/root
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export NODE_ENV=production
export PORT=4000
export HOST=127.0.0.1
export TX5DR_SERVER_PORT_STRICT=1
export TX5DR_SERVER_READY_FILE=/opt/tx5dr-data/runtime/server-ready.json
export TX5DR_RUNTIME_FLAVOR=android-bridge
export TX5DR_SERVER_HOST=127.0.0.1
export TX5DR_NETWORK_ACCESS_FILE=/opt/tx5dr-data/runtime/android-network-access.json
export TX5DR_ANDROID_SERIAL_DEVICES_FILE=/opt/tx5dr-data/runtime/android-serial-devices.json
export TX5DR_ANDROID_AUDIO_DEVICES_FILE=/opt/tx5dr-data/runtime/android-audio-devices.json
export TX5DR_DATA_DIR=/opt/tx5dr-user/data
export TX5DR_LOGS_DIR=/opt/tx5dr-user/logs
export TX5DR_PLUGINS_DIR=/opt/tx5dr-user/plugins
export TX5DR_PLUGIN_DATA_DIR=/opt/tx5dr-user/plugin-data
export TX5DR_CONFIG_DIR=/opt/tx5dr-data/config
export TX5DR_CACHE_DIR=/opt/tx5dr-data/cache
export TX5DR_RTAUDIO_API=alsa
cd /opt/tx5dr/current
server_pid=
client_pid=
cleanup() {
  set +e
  for pid in ${'$'}server_pid ${'$'}client_pid; do
    [ -n "${'$'}pid" ] && kill -TERM "${'$'}pid" 2>/dev/null || true
  done
  for _ in 1 2 3 4 5; do
    alive=0
    for pid in ${'$'}server_pid ${'$'}client_pid; do
      if [ -n "${'$'}pid" ] && kill -0 "${'$'}pid" 2>/dev/null; then alive=1; fi
    done
    [ "${'$'}alive" = 0 ] && break
    sleep 1
  done
  for pid in ${'$'}server_pid ${'$'}client_pid; do
    [ -n "${'$'}pid" ] && kill -KILL "${'$'}pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT TERM INT
node /opt/tx5dr/current/packages/server/dist/scripts/server-launcher.js /opt/tx5dr/current/packages/server/dist/index.js > >(sed -u 's/^/[server] /') 2>&1 &
server_pid=$!
PORT=8076 HOST=0.0.0.0 TARGET=http://127.0.0.1:4000 STATIC_DIR=/opt/tx5dr/current/packages/web/dist TX5DR_PORT_SCAN_STEPS=0 TX5DR_CLIENT_TOOLS_READY_FILE=/opt/tx5dr-data/runtime/client-tools-ready.json TX5DR_CLIENT_TOOLS_LOG_FILE=/opt/tx5dr-user/logs/client-tools.log node packages/client-tools/src/proxy.js > >(sed -u 's/^/[client-tools] /') 2>&1 &
client_pid=$!
wait -n ${'$'}server_pid ${'$'}client_pid
exit_code=$?
cleanup
exit ${'$'}exit_code
""".trimIndent()
        return baseCommand + listOf("/usr/bin/env", "-i", "/bin/bash", "-lc", script)
    }

    fun cleanupStaleProcesses(paths: RuntimePaths, reason: String) {
        val candidates = staleRuntimePids(paths)
        if (candidates.isEmpty()) return
        LogBus.w(TAG, "Cleaning stale runtime processes ($reason): ${candidates.joinToString(",")}")
        candidates.forEach { pid -> runCatching { android.os.Process.sendSignal(pid, 15) } }
        Thread.sleep(500)
        staleRuntimePids(paths).filter { it in candidates }.forEach { pid ->
            runCatching { android.os.Process.sendSignal(pid, 9) }
        }
    }

    fun isHealthy(url: String): Boolean = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 1000
        conn.readTimeout = 1500
        conn.responseCode in 200..399
    } catch (_: Throwable) {
        false
    }

    fun readyDetail(paths: RuntimePaths): String? {
        serverReadyError(paths)?.let { return it }
        val serverReady = readReadyJson(paths.serverReadyFile)
        val clientReady = readReadyJson(paths.clientToolsReadyFile)
        val details = buildList {
            serverReady?.optInt("httpPort", 0)?.takeIf { it > 0 }?.let { add("API port $it") }
            clientReady?.optInt("httpPort", 0)?.takeIf { it > 0 }?.let { add("page proxy port $it") }
        }
        return details.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun staleRuntimePids(paths: RuntimePaths): List<Int> {
        val myPid = android.os.Process.myPid()
        val myUid = android.os.Process.myUid()
        return File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == myPid) return@mapNotNull null
            val uid = readProcUid(File(dir, "status")) ?: return@mapNotNull null
            if (uid != myUid) return@mapNotNull null
            val cmdline = readProcCmdline(File(dir, "cmdline"))
            if (isTx5drRuntimeCommand(paths, cmdline)) pid else null
        }
    }

    private fun readProcUid(statusFile: File): Int? = try {
        statusFile.useLines { lines ->
            lines.firstOrNull { it.startsWith("Uid:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toIntOrNull()
        }
    } catch (_: Throwable) {
        null
    }

    private fun readProcCmdline(cmdlineFile: File): String = try {
        cmdlineFile.readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ').trim()
    } catch (_: Throwable) {
        ""
    }

    private fun isTx5drRuntimeCommand(paths: RuntimePaths, cmdline: String): Boolean {
        if (cmdline.isBlank() || cmdline.contains("com.tx5dr.bridge")) return false
        val rootfsArg = "--rootfs=${paths.rootfsDir.absolutePath}"
        return cmdline.contains("server-launcher.js") ||
            cmdline.contains("packages/client-tools/src/proxy.js") ||
            cmdline.contains("decode-worker-entry.js") ||
            cmdline.contains("tx5dr-android-serial-pty") ||
            (cmdline.contains("libproot_exec.so") && cmdline.contains(rootfsArg))
    }

    private fun serverReadyError(paths: RuntimePaths): String? {
        val ready = readReadyJson(paths.serverReadyFile) ?: return null
        val error = ready.optJSONObject("error") ?: return null
        return error.optString("message").takeIf { it.isNotBlank() }
    }

    private fun readReadyJson(file: File): JSONObject? = try {
        if (!file.isFile) null else JSONObject(file.readText())
    } catch (_: Throwable) {
        null
    }
}
