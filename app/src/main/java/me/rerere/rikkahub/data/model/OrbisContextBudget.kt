package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

internal enum class OrbisBudgetReason {
    READY_REFERENCE, GENERATING, NO_ASSISTANT, MODEL_UNKNOWN, MODEL_CHANGED,
    INCOMPLETE, TOOL_CONTINUATION, USAGE_MISSING, USAGE_INVALID, HISTORY_EDITED, LIMIT_UNKNOWN,
    REMINDERS_DISABLED, CURRENT_ESTIMATE, ESTIMATE_PENDING,
}

/** Provenance-labelled current estimate or persisted usage, never a claim of exact capacity. */
internal data class OrbisContextBudget(
    val reason: OrbisBudgetReason,
    val usage: TokenUsage?,
    val completedAt: String?,
    val modelMatches: Boolean,
    val referenceLimit: Int?,
    val inputRatio: Double?,
    val reminderThresholdTokens: Int = 0,
    val currentUsageTokens: Long? = null,
    val currentUsageSource: String? = null,
) {
    // Percentage is deliberately not clamped: an over-limit reference must not look safe.
    val percentLabel: String
        get() = inputRatio?.let { "${(it * 100).toLong()}%" } ?: "—"

    val thresholdLabel: String
        get() = if (reminderThresholdTokens == 0) "提醒关" else formatOrbisTokenCount(reminderThresholdTokens.toLong())

    val reminderStartsAtTokens: Long?
        get() = reminderThresholdTokens.takeIf { it > 0 }?.let { (it.toLong() * 9 + 9) / 10 }
}

internal fun formatOrbisTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000L == 0L -> "${tokens / 1_000_000}M"
    tokens >= 1_000 && tokens % 1_000L == 0L -> "${tokens / 1_000}K"
    tokens >= 1_000 -> java.math.BigDecimal.valueOf(tokens).movePointLeft(3)
        .setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "K"
    else -> tokens.toString()
}

/** Caller passes only selected branch messages and an explicitly identified reference limit. */
@Suppress("DEPRECATION")
internal fun deriveOrbisContextBudget(
    currentMessages: List<UIMessage>,
    currentModelId: Uuid?,
    referenceLimit: Int?,
    isGenerating: Boolean,
    reminderThresholdTokens: Int = 0,
    currentUsageTokens: Long? = null,
    currentUsageSource: String? = null,
    currentEstimatePending: Boolean = false,
): OrbisContextBudget {
    // Do not silently fall back to an older successful response if the latest one lacks usage.
    val last = currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT && !it.isSynthetic }
    val usage = last?.usage
    val modelMatches = currentModelId != null && last?.modelId == currentModelId
    val limit = referenceLimit?.takeIf { it > 0 }
    val toolAmbiguity = last?.parts?.any {
        it is UIMessagePart.Tool || it is UIMessagePart.ServerTool ||
            it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult
    } == true
    val referenceReason = when {
        isGenerating -> OrbisBudgetReason.GENERATING
        last == null -> OrbisBudgetReason.NO_ASSISTANT
        currentModelId == null || last.modelId == null -> OrbisBudgetReason.MODEL_UNKNOWN
        !modelMatches -> OrbisBudgetReason.MODEL_CHANGED
        last.finishedAt == null -> OrbisBudgetReason.INCOMPLETE
        toolAmbiguity -> OrbisBudgetReason.TOOL_CONTINUATION
        last.usageContextInvalidated -> OrbisBudgetReason.HISTORY_EDITED
        usage == null || usage.promptTokens == 0 -> OrbisBudgetReason.USAGE_MISSING
        usage.promptTokens < 0 || usage.completionTokens < 0 || usage.cachedTokens < 0 ||
            usage.cachedTokens > usage.promptTokens -> OrbisBudgetReason.USAGE_INVALID
        limit == null -> OrbisBudgetReason.LIMIT_UNKNOWN
        else -> OrbisBudgetReason.READY_REFERENCE
    }
    val threshold = reminderThresholdTokens.coerceIn(0, 1_000_000)
    // A caller may supply a fresh estimate after context projection/compaction. It must never be
    // labelled as provider usage; keeping its provenance is as important as its numerical value.
    val estimate = currentUsageTokens?.takeIf { it >= 0 }
    val usableReference = referenceReason == OrbisBudgetReason.READY_REFERENCE ||
        referenceReason == OrbisBudgetReason.LIMIT_UNKNOWN
    val tokens = if (currentEstimatePending) null else estimate ?: usage?.promptTokens?.toLong()?.takeIf { usableReference }
    val reason = when {
        isGenerating -> OrbisBudgetReason.GENERATING
        currentEstimatePending -> OrbisBudgetReason.ESTIMATE_PENDING
        threshold == 0 -> OrbisBudgetReason.REMINDERS_DISABLED
        estimate != null -> OrbisBudgetReason.CURRENT_ESTIMATE
        usableReference -> OrbisBudgetReason.READY_REFERENCE
        else -> referenceReason
    }
    return OrbisContextBudget(
        reason = reason, usage = usage, completedAt = last?.finishedAt?.toString(),
        modelMatches = modelMatches, referenceLimit = limit,
        inputRatio = tokens?.takeIf { threshold > 0 && !isGenerating }?.toDouble()?.div(threshold),
        reminderThresholdTokens = threshold,
        currentUsageTokens = tokens,
        currentUsageSource = if (estimate != null) currentUsageSource?.takeIf { it.isNotBlank() }
            ?: "当前上下文估算（不是供应商精确用量）"
            else if (tokens != null) "最近完成回复的供应商输入用量；新增内容可能尚未计入" else null,
    )
}
