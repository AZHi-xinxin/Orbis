package me.rerere.rikkahub.data.orbis.observation

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

internal data class ObservationFacts(
    val window: String, val assistant: String, val model: String,
    val modelChanged: Boolean? = null, val regeneration: Long = 0,
    val deleted: Long = 0, val compactions: Long = 0,
    val longestWindow: String? = null, val windowCount: Long = 0,
    val assistantCount: Int = 0, val lastActive: String? = null,
    val tts: String, val toolModels: Map<String, String>,
)

/** A compact report only. No command, advice, model-authorship claim or copied conversation text. */
internal fun observationReport(f: ObservationFacts): String = buildJsonObject {
    fun label(text: String) = text.filterNot { it.isISOControl() }.take(160)
    put("窗口", label(f.window)); put("助手", label(f.assistant))
    put("模型", label(f.model))
    put("换模型", f.modelChanged?.let(::JsonPrimitive) ?: JsonPrimitive("尚无上轮记录"))
    if (f.regeneration > 0) put("本条重刷", f.regeneration)
    if (f.deleted > 0) put("已删消息", f.deleted)
    put("本窗自压缩", f.compactions)
    put("使用最久窗口", f.longestWindow?.let(::label) ?: "尚无记录")
    put("同助手窗口数", f.windowCount); put("助手总数", f.assistantCount)
    put("本助手最近活跃", f.lastActive ?: "尚无记录")
    put("TTS", label(f.tts))
    put("工具模型", buildJsonObject { f.toolModels.forEach { (key, value) -> put(key, label(value)) } })
}.toString()

internal fun observationToolModels(settings: Settings): Map<String, String> {
    fun name(id: Uuid?): String = id?.let(settings::findModelById)?.modelId ?: "未配置"
    return linkedMapOf(
        "快速" to name(settings.fastModelId), "翻译" to name(settings.translateModeId),
        "图像" to name(settings.imageGenerationModelId), "OCR" to name(settings.ocrModelId),
        "压缩" to name(settings.compressModelId), "通话归档" to name(settings.orbisVoiceArchiveModelId),
        "归档备用" to name(settings.orbisVoiceArchiveFallbackModelId),
    )
}

internal class ObservationInterval(private var since: Long) {
    fun take(now: Long): Long {
        val elapsed = (now - since).coerceAtLeast(0)
        since = maxOf(since, now)
        return elapsed
    }
}

internal fun observationTts(settings: Settings): String = when (val p = settings.getSelectedTTSProvider()) {
    null -> "未配置"
    is TTSProviderSetting.SystemTTS -> "系统TTS"
    is TTSProviderSetting.XAI -> "xAI TTS"
    is TTSProviderSetting.Volcengine -> p.resourceId
    else -> {
        // Serialize only in memory and allowlist model, never key/url/header/voice-reference.
        Json.encodeToJsonElement(TTSProviderSetting.serializer(), p).jsonObject["model"]
            ?.jsonPrimitive?.contentOrNull ?: p.name
    }
}

private const val OBSERVATION_PREFIX = "[Orbis全局观测]"
private const val OBSERVATION_PROJECTION = "orbis_observation_projection"

/** Exact host ownership only: literal user/model text and unrelated metadata are not rewritten. */
internal fun UIMessagePart.isObservationProjection(): Boolean = this is UIMessagePart.Text &&
    text.startsWith(OBSERVATION_PREFIX) &&
    (metadata?.get(OBSERVATION_PROJECTION) as? JsonPrimitive)?.contentOrNull == "report_v1" &&
    (metadata?.get("request_only") as? JsonPrimitive)?.booleanOrNull == true &&
    (metadata?.get("human_authored") as? JsonPrimitive)?.booleanOrNull == false

/** Request-copy cleanup for a compact/rebase, never a persisted conversation edit. */
internal fun removeObservationProjection(messages: List<UIMessage>): List<UIMessage> {
    fun removeParts(parts: List<UIMessagePart>): List<UIMessagePart> {
        var changed = false
        val result = parts.mapNotNull { part ->
            if (part.isObservationProjection()) {
                changed = true
                null
            } else if (part is UIMessagePart.Tool) {
                val output = removeParts(part.output)
                if (output === part.output) part else {
                    changed = true
                    part.copy(output = output)
                }
            } else part
        }
        return if (changed) result else parts
    }
    var changed = false
    val result = messages.map { message ->
        val parts = removeParts(message.parts)
        if (parts === message.parts) message else {
            changed = true
            message.copy(parts = parts)
        }
    }
    return if (changed) result else messages
}

/** One frozen wake's report at its existing input tail, never in the reusable SYSTEM prefix. */
internal fun projectObservation(messages: List<UIMessage>, report: String?): List<UIMessage> {
    val clean = removeObservationProjection(messages)
    if (report.isNullOrBlank()) return clean
    val part = UIMessagePart.Text(OBSERVATION_PREFIX + report, buildJsonObject {
        put(OBSERVATION_PROJECTION, "report_v1")
        put("source", "orbis_observation")
        put("request_only", true)
        put("human_authored", false)
    })
    // Use the latest existing user-data carrier, including an event when it is the current wake.
    // Do not reach back past it to edit an older human turn or manufacture another human message.
    val userIndex = clean.indexOfLast { it.role == MessageRole.USER }
    if (userIndex >= 0) return clean.mapIndexed { index, message ->
        if (index == userIndex) message.copy(parts = message.parts + part) else message
    }
    // A compact tool may have removed every human turn. Its completed result can carry the
    // same frozen host data without splitting the assistant/tool-result protocol unit.
    for (index in clean.indices.reversed()) {
        val message = clean[index]
        if (message.role != MessageRole.ASSISTANT) continue
        val toolIndex = message.parts.indexOfLast { it is UIMessagePart.Tool && it.isExecuted }
        if (toolIndex < 0) continue
        return clean.mapIndexed { current, original ->
            if (current != index) original else original.copy(parts = original.parts.mapIndexed { i, originalPart ->
                if (i != toolIndex) originalPart else {
                    val tool = originalPart as UIMessagePart.Tool
                    tool.copy(output = tool.output + part)
                }
            })
        }
    }
    // No compatible input/result exists: do not invent a USER wake or a second SYSTEM block.
    return clean
}
