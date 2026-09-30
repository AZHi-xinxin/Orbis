package me.rerere.rikkahub.data.orbis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GardenQuickChatPolicyTest {
    private val ready = GardenQuickChatState(
        currentAssistantId = "assistant-a",
        targetAssistantId = "assistant-a",
        conversationAssistantId = "assistant-a",
        assistantExists = true,
        conversationExists = true,
        initialized = true,
    )

    @Test fun existingInitializedConversationForCapturedCurrentAssistantCanSend() {
        assertTrue(gardenQuickChatTargetAllowed(ready))
        assertNull(gardenQuickChatSendBlockReason(ready, "A message explicitly typed by the user"))
        assertTrue(canSendGardenQuickChat(ready, "A message explicitly typed by the user"))
    }

    @Test fun everyOwnerMustMatchWithoutFallingBackToTheCurrentAssistant() {
        listOf(
            ready.copy(currentAssistantId = "assistant-b") to "garden_quick_chat_assistant_changed",
            ready.copy(targetAssistantId = "assistant-b") to "garden_quick_chat_assistant_changed",
            ready.copy(conversationAssistantId = "assistant-b") to "garden_quick_chat_owner_changed",
            ready.copy(conversationAssistantId = null) to "garden_quick_chat_owner_changed",
            ready.copy(conversationAssistantId = "") to "garden_quick_chat_owner_changed",
        ).forEach { (state, reason) ->
            assertFalse(gardenQuickChatTargetAllowed(state))
            assertEquals(reason, gardenQuickChatSendBlockReason(state, "hello"))
            assertFalse(canSendGardenQuickChat(state, "hello"))
        }
    }

    @Test fun blankCapturedOrCurrentAssistantCannotBecomeAValidOwner() {
        listOf("", " ", "\t\n").forEach { emptyId ->
            val state = ready.copy(
                currentAssistantId = emptyId,
                targetAssistantId = emptyId,
                conversationAssistantId = emptyId,
            )
            assertFalse(gardenQuickChatTargetAllowed(state))
            assertEquals("garden_quick_chat_invalid_assistant", gardenQuickChatSendBlockReason(state, "hello"))
        }
    }

    @Test fun ownerComparisonIsExactAndNeverNormalizesDistinctIds() {
        listOf("ASSISTANT-A", " assistant-a", "assistant-a ").forEach { owner ->
            assertEquals("garden_quick_chat_owner_changed",
                gardenQuickChatTargetBlockReason(ready.copy(conversationAssistantId = owner)))
        }
    }

    @Test fun deletedAssistantAndMissingConversationFailClosedDespiteMatchingIds() {
        val missingAssistant = ready.copy(assistantExists = false)
        val missingConversation = ready.copy(conversationExists = false)
        assertEquals("garden_quick_chat_assistant_missing", gardenQuickChatTargetBlockReason(missingAssistant))
        assertEquals("garden_quick_chat_conversation_missing", gardenQuickChatTargetBlockReason(missingConversation))
        assertFalse(canSendGardenQuickChat(missingAssistant, "hello"))
        assertFalse(canSendGardenQuickChat(missingConversation, "hello"))
    }

    @Test fun unloadedOrFailedInitializationCannotSend() {
        val state = ready.copy(initialized = false)
        assertFalse(gardenQuickChatTargetAllowed(state))
        assertEquals("garden_quick_chat_not_initialized", gardenQuickChatSendBlockReason(state, "hello"))
    }

    @Test fun busyConversationCanBeViewedButEveryBusyStateIndividuallyRejectsSending() {
        listOf(
            ready.copy(generating = true) to "garden_quick_chat_generating",
            ready.copy(submitting = true) to "garden_quick_chat_submitting",
            ready.copy(queued = true) to "garden_quick_chat_queued",
            ready.copy(pendingTool = true) to "garden_quick_chat_pending_tool",
            ready.copy(voiceActive = true) to "garden_quick_chat_voice_active",
        ).forEach { (state, reason) ->
            assertTrue(gardenQuickChatTargetAllowed(state))
            assertEquals(reason, gardenQuickChatSendBlockReason(state, "hello"))
            assertFalse(canSendGardenQuickChat(state, "hello"))
        }
    }

    @Test fun ownerFailureTakesPrecedenceOverBusyAndBlankDraft() {
        val changed = ready.copy(conversationAssistantId = "assistant-b", generating = true, pendingTool = true)
        assertEquals("garden_quick_chat_owner_changed", gardenQuickChatSendBlockReason(changed, ""))
    }

    @Test fun whitespaceOnlyDraftsAreRejectedIncludingUnicodeWhitespace() {
        listOf("", " ", "\t\r\n", "\u3000", "\u00a0").forEach { text ->
            assertEquals("garden_quick_chat_empty_text", gardenQuickChatSendBlockReason(ready, text))
            assertFalse(canSendGardenQuickChat(ready, text))
        }
    }

    @Test fun asciiByteLimitIsInclusiveAndDoesNotSilentlyTruncate() {
        val exact = "x".repeat(GARDEN_QUICK_CHAT_MAX_TEXT_BYTES)
        assertTrue(canSendGardenQuickChat(ready, exact))
        assertEquals("garden_quick_chat_text_too_large", gardenQuickChatSendBlockReason(ready, exact + "x"))
        assertEquals(GARDEN_QUICK_CHAT_MAX_TEXT_BYTES, exact.length)
    }

    @Test fun chineseAndEmojiUseUtf8ByteCountRatherThanCharacterCount() {
        val chinese = "中".repeat(GARDEN_QUICK_CHAT_MAX_TEXT_BYTES / 3) + "xx"
        assertEquals(GARDEN_QUICK_CHAT_MAX_TEXT_BYTES, chinese.toByteArray(Charsets.UTF_8).size)
        assertTrue(canSendGardenQuickChat(ready, chinese))
        assertEquals("garden_quick_chat_text_too_large", gardenQuickChatSendBlockReason(ready, chinese + "中"))
        val emoji = "\uD83C\uDF3F".repeat(GARDEN_QUICK_CHAT_MAX_TEXT_BYTES / 4)
        assertTrue(canSendGardenQuickChat(ready, emoji))
        assertEquals("garden_quick_chat_text_too_large", gardenQuickChatSendBlockReason(ready, emoji + "\uD83C\uDF3F"))
    }

    @Test fun surroundingWhitespaceCountsTowardByteLimitWithoutMutatingDraft() {
        val draft = "\n" + "x".repeat(GARDEN_QUICK_CHAT_MAX_TEXT_BYTES - 2) + " "
        val original = draft
        assertTrue(canSendGardenQuickChat(ready, draft))
        assertFalse(canSendGardenQuickChat(ready, draft + " "))
        assertEquals(original, draft)
    }

    @Test fun persistedReceiptCanClearItsOwnUnchangedDraft() {
        assertTrue(canClearGardenQuickChatDraft("chat-a", "chat-a", 3, 3, "hello", "hello"))
    }

    @Test fun persistedReceiptCannotClearAnotherWindowsDraft() {
        assertFalse(canClearGardenQuickChatDraft("chat-b", "chat-a", 3, 3, "hello", "hello"))
    }

    @Test fun changedRevisionProtectsRecreatedSameTextAndSwitchedBackWindow() {
        assertFalse(canClearGardenQuickChatDraft("chat-a", "chat-a", 5, 3, "hello", "hello"))
        assertFalse(canClearGardenQuickChatDraft("chat-a", "chat-a", 4, 3, "changed", "hello"))
    }

    @Test fun differentDraftTextIsPreservedEvenWhenRevisionMatches() {
        assertFalse(canClearGardenQuickChatDraft("chat-a", "chat-a", 3, 3, "hello again", "hello"))
    }

    @Test fun absentSelectionCannotConsumePersistedReceipt() {
        assertFalse(canClearGardenQuickChatDraft(null, "chat-a", 3, 3, "hello", "hello"))
        assertFalse(canClearGardenQuickChatDraft("", "", 3, 3, "hello", "hello"))
    }

    @Test fun emptyOrWhitespaceSendCannotClearADraft() {
        listOf("", " ", "\t\n", "\u3000").forEach { text ->
            assertFalse(canClearGardenQuickChatDraft("chat-a", "chat-a", 3, 3, text, text))
        }
    }
}
