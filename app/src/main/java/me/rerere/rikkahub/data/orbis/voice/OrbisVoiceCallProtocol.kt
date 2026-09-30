package me.rerere.rikkahub.data.orbis.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
enum class OrbisVoiceCallMarkerKind { BEGIN, END, SUMMARY }

@Serializable
data class OrbisVoiceCallMarker(
    val kind: OrbisVoiceCallMarkerKind,
    val callId: String,
    val durationMs: Long? = null,
    val summary: String? = null,
)

/** Structured first-line markers; UI folding must never control model context or archive reads. */
object OrbisVoiceCallProtocol {
    const val PREFIX = "ORBIS_VOICE_CALL_V1 "
    const val CALL_MODE_PREFIX = "CALL_MODE_V1 "
    private val json = Json { encodeDefaults = false }

    fun begin(id: String): String {
        validateVoiceCallId(id)
        return encode(OrbisVoiceCallMarker(OrbisVoiceCallMarkerKind.BEGIN, id)) +
            "\n【已进入语音通话】\n通话输入是语音转写后的文字，不含音频、语调或呼吸信息。回答会被朗读，请用自然短句，不写代码块、装饰符号或括号动作。"
    }

    fun userTurn(id: String, text: String): String {
        validateVoiceCallId(id)
        require(text.isNotBlank()) { "empty_voice_call_turn" }
        val metadata = buildJsonObject { put("active", true); put("callId", id) }
        return CALL_MODE_PREFIX + metadata.toString() + "\n" + text
    }

    fun end(record: OrbisVoiceCallRecord): String =
        encode(OrbisVoiceCallMarker(OrbisVoiceCallMarkerKind.END, record.id, record.durationMs)) +
            "\n" + title(record.durationMs) + "\nCALL_MODE_V1 {\"active\":false}"

    /** Call only after the complete archive has been durably saved; text remains model-readable. */
    fun summary(record: OrbisVoiceCallRecord): String {
        require(record.archiveStatus == OrbisVoiceArchiveStatus.READY && isUsableVoiceArchiveText(record.summary)) {
            "voice_call_summary_not_ready"
        }
        return encode(OrbisVoiceCallMarker(OrbisVoiceCallMarkerKind.SUMMARY, record.id,
            record.durationMs, record.summary)) + "\n" + title(record.durationMs)
    }

    /** Does not scan prose/code blocks for marker lookalikes, or partially parse malformed JSON. */
    fun parse(text: String): OrbisVoiceCallMarker? {
        val firstLine = text.lineSequence().firstOrNull() ?: return null
        if (!firstLine.startsWith(PREFIX)) return null
        return runCatching {
            json.decodeFromString<OrbisVoiceCallMarker>(firstLine.removePrefix(PREFIX)).also(::validate)
        }.getOrNull()
    }

    fun title(durationMs: Long?): String {
        if (durationMs == null || durationMs < 0) return "【通话结束】"
        val seconds = durationMs / 1000
        return "【通话时长 ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}】"
    }

    /** Accept one complete JSON answer, optionally enclosed in a single JSON code fence. */
    fun parseModelArchive(reply: String): OrbisVoiceModelArchive? {
        val trimmed = reply.trim()
        val payload = if (trimmed.startsWith("```")) {
            Regex("\\A```(?:json)?[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```\\z", RegexOption.IGNORE_CASE)
                .matchEntire(trimmed)?.groupValues?.get(1) ?: return null
        } else trimmed
        return runCatching {
            val objectValue = json.parseToJsonElement(payload) as? JsonObject ?: return null
            if (objectValue.keys != setOf("summary", "transcript")) return null
            fun string(name: String): String? = (objectValue[name] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.trim()
            val summary = string("summary") ?: return null
            val transcript = string("transcript") ?: return null
            if (!isUsableVoiceArchiveText(summary) || !isUsableVoiceArchiveText(transcript)) return null
            OrbisVoiceModelArchive(summary, transcript)
        }.getOrNull()
    }

    private fun encode(marker: OrbisVoiceCallMarker): String {
        validate(marker)
        return PREFIX + json.encodeToString(marker)
    }

    private fun validate(marker: OrbisVoiceCallMarker) {
        validateVoiceCallId(marker.callId)
        require(marker.durationMs == null || marker.durationMs >= 0)
        if (marker.kind == OrbisVoiceCallMarkerKind.SUMMARY) require(isUsableVoiceArchiveText(marker.summary))
        else require(marker.summary == null)
    }
}

internal fun isUsableVoiceArchiveText(value: String?): Boolean {
    val text = value?.trim().orEmpty()
    if (text.isBlank()) return false
    if (text.lowercase() in setOf("null", "none", "n/a", "summary", "transcript", "摘要", "通话摘要", "全文",
            "通话全文", "文字版全文", "...", "…", "……", "待填写", "待生成", "todo")) return false
    if (Regex("\\A(?:<[^>]*>|\\{\\{[^}]*\\}\\}|\\$\\{[^}]*\\})\\z").matches(text)) return false
    if (Regex("\\A(?:请)?(?:在此)?(?:填写|生成|写入)(?:通话)?(?:摘要|全文|记录)[。.!！]?\\z").matches(text)) return false
    return true
}
