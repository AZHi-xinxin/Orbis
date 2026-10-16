package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.chatgpt.ChatGptPlanCapabilities

/** Presentation only: opening a picker must not overwrite a saved assistant or draft. */
internal data class ReasoningSelectionPolicy(
    val levels: List<ReasoningLevel>,
    val effectiveLevel: ReasoningLevel,
    val notice: String? = null,
)

internal fun reasoningSelectionPolicy(
    requested: ReasoningLevel,
    model: Model?,
    provider: ProviderSetting?,
): ReasoningSelectionPolicy {
    val currentModel = model ?: return ReasoningSelectionPolicy(ReasoningLevel.entries.toList(), requested)
    val levels = ChatGptPlanCapabilities.reasoningLevels(currentModel, provider)
        ?: return ReasoningSelectionPolicy(ReasoningLevel.entries.toList(), requested)
    val effective = ChatGptPlanCapabilities.reasoningLevelForRequest(currentModel.modelId, requested)
    val notice = when {
        effective != requested -> "此前设为关闭；当前模型不支持关闭推理，本次按最低的低档执行。自动由模型决定。"
        ReasoningLevel.OFF !in levels -> "当前模型不支持关闭推理，最低为低档。自动由模型决定。"
        else -> "自动由模型决定；其他档位使用你选择的推理强度。"
    }
    return ReasoningSelectionPolicy(levels, effective, notice)
}
