package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.SearchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationComposerScopeTest {
    private val globalModel = Model(modelId = "global-model", tools = setOf(BuiltInTools.Search))
    private val ownerModel = Model(modelId = "owner-model", inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        tools = setOf(BuiltInTools.UrlContext))
    private val nextModel = Model(modelId = "next-model")
    private val global = Assistant(name = "Global selection", chatModelId = globalModel.id,
        reasoningLevel = ReasoningLevel.HIGH, enableWebSearch = true)
    private val owner = Assistant(name = "Conversation owner", chatModelId = ownerModel.id,
        reasoningLevel = ReasoningLevel.LOW, mcpServers = setOf(Uuid.random()))
    private fun settings() = Settings(assistantId = global.id, chatModelId = globalModel.id,
        assistants = listOf(global, owner), providers = listOf(ProviderSetting.OpenAI(
            models = listOf(globalModel, ownerModel, nextModel))))

    @Test fun modelReasoningSearchAndImageCapabilitiesComeFromConversationOwner() {
        val scope = requireNotNull(conversationComposerScope(settings(), owner.id))
        assertSame(owner, scope.assistant)
        assertSame(ownerModel, scope.model)
        assertEquals(ReasoningLevel.LOW, scope.assistant.reasoningLevel)
        assertFalse(scope.assistant.enableWebSearch)
        val model = requireNotNull(scope.model)
        assertFalse(BuiltInTools.Search in model.tools)
        assertTrue(Modality.IMAGE in model.inputModalities)
    }

    @Test fun changingGlobalSelectionDoesNotChangeAnOpenConversationControls() {
        val before = settings()
        val after = before.copy(assistantId = Uuid.random(), assistants = listOf(owner, global))
        assertEquals(conversationComposerScope(before, owner.id), conversationComposerScope(after, owner.id))
    }

    @Test fun ownerWithoutOverrideUsesGlobalDefaultModelNotSelectedAssistantOverride() {
        val noOverride = owner.copy(chatModelId = null)
        val current = settings().copy(chatModelId = nextModel.id, assistants = listOf(global, noOverride))
        assertSame(nextModel, conversationComposerScope(current, owner.id)?.model)
        assertSame(noOverride, conversationComposerScope(current, owner.id)?.assistant)
    }

    @Test fun missingOverrideModelDoesNotSilentlyUseAnotherModel() {
        val current = settings().copy(assistants = listOf(global, owner.copy(chatModelId = Uuid.random())))
        assertNull(conversationComposerScope(current, owner.id)?.model)
        assertEquals(owner.id, conversationComposerScope(current, owner.id)?.assistant?.id)
    }

    @Test fun missingLoadingAndAmbiguousOwnersNeverFallBackToGlobalSelection() {
        val current = settings()
        assertNull(conversationComposerScope(current, Uuid.random()))
        assertNull(conversationComposerScope(current.copy(assistants = listOf(global)), owner.id))
        assertNull(conversationComposerScope(current.copy(assistants = listOf(global, owner, owner)), owner.id))
        assertNull(conversationComposerScope(current.copy(init = true), owner.id))
    }

    @Test fun choosingModelChangesOnlyOwnerModelFieldAndNotGlobalPreference() {
        val current = settings()
        val updated = conversationComposerWithModel(current, owner.id, nextModel.id)
        assertEquals(current.copy(assistants = listOf(global, owner.copy(chatModelId = nextModel.id))), updated)
        assertSame(global, updated.assistants.first())
        assertEquals(global.id, updated.assistantId)
        assertEquals(globalModel.id, updated.chatModelId)
        assertSame(nextModel, conversationComposerScope(updated, owner.id)?.model)
    }

    @Test fun modelSelectionIgnoresDeletedOrNonChatModels() {
        val imageModel = Model(type = ModelType.IMAGE)
        val current = settings().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(ownerModel, imageModel))))
        assertSame(current, conversationComposerWithModel(current, owner.id, Uuid.random()))
        assertSame(current, conversationComposerWithModel(current, owner.id, imageModel.id))
    }

    @Test fun reasoningEditPreservesLatestOwnerMcpNameAndOtherSettings() {
        val newerOwner = owner.copy(name = "New name", mcpServers = setOf(Uuid.random()), temperature = 0.42f)
        val current = settings().copy(assistants = listOf(global, newerOwner))
        val updated = conversationComposerWithReasoning(current, owner.id, ownerModel.id, ReasoningLevel.MAX)
        assertEquals(current.copy(assistants = listOf(global, newerOwner.copy(reasoningLevel = ReasoningLevel.MAX))), updated)
        assertSame(global, updated.assistants.first())
    }

    @Test fun searchModeEditsOwnerAndItsModelWhilePreservingOtherToolsAndGlobalAssistant() {
        SearchMode.entries.forEach { mode ->
            val current = settings()
            val updated = conversationComposerWithSearch(current, owner.id, ownerModel.id, mode)
            assertSame(global, updated.assistants.first())
            assertEquals(global.id, updated.assistantId)
            assertEquals(globalModel, updated.findModelById(globalModel.id))
            val changedOwner = updated.assistants.last()
            assertEquals(owner.copy(enableWebSearch = mode == SearchMode.LOCAL), changedOwner)
            val changedModel = requireNotNull(updated.findModelById(ownerModel.id))
            assertEquals(ownerModel.copy(tools = if (mode == SearchMode.BUILT_IN)
                ownerModel.tools + BuiltInTools.Search else ownerModel.tools - BuiltInTools.Search), changedModel)
        }
    }

    @Test fun switchingAwayFromBuiltinSearchRemovesOnlySearchFromOwnerModel() {
        val current = conversationComposerWithSearch(settings(), owner.id, ownerModel.id, SearchMode.BUILT_IN)
        for (mode in listOf(SearchMode.LOCAL, SearchMode.OFF)) {
            val updated = conversationComposerWithSearch(current, owner.id, ownerModel.id, mode)
            assertEquals(setOf(BuiltInTools.UrlContext), updated.findModelById(ownerModel.id)?.tools)
            assertEquals(globalModel, updated.findModelById(globalModel.id))
        }
    }

    @Test fun delayedReasoningAndSearchCallbacksDoNotEditAChangedModelSelection() {
        val current = conversationComposerWithModel(settings(), owner.id, nextModel.id)
        assertSame(current, conversationComposerWithReasoning(current, owner.id, ownerModel.id, ReasoningLevel.HIGH))
        assertSame(current, conversationComposerWithSearch(current, owner.id, ownerModel.id, SearchMode.BUILT_IN))
    }

    @Test fun deletedLoadingOrAmbiguousOwnerMakesAllCallbacksNoOps() {
        val original = settings()
        val unavailable = listOf(
            original.copy(assistants = listOf(global)),
            original.copy(init = true),
            original.copy(assistants = listOf(global, owner, owner)),
        )
        unavailable.forEach { current ->
            assertSame(current, conversationComposerWithModel(current, owner.id, nextModel.id))
            assertSame(current, conversationComposerWithReasoning(current, owner.id, ownerModel.id, ReasoningLevel.HIGH))
            assertSame(current, conversationComposerWithSearch(current, owner.id, ownerModel.id, SearchMode.LOCAL))
        }
    }

    @Test fun missingModelCannotEnableBuiltinSearch() {
        val current = settings().copy(providers = emptyList())
        assertSame(current, conversationComposerWithSearch(current, owner.id, null, SearchMode.BUILT_IN))
    }
}
