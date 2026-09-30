package me.rerere.rikkahub.service

import android.app.Application
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.LcExternalRecoveryGate
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.OrbisQueuePauseStore
import me.rerere.rikkahub.data.orbis.QueuePauseStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** Real AtomicFile restart tests, synthetic receipts only. No ChatService/Koin/network or host data. */
@RunWith(AndroidJUnit4::class)
class AutomaticWakeLaneDeviceTest {
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
            check(!LcExternalRecoveryGate.isAllowed())
        }
        private val cache = instrumentation.targetContext.cacheDir.canonicalFile
        private val root = Files.createTempDirectory(cache.toPath(), "wake-lane-isolated-").toFile().canonicalFile
        fun open(name: String): OrbisQueuePauseStore {
            check(name in setOf("human.json", "automatic.json"))
            val atomic = AtomicFile(File(root, name))
            return OrbisQueuePauseStore(read = {
                if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists())
                    atomic.openRead().bufferedReader().use { it.readText() } else null
            }, write = { text ->
                val stream = atomic.startWrite()
                try { stream.write(text.toByteArray()); atomic.finishWrite(stream) }
                catch (error: Throwable) { atomic.failWrite(stream); throw error }
            })
        }
        override fun close() {
            check(root.parentFile == cache && root.name.startsWith("wake-lane-isolated-") &&
                !Files.isSymbolicLink(root.toPath()))
            val names = setOf("human.json", "automatic.json").flatMap { listOf(it, "$it.bak", "$it.new") }
            checkNotNull(root.listFiles()).forEach {
                check(it.name in names && it.isFile && it.canonicalFile.parentFile == root &&
                    !Files.isSymbolicLink(it.toPath()))
                check(it.delete())
            }
            check(root.delete())
        }
    }
    private val conversation = "00000000-0000-4000-8000-000000000017"
    private fun wake(queue: AutomaticWakeQueue): Uuid = Uuid.random().also {
        queue.enqueue(listOf(UIMessagePart.Text("synthetic wake only")), true, it, it.toString())
    }

    @Test fun persistedHumanPauseDoesNotDisableFreshWakeAfterRestart() {
        Fixture().use { f ->
            f.open("human.json").pause(conversation)
            val human = MessageQueue(initiallyPaused = f.open("human.json").isPaused(conversation))
            val auto = AutomaticWakeQueue()
            val id = wake(auto)
            val next = takeNextConversationInput(human, auto, false, false,
                f.open("automatic.json").status(conversation) == QueuePauseStatus.UNPAUSED)
            assertEquals(id, next!!.id)
            assertTrue(human.state.value.paused)
            assertTrue(human.state.value.messages.isEmpty())
        }
    }

    @Test fun unresolvedToolHoldSurvivesRestartWithoutConsumingFreshWake() {
        Fixture().use { f ->
            f.open("automatic.json").pause(conversation, "unknown_tool_result")
            val auto = AutomaticWakeQueue()
            val id = wake(auto)
            assertNull(takeNextConversationInput(MessageQueue(), auto, false, false,
                f.open("automatic.json").status(conversation) == QueuePauseStatus.UNPAUSED))
            assertEquals(id, auto.pending.single().id)
        }
    }

    @Test fun explicitAcknowledgementChangesOnlyAutomaticHoldAndNotHumanPause() {
        Fixture().use { f ->
            f.open("human.json").pause(conversation)
            f.open("automatic.json").pause(conversation, "unknown_tool_result")
            f.open("automatic.json").resume(conversation)
            assertTrue(f.open("human.json").isPaused(conversation))
            assertFalse(f.open("automatic.json").isPaused(conversation))
        }
    }

    @Test fun onlyTheNextNeverDispatchedReceiptLeavesAutomaticLane() {
        Fixture().use { f ->
            val auto = AutomaticWakeQueue()
            val first = wake(auto)
            val second = wake(auto)
            val human = MessageQueue(initiallyPaused = true)
            assertEquals(first, takeNextConversationInput(human, auto, false, false, true)!!.id)
            assertNull(takeNextConversationInput(human, auto, true, false, true))
            assertEquals(second, auto.pending.single().id)
            assertFalse(f.open("automatic.json").isPaused(conversation))
            assertEquals(second, takeNextConversationInput(human, auto, false, false, true)!!.id)
            assertNull(takeNextConversationInput(human, auto, false, false, true))
        }
    }
}
