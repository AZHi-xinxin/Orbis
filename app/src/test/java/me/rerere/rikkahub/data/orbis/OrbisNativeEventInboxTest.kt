package me.rerere.rikkahub.data.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisNativeEventInboxTest {
    private val binding = OrbisEventBinding("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
    private fun inbox(): OrbisEventInbox { var disk: String? = null; return OrbisEventInbox({ disk }, { disk = it }) }
    @Test fun nativeIdentityIsInternalAndNotAnExternalSource() {
        assertTrue(isNativeSentinelSource("native_sentinel.rule-1"))
        assertFalse("native_sentinel.rule-1" in ORBIS_EVENT_SOURCES)
        listOf("native_sentinel.", "native_sentinel../x", "native_sentinel.a.b", "native_sentinel./etc/x").forEach {
            assertFalse(isNativeSentinelSource(it))
        }
    }
    @Test fun nativeRulesUseIndependentBindingsAndStableReceipts() {
        val inbox = inbox()
        val source = "native_sentinel.rule-1"
        inbox.bind(setOf(source), binding)
        val request = OrbisIncomingEvent("native:one", source, "  AI 原文\n", localImage = "a".repeat(64) + ".jpg")
        val accepted = inbox.accept(request, binding, 100)
        assertEquals(request.localImage, accepted.first.localImage)
        assertEquals(request.text, accepted.first.text)
        assertTrue(inbox.accept(request, binding, 200).second)
        assertTrue(runCatching { inbox.accept(request.copy(localImage = "b".repeat(64) + ".jpg"), binding, 300) }.isFailure)
    }
    @Test fun remoteSourcesCannotAttachLocalFiles() {
        val inbox = inbox()
        inbox.bind(setOf("lc_sentinel"), binding)
        assertTrue(runCatching { inbox.accept(OrbisIncomingEvent("a", "lc_sentinel", "data", localImage = "a".repeat(64) + ".jpg"), binding, 1) }.isFailure)
    }
    @Test fun nativeAttachmentRejectsArbitraryPathsAndUris() {
        val inbox = inbox(); val source = "native_sentinel.one"
        inbox.bind(setOf(source), binding)
        listOf("../../prefs.xml", "/secret.jpg", "file:///secret", "https://example.com/photo", "small.jpg").forEach {
            assertTrue(runCatching { inbox.accept(OrbisIncomingEvent("a", source, "data", localImage = it), binding, 1) }.isFailure)
        }
    }
    @Test fun suppressionPersistsAsAReceiptAndIsNeverNewOnReplay() {
        val inbox = inbox(); val source = "native_sentinel.one"
        inbox.bind(setOf(source), binding)
        val input = OrbisIncomingEvent("a", source, "data")
        val event = inbox.accept(input, binding, 1).first
        inbox.mark(event.id, "suppressed", "human_master_paused")
        assertEquals("suppressed", inbox.accept(input, binding, 2).first.state)
        assertTrue(inbox.accept(input, binding, 3).second)
    }
    @Test fun lateCompletionCannotOverwriteExplicitSuppression() {
        val inbox = inbox(); val source = "native_sentinel.one"
        inbox.bind(setOf(source), binding)
        val event = inbox.accept(OrbisIncomingEvent("late", source, "data"), binding, 1).first
        inbox.mark(event.id, "suppressed", "human_master_paused")
        listOf("unknown", "replied", "generating", "queued").forEach { status ->
            inbox.mark(event.id, status)
            assertEquals("suppressed", inbox.get(event.id)?.state)
            assertEquals("human_master_paused", inbox.get(event.id)?.error)
        }
    }
}
