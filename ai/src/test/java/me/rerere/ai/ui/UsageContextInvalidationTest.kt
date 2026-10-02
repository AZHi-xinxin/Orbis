package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationResult
import org.junit.Assert.*
import org.junit.Test

class UsageContextInvalidationTest {
    private val oldUsage = TokenUsage(promptTokens = 900000, completionTokens = 300, cachedTokens = 850000)
    private fun old() = UIMessage.assistant("synthetic old").copy(usage = oldUsage, usageContextInvalidated = true)
    private val fresh = TokenUsage(promptTokens = 120, completionTokens = 15, cachedTokens = 0, totalTokens = 135)

    @Test fun invalidationIsDurableAndMissingFieldInOldDataDefaultsFalse() {
        val codec = Json { encodeDefaults = true }
        val encoded = codec.encodeToString(UIMessage.serializer(), old())
        assertEquals(oldUsage, codec.decodeFromString<UIMessage>(encoded).usage)
        assertTrue(codec.decodeFromString<UIMessage>(encoded).usageContextInvalidated)
        assertFalse(codec.decodeFromString<UIMessage>(encoded.replace("\"usageContextInvalidated\":true,", "")).usageContextInvalidated)
    }

    @Test fun textAndFinishWithoutFreshUsageKeepHistoricalUsageInvalidated() {
        val handler = StreamChunkHandler()
        var messages = handler.handle(listOf(old()), StreamChunk.TextDelta("new", " continued"))
        messages = handler.handle(messages, StreamChunk.Finish("stop"))
        assertEquals(oldUsage, messages.last().usage); assertTrue(messages.last().usageContextInvalidated)
    }

    @Test fun outputOnlyOrMalformedUsageCannotRevalidateOldInputCounters() {
        listOf(TokenUsage(completionTokens = 20), TokenUsage(promptTokens = -1),
            TokenUsage(promptTokens = 10, cachedTokens = 11), TokenUsage(promptTokens = 10, completionTokens = -1)).forEach {
            val message = StreamChunkHandler().handle(listOf(old()), StreamChunk.Usage(it)).last()
            assertEquals(oldUsage, message.usage); assertTrue(message.usageContextInvalidated)
        }
    }

    @Test fun freshStreamUsageReplacesRatherThanMergesStalePromptAndCache() {
        val message = StreamChunkHandler().handle(listOf(old()), StreamChunk.Usage(fresh)).last()
        assertEquals(fresh, message.usage); assertFalse(message.usageContextInvalidated)
        assertEquals(oldUsage, old().usage)
    }

    @Test fun nonStreamMissingUsageKeepsHistoricalRecordButFreshUsageRevalidatesIt() {
        val model = Model(modelId = "synthetic")
        val missing = listOf(old()).handleTextGenerationResult(TextGenerationResult("synthetic", model.modelId, UIMessage.assistant("new")), model).last()
        assertEquals(oldUsage, missing.usage); assertTrue(missing.usageContextInvalidated)
        val updated = listOf(old()).handleTextGenerationResult(TextGenerationResult("synthetic", model.modelId, UIMessage.assistant("new"), usage = fresh), model).last()
        assertEquals(fresh, updated.usage); assertFalse(updated.usageContextInvalidated)
    }

    @Test fun aNewResponseAfterUserInputDoesNotInheritHistoryInvalidation() {
        val original = old()
        val messages = StreamChunkHandler().handle(listOf(original, UIMessage.user("new question")), StreamChunk.Usage(fresh))
        assertEquals(original, messages.first()); assertEquals(fresh.promptTokens, messages.last().usage!!.promptTokens)
        assertFalse(messages.last().usageContextInvalidated)
    }
}
