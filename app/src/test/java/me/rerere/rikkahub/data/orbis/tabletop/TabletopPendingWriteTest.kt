package me.rerere.rikkahub.data.orbis.tabletop

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TabletopPendingWriteTest {
    @get:Rule val folder=TemporaryFolder()

    @Test fun healthyHeartbeatEnvelopeDoesNotDisconnectReminderWhileRequestIsInFlight()=runBlocking {
        val file=File(folder.root,"heartbeat.json")
        val mutex=Mutex()
        val saved=CompletableDeferred<Unit>();val reply=CompletableDeferred<Unit>()
        val send=launch {
            mutex.withLock {
                file.writeText("persisted-request-id")
                saved.complete(Unit)
                reply.await()
                file.delete()
            }
        }
        saved.await()
        assertTrue(file.exists())
        assertFalse(tabletopOperationNeedsReconcile(file,mutex))
        reply.complete(Unit);send.join()
        assertFalse(tabletopOperationNeedsReconcile(file,mutex))
    }

    @Test fun failedOrRestartedWriteStillNeedsSameRequestReconciliation()=runBlocking {
        val file=File(folder.root,"speech.json");val mutex=Mutex()
        val saved=CompletableDeferred<Unit>();val disconnect=CompletableDeferred<Unit>()
        val send=launch {
            mutex.withLock {
                file.writeText("original-request-id")
                saved.complete(Unit);disconnect.await()
                // A transport failure deliberately leaves the durable envelope.
            }
        }
        saved.await();assertFalse(tabletopOperationNeedsReconcile(file,mutex))
        disconnect.complete(Unit);send.join()
        assertTrue(tabletopOperationNeedsReconcile(file,mutex))
        assertTrue(tabletopOperationNeedsReconcile(file,Mutex()))
        assertEquals("original-request-id",file.readText())
    }
}
