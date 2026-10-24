package me.rerere.rikkahub.data.orbis.body

import org.junit.Assert.*
import org.junit.Test

class BodySetupTest {
    private fun state(mode: String, running: Boolean = true, words: List<String> = listOf("休息")) =
        BodyState(running = running, versions = listOf(BodyVersion(1, 0,
            BodyDefinition(listOf(BodyDimension("energy", "活力")),
                listOf(BodyRule("rest", mapOf("energy" to 1.0), keywords = words)), triggerMode = mode))))

    @Test fun newDefinitionsDefaultToAutoButExistingManualAndAutoEditsKeepTheirMode() {
        assertEquals("auto", BodySetup.defaultDraftMode(BodyState()))
        assertEquals("manual", BodySetup.defaultDraftMode(state("manual")))
        assertEquals("auto", BodySetup.defaultDraftMode(state("auto")))
        assertEquals("manual", BodySetup.defaultDraftMode(BodyState(draft = BodyDefinition())))
        assertEquals("manual", BodyRules.json.decodeFromString<BodyDefinition>("{}").triggerMode)
    }

    @Test fun manualMissingTriggersAndPausedAreVisibleProblemsButRunningAutoNeedsNoNag() {
        assertEquals("needs_definition", BodySetup.notice(BodyState())!!.code)
        val manual = state("manual");val before = BodyRules.json.encodeToString(BodyState.serializer(), manual)
        val notice = BodySetup.notice(manual)!!
        assertEquals("manual_mode", notice.code)
        assertTrue(notice.humanMessage.contains("当前仍由 AI 逐次触发"))
        assertTrue(notice.aiNextStep.contains("triggerMode=auto"))
        assertFalse(notice.request.contains("triggerMode")) // No API syntax for the human to remember.
        assertEquals(before, BodyRules.json.encodeToString(BodyState.serializer(), manual))
        assertEquals("message_triggers_missing", BodySetup.notice(state("auto", words = emptyList()))!!.code)
        assertEquals("auto_paused", BodySetup.notice(state("auto", running = false))!!.code)
        assertNull(BodySetup.notice(state("auto")))
    }
}
