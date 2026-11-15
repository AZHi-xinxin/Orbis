package me.rerere.rikkahub.data.orbis

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class OrbisTouchPairingDeviceTest {
    @Test fun humanPairingPersistsAcrossInstancesAndRotationOnlyRevokesTouchKey() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName == "org.orbis.agent.bodylab")
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "touch-pairing-test-").toFile()
        try {
            val ctx = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
            val first = OrbisEventStore(ctx)
            assertFalse(first.authenticateTouch("not-paired"))
            assertFalse(File(root, "orbis-touch-token-v1.txt").exists())
            val key = first.touchCredentialForHuman()
            assertTrue(key.matches(Regex("[0-9a-f]{64}")))
            val reopened = OrbisEventStore(ctx)
            assertEquals(key, reopened.touchCredentialForHuman())
            assertTrue(reopened.authenticateTouch(key))
            assertFalse(reopened.authenticate(key))
            val legacy = File(root, "orbis-event-token-v1.txt").readText()
            val next = reopened.rotateTouchCredentialForHuman()
            assertNotEquals(key, next)
            assertFalse(first.authenticateTouch(key)); assertTrue(first.authenticateTouch(next))
            assertTrue(first.authenticate(legacy)); assertFalse(first.authenticate(next))
            assertEquals(legacy, File(root, "orbis-event-token-v1.txt").readText())
        } finally { assertTrue(root.deleteRecursively()) }
    }
    @Test fun corruptedTouchCredentialIsNotSilentlyReplaced() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName == "org.orbis.agent.bodylab")
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "touch-pairing-test-").toFile()
        try {
            val ctx = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
            File(root, "orbis-touch-token-v1.txt").writeText("broken")
            assertTrue(runCatching { OrbisEventStore(ctx).touchCredentialForHuman() }.isFailure)
            assertEquals("broken", File(root, "orbis-touch-token-v1.txt").readText())
        } finally { assertTrue(root.deleteRecursively()) }
    }
}
