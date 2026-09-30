package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSaveReadinessTest {
    @Test fun unreadExistingHistoryCannotBeSavedFromAPlaceholder() {
        val failure = runCatching { requireWholeConversationSaveReady(exists = true, initialized = false) }
            .exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("对话尚未完整加载，本次未保存。请返回后重试。", failure?.message)
        assertNull(failure?.cause)
    }

    @Test fun initializedExistingHistoryCanBeSaved() {
        requireWholeConversationSaveReady(exists = true, initialized = true)
    }

    @Test fun genuinelyNewConversationCanBeInsertedBeforeInitialization() {
        requireWholeConversationSaveReady(exists = false, initialized = false)
    }

    @Test fun initializedNewConversationCanBeInserted() {
        requireWholeConversationSaveReady(exists = false, initialized = true)
    }
}
