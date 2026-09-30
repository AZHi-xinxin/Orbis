package me.rerere.rikkahub.service

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.OrbisToolRecordEdit
import me.rerere.rikkahub.data.model.applyToolRecordEdit
import me.rerere.rikkahub.data.model.withCommittedToolRecordEdits

/** Caller owns the session mutex and generation-start barrier. Never publish an uncommitted edit. */
internal suspend fun persistOrbisToolRecordEdit(
    state: MutableStateFlow<Conversation>,
    edit: OrbisToolRecordEdit,
    now: LocalDateTime,
    persist: suspend (Conversation) -> UIMessage,
) {
    val before = state.value
    val desired = before.applyToolRecordEdit(edit, now)
    withContext(NonCancellable) {
        val committedMessage = persist(before)
        val expected = desired.currentMessages.single { it.id == edit.messageId }
        check(committedMessage.parts == expected.parts && committedMessage.deletedToolRecords == expected.deletedToolRecords &&
            committedMessage.toolRecordRevision == expected.toolRecordRevision) { "工具记录提交结果不一致，请重新打开聊天。" }
        state.update { current -> withCommittedToolRecordEdits(current, desired) }
    }
}
