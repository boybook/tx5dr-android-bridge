package com.tx5dr.bridge

import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeUpdateShutdownTest {
    @Test fun includesEphemeralShutdownTokenAndPreservesActualDataMount() {
        val token = "a".repeat(64)
        val command = RuntimeProcessSupport.buildRuntimeCommand(listOf("proot"), 8076, token)
        val script = command.last()
        assertTrue(script.contains("export TX5DR_INTERNAL_SHUTDOWN_TOKEN='$token'"))
        assertTrue(script.contains("export TX5DR_DATA_DIR=/opt/tx5dr-user/data"))
        assertTrue(script.contains("export HOST=127.0.0.1"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun refusesShellMetacharactersInShutdownToken() {
        RuntimeProcessSupport.buildRuntimeCommand(emptyList(), 8076, "bad'; command")
    }
}
