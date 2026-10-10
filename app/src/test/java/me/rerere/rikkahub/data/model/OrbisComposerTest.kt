package me.rerere.rikkahub.data.model

import org.junit.Assert.*
import org.junit.Test

class OrbisComposerTest {
    @Test fun closedPanelOpensRequestedPanel() {
        assertEquals(OrbisComposerPanel.CAPABILITIES, toggleOrbisComposerPanel(null, OrbisComposerPanel.CAPABILITIES))
    }
    @Test fun sameTabClosesPanel() {
        OrbisComposerPanel.entries.forEach { assertNull(toggleOrbisComposerPanel(it, it)) }
    }
    @Test fun otherTabReplacesRatherThanStacksPanel() {
        assertEquals(OrbisComposerPanel.VOICE, toggleOrbisComposerPanel(OrbisComposerPanel.EMOTIONS, OrbisComposerPanel.VOICE))
    }
    @Test fun capabilitiesKeepPrototypeOrderWithoutExecuteAction() {
        assertEquals(listOf("CAMERA", "IMAGE", "FILE", "SCREEN_SHARE", "TOOLS", "CONTEXT", "EXTENSIONS"), OrbisComposerAction.entries.map { it.name })
    }
    @Test fun eachCapabilityDispatchesOnlyItsOwnDestination() {
        val expected = mapOf(
            OrbisComposerAction.CAMERA to "camera", OrbisComposerAction.IMAGE to "image",
            OrbisComposerAction.FILE to "file", OrbisComposerAction.TOOLS to "mcp_settings",
            OrbisComposerAction.CONTEXT to "context", OrbisComposerAction.EXTENSIONS to "extensions",
            OrbisComposerAction.SCREEN_SHARE to "screen_share",
        )
        expected.forEach { (action, target) ->
            val calls = mutableListOf<String>()
            dispatchOrbisCapability(action, { calls += "camera" }, { calls += "image" },
                { calls += "file" }, { calls += "mcp_settings" }, { calls += "context" },
                { calls += "extensions" }, { calls += "screen_share" })
            assertEquals(listOf(target), calls)
        }
    }
    @Test fun permissionEntryClosesCapabilityPanelButDoesNotExecuteAnyCallback() {
        assertNull(orbisComposerPanelAfterCapability(OrbisComposerPanel.CAPABILITIES, OrbisComposerAction.SCREEN_SHARE))
        assertNull(orbisComposerPanelAfterCapability(OrbisComposerPanel.CAPABILITIES, OrbisComposerAction.TOOLS))
        assertEquals(OrbisComposerPanel.CAPABILITIES,
            orbisComposerPanelAfterCapability(OrbisComposerPanel.CAPABILITIES, OrbisComposerAction.IMAGE))
        assertNull(orbisComposerPanelAfterCapability(null, OrbisComposerAction.SCREEN_SHARE))
    }
    @Test fun emotionsHaveUniqueIdsAndOnlyKnownCategories() {
        assertEquals(orbisQuickEmotions.size, orbisQuickEmotions.map { it.id }.distinct().size)
        assertEquals(setOf("抱抱", "搞怪", "干活"), orbisQuickEmotions.map { it.category }.toSet())
    }
    @Test fun draftsContainActualReadableTextNotFabricatedFileReferences() {
        orbisQuickEmotions.forEach {
            assertTrue(it.draftText.contains(it.glyph))
            assertTrue(it.draftText.contains(it.label))
            assertFalse(it.draftText.contains("sticker_ref"))
            assertFalse(it.draftText.contains("file:"))
        }
    }
    @Test fun dictationKeepsInitialDraftAndReplacesOnlyItsOwnPartialTranscript() {
        val guard = OrbisDictationDraft()
        val ticket = guard.begin("草稿")
        assertEquals("草稿 你好", guard.update(ticket, "草稿", "你好").text)
        assertEquals("草稿 你好呀", guard.update(ticket, "草稿 你好", "你好呀").text)
    }
    @Test fun closingVoiceInvalidatesLateCallbacksBeforeEmojiInsertion() {
        val guard = OrbisDictationDraft()
        val ticket = guard.begin("")
        guard.cancel()
        assertEquals(OrbisDictationUpdate(), guard.update(ticket, "🫂（抱一下）", "late"))
    }
    @Test fun newerManualEditingWinsAndRequestsRecordingStop() {
        val guard = OrbisDictationDraft()
        val ticket = guard.begin("old")
        assertEquals(OrbisDictationUpdate(stopCapture = true), guard.update(ticket, "edited", "late"))
        assertEquals(OrbisDictationUpdate(), guard.update(ticket, "edited", "later"))
    }
    @Test fun oldCallbackCannotStopOrOverwriteNewRecording() {
        val guard = OrbisDictationDraft()
        val old = guard.begin("old")
        val current = guard.begin("new")
        assertEquals(OrbisDictationUpdate(), guard.update(old, "new", "late"))
        assertEquals("new hi", guard.update(current, "new", "hi").text)
    }
}
