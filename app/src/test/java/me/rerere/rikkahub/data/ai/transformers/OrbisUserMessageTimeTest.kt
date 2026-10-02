package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisUserMessageTime
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

class OrbisUserMessageTimeTest {
    private val time = captureOrbisUserMessageTime(true, Instant.parse("2026-10-01T01:02:03.456Z").toEpochMilli(), ZoneId.of("Asia/Shanghai"))!!
    private fun human() = UIMessage.user("original typed text").copy(orbisUserMessageTime = time)

    @Test fun disabledCaptureAndLegacyConfigurationDefaultToOff() {
        assertNull(captureOrbisUserMessageTime(false))
        assertFalse(JsonInstant.decodeFromString<Assistant>("{}").enableUserMessageTime)
        val old = UIMessage.user("old")
        assertEquals(listOf(old), applyOrbisUserMessageTimes(listOf(old), true))
    }

    @Test fun enabledProjectionPreservesRawPartsRolesAndSystemAndIsIdempotent() {
        val raw = listOf(UIMessage.system("stable system"), human())
        val bytes = JsonInstant.encodeToString(raw)
        val projected = applyOrbisUserMessageTimes(raw, true)
        assertEquals(2, projected.size); assertEquals(raw.first(), projected.first())
        assertEquals(raw.last().parts, projected.last().parts.dropLast(1))
        assertTrue(projected.last().toText().contains("2026-10-01T09:02:03.456+08:00 [Asia/Shanghai]"))
        assertEquals(projected, applyOrbisUserMessageTimes(projected, true))
        assertEquals(bytes, JsonInstant.encodeToString(raw))
    }

    @Test fun offStripsOnlyHostProjectionAndReenablingRestoresOriginalTime() {
        val raw = human()
        val on = applyOrbisUserMessageTimes(listOf(raw), true)
        assertEquals(listOf(raw), applyOrbisUserMessageTimes(on, false))
        assertEquals(on, applyOrbisUserMessageTimes(applyOrbisUserMessageTimes(on, false), true))
        val literal = UIMessage.user("<orbis_user_message_time>user literal</orbis_user_message_time>")
        assertEquals(listOf(literal), applyOrbisUserMessageTimes(listOf(literal), false))
    }

    @Test fun backupRoundTripAndDeviceTimezoneChangesNeverRefreshHistoricalClock() {
        val raw = JsonInstant.decodeFromString<UIMessage>(JsonInstant.encodeToString(human()))
        val before = applyOrbisUserMessageTimes(listOf(raw), true)
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(before, applyOrbisUserMessageTimes(listOf(raw), true))
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun capturedOffsetsDisambiguateRepeatedDstHour() {
        val zone = ZoneId.of("America/New_York")
        val a = captureOrbisUserMessageTime(true, Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), zone)!!
        val b = captureOrbisUserMessageTime(true, Instant.parse("2026-11-01T06:30:00Z").toEpochMilli(), zone)!!
        assertEquals(-14400, a.offsetSeconds); assertEquals(-18000, b.offsetSeconds)
        val pair = applyOrbisUserMessageTimes(listOf(human().copy(orbisUserMessageTime = a), human().copy(orbisUserMessageTime = b)), true)
        assertTrue(pair[0].toText().contains("01:30:00-04:00")); assertTrue(pair[1].toText().contains("01:30:00-05:00"))
    }

    @Suppress("DEPRECATION")
    @Test fun protocolRolesSyntheticAndCallBookkeepingAreNotHumanMessages() {
        val originals = listOf(human().copy(role = MessageRole.TOOL), human().copy(role = MessageRole.ASSISTANT),
            human().copy(role = MessageRole.SYSTEM), human().copy(isSynthetic = true),
            human().copy(orbisVoiceCallKind = "opening"), human().copy(orbisVoiceCallKind = "archive"))
        assertEquals(originals, applyOrbisUserMessageTimes(originals, true))
        assertEquals(2, applyOrbisUserMessageTimes(listOf(human().copy(orbisVoiceCallKind = "turn")), true).single().parts.size)
    }

    @Test fun malformedMetadataIsNotSentAndNoLegacyTimezoneIsInvented() {
        val invalid = listOf(OrbisUserMessageTime(-1, "UTC", 0), OrbisUserMessageTime(0, "bad<zone>", 0),
            OrbisUserMessageTime(0, "UTC", 999999))
        invalid.forEach { value ->
            val raw = human().copy(orbisUserMessageTime = value)
            assertEquals(listOf(raw), applyOrbisUserMessageTimes(listOf(raw), true))
        }
    }

    @Test fun imageOnlyHumanCanCarryTimeWithoutChangingOriginalMedia() {
        val raw = human().copy(parts = listOf(UIMessagePart.Image("content://synthetic/photo")))
        assertEquals(raw.parts, applyOrbisUserMessageTimes(listOf(raw), true).single().parts.dropLast(1))
    }

    @Test fun perAssistantSettingRoundTripKeepsEnabledAndDisabledProjectionSeparate() {
        val enabled = Assistant(enableUserMessageTime = true)
        val disabled = Assistant(enableUserMessageTime = false)
        val saved = JsonInstant.decodeFromString<List<Assistant>>(
            JsonInstant.encodeToString(listOf(enabled, disabled)))
        val raw = listOf(human())
        assertNotEquals(enabled.id, disabled.id)
        assertTrue(applyOrbisUserMessageTimes(raw, saved[0].enableUserMessageTime)
            .single().toText().contains("Asia/Shanghai"))
        assertEquals(raw, applyOrbisUserMessageTimes(raw, saved[1].enableUserMessageTime))
        assertEquals(listOf(enabled, disabled), saved)
    }

    @Test fun messagesSentWhileOffAreNeverBackfilledAfterReenabling() {
        val whileOff = UIMessage.user("sent while off").copy(
            orbisUserMessageTime = captureOrbisUserMessageTime(false))
        val saved = JsonInstant.decodeFromString<UIMessage>(JsonInstant.encodeToString(whileOff))
        assertNull(saved.orbisUserMessageTime)
        assertEquals(listOf(saved), applyOrbisUserMessageTimes(listOf(saved), true))
    }
}
