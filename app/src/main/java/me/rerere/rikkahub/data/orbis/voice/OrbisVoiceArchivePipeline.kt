package me.rerere.rikkahub.data.orbis.voice

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

internal val VOICE_ARCHIVE_FAILURE_CODES = setOf("model_not_configured", "request_failed", "invalid_archive_response",
    "archive_timeout", "archive_source_changed", "archive_interrupted", "archive_preparation_failed",
    "process_interrupted_no_auto_retry", "archive_persistence_failed", "archive_source_unreadable")

internal class VoiceArchiveFailure(val safeCode: String) : IllegalStateException(safeCode) {
    init { require(safeCode in VOICE_ARCHIVE_FAILURE_CODES) }
}

/** A fallback alone does not opt the user into sending private transcripts anywhere. */
internal fun voiceArchiveModelPlan(settings: Settings): List<Uuid> =
    settings.orbisVoiceArchiveModelId?.let { listOfNotNull(it, settings.orbisVoiceArchiveFallbackModelId).distinct() }.orEmpty()

internal data class VoiceArchiveResult(val modelId: Uuid, val archive: OrbisVoiceModelArchive)

/** Bounded model-only attempts. A cancelled job or an unacknowledged local write NEVER calls fallback. */
internal suspend fun runIndependentVoiceArchive(
    record: OrbisVoiceCallRecord, conversation: Conversation, settings: Settings,
    beforeRequest: suspend (Uuid) -> Unit,
    request: suspend (OrbisVoiceArchiveRequest) -> TextGenerationResult,
): VoiceArchiveResult {
    val plan = voiceArchiveModelPlan(settings)
    if (plan.isEmpty()) throw VoiceArchiveFailure("model_not_configured")
    // Source integrity is not a model/provider failure. Do not try primary OR fallback on a UI-only view.
    strictVoiceCallReadableTranscript(record)
    var failure = VoiceArchiveFailure("request_failed")
    for (model in plan) {
        val prepared = try { prepareIsolatedVoiceArchive(record, conversation, settings, model) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = VoiceArchiveFailure("archive_preparation_failed"); continue }
        beforeRequest(model) // durable intent is outside the model-error fallback boundary
        try {
            val result = request(prepared)
            if (result.message.role != MessageRole.ASSISTANT ||
                result.message.parts.any { it !is UIMessagePart.Text && it !is UIMessagePart.Reasoning } ||
                result.finishReason?.lowercase() in setOf("length", "max_tokens", "tool_calls", "function_call", "pause_turn"))
                throw VoiceArchiveFailure("invalid_archive_response")
            val archive = OrbisVoiceCallProtocol.parseModelArchive(result.message.toText())
                ?: throw VoiceArchiveFailure("invalid_archive_response")
            return VoiceArchiveResult(model, archive)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error as? VoiceArchiveFailure ?: VoiceArchiveFailure("request_failed") }
    }
    throw failure
}

internal fun voiceArchiveFailureMessage(code: String): String = when (code) {
    "archive_source_unreadable" -> "部分原始消息片段无法读取，未向归档模型发送不完整记录。原文件与仍可读取的记录保留，请先核对。"
    "model_not_configured" -> "原始通话已保存。未配置独立归档模型，没有发送模型请求；可在设置中选择后手动整理。"
    "archive_source_changed" -> "整理期间原文继续更新，未用旧摘要覆盖新记录；完整原文保留，可稍后手动整理。"
    "archive_interrupted", "process_interrupted_no_auto_retry" -> "整理中断，原始通话保留；不会自动重试，可以手动整理。"
    else -> "本次独立整理未完成，原始通话保留；未恢复旧队列或执行任何工具，可以手动重试。"
}
