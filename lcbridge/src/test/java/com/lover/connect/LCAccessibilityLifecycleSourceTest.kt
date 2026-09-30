package com.lover.connect

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-level lifecycle contracts: no Android service or user's permission state is touched. */
class LCAccessibilityLifecycleSourceTest {
    private fun source(): String = listOf(
        File("src/main/java/com/lover/connect/LCAccessibilityService.kt"),
        File("lcbridge/src/main/java/com/lover/connect/LCAccessibilityService.kt"),
    ).firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
        ?: error("Cannot locate lcbridge source for lifecycle contract test")

    private fun body(functionName: String): String = source()
        .substringAfter("override fun $functionName(", missingDelimiterValue = "")
        .substringBefore("\n    override fun ").substringBefore("\n    fun dismissLockOverlay")

    @Test fun unbindClearsOnlyOwnedConnectionAndKeepsExecutorForSameInstanceRebind() {
        val unbind = body("onUnbind")
        assertTrue(unbind.contains("if (instance === this)"))
        assertTrue(unbind.contains("instance = null"))
        assertTrue(unbind.contains("AppRestRuntime.invalidate()"))
        assertTrue(unbind.contains("removeLockOverlay()"))
        assertTrue(unbind.contains("\"accessibility_connected\", false"))
        assertTrue(unbind.contains("\"accessibility_unbound_at\""))
        assertTrue(unbind.contains("return super.onUnbind(intent)"))
        assertFalse(unbind.contains("screenshotExecutor.shutdown"))
    }

    @Test fun staleDestroyCannotPublishDisconnectedOverANewerService() {
        val destroy = body("onDestroy")
        val owned = destroy.substringAfter("if (instance === this) {").substringBefore("\n        }")
        assertTrue(owned.contains("instance = null"))
        assertTrue(owned.contains("\"accessibility_connected\", false"))
        assertTrue(owned.contains("\"accessibility_destroyed_at\""))
        val afterOwnership = destroy.substringAfter("\n        }")
        assertFalse(afterOwnership.contains("instance = null"))
        assertFalse(afterOwnership.contains("\"accessibility_connected\""))
        assertTrue(afterOwnership.contains("screenshotExecutor.shutdownNow()"))
        assertTrue(afterOwnership.contains("super.onDestroy()"))
    }

    @Test fun disconnectCallbacksDoNotRebindOrExpandSystemPermission() {
        val callbacks = body("onUnbind") + body("onDestroy")
        assertFalse(callbacks.contains("bindService("))
        assertFalse(callbacks.contains("startService("))
        assertFalse(callbacks.contains("putString(Settings.Secure"))
        assertFalse(callbacks.contains("requestPermission"))
    }
}
