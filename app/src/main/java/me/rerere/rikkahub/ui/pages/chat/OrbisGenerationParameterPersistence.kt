package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.OrbisGenerationParameterEdit
import me.rerere.rikkahub.data.model.OrbisGenerationParameters

/** Only an explicitly started local write is protected, never generation/network work. */
internal suspend fun persistOrbisGenerationParameters(
    edit: OrbisGenerationParameterEdit,
    update: suspend ((Settings) -> Settings) -> Unit,
): OrbisGenerationParameters {
    currentCoroutineContext().ensureActive()
    return withContext(NonCancellable) {
        var saved: OrbisGenerationParameters? = null
        update { latest ->
            check(!latest.init) { "设置尚未载入，请稍后重试。" }
            val current = checkNotNull(latest.getAssistantById(edit.assistantId)) { "当前 AI 已不存在。" }
            val merged = edit.mergeInto(current)
            saved = OrbisGenerationParameters.from(merged)
            latest.copy(assistants = latest.assistants.map { if (it.id == merged.id) merged else it })
        }
        checkNotNull(saved)
    }
}
