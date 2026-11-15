package me.rerere.rikkahub.data.orbis.diagnostics

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class FaultReportDataTest {
    @Test fun throwableMessagesAndPathsAreNotCollected() {
        val cause=IllegalArgumentException("synthetic-api-key and private chat")
        cause.stackTrace=arrayOf(StackTraceElement("me.rerere.workspace.AndroidWorkspaceDirectory","openRoot","/private/model-key.txt",123))
        val error=IllegalStateException("https://private.example/api",cause)
        error.addSuppressed(Exception("suppressed private chat"))
        val text=Json.encodeToString(faultFrames(error))
        for(secret in listOf("synthetic-api-key","private chat","private.example","model-key.txt","/private/"))assertFalse(text.contains(secret))
        assertTrue(text.contains("AndroidWorkspaceDirectory.openRoot:123"))
        assertTrue(text.contains("IllegalArgumentException"))
    }
    @Test fun defaultExpiryAndClockRollbackStopCapture() {
        val now=10000000L
        assertFalse(faultCaptureActive(0,now))
        assertTrue(faultCaptureActive(now+FAULT_CAPTURE_MILLIS,now))
        assertFalse(faultCaptureActive(now,now))
        assertFalse(faultCaptureActive(now+FAULT_CAPTURE_MILLIS,now-1))
    }
    @Test fun historyAndCauseDepthAreBounded() {
        var e:Throwable=Exception("hidden")
        repeat(10){e=Exception("hidden",e)}
        assertEquals(5,faultFrames(e).size)
        var events=emptyList<FaultEvent>()
        repeat(80){events=appendFaultEvent(events,FaultEvent(it.toLong(),"PREPARE_CLICKED","fixture","1"))}
        assertEquals(48,events.size);assertEquals(32L,events.first().at)
    }
}
