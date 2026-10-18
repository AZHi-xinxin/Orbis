package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ForkMessageFailureTest {
    @Test fun successfulForkIsReturnedWithoutReporting() = runBlocking {
        assertEquals("new-chat", forkOrReportFailure({ "new-chat" }, { fail("No failure") }))
    }
    @Test fun failedCopyOrSaveReportsOnceAndDoesNotProduceNavigationTarget() = runBlocking {
        for (failure in listOf(IOException("copy failed"), IllegalStateException("save not verified"))) {
            val reported = mutableListOf<Exception>()
            val result = forkOrReportFailure<String>({ throw failure }, { reported += it })
            assertNull(result)
            assertEquals(listOf(failure), reported)
        }
    }
    @Test fun cancelledForkIsNotReportedOrRetried() {
        val cancellation = CancellationException("left screen")
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { forkOrReportFailure<String>({ throw cancellation }, { fail("Cancellation") }) }
        }
        assertSame(cancellation, thrown)
    }
}
