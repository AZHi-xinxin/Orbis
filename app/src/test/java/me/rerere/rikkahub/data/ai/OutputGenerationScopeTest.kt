package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Pure synthetic messages: no Android context, storage, model, output plugins or network. */
class OutputGenerationScopeTest {
    @Test fun `empty human and assistant tails choose the expected frozen boundary`() {
        assertEquals(0, frozenOutputPrefixCount(emptyList()))
        assertEquals(0, frozenOutputPrefixCount(listOf(UIMessage.assistant("active continuation"))))
        val human = listOf(UIMessage.user("question"))
        assertEquals(1, frozenOutputPrefixCount(human))
        assertEquals(1, frozenOutputPrefixCount(human + UIMessage.assistant("active answer")))
        assertEquals(3, frozenOutputPrefixCount(human + UIMessage.assistant("history") + UIMessage.user("new question")))
    }

    @Test fun `non idempotent transform never revisits five thousand frozen messages`() = runBlocking {
        val frozen = (0 until 5000).map { UIMessage.assistant("history $it already transformed!") }
        val scope = OutputGenerationScope(frozen)
        val active = UIMessage.assistant("new reply")
        var transformedCount = 0
        suspend fun apply(snapshot: List<UIMessage>): List<UIMessage> = scope.apply(snapshot) { suffix ->
            transformedCount += suffix.size
            suffix.map { it.copy(parts = it.parts.map { part ->
                if (part is UIMessagePart.Text) part.copy(text = part.text + "!") else part
            }) }
        }

        val first = apply(frozen + active)
        val second = apply(first)
        assertEquals(2, transformedCount)
        frozen.indices.forEach { index -> assertSame(frozen[index], second[index]) }
        assertEquals("history 3000 already transformed!", text(second[3000]))
        assertEquals("new reply!!", text(second.last()))
        assertEquals(active.id, second.last().id)
    }

    @Test fun `frozen objects win over same identity copies from the working stream`() = runBlocking {
        val human = UIMessage.user("durable human")
        val assistant = UIMessage.assistant("durable transformed history")
        val scope = OutputGenerationScope(listOf(human, assistant))
        val active = UIMessage.assistant("active")
        val result = scope.apply(listOf(human.copy(), assistant.copy(parts = listOf(UIMessagePart.Text("working raw text"))), active)) { it }
        assertSame(human, result[0])
        assertSame(assistant, result[1])
        assertSame(active, result[2])
    }

    @Test fun `constructor snapshots list container and empty active suffix invokes no transformer`() = runBlocking {
        val historical = UIMessage.user("history")
        val callerList = mutableListOf(historical)
        val scope = OutputGenerationScope(callerList)
        callerList.clear()
        val result = scope.apply(listOf(historical)) { error("No active messages to transform") }
        assertEquals(1, scope.frozenPrefixCount)
        assertSame(historical, result.single())
        assertTrue(OutputGenerationScope(emptyList()).apply(emptyList()) { error("empty") }.isEmpty())
    }

    @Test fun `changed reordered and missing input prefix are rejected before transformer runs`() = runBlocking {
        val frozen = listOf(UIMessage.user("one"), UIMessage.assistant("two"))
        val scope = OutputGenerationScope(frozen)
        val active = UIMessage.assistant("active")
        val invalid = listOf(
            listOf(frozen[0].copy(id = Uuid.random()), frozen[1], active),
            listOf(frozen[1], frozen[0], active),
            frozen.take(1),
        )
        invalid.forEach { input ->
            var called = false
            val error = runCatching { scope.apply(input) { called = true; it } }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertFalse(called)
        }
    }

    @Test fun `active cardinality changes are rejected`() = runBlocking {
        val frozen = UIMessage.user("question")
        val active = UIMessage.assistant("answer")
        val scope = OutputGenerationScope(listOf(frozen))
        val input = listOf(frozen, active)
        assertTrue(runCatching { scope.apply(input) { emptyList() } }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { scope.apply(input) { it + UIMessage.assistant("injected") } }
            .exceptionOrNull() is IllegalStateException)
    }

    @Test fun `active identity or order changes are rejected`() = runBlocking {
        val first = UIMessage.assistant("first")
        val second = UIMessage.assistant("second")
        val scope = OutputGenerationScope(emptyList())
        assertTrue(runCatching { scope.apply(listOf(first)) { listOf(it.single().copy(id = Uuid.random())) } }
            .exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { scope.apply(listOf(first, second)) { it.reversed() } }
            .exceptionOrNull() is IllegalStateException)
    }

    @Test fun `mutable transformer cannot evade identity checks or mutate the input list`() = runBlocking {
        val first = UIMessage.assistant("first")
        val second = UIMessage.assistant("second")
        val input = mutableListOf(first, second)
        val scope = OutputGenerationScope(emptyList())
        assertTrue(runCatching { scope.apply(input) { suffix ->
            val mutable = suffix as MutableList<UIMessage>
            mutable[0] = first.copy(id = Uuid.random())
            mutable
        } }.exceptionOrNull() is IllegalStateException)
        assertSame(first, input[0])
        assertSame(second, input[1])
    }

    @Test fun `approved tool tail remains active rather than frozen`() = runBlocking {
        val history = UIMessage.user("request")
        val pending = UIMessagePart.Tool("synthetic-call", "synthetic-tool", "{}")
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending))
        val input = listOf(history, assistant)
        val scope = OutputGenerationScope(input.take(frozenOutputPrefixCount(input)))
        val result = scope.apply(input) { suffix ->
            assertEquals(listOf(assistant), suffix)
            listOf(assistant.copy(parts = listOf(pending.copy(output = listOf(UIMessagePart.Text("synthetic result"))))))
        }
        assertSame(history, result.first())
        assertEquals(assistant.id, result.last().id)
        assertTrue(result.last().getTools().single().isExecuted)
    }

    @Test fun `successful compact must rebuild scope before transforming the shorter page`() = runBlocking {
        val old = listOf(UIMessage.user("one"), UIMessage.assistant("two"), UIMessage.user("three"))
        val oldScope = OutputGenerationScope(old)
        val summary = UIMessage.assistant("new durable summary")
        val active = UIMessage.assistant("current compact receipt")
        val compacted = listOf(summary, active)
        assertTrue(runCatching { oldScope.apply(compacted) { it } }.exceptionOrNull() is IllegalStateException)

        val nextScope = OutputGenerationScope(compacted.take(frozenOutputPrefixCount(compacted)))
        val result = nextScope.apply(compacted) { suffix ->
            assertEquals(listOf(active), suffix)
            suffix.map { it.copy(parts = it.parts + UIMessagePart.Text("continued")) }
        }
        assertEquals(1, nextScope.frozenPrefixCount)
        assertSame(summary, result.first())
        assertEquals(active.id, result.last().id)
        assertEquals(2, result.last().parts.size)
    }

    @Test fun `transform failure propagates without changing frozen history`() = runBlocking {
        val historical = UIMessage.user("history")
        val active = UIMessage.assistant("active")
        val scope = OutputGenerationScope(listOf(historical))
        val failure = IllegalStateException("synthetic failure")
        assertSame(failure, runCatching { scope.apply(listOf(historical, active)) { throw failure } }.exceptionOrNull())
        assertSame(historical, scope.apply(listOf(historical, active)) { it }.first())
    }

    private fun text(message: UIMessage) = (message.parts.single() as UIMessagePart.Text).text
}
