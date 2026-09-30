package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbisChatFlowSettingsTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `old appearance adds paragraph flow but does not classify any actions`() {
        val old = json.decodeFromString<OrbisAppearance>("""{
            "bubbleStyle":"glass", "chatTextColor":-256, "eventOpacity":0.35,
            "backgroundImage":"synthetic-kept.png", "composerOpacity":0.75
        }""")
        assertEquals(OrbisChatFlowSettings(), old.chatFlow)
        assertTrue(old.chatFlow.enabled)
        assertFalse(old.chatFlow.distinguishActions)
        assertTrue(old.chatFlow.effectiveMarkers.isEmpty())
        assertFalse(old.chatFlow.foldActions)
        assertEquals(-256, old.chatTextColor)
        assertEquals("synthetic-kept.png", old.backgroundImage)
        assertEquals(.35f, old.eventOpacity, 0f)
        assertEquals(.75f, old.composerOpacity, 0f)
    }

    @Test
    fun `partial and empty settings decode with safe independent defaults`() {
        assertEquals(OrbisChatFlowSettings(), json.decodeFromString<OrbisChatFlowSettings>("{}"))
        val value = json.decodeFromString<OrbisChatFlowSettings>("""{"collapseActions":true}""")
        assertFalse(value.foldActions)
        assertFalse(value.distinguishActions)
    }

    @Test
    fun `all markers have stable serialization and matching symbols`() {
        val values = OrbisActionMarker.entries
        assertEquals(listOf('(', '（', '[', '『', '「', '〔', '【'), values.map { it.open })
        assertEquals(listOf(')', '）', ']', '』', '」', '〕', '】'), values.map { it.close })
        assertEquals(listOf("ascii_round", "fullwidth_round", "square", "double_corner", "corner", "tortoise", "lenticular"),
            values.map { json.encodeToString(it).trim('"') })
        values.forEach { marker ->
            assertEquals(marker, json.decodeFromString<OrbisActionMarker>(json.encodeToString(marker)))
        }
    }

    @Test
    fun `all booleans and marker selections round trip`() {
        for (enabled in listOf(false, true)) for (distinguish in listOf(false, true)) {
            for (collapse in listOf(false, true)) for (markers in listOf(emptySet(), OrbisActionMarker.entries.toSet())) {
                val value = OrbisChatFlowSettings(enabled, distinguish, markers, collapse)
                assertEquals(value, json.decodeFromString<OrbisChatFlowSettings>(json.encodeToString(value)))
            }
        }
    }

    @Test
    fun `first marker selection enables only classification and defaults to dimmed visible text`() {
        val value = OrbisChatFlowSettings().withMarker(OrbisActionMarker.FULLWIDTH_ROUND, true)
        assertTrue(value.distinguishActions)
        assertEquals(setOf(OrbisActionMarker.FULLWIDTH_ROUND), value.effectiveMarkers)
        assertFalse(value.collapseActions)
        assertFalse(value.foldActions)
    }

    @Test
    fun `marker selections are multi select and idempotent`() {
        val first = OrbisChatFlowSettings().withMarker(OrbisActionMarker.SQUARE, true)
        val both = first.withMarker(OrbisActionMarker.DOUBLE_CORNER, true)
        assertEquals(setOf(OrbisActionMarker.SQUARE, OrbisActionMarker.DOUBLE_CORNER), both.markers)
        assertEquals(both, both.withMarker(OrbisActionMarker.SQUARE, true))
        assertEquals(first, both.withMarker(OrbisActionMarker.DOUBLE_CORNER, false))
    }

    @Test
    fun `no action distinction overrides remembered markers and folding without erasing them`() {
        val selected = OrbisChatFlowSettings(distinguishActions = true,
            markers = OrbisActionMarker.entries.toSet(), collapseActions = true)
        val disabled = selected.withNoActionDistinction(true)
        assertTrue(disabled.enabled)
        assertEquals(selected.markers, disabled.markers)
        assertTrue(disabled.collapseActions)
        assertTrue(disabled.effectiveMarkers.isEmpty())
        assertFalse(disabled.foldActions)
        assertEquals(selected, disabled.withNoActionDistinction(false))
    }

    @Test
    fun `turning off no distinction does not silently choose a marker`() {
        val value = OrbisChatFlowSettings().withNoActionDistinction(false)
        assertTrue(value.distinguishActions)
        assertTrue(value.markers.isEmpty())
        assertTrue(value.effectiveMarkers.isEmpty())
        assertFalse(value.foldActions)
    }

    @Test
    fun `removing the last marker does not guess a replacement`() {
        val value = OrbisChatFlowSettings().withMarker(OrbisActionMarker.CORNER, true)
            .copy(collapseActions = true).withMarker(OrbisActionMarker.CORNER, false)
        assertTrue(value.distinguishActions)
        assertTrue(value.collapseActions)
        assertTrue(value.markers.isEmpty())
        assertFalse(value.foldActions)
    }

    @Test
    fun `layout master switch overrides styling and preserves every preference`() {
        val selected = OrbisChatFlowSettings(distinguishActions = true,
            markers = setOf(OrbisActionMarker.TORTOISE), collapseActions = true)
        val disabled = selected.copy(enabled = false)
        assertTrue(disabled.distinguishActions)
        assertEquals(selected.markers, disabled.markers)
        assertTrue(disabled.collapseActions)
        assertTrue(disabled.effectiveMarkers.isEmpty())
        assertFalse(disabled.foldActions)
        assertEquals(selected, disabled.copy(enabled = true))
    }

    @Test
    fun `folding needs layout classification a marker and the explicit fold toggle`() {
        for (enabled in listOf(false, true)) for (distinguish in listOf(false, true)) {
            for (collapse in listOf(false, true)) for (hasMarker in listOf(false, true)) {
                val value = OrbisChatFlowSettings(enabled, distinguish,
                    if (hasMarker) setOf(OrbisActionMarker.ASCII_ROUND) else emptySet(), collapse)
                assertEquals(enabled && distinguish && collapse && hasMarker, value.foldActions)
            }
        }
    }

    @Test
    fun `nested settings edits preserve color background and unrelated display fields`() {
        val old = DisplaySetting(userNickname = "Synthetic", showUserAvatar = false,
            orbisAppearance = OrbisAppearance(chatTextColor = -256, bubbleOpacity = .4f,
                backgroundImage = "synthetic-background.png", eventOpacity = .2f))
        val flow = OrbisChatFlowSettings().withMarker(OrbisActionMarker.LENTICULAR, true)
        val changed = old.copy(orbisAppearance = old.orbisAppearance.copy(chatFlow = flow).normalized())
        assertEquals(old, changed.copy(orbisAppearance = changed.orbisAppearance.copy(chatFlow = old.orbisAppearance.chatFlow)))
        assertEquals(changed, json.decodeFromString<DisplaySetting>(json.encodeToString(changed)))
    }
}
