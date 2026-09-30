package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.data.model.OrbisCompactionEvent
import me.rerere.rikkahub.data.model.formatOrbisTokenCount

/** Metadata only: this screen must not load archived transcripts or generate a summary. */
internal data class OrbisCompactionUiState(
    val history: List<OrbisCompactionEvent> = emptyList(),
    val latestRollback: OrbisCompactionEvent? = null,
    val projectedRollbackTokens: Long? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Explanatory UI only. The storage transaction always checks the live value again. */
internal fun orbisRollbackLimitMessage(projectedTokens: Long?, thresholdTokens: Int): String? {
    if (thresholdTokens <= 0 || projectedTokens == null) return null
    val maximum = thresholdTokens.toLong() + 50_000L
    return if (projectedTokens > maximum) {
        "恢复后预计 ${formatOrbisTokenCount(projectedTokens)}（$projectedTokens tokens），超过当前允许回滚上限 " +
            "${formatOrbisTokenCount(maximum)}（$maximum tokens，提醒阈值 + 50K）。如仍要恢复，请先在上方调高阈值。"
    } else null
}

internal fun parseOrbisCompactionThreshold(text: String): Int? =
    text.trim().toIntOrNull()?.takeIf { it in 0..1_000_000 }

internal fun orbisCompactionBasisLabel(basis: String): String = when {
    basis.startsWith("previous_provider_") -> "最近供应商用量 + 回复与新增内容估算；不是下一次请求的精确用量"
    basis.startsWith("estimated_text_") -> "正文与工具载荷的本地估算；附件与供应商计数方式可能不同"
    basis.startsWith("estimate;") -> "含请求与工具定义的本地估算；不是供应商精确用量"
    basis.isBlank() -> "用量来源未记录"
    else -> basis
}
