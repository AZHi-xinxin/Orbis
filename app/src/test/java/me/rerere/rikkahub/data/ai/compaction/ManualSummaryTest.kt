package me.rerere.rikkahub.data.ai.compaction

import kotlinx.coroutines.*
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlin.uuid.Uuid

class ManualSummaryTest {
    private fun source() = Conversation(assistantId = Uuid.random(), messageNodes = (1..8).map {
        UIMessage.user("原文 $it：" + "内容".repeat(400)).toMessageNode()
    })
    private fun draft(c: Conversation = source(), count: Int = 6): ManualSummaryDraft = ManualSummaryDraft(
        c.id.toString(), c.assistantId.toString(), manualContextFingerprint(c), 6,
        "以自己的第一人称，细致保留约定", splitManualSummary(manualSummarySource(c, 6), count),
        modelId = Uuid.random().toString(), modelName = "synthetic")
    private fun result(text: String, finish: String? = "stop") = TextGenerationResult("test", "synthetic", UIMessage.assistant(text), finish)

    @Test fun `exact chosen part count covers all text including surrogate pairs`() {
        val text = "中文😀abc".repeat(15000)
        val pieces = splitManualSummary(text, 19)
        assertEquals(19, pieces.size); assertEquals(text, pieces.joinToString(""))
        assertTrue(pieces.all { !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        assertEquals(1, splitManualSummary(text, 1).size)
        assertTrue(runCatching { splitManualSummary("a".repeat(600_000), 1) }.isFailure)
        assertTrue(runCatching { splitManualSummary("x", 2) }.isFailure)
        for (mixed in listOf("aab😀", "😀".repeat(10000) + "a".repeat(20000))) {
            val n = if (mixed.length < 10) 4 else recommendedManualSummaryParts(mixed)
            assertEquals(mixed, splitManualSummary(mixed, n).joinToString(""))
        }
    }
    @Test fun `request includes custom instructions and one segment without conversation or other summaries`() {
        val d = draft().copy(summaries = List(6) { "PREVIOUS_SUMMARY_SECRET" })
        val messages = manualSummaryMessages(d, 2)
        assertEquals(2, messages.size)
        assertTrue(messages.first().toText().contains(d.prompt))
        assertTrue(messages.last().toText().endsWith(d.chunks[2]))
        assertFalse(messages.any { it.toText().contains("PREVIOUS_SUMMARY_SECRET") })
        assertTrue(messages.all { it.parts.all { p -> p is UIMessagePart.Text } })
    }
    @Test fun `reasoning and attachments never enter source and original remains untouched`() {
        val c = source().copy(messageNodes = listOf(UIMessage.assistant("public").copy(parts = listOf(
            UIMessagePart.Text("PUBLIC_REPLY"), UIMessagePart.Reasoning("PRIVATE_REASONING"),
            UIMessagePart.Image("data:image/png;base64,PRIVATE_IMAGE"))).toMessageNode()))
        val fp = manualContextFingerprint(c)
        val text = manualSummarySource(c, 1)
        assertTrue(text.contains("PUBLIC_REPLY")); assertFalse(text.contains("PRIVATE_"))
        assertEquals(fp, manualContextFingerprint(c))
    }
    @Test fun `truncated reasoning only and tool outputs are not completed summaries`() {
        for (reason in listOf("length", "max_tokens", "MAX_TOKENS", "incomplete", "pause_turn", "content_filter"))
            assertTrue(reason, runCatching { acceptedManualSummary(result("partial", reason)) }.isFailure)
        assertTrue(runCatching { acceptedManualSummary(result("").copy(message = UIMessage.assistant("").copy(
            parts = listOf(UIMessagePart.Reasoning("not a summary"))))) }.isFailure)
        assertEquals("完整摘要", acceptedManualSummary(result("完整摘要", "STOP")))
        assertEquals("正文", acceptedManualSummary(result("<think>隐私思考</think>正文")))
        assertTrue(runCatching { acceptedManualSummary(result("<think>未完成")) }.isFailure)
    }
    @Test fun `failed segment retains successes and reload only requests missing segments`() = runBlocking {
        val folder = Files.createTempDirectory("manual-summary-test").toFile()
        try {
            val store = ManualSummaryStore(folder)
            val d = draft(); store.write(d)
            val firstCalls = mutableListOf<Int>()
            assertTrue(runCatching { runManualSummary(d, 1, { _, i ->
                firstCalls += i; if (i == 3) error("synthetic failure") else "摘要 $i"
            }, store::write) }.isFailure)
            assertEquals(listOf(0,1,2,3), firstCalls)
            val restored = ManualSummaryStore(folder).read(d.conversationId)!!
            assertEquals(3, restored.completed)
            val resumeCalls = mutableListOf<Int>()
            val complete = runManualSummary(restored, 1, { _, i -> resumeCalls += i; "摘要 $i" }, store::write)
            assertEquals(listOf(3,4,5), resumeCalls); assertTrue(complete.complete)
            assertEquals(complete, store.read(d.conversationId))
        } finally { folder.deleteRecursively() }
    }
    @Test fun `parallel failure saves already inflight successes and starts no new work`() = runBlocking {
        val d = draft()
        val started = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>(); var saved = d
        assertTrue(runCatching { runManualSummary(d, 3, { _, i ->
            calls += i
            if (i == 2) { started.complete(Unit); error("failure") }
            started.await(); delay(20); "summary $i"
        }, { saved = it }) }.isFailure)
        assertEquals(setOf(0,1,2), calls.toSet()); assertEquals(2, saved.completed)
        assertNull(saved.summaries[2]); assertEquals("summary 0", saved.summaries[0])
    }
    @Test fun `cancellation keeps completed checkpoint without starting rest`() = runBlocking {
        var saved = draft()
        val checkpoint = CompletableDeferred<Unit>()
        val job = launch { runManualSummary(saved, 1, { _, i -> if (i == 1) awaitCancellation(); "saved" }, {
            saved = it; checkpoint.complete(Unit)
        }) }
        checkpoint.await(); job.cancelAndJoin()
        assertEquals(1, saved.completed)
    }
    @Test fun `checkpoint failure prevents more requests and keeps durable earlier state`() = runBlocking {
        val d = draft(); val calls = mutableListOf<Int>(); var saved = d
        assertTrue(runCatching { runManualSummary(d, 1, { _, i -> calls += i; "summary $i" }, {
            if (it.completed == 2) error("disk full"); saved = it
        }) }.isFailure)
        assertEquals(listOf(0,1), calls); assertEquals(1, saved.completed)
    }
    @Test fun `changed source or wrong owner cannot reuse paid draft to replace chat`() {
        val c = source(); val d = draft(c)
        d.requireSource(c)
        assertTrue(runCatching { d.requireSource(c.copy(messageNodes = c.messageNodes.dropLast(1))) }.isFailure)
        assertTrue(runCatching { d.requireSource(c.copy(assistantId = Uuid.random())) }.isFailure)
        assertTrue(runCatching { d.requireSource(c.copy(compactionEpoch = 1)) }.isFailure)
    }
    @Test fun `generated preview retains all summaries in order and notes match reviewed text`() {
        val c = source(); val parts = listOf("第一段约定", "第二段用户修订", "第三段待办")
        val p = prepareOrbisGeneratedContext(c, 6, parts)
        validateManualContextReplacement(c, p.replacementNodes, p.metadata)
        assertEquals(parts, p.metadata.manualSummaryParts)
        assertEquals(2, p.keptCount); assertEquals(3, p.replacementNodes.size)
        assertTrue(p.summaryText.indexOf(parts[0]) < p.summaryText.indexOf(parts[1]))
        assertTrue(p.summaryText.contains("可能经人类编辑"))
        assertEquals(c.messageNodes.drop(6), p.replacementNodes.drop(1))
        assertFalse(p.metadata.captureLocalMemory); assertFalse(p.metadata.preserveBranch)
        assertTrue(runCatching { validateManualContextReplacement(c, p.replacementNodes,
            p.metadata.copy(manualSummaryParts = listOf("tampered"))) }.isFailure)
    }
    @Test fun `oversized summaries cannot make unreadable room rows and drafts are preserved`() {
        val d = draft().copy(summaries = List(6) { "中".repeat(90_000) }).validate()
        assertTrue(d.complete)
        assertTrue(runCatching { prepareOrbisGeneratedContext(source(), 6, d.summaries.filterNotNull()) }.isFailure)
        assertEquals(6, d.completed)
    }
    @Test fun `no duplicate network calls when completed draft is resumed`() = runBlocking {
        val d = draft().copy(summaries = List(6) { "done" })
        assertEquals(d, runManualSummary(d, 4, { _, _ -> error("must not request") }, { error("must not save") }))
    }
    @Test fun `JSON escaping is included in single row capacity check`() {
        val parts = List(7) { "\"".repeat(90_000) }
        assertTrue(generatedManualSummaryText(parts).toByteArray().size < 1024 * 1024)
        assertTrue(runCatching { prepareOrbisGeneratedContext(source(), 6, parts) }.isFailure)
    }
    @Test fun `custom segment count is independent of conservative automatic suggestion`() {
        val text = "中".repeat(200_000)
        assertEquals(9, recommendedManualSummaryParts(text))
        assertEquals(1, minimumManualSummaryParts(text))
        assertEquals(listOf(100_000,100_000), splitManualSummary(text, 2).map { it.length })
    }
    @Test fun `checkpoints do not rewrite original source and interrupted temporary files are ignored`() {
        val folder = Files.createTempDirectory("manual-summary-parts").toFile()
        try {
            val store = ManualSummaryStore(folder); val d = draft()
            store.write(d)
            val plan = java.io.File(folder, "${d.conversationId}.json")
            assertTrue(plan.setLastModified(123_000L))
            val next = d.copy(summaries = listOf("paid first") + List(5) { null })
            store.write(next)
            assertEquals(123_000L, plan.lastModified())
            java.io.File(folder, "${d.conversationId}.parts/1.txt.tmp").writeText("incomplete disk write")
            assertEquals(next, ManualSummaryStore(folder).read(d.conversationId))
            assertTrue(runCatching { store.write(next.copy(prompt = "changed requirements")) }.isFailure)
            assertEquals(next, ManualSummaryStore(folder).read(d.conversationId))
            val observer = ManualSummaryStore(folder)
            assertEquals(next, observer.read(d.conversationId))
            store.clear(d.conversationId); assertNull(store.read(d.conversationId))
            assertNull(observer.read(d.conversationId))
            store.write(d.copy(prompt = "new plan after discard"))
            assertEquals("new plan after discard", observer.read(d.conversationId)!!.prompt)
        } finally { folder.deleteRecursively() }
    }
}
