package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.ai.withHostToolFailure
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.OrbisVoiceNoteDisplayItem
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import me.rerere.rikkahub.ui.components.message.orbisVoiceNoteDisplay
import org.junit.Assert.*
import org.junit.Test

/** Synthetic only; no microphone, TTS provider, real files or conversations. */
class OrbisVoiceNoteTest {
    // Time-based constructor defaults must not change snapshot field omission between calls.
    private val snapshots = Json { encodeDefaults = true }
    private fun voice(text: String = "合成转写") = UIMessagePart.Audio(
        "file:///synthetic/upload/test.wav", orbisVoiceNoteMetadata(text, "原始合成转写", 1234))
    private fun tool(vararg output: UIMessagePart, name: String = "orbis_voice_note") =
        UIMessagePart.Tool("synthetic-call", name, "{}", output.toList())

    @Test fun voiceFlagRequiresTypedTrueAndNeverThrowsForMalformedValues() {
        listOf(JsonNull, JsonPrimitive(false), JsonPrimitive("true"), JsonPrimitive(1),
            JsonObject(emptyMap()), JsonArray(emptyList())).forEach { malformed ->
            assertFalse(voice().copy(metadata = buildJsonObject { put("orbis_voice_note", malformed) }).isOrbisVoiceNote())
        }
        assertFalse(UIMessagePart.Audio("ordinary").isOrbisVoiceNote())
        assertTrue(voice().isOrbisVoiceNote())
    }

    @Test fun transcriptRequiresStringAndDurationRequiresBoundedNumber() {
        listOf(JsonNull, JsonPrimitive(true), JsonPrimitive(123), JsonObject(emptyMap()), JsonArray(emptyList())).forEach {
            assertEquals("", voice().copy(metadata = buildJsonObject { put("transcript", it) }).voiceNoteTranscript())
        }
        listOf(JsonNull, JsonPrimitive(-1), JsonPrimitive("1234"), JsonPrimitive(Long.MAX_VALUE),
            JsonObject(emptyMap()), JsonArray(emptyList())).forEach {
            assertEquals(0L, voice().copy(metadata = buildJsonObject { put("duration_ms", it) }).voiceNoteDurationMs())
        }
        assertEquals(1234L, voice().voiceNoteDurationMs())
        assertEquals(32_000, voice("a".repeat(40_000)).voiceNoteTranscript().length)
    }

    @Test fun requestProjectionRecursesThroughToolsAndLeavesStoredHistoryUntouched() {
        val audio = voice()
        val source = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool(tool(audio))))
        val request = source.withVoiceNoteTranscripts()
        val outer = request.parts.single() as UIMessagePart.Tool
        val projected = (outer.output.single() as UIMessagePart.Tool).output.single() as UIMessagePart.Text
        assertEquals("[语音条转写] 合成转写", projected.text)
        assertNull(projected.metadata)
        assertSame(audio, ((source.parts.single() as UIMessagePart.Tool).output.single() as UIMessagePart.Tool).output.single())
        assertEquals(source.id, request.id)
        assertEquals(source.createdAt, request.createdAt)
    }

    @Test fun requestProjectionDoesNotPretendEmptyTranscriptHasContent() {
        val request = UIMessage(role = MessageRole.USER, parts = listOf(voice("  "))).withVoiceNoteTranscripts()
        assertEquals("[语音条：未取得转写文字，不要猜测内容。]", (request.parts.single() as UIMessagePart.Text).text)
    }

    @Test fun ordinaryAudioAndOtherPartsKeepTheirProviderMeaning() {
        val ordinary = UIMessagePart.Audio("https://example.invalid/audio.mp3")
        val text = UIMessagePart.Text("未改动")
        val source = UIMessage(role = MessageRole.USER, parts = listOf(ordinary, text))
        assertEquals(source, source.withVoiceNoteTranscripts())
    }

    @Test fun successfulVoiceToolExposesOneNativeBubbleInOrderWithoutDiscardingToolRecord() {
        val audio = voice()
        val blocks = listOf(UIMessagePart.Text("前"), tool(audio), UIMessagePart.Text("后")).groupMessageParts()
        assertEquals(3, blocks.size)
        assertTrue(blocks.none { it is MessagePartBlock.ThinkingBlock })
        assertEquals(audio, (blocks[1] as MessagePartBlock.ContentBlock).part)
        assertEquals(1, (blocks[1] as MessagePartBlock.ContentBlock).index)
    }

    @Test fun pendingFailedOrUnrelatedToolsDoNotInventPlayableVoice() {
        listOf(tool(), tool(UIMessagePart.Text("失败")),
            tool(UIMessagePart.Audio("file:///ordinary.wav")), tool(voice(), name = "other_tool")).forEach { item ->
            assertTrue(listOf(item).groupMessageParts().none { it is MessagePartBlock.ContentBlock })
        }
    }

    @Test fun voiceAttachmentReferencesInsideNestedToolsRemainDiscoverableForBackupAndCleanup() {
        val audio = voice()
        assertEquals(setOf(audio.url), listOf(tool(tool(audio))).localFileUrls())
        assertEquals(audio.metadata, ((tool(audio).copy(output = listOf(audio.copy(url = "file:///copied.wav"))))
            .output.single() as UIMessagePart.Audio).metadata)
    }

    @Test fun voiceOnlyProjectionHasOneBubbleNoEmptyProseOrToolCardAndPreservesOriginalDetails() {
        val reasoning = UIMessagePart.Reasoning("synthetic reasoning")
        val originalTool = tool(voice())
        val source = listOf(reasoning, UIMessagePart.Text("  \n"), originalTool, UIMessagePart.Text(""))
        val before = snapshots.encodeToString(source)
        val rows = source.orbisVoiceNoteDisplay()!!
        assertEquals(1, rows.size)
        val row = rows.single() as OrbisVoiceNoteDisplayItem.Voice
        assertEquals(listOf(reasoning, originalTool), row.details)
        assertEquals(2, row.partIndex)
        assertEquals(0, row.outputIndex)
        assertSame(originalTool.output.single(), row.audio)
        assertEquals(before, snapshots.encodeToString(source))
    }

    @Test fun mixedReplyKeepsOtherActionsReasoningAndTextInOrderAroundIndependentVoices() {
        val reasoning = UIMessagePart.Reasoning("keep reasoning with normal work")
        val other = tool(UIMessagePart.Text("other result"), name = "other_tool")
        val before = UIMessagePart.Text("before")
        val after = UIMessagePart.Text("after")
        val rows = listOf(reasoning, other, before, tool(voice()), after).orbisVoiceNoteDisplay()!!
        assertEquals(3, rows.size)
        assertEquals(listOf(reasoning, other, before), (rows[0] as OrbisVoiceNoteDisplayItem.Content).parts)
        assertEquals(1, (rows[1] as OrbisVoiceNoteDisplayItem.Voice).details.size)
        assertEquals(listOf(after), (rows[2] as OrbisVoiceNoteDisplayItem.Content).parts)
    }

    @Test fun failureDenialPendingMixedOutputAndOtherToolsNeverBecomeHiddenSuccess() {
        val variants = listOf(tool(), tool(UIMessagePart.Text("failed")),
            tool(voice(), UIMessagePart.Text("partial failure")),
            tool(voice()).copy(approvalState = ToolApprovalState.Denied("denied")),
            tool(voice()).copy(approvalState = ToolApprovalState.Pending),
            tool().withHostToolFailure(HostToolFailure.INTERRUPTED),
            tool(voice(), name = "other_tool"))
        variants.forEach { item ->
            assertTrue(item.successfulOrbisVoiceNotes().isEmpty())
            assertNull(listOf(item).orbisVoiceNoteDisplay())
            val rows = listOf(tool(voice()), item).orbisVoiceNoteDisplay()!!
            assertEquals(listOf(item), (rows.last() as OrbisVoiceNoteDisplayItem.Content).parts)
        }
    }

    @Test fun multipleVoicesHaveIndependentStableIdentitiesAndNoProviderMessageDuplication() {
        val a = voice("first")
        val b = voice("second")
        val source = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool(a, b)))
        val rows = source.parts.orbisVoiceNoteDisplay()!!.filterIsInstance<OrbisVoiceNoteDisplayItem.Voice>()
        assertEquals(2, rows.size)
        assertNotEquals(a.voiceNoteId(), b.voiceNoteId())
        assertNotEquals(a.voiceNotePlaybackKey(source.id.toString(), rows[0].occurrenceKey),
            b.voiceNotePlaybackKey(source.id.toString(), rows[1].occurrenceKey))
        val request = source.withVoiceNoteTranscripts()
        assertEquals(1, request.parts.size)
        assertEquals(2, (request.parts.single() as UIMessagePart.Tool).output.size)
        assertEquals(source.id, request.id)
    }

    @Test fun persistedIdentityAndPlayedStateSurviveSerializationAndBackupUrlRelocation() {
        val audio = voice()
        val source = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool(audio)))
        val key = audio.voiceNotePlaybackKey(source.id.toString(), "0:0")
        val played = source.withVoiceNotePlayed(0, 0, key)
        val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(played))
        val savedAudio = (restored.parts.single() as UIMessagePart.Tool).output.single() as UIMessagePart.Audio
        val relocated = savedAudio.copy(url = "file:///different-device/upload/restored.wav")
        assertTrue(relocated.voiceNotePlayed())
        assertEquals(audio.voiceNoteId(), relocated.voiceNoteId())
        assertEquals(key, relocated.voiceNotePlaybackKey(restored.id.toString(), "0:0"))
        assertFalse(audio.voiceNotePlayed())
        assertEquals(source.withVoiceNoteTranscripts(), played.withVoiceNoteTranscripts())
    }

    @Test fun narrowPlayedEditPreservesLatestTextAndRejectsStaleOrWrongTargets() {
        val audio = voice()
        val source = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool(audio)))
        val node = MessageNode.of(source)
        val edit = OrbisVoiceNotePlayedEdit(node.id, source.id, 0, 0, audio.voiceNotePlaybackKey(source.id.toString(), "0:0"))
        val latest = source.copy(parts = source.parts + UIMessagePart.Text("new streaming text"))
        val saved = edit.applyTo(latest)
        assertEquals(latest.parts.last(), saved.parts.last())
        assertTrue(((saved.parts.first() as UIMessagePart.Tool).output.single() as UIMessagePart.Audio).voiceNotePlayed())
        assertSame(saved, edit.applyTo(saved))
        assertSame(latest, edit.copy(expectedPlaybackKey = "wrong").applyTo(latest))
        assertSame(latest, edit.copy(partIndex = 99).applyTo(latest))
        val other = UIMessage(role = MessageRole.ASSISTANT, parts = source.parts)
        assertSame(other, edit.applyTo(other))
        val replaced = source.copy(parts = listOf(tool(voice("replacement"))))
        assertSame(replaced, edit.applyTo(replaced))
    }

    @Test fun legacyAudioUsesStableSlotAndMalformedIdentityCannotThrowOrImpersonatePlayedState() {
        val legacy = UIMessagePart.Audio("file:///old.wav", buildJsonObject {
            put("orbis_voice_note", true); put("transcript", "legacy")
            put("voice_note_id", JsonObject(emptyMap())); put("voice_note_played", "true")
        })
        assertNull(legacy.voiceNoteId())
        assertFalse(legacy.voiceNotePlayed())
        assertEquals(legacy.voiceNotePlaybackKey("msg", "2:0"), legacy.copy(url = "file:///new.wav").voiceNotePlaybackKey("msg", "2:0"))
        assertNotEquals(legacy.voiceNotePlaybackKey("msg", "2:0"), legacy.voiceNotePlaybackKey("msg", "2:1"))
        val direct = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(legacy))
        val changed = direct.withVoiceNotePlayed(0, null, legacy.voiceNotePlaybackKey(direct.id.toString(), "0:direct"))
        assertTrue((changed.parts.single() as UIMessagePart.Audio).voiceNotePlayed())
    }

    @Test fun committedHeardStateSurvivesStaleStreamingSnapshotWithoutCopyingOtherCurrentChanges() {
        val audio = voice()
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool(audio)))
        val node = MessageNode.of(message)
        val snapshot = Conversation(assistantId = kotlin.uuid.Uuid.random(), messageNodes = listOf(node))
        val playedMessage = message.withVoiceNotePlayed(0, 0, audio.voiceNotePlaybackKey(message.id.toString(), "0:0"))
        val current = snapshot.copy(title = "current title must not replace snapshot", messageNodes = listOf(node.copy(messages = listOf(playedMessage))))
        val streaming = snapshot.copy(messageNodes = listOf(node.copy(messages = listOf(message.copy(parts = message.parts + UIMessagePart.Text("new stream"))))))
        val merged = withCommittedVoiceNotePlayed(streaming, current)
        assertEquals(streaming.title, merged.title)
        assertEquals(streaming.currentMessages.single().parts.last(), merged.currentMessages.single().parts.last())
        assertTrue(((merged.currentMessages.single().parts.first() as UIMessagePart.Tool).output.single() as UIMessagePart.Audio).voiceNotePlayed())
        assertSame(merged, withCommittedVoiceNotePlayed(merged, current))
        assertSame(streaming, withCommittedVoiceNotePlayed(streaming, current.copy(id = kotlin.uuid.Uuid.random())))
        val replacement = streaming.copy(messageNodes = listOf(node.copy(messages = listOf(message.copy(parts = listOf(tool(voice("new note"))))))))
        assertSame(replacement, withCommittedVoiceNotePlayed(replacement, current))
    }

    @Test fun committedPlaybackMergeCoversDirectAndNestedNotesButNeverInventsRemovedAudio() {
        val a = voice("a")
        val b = voice("b")
        fun played(audio: UIMessagePart.Audio) = audio.copy(metadata = JsonObject(audio.metadata!! + ("voice_note_played" to JsonPrimitive(true))))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(a, tool(tool(b))))
        val node = MessageNode.of(message)
        val snapshot = Conversation(assistantId = kotlin.uuid.Uuid.random(), messageNodes = listOf(node))
        val current = snapshot.copy(messageNodes = listOf(node.copy(messages = listOf(message.copy(parts = listOf(played(a), tool(tool(played(b)))))))))
        val merged = withCommittedVoiceNotePlayed(snapshot, current).currentMessages.single()
        assertTrue((merged.parts[0] as UIMessagePart.Audio).voiceNotePlayed())
        assertTrue((((merged.parts[1] as UIMessagePart.Tool).output.single() as UIMessagePart.Tool).output.single() as UIMessagePart.Audio).voiceNotePlayed())
        val removed = snapshot.copy(messageNodes = listOf(node.copy(messages = listOf(message.copy(parts = listOf(UIMessagePart.Text("removed")))))))
        assertSame(removed, withCommittedVoiceNotePlayed(removed, current))
    }
}
