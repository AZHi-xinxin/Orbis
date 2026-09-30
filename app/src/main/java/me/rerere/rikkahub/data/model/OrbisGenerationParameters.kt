package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import kotlin.uuid.Uuid

/** Assistant-scoped fields only. This is an editor snapshot, not a conversation override. */
@Serializable
data class OrbisGenerationParameters(
    val temperature: Float? = null,
    val topP: Float? = null,
    val contextMessageLimit: Int = 0,
    val streamOutput: Boolean = true,
    val maxTokens: Int? = null,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
) {
    fun validated(): OrbisGenerationParameters = apply {
        require(temperature == null || (temperature.isFinite() && temperature in 0f..2f)) { "温度应为 0–2。" }
        require(topP == null || (topP.isFinite() && topP in 0f..1f)) { "top-p 应为 0–1。" }
        require(contextMessageLimit == 0 || contextMessageLimit >= 20) { "上下文消息数量应为 0（不限制）或至少 20。" }
        require(maxTokens == null || maxTokens > 0) { "最大输出 token 应为正整数，或留空使用默认值。" }
    }

    companion object {
        fun from(assistant: Assistant) = OrbisGenerationParameters(
            assistant.temperature, assistant.topP, assistant.contextMessageLimit,
            assistant.streamOutput, assistant.maxTokens, assistant.reasoningLevel,
        )
    }
}

/** Text remains a draft until explicitly validated; partial input never changes SettingsStore. */
@Serializable
data class OrbisGenerationParameterDraft(
    val temperatureEnabled: Boolean,
    val temperature: String,
    val topPEnabled: Boolean,
    val topP: String,
    val contextMessageLimit: String,
    val streamOutput: Boolean,
    val maxTokens: String,
    val reasoningLevel: ReasoningLevel,
) {
    fun parameters(): OrbisGenerationParameters = OrbisGenerationParameters(
        temperature = if (temperatureEnabled) requireNotNull(temperature.trim().toFloatOrNull()) { "请填写有效的温度。" } else null,
        topP = if (topPEnabled) requireNotNull(topP.trim().toFloatOrNull()) { "请填写有效的 top-p。" } else null,
        contextMessageLimit = requireNotNull(contextMessageLimit.trim().toIntOrNull()) { "请填写有效的上下文消息数量。" },
        streamOutput = streamOutput,
        maxTokens = if (maxTokens.isBlank()) null else requireNotNull(maxTokens.trim().toIntOrNull()) { "请填写有效的最大输出 token。" },
        reasoningLevel = reasoningLevel,
    ).validated()

    companion object {
        fun from(value: OrbisGenerationParameters) = OrbisGenerationParameterDraft(
            value.temperature != null, (value.temperature ?: 1f).toString(),
            value.topP != null, (value.topP ?: 1f).toString(),
            value.contextMessageLimit.toString(), value.streamOutput,
            value.maxTokens?.toString().orEmpty(), value.reasoningLevel,
        )
    }
}

class OrbisGenerationParameterConflict(val fieldName: String) : IllegalStateException(
    "「$fieldName」已在别处修改。请关闭并重新打开对话设置，确认最新值后再保存。"
)

data class OrbisGenerationParameterEdit(
    val assistantId: Uuid,
    val before: OrbisGenerationParameters,
    val after: OrbisGenerationParameters,
) {
    /** Preserve all untouched fields, including changes made while the sheet was open. */
    fun mergeInto(latest: Assistant): Assistant {
        require(latest.id == assistantId) { "当前 AI 已变化，请重新打开对话设置。" }
        after.validated()
        fun <T> merge(name: String, old: T, desired: T, current: T): T {
            if (old == desired) return current
            // Retrying a persistence failure is safe when RAM already contains the desired value.
            if (current != old && current != desired) throw OrbisGenerationParameterConflict(name)
            return desired
        }
        return latest.copy(
            temperature = merge("温度", before.temperature, after.temperature, latest.temperature),
            topP = merge("top-p", before.topP, after.topP, latest.topP),
            contextMessageLimit = merge("上下文消息数量", before.contextMessageLimit, after.contextMessageLimit, latest.contextMessageLimit),
            streamOutput = merge("流式输出", before.streamOutput, after.streamOutput, latest.streamOutput),
            maxTokens = merge("最大输出 token", before.maxTokens, after.maxTokens, latest.maxTokens),
            reasoningLevel = merge("推理预算", before.reasoningLevel, after.reasoningLevel, latest.reasoningLevel),
        )
    }
}
