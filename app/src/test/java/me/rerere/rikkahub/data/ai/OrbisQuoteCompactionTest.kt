package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisMessageQuote
import me.rerere.ai.ui.OrbisUserMessageTime
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.applyOrbisQuotes
import me.rerere.rikkahub.data.ai.transformers.applyOrbisUserMessageTimes
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisQuoteCompactionTest {
    @Test fun `old retained input gets only static quote and captured time under same wake setting`() = runBlocking {
        val quote = OrbisMessageQuote(Uuid.random(), Uuid.random(), Uuid.random(), MessageRole.ASSISTANT,
            "{{ unexecuted_template }} quoted body", "2026-01-01T00:00")
        val retained = UIMessage.user("old authored reply").copy(orbisQuote = quote,
            orbisUserMessageTime = OrbisUserMessageTime(1_767_225_600_000, "UTC", 0))
        val tool = UIMessagePart.Tool("compact", "compact", "{}")
        val raw = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val done = tool.copy(output = listOf(UIMessagePart.Text("ok")))
        listOf(false, true).forEach { enabled ->
            val snapshot = GenerationInputSnapshot(enabled)
            snapshot.input { listOf(UIMessage.system("unchanged system"), UIMessage.user("current short context")) }
            val rebased = snapshot.prepareCompactionInput(listOf(UIMessage.assistant("summary"), retained), raw, listOf(done), raw.id)
            val projected = rebased.single { it.id == retained.id }
            assertEquals(applyOrbisUserMessageTimes(applyOrbisQuotes(listOf(retained)), enabled).single(), projected)
            assertEquals("unchanged system", rebased.first().toText())
            assertTrue(projected.toText().contains("{{ unexecuted_template }}"))
            assertEquals(enabled, projected.toText().contains("<orbis_user_message_time>"))
            assertEquals(listOf(UIMessagePart.Text("old authored reply")), retained.parts)
            snapshot.acceptCompactionInput(rebased, raw, listOf(done), raw.id)
            assertEquals(rebased, snapshot.input { error("must not rerun") })
        }
    }
}
