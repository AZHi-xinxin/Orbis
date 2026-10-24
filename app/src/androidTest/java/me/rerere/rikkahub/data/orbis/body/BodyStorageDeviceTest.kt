package me.rerere.rikkahub.data.orbis.body

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.activities.AndroidActivityStorage
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class BodyStorageDeviceTest {
    @Test fun androidAtomicFilePersistsReopensAndRejectsStaleWriter() {
        val test = InstrumentationRegistry.getInstrumentation()
        check(test is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, test.targetContext.applicationContext.javaClass)
        val base = test.targetContext.cacheDir.canonicalFile
        val root = File(base, "body-fixture-${UUID.randomUUID()}")
        check(root.mkdir())
        try {
            fun open() = BodyRepository(AndroidActivityStorage(root, "owner", "body")) { BodyClock("p1", 0, 1000) }
            val repo = open()
            repo.define(BodyDefinition(listOf(BodyDimension("energy", "活力")), listOf(BodyRule("rest", mapOf("energy" to 5.0)))), 0, "draft")
            repo.activate(1, "activate"); repo.setRunning(true)
            val stale = open()
            repo.event("chat", "rest", 0, true, "one")
            val saved = open().snapshot()
            assertEquals(repo.snapshot(), saved)
            assertThrows(IllegalStateException::class.java) { stale.event("chat", "rest", 0, false, "two") }
            assertEquals(saved, open().snapshot())
            val again = open(); again.event("chat", "rest", 0, true, "one")
            assertEquals(saved, again.snapshot())
            again.undo("chat", saved.revision, "undo")
            assertNull(open().snapshot().snapshot.sessions["chat"])
            assertNull(AndroidActivityStorage(root, "other-owner", "body").read())
        } finally {
            check(root.canonicalFile.parentFile == base && root.name.startsWith("body-fixture-"))
            root.deleteRecursively()
        }
    }
}
