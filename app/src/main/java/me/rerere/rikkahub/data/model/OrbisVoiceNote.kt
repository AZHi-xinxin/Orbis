package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.data.ai.hostToolFailure
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.uuid.Uuid

private const val VOICE_NOTE_KEY = "orbis_voice_note"
const val ORBIS_VOICE_NOTE_MAX_TEXT_CHARS = 2000
fun UIMessagePart.Audio.isOrbisVoiceNote() = (metadata?.get(VOICE_NOTE_KEY) as? JsonPrimitive)
    ?.takeUnless { it.isString }?.booleanOrNull == true
fun UIMessagePart.Audio.voiceNoteTranscript() = (metadata?.get("transcript") as? JsonPrimitive)
    ?.takeIf { it.isString }?.contentOrNull?.take(32_000).orEmpty()
fun UIMessagePart.Audio.voiceNoteDurationMs() = (metadata?.get("duration_ms") as? JsonPrimitive)
    ?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..86_400_000L } ?: 0L
fun UIMessagePart.Audio.voiceNoteId(): String? = (metadata?.get("voice_note_id") as? JsonPrimitive)
    ?.takeIf { it.isString }?.contentOrNull?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
fun UIMessagePart.Audio.voiceNotePlayed(): Boolean = (metadata?.get("voice_note_played") as? JsonPrimitive)
    ?.takeUnless { it.isString }?.booleanOrNull == true

/** Stable across database replay and backup URL relocation. Legacy notes use their original slot. */
fun UIMessagePart.Audio.voiceNotePlaybackKey(messageKey: String, occurrenceKey: String = ""): String =
    MessageDigest.getInstance("SHA-256").digest(
        "$messageKey|${voiceNoteId() ?: "legacy:$occurrenceKey:${JsonObject(metadata.orEmpty().filterKeys { it != "voice_note_played" }.toSortedMap())}"}"
            .toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it.toInt() and 255) }

fun orbisVoiceNoteMetadata(transcript: String, original: String = transcript, durationMs: Long = 0L,
    noteId: String = UUID.randomUUID().toString()) = buildJsonObject {
    require(noteId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
    put(VOICE_NOTE_KEY, true)
    put("voice_note_id", noteId)
    put("voice_note_played", false)
    put("transcript", transcript.take(32_000))
    put("original_transcript", original.take(32_000))
    put("duration_ms", durationMs.takeIf { it in 0..86_400_000L } ?: 0L)
}

/** Only a completed, audio-only native result is presentation-safe. Never conceal errors/mixed output. */
fun UIMessagePart.Tool.successfulOrbisVoiceNotes(): List<UIMessagePart.Audio> {
    if (toolName !in setOf("orbis_voice_note", "orbis_send_audio") || !isExecuted || hostToolFailure() != null ||
        approvalState is ToolApprovalState.Denied || approvalState is ToolApprovalState.Pending) return emptyList()
    if (output.any { it !is UIMessagePart.Audio || !it.isOrbisVoiceNote() }) return emptyList()
    return output.filterIsInstance<UIMessagePart.Audio>()
}

/** Host should merge this narrow edit into the latest persisted message, never replace a streamed node. */
data class OrbisVoiceNotePlayedEdit(
    val nodeId: Uuid,
    val messageId: Uuid,
    val partIndex: Int,
    val outputIndex: Int?,
    val expectedPlaybackKey: String,
) {
    fun applyTo(message: UIMessage): UIMessage = if (message.id == messageId)
        message.withVoiceNotePlayed(partIndex, outputIndex, expectedPlaybackKey) else message
}

/** Presentation metadata only; keep tool call identity, provider metadata and original output in place. */
fun UIMessage.withVoiceNotePlayed(partIndex: Int, outputIndex: Int?, expectedPlaybackKey: String): UIMessage {
    val part = parts.getOrNull(partIndex) ?: return this
    val audio = (if (outputIndex == null) part else (part as? UIMessagePart.Tool)?.output?.getOrNull(outputIndex))
        as? UIMessagePart.Audio ?: return this
    val occurrenceKey = "$partIndex:${outputIndex ?: "direct"}"
    if (!audio.isOrbisVoiceNote() || audio.voiceNotePlayed() ||
        audio.voiceNotePlaybackKey(id.toString(), occurrenceKey) != expectedPlaybackKey) return this
    val played = audio.copy(metadata = buildJsonObject {
        audio.metadata?.forEach { (key, value) -> put(key, value) }
        put("voice_note_played", true)
    })
    val replacement = if (outputIndex == null) played else (part as UIMessagePart.Tool).copy(
        output = part.output.mapIndexed { index, value -> if (index == outputIndex) played else value })
    return copy(parts = parts.mapIndexed { index, value -> if (index == partIndex) replacement else value })
}

/** Preserve committed presentation-only playback state when an older generation snapshot is saved. */
fun withCommittedVoiceNotePlayed(snapshot: Conversation, current: Conversation): Conversation {
    if (snapshot === current) return snapshot
    if (snapshot.id != current.id) return snapshot
    val currentNodes = current.messageNodes.associateBy { it.id }
    var changed = false
    val nodes = snapshot.messageNodes.map { node ->
        val latest = currentNodes[node.id] ?: return@map node
        if (latest === node) return@map node
        val latestMessages = latest.messages.associateBy { it.id }
        val messages = node.messages.map messages@{ message ->
            val committed = latestMessages[message.id] ?: return@messages message
            if (message === committed || message.parts === committed.parts) return@messages message
            val playedIds = mutableSetOf<String>()
            fun collect(parts: List<UIMessagePart>) {
                parts.forEach { part -> when (part) {
                    is UIMessagePart.Audio -> if (part.isOrbisVoiceNote() && part.voiceNotePlayed()) part.voiceNoteId()?.let(playedIds::add)
                    is UIMessagePart.Tool -> collect(part.output)
                    else -> Unit
                } }
            }
            collect(committed.parts)
            fun merge(part: UIMessagePart, old: UIMessagePart?): UIMessagePart = when (part) {
                is UIMessagePart.Audio -> {
                    val legacy = old as? UIMessagePart.Audio
                    val matches = part.voiceNoteId()?.let { it in playedIds } ?: (
                        legacy != null && legacy.voiceNoteId() == null && legacy.isOrbisVoiceNote() && legacy.voiceNotePlayed() &&
                            legacy.url == part.url && legacy.metadata?.filterKeys { it != "voice_note_played" } ==
                            part.metadata?.filterKeys { it != "voice_note_played" })
                    if (part.isOrbisVoiceNote() && !part.voiceNotePlayed() && matches) part.copy(metadata = buildJsonObject {
                        part.metadata?.forEach { (key, value) -> put(key, value) }
                        put("voice_note_played", true)
                    }) else part
                }
                is UIMessagePart.Tool -> {
                    val previous = (old as? UIMessagePart.Tool)?.takeIf { it.toolCallId == part.toolCallId && it.toolName == part.toolName }
                    val output = part.output.mapIndexed { index, child -> merge(child, previous?.output?.getOrNull(index)) }
                    if (output == part.output) part else part.copy(output = output)
                }
                else -> part
            }
            val parts = message.parts.mapIndexed { index, part -> merge(part, committed.parts.getOrNull(index)) }
            if (parts == message.parts) message else message.copy(parts = parts)
        }
        if (messages == node.messages) node else { changed = true; node.copy(messages = messages) }
    }
    return if (changed) snapshot.copy(messageNodes = nodes) else snapshot
}

/** Only project native voice notes to text on the request copy. Never change stored history.
 * Works inside tool results too, so text-only gateways never receive an unsupported audio part.
 */
fun UIMessage.withVoiceNoteTranscripts(): UIMessage = copy(parts = parts.map { it.voiceNoteRequestPart() })
fun UIMessagePart.Audio.withSharedAcoustics(measurement: OrbisVoiceAcoustics): UIMessagePart.Audio = copy(metadata = buildJsonObject {
    metadata?.forEach { (key, value) -> put(key, value) }
    put("acoustic_v1_shared", true)
    put("acoustic_v1", Json.encodeToJsonElement(OrbisVoiceAcoustics.serializer(), measurement))
})

private fun UIMessagePart.Audio.sharedAcousticsText(): String {
    if (metadata?.get("acoustic_v1_shared") != JsonPrimitive(true)) return ""
    val measurement = runCatching { Json.decodeFromJsonElement(OrbisVoiceAcoustics.serializer(), metadata!!.getValue("acoustic_v1")) }.getOrNull() ?: return ""
    if (measurement.durationMs !in 1..120_000 || measurement.sampleRate !in 8000..48000 ||
        measurement.pitchHz.size > 480 || measurement.pitchHz.any { it != null && (!it.isFinite() || it !in 50.0..600.0) } ||
        measurement.rmsDbfs !in -100.0..0.0 || measurement.peakDbfs !in -100.0..0.0 || measurement.dynamicRangeDb !in 0.0..100.0 ||
        measurement.pauseThresholdDbfs !in -70.0..-10.0 || measurement.minimumPauseMs !in 100..2000 ||
        measurement.pitchStepMs != 250 || measurement.lowEnergySegments.size > 1200 ||
        measurement.lowEnergyPauseCount != measurement.lowEnergySegments.size ||
        measurement.lowEnergyPauseMs !in 0..measurement.durationMs ||
        measurement.lowEnergySegments.any { it.startMs !in 0..measurement.durationMs || it.durationMs !in 1..measurement.durationMs || it.startMs + it.durationMs > measurement.durationMs }) return ""
    return "\n[人类选择附带的本机声学测量] " + measurement.description(voiceNoteTranscript()) +
        "\n[acoustic_v1，F0 为每 250ms 的 50ms 窗粗估值，null 表示不可靠；不是完整轨迹或心理推断] " +
        Json.encodeToString(OrbisVoiceAcoustics.serializer(), measurement)
}
private fun UIMessagePart.voiceNoteRequestPart(): UIMessagePart = when (this) {
    is UIMessagePart.Audio -> if (isOrbisVoiceNote()) UIMessagePart.Text(
        (if (voiceNoteTranscript().isBlank()) "[语音条：未取得转写文字，不要猜测内容。]"
        else "[语音条转写] ${voiceNoteTranscript()}") + sharedAcousticsText()
    ) else this
    is UIMessagePart.Tool -> copy(output = output.map { it.voiceNoteRequestPart() })
    else -> this
}

/** PCM16 LE, mono. A bounded collector; the capture thread never touches the UI or a second mic. */
class OrbisVoicePcmBuffer(private val maxSeconds: Int = 120) {
    init { require(maxSeconds in 1..120) { "录音上限须为 1–120 秒" } }
    private val bytes = ByteArrayOutputStream()
    private var rate = 0
    private var closed = false
    @Volatile var limitReached: Boolean = false
        private set
    @Synchronized fun append(data: ByteArray, sampleRate: Int) {
        if (closed || data.isEmpty() || limitReached) return
        require(sampleRate in 8_000..48_000) { "不支持的录音采样率" }
        if (rate == 0) rate = sampleRate
        require(rate == sampleRate) { "录音格式在中途改变" }
        val remaining = (rate * 2 * maxSeconds - bytes.size()).coerceAtLeast(0)
        // Transport/capture chunk boundaries need not coincide with a 16-bit sample.
        // Retain every byte here so the next block completes an odd trailing byte.
        val count = minOf(data.size, remaining)
        bytes.write(data, 0, count)
        if (bytes.size() >= rate * 2 * maxSeconds) limitReached = true
    }
    @Synchronized fun finish(): Pair<ByteArray, Long> {
        closed = true
        require(rate > 0 && bytes.size() >= rate / 5) { "录音太短或没有取得声音，请重新录制" }
        // Do not silently drop or invent a final half-sample in a corrupted stream.
        require(bytes.size() % 2 == 0) { "录音数据未完整结束，请重新录制" }
        return pcm16MonoWav(bytes.toByteArray(), rate) to (bytes.size().toLong() * 1000 / (rate * 2))
    }
    @Synchronized fun discard() { closed = true; bytes.reset() }
    @Synchronized fun acousticSnapshot(): Pair<ByteArray, Int>? =
        if (rate == 0 || bytes.size() < 2 || bytes.size() % 2 != 0) null else bytes.toByteArray() to rate
}

fun pcm16MonoWav(pcm: ByteArray, sampleRate: Int): ByteArray {
    require(sampleRate in 8_000..192_000 && pcm.size % 2 == 0)
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + pcm.size)
    header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
    header.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
    header.put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size)
    return header.array() + pcm
}
