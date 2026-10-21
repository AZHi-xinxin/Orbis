package me.rerere.rikkahub.data.orbis.observation

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationInputSnapshot
import org.junit.Assert.*
import org.junit.Test

class AiObservationProjectionTest {
    private val firstReport = "{\"窗口\":\"本窗\",\"本助手最近活跃\":\"2026-10-21T01:00:00Z\"}"
    private val nextReport = "{\"窗口\":\"本窗\",\"本助手最近活跃\":\"2026-10-21T02:00:00Z\"}"

    private fun reports(messages: List<UIMessage>): List<UIMessagePart.Text> {
        fun inParts(parts: List<UIMessagePart>): List<UIMessagePart.Text> = parts.flatMap { part ->
            when {
                part.isObservationProjection() -> listOf(part as UIMessagePart.Text)
                part is UIMessagePart.Tool -> inParts(part.output)
                else -> emptyList()
            }
        }
        return messages.flatMap { inParts(it.parts) }
    }

    @Test fun changingFactsOnlyChangesLatestInputTailAndNeverTheSystemOrOldHistory() {
        val system = UIMessage.system("固定系统说明")
        val secondSystem = UIMessage.system("已有第二段系统说明")
        val oldHuman = UIMessage.user("旧问题")
        val oldAnswer = UIMessage.assistant("旧回答")
        val human = UIMessage.user("新问题")
        val original = listOf(system, secondSystem, oldHuman, oldAnswer, human)
        val first = projectObservation(original, firstReport)
        val second = projectObservation(original, nextReport)
        original.dropLast(1).forEachIndexed { index, message ->
            assertSame(message, first[index]); assertSame(message, second[index])
        }
        assertEquals(original.map { it.id }, first.map { it.id })
        assertEquals(original.map { it.role }, first.map { it.role })
        assertEquals(human.parts, first.last().parts.dropLast(1))
        assertEquals(human.parts, second.last().parts.dropLast(1))
        assertEquals("[Orbis全局观测]$firstReport", reports(first).single().text)
        assertEquals("[Orbis全局观测]$nextReport", reports(second).single().text)
        assertEquals("新问题", human.toText())
    }

    @Test fun eachHumanWakeRefreshesFactsWithoutSavingPreviousReportsIntoHistory() = runTest {
        val system = UIMessage.system("stable prefix")
        val human = UIMessage.user("first question")
        val firstOriginal = listOf(system, human)
        val firstSnapshot = GenerationInputSnapshot(observation = firstReport)
        val first = firstSnapshot.input { firstOriginal }
        val answer = UIMessage.assistant("first answer")
        val newHuman = UIMessage.user("second question")
        val secondOriginal = firstOriginal + answer + newHuman
        val second = GenerationInputSnapshot(observation = nextReport).input { secondOriginal }
        assertEquals(firstReport, reports(first).single().text.removePrefix("[Orbis全局观测]"))
        assertEquals(nextReport, reports(second).single().text.removePrefix("[Orbis全局观测]"))
        assertEquals(secondOriginal.dropLast(1), second.dropLast(1))
        assertEquals(firstOriginal, removeObservationProjection(first))
        assertEquals(secondOriginal, removeObservationProjection(second))
        assertTrue(reports(firstOriginal).isEmpty()); assertTrue(reports(secondOriginal).isEmpty())
    }

    @Test fun toolContinuationsUseOneFrozenReportAndKeepToolProtocolUnitsIntact() = runTest {
        val original = listOf(UIMessage.system("system"), UIMessage.user("question"))
        val snapshot = GenerationInputSnapshot(observation = firstReport)
        val prefix = snapshot.input { original }
        val tool = UIMessagePart.Tool("tool-1", "example", "{\"x\":1}")
        val response = UIMessage.assistant("").copy(parts = listOf(tool))
        val result = tool.copy(output = listOf(UIMessagePart.Text("actual result")))
        snapshot.appendCompletedResponse(response, listOf(result))
        val continuation = snapshot.input { error("must not recompute current facts on tool continuation") }
        assertEquals(prefix, continuation.take(prefix.size))
        assertEquals(result, continuation.last().getTools().single())
        assertEquals(1, reports(continuation).size)
        assertEquals(MessageRole.ASSISTANT, continuation.last().role)
        assertEquals(original, removeObservationProjection(prefix))
    }

    @Test fun mediaOnlyHumanGetsSeparateTextAndEveryOriginalPartSurvives() {
        val image = UIMessagePart.Image("file:///synthetic.png")
        val audio = UIMessagePart.Audio("file:///synthetic.wav")
        val document = UIMessagePart.Document("file:///synthetic.pdf", "synthetic.pdf", "application/pdf")
        val human = UIMessage.user("").copy(parts = listOf(image, audio, document))
        val original = listOf(human)
        val projected = projectObservation(original, firstReport).single()
        assertEquals(human.id, projected.id); assertEquals(human.role, projected.role)
        human.parts.forEachIndexed { index, part -> assertSame(part, projected.parts[index]) }
        assertTrue(projected.parts.last().isObservationProjection())
        assertEquals(listOf(image, audio, document), human.parts)
        assertFalse(projected.isSynthetic)
    }

    @Test fun currentEventOrSyntheticCarrierDoesNotRewriteAnEarlierHumanTurn() {
        val oldHuman = UIMessage.user("old human")
        val event = UIMessage.user("event body").copy(orbisEvent = OrbisEventMetadata(
            "record", "synthetic-source", "event", 100))
        val reminder = UIMessage.user("<time_reminder>fixed time</time_reminder>").copy(isSynthetic = true)
        for (carrier in listOf(event, reminder)) {
            val original = listOf(oldHuman, UIMessage.assistant("old answer"), carrier)
            val projected = projectObservation(original, firstReport)
            assertSame(oldHuman, projected.first())
            assertEquals(carrier.copy(parts = projected.last().parts), projected.last())
            assertEquals(carrier.parts, projected.last().parts.dropLast(1))
            assertEquals(carrier.isSynthetic, projected.last().isSynthetic)
            assertEquals(carrier.orbisEvent, projected.last().orbisEvent)
            assertEquals(JsonPrimitive(false), projected.last().parts.last().metadata?.get("human_authored"))
        }
    }

    @Test fun nullBlankAndEmptyInputsNeverInventMessages() {
        val original = listOf(UIMessage.user("hello"))
        for (report in listOf(null, "", " \n\t")) assertSame(original, projectObservation(original, report))
        val empty = emptyList<UIMessage>()
        assertSame(empty, projectObservation(empty, firstReport))
        val noCarrier = listOf(UIMessage.system("system"), UIMessage.assistant("summary"))
        assertSame(noCarrier, projectObservation(noCarrier, firstReport))
        assertEquals(original, projectObservation(projectObservation(original, firstReport), null))
    }

    @Test fun reprojectingReplacesOnlyOwnedReportAndDoesNotAccumulateCopies() {
        val original = listOf(UIMessage.system("system"), UIMessage.user("question"))
        val once = projectObservation(original, firstReport)
        assertEquals(once, projectObservation(once, firstReport))
        val changed = projectObservation(once, nextReport)
        assertEquals(1, reports(changed).size)
        assertEquals("[Orbis全局观测]$nextReport", reports(changed).single().text)
        assertEquals(original, removeObservationProjection(changed))
        assertSame(original, removeObservationProjection(original))
    }

    @Test fun literalLookalikesAndMalformedMetadataAreNotRemoved() {
        val literal = UIMessagePart.Text("[Orbis全局观测]我引用的文字")
        val keyOnly = UIMessagePart.Text("[Orbis全局观测]保留", buildJsonObject {
            put("orbis_observation_projection", "report_v1")
        })
        val unrelated = UIMessagePart.Text("普通正文", buildJsonObject {
            put("orbis_observation_projection", "report_v1"); put("request_only", true); put("human_authored", false)
        })
        val invalidMetadata = UIMessagePart.Text("[Orbis全局观测]仍保留", buildJsonObject {
            put("orbis_observation_projection", buildJsonObject { put("unexpected", true) })
        })
        val human = UIMessage.user("").copy(parts = listOf(literal, keyOnly, unrelated, invalidMetadata))
        val original = listOf(human)
        assertSame(original, removeObservationProjection(original))
        val projected = projectObservation(original, firstReport)
        human.parts.forEachIndexed { index, part -> assertSame(part, projected.single().parts[index]) }
        assertEquals(1, reports(projected).size)
    }

    @Test fun compactWithoutHumanUsesCompletedToolOutputWithoutChangingCallIdentityOrOriginalResult() {
        val image = UIMessagePart.Image("file:///result.png")
        val tool = UIMessagePart.Tool("compact-id", "compact_context", "{\"summary\":\"keep\"}",
            output = listOf(UIMessagePart.Text("compacted"), image))
        val reasoning = UIMessagePart.Reasoning("preserve signed reasoning", metadata = buildJsonObject { put("signature", "synthetic") })
        val call = UIMessage.assistant("").copy(parts = listOf(reasoning, tool))
        val original = listOf(UIMessage.system("system"), UIMessage.assistant("summary"), call)
        val projected = projectObservation(original, firstReport)
        assertEquals(original.map { it.id }, projected.map { it.id })
        assertEquals(original.map { it.role }, projected.map { it.role })
        assertSame(reasoning, projected.last().parts.first())
        val result = projected.last().getTools().single()
        assertEquals(tool.copy(output = result.output), result)
        tool.output.forEachIndexed { index, part -> assertSame(part, result.output[index]) }
        assertTrue(result.output.last().isObservationProjection())
        assertEquals(2, tool.output.size)
        assertEquals(1, reports(projected).size)
        assertEquals(projected, projectObservation(projected, firstReport))
        assertEquals(original, removeObservationProjection(projected))
    }

    @Test fun fallbackChoosesLatestCompletedToolAndNeverTurnsPendingToolIntoExecuted() {
        val firstTool = UIMessagePart.Tool("first", "one", "{}", output = listOf(UIMessagePart.Text("one result")))
        val latestTool = UIMessagePart.Tool("second", "two", "{}", output = listOf(UIMessagePart.Text("two result")))
        val pending = UIMessagePart.Tool("third", "three", "{}")
        val original = listOf(UIMessage.assistant("").copy(parts = listOf(firstTool)),
            UIMessage.assistant("").copy(parts = listOf(latestTool, pending)))
        val projected = projectObservation(original, firstReport)
        assertSame(original.first(), projected.first())
        assertSame(pending, projected.last().parts.last())
        assertFalse(pending.isExecuted)
        assertTrue(projected.last().getTools().first().output.last().isObservationProjection())
        val onlyPending = listOf(UIMessage.assistant("").copy(parts = listOf(pending)))
        assertSame(onlyPending, projectObservation(onlyPending, firstReport))
    }

    @Test fun completedToolReportsAreRemovedRecursivelyBeforeMovingBackToNewHumanTail() {
        val tool = UIMessagePart.Tool("old", "example", "{}", output = listOf(UIMessagePart.Text("result")))
        val previous = projectObservation(listOf(UIMessage.assistant("").copy(parts = listOf(tool))), firstReport)
        val newHuman = UIMessage.user("new question")
        val next = projectObservation(previous + newHuman, nextReport)
        assertEquals(tool, next.first().getTools().single())
        assertTrue(next.last().parts.last().isObservationProjection())
        assertEquals(1, reports(next).size)
        assertEquals(newHuman.parts, next.last().parts.dropLast(1))
        assertEquals(1, reports(previous).size)
    }
}
