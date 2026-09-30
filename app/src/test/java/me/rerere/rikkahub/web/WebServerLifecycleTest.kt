package me.rerere.rikkahub.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class WebServerLifecycleTest {
    @Test fun `new installs use a separate port`() {
        assertEquals(8081, DEFAULT_WEB_SERVER_PORT)
    }

    @Test fun `offer migration only for idle legacy port and preserve custom ports`() {
        assertTrue(showOrbisSeparatePortAction(8080, false, false))
        assertFalse(showOrbisSeparatePortAction(8080, true, false))
        assertFalse(showOrbisSeparatePortAction(8080, false, true))
        listOf(8081, 8082, 9000).forEach {
            assertFalse(showOrbisSeparatePortAction(it, false, false))
        }
    }

    @Test fun `failed first command closes notification but initial idle does not`() {
        assertFalse(shouldFinishWebService(0, 0, false, false))
        assertTrue(shouldFinishWebService(1, 1, false, false))
        assertFalse(shouldFinishWebService(1, 1, false, true))
        assertFalse(shouldFinishWebService(1, 1, true, false))
    }

    @Test fun `stale completion cannot close a newer command`() {
        assertFalse(shouldFinishWebService(1, 2, false, false))
        assertTrue(shouldFinishWebService(2, 2, false, false))
    }

    @Test fun `overlapping operations are serialized`() = runTest {
        val operations = SerialWebOperations(this, StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = operations.submit {
            events += "start-begin"
            release.await()
            events += "start-end"
        }
        runCurrent()
        val second = operations.submit { events += "stop" }
        runCurrent()
        assertEquals(listOf("start-begin"), events)
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("start-begin", "start-end", "stop"), events)
    }

    @Test fun `restart stop and start cannot be interleaved`() = runTest {
        val operations = SerialWebOperations(this, StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val restart = operations.submit {
            events += "restart-stop"
            release.await()
            events += "restart-start"
        }
        runCurrent()
        val next = operations.submit { events += "next-command" }
        release.complete(Unit)
        restart.join()
        next.join()
        assertEquals(listOf("restart-stop", "restart-start", "next-command"), events)
    }

    @Test fun `submission order survives reverse IO scheduling before either command runs`() = runTest {
        val dispatcher = object : CoroutineDispatcher() {
            val pending = ArrayDeque<Runnable>()
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
            fun runLast() { pending.removeLast().run() }
        }
        val operations = SerialWebOperations(this, dispatcher)
        val events = mutableListOf<String>()
        val start = operations.submit { events += "start" }
        val stop = operations.submit { events += "stop" }
        // Only the first operation can reach IO. The second is already queued on the mutex.
        assertEquals(1, dispatcher.pending.size)
        dispatcher.runLast()
        runCurrent()
        assertEquals(listOf("start"), events)
        assertEquals(1, dispatcher.pending.size)
        dispatcher.runLast()
        runCurrent()
        start.join()
        stop.join()
        assertEquals(listOf("start", "stop"), events)
    }
}
