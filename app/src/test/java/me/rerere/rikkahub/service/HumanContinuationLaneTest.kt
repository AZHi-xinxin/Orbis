package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.HostToolApproval
import kotlinx.serialization.json.JsonNull
import me.rerere.rikkahub.data.ai.finishInterruptedHostTools
import me.rerere.rikkahub.data.ai.withHostToolFailure
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.DeletedToolRecord
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class HumanContinuationLaneTest {
    private fun pendingConversation() = base().let { c -> c.copy(messageNodes = c.messageNodes +
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("approve-a", "orbis_context_prune", "{}", approvalState = ToolApprovalState.Pending),
            UIMessagePart.Tool("approve-b", "local-write", "{}", approvalState = ToolApprovalState.Pending)
        )).toMessageNode()) }

    @Test fun `approval accepts same turn finalization and remembers once without waiting for producer`() = runTest {
        val finished = CompletableDeferred<Unit>()
        val lane = HumanContinuationLane(base(), backgroundScope, { true }, { fail(it.toString()) })
        var owner = -1L
        lane.send(null) { c, token, publish ->
            owner = token
            publish(c.copy(messageNodes = c.messageNodes + pendingConversation().messageNodes.last()))
            withContext(NonCancellable) { finished.await(); publish(c.answer("must not overwrite approval")) }
        }
        runCurrent()
        val a = checkNotNull(lane.captureApproval("approve-a"))
        val b = checkNotNull(lane.captureApproval("approve-b"))
        lane.publish(owner, lane.state.value.copy(title = "same turn finalized"))
        var grants = 0
        var resumed = 0
        assertTrue(lane.resolveApproval(a, { grants++; it.copy(approvalState = ToolApprovalState.Approved) }) { _, _, _ -> resumed++ })
        assertFalse(lane.resolveApproval(a, { error("duplicate must not remember") }) { _, _, _ -> error("duplicate") })
        assertTrue(lane.resolveApproval(b, { it.copy(approvalState = ToolApprovalState.Approved) }) { _, _, _ -> resumed++ })
        runCurrent()
        assertEquals(1, grants); assertEquals(1, resumed)
        assertEquals("same turn finalized", lane.state.value.title)
        finished.complete(Unit); runCurrent()
        assertEquals(2, lane.state.value.currentMessages.last().getTools().size)
    }

    @Test fun `new human input rejects delayed approval before saving grant`() = runTest {
        val lane = HumanContinuationLane(pendingConversation(), backgroundScope, { true }, { fail(it.toString()) })
        val target = checkNotNull(lane.captureApproval("approve-a"))
        lane.send(UIMessage.user("new input"), answer = false) { _, _, _ -> }
        assertFalse(lane.resolveApproval(target, { error("stale approval cannot save a grant") }) { _, _, _ -> error("stale") })
        assertNull(lane.captureApproval("approve-a"))
    }

    @Test fun `edited arguments reject delayed approval`() = runTest {
        val lane = HumanContinuationLane(pendingConversation(), backgroundScope, { true }, { fail(it.toString()) })
        val target = checkNotNull(lane.captureApproval("approve-a"))
        lane.state.value = lane.state.value.copy(messageNodes = lane.state.value.messageNodes.map { node ->
            node.copy(messages = node.messages.map { msg -> msg.copy(parts = msg.parts.map { part ->
                if (part == target.tool) target.tool.copy(input = "{\"different\":true}") else part
            }) })
        })
        assertFalse(lane.resolveApproval(target, { error("changed input") }) { _, _, _ -> error("changed input") })
    }

    @Test fun `unavailable approval settles card without execution or remembered grant`() = runTest {
        val lane = HumanContinuationLane(pendingConversation(), backgroundScope, { true }, { fail(it.toString()) })
        assertTrue(lane.resolveApproval(checkNotNull(lane.captureApproval("approve-a")), {
            it.withHostToolFailure(me.rerere.rikkahub.data.ai.HostToolFailure.APPROVAL_CHANGED)
        }) { _, _, _ -> error("second approval remains pending") })
        runCurrent(); assertNull(lane.captureApproval("approve-a"))
        assertNotNull(lane.captureApproval("approve-b"))
    }

    @Test fun `explicit batch handoff accepts saved pending decisions but ordinary handoff does not`() = runTest {
        val lane = HumanContinuationLane(pendingConversation(), backgroundScope, { true }, { fail(it.toString()) })
        lane.requestSave(); runCurrent()
        assertFalse(lane.retireForHistoryEdit(lane.state.value))
        assertTrue(lane.retireForHistoryEdit(lane.state.value, allowPendingApprovals = true))
        assertNull(lane.captureApproval("approve-a"))
    }

    @Test fun `current explicit user grant is honored after an older uncertain outcome`() {
        val tool = Tool("write_file", "synthetic", execute = { emptyList() })
        val interrupted = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("old", tool.name, "{}"))).finishInterruptedHostTools()
        assertSame(tool, reviewUncertainContinuationTools(listOf(tool), listOf(interrupted),
            hasCurrentGrant = { true }).single())
        assertTrue(reviewUncertainContinuationTools(listOf(tool), listOf(interrupted),
            requireFreshApproval = true, hasCurrentGrant = { true }).single().needsApproval(JsonNull))
    }
    private fun base() = Conversation(assistantId = Uuid.random(), messageNodes = listOf(UIMessage.user("old").toMessageNode()))
    private fun Conversation.answer(text: String) = copy(messageNodes = messageNodes + UIMessage.assistant(text).toMessageNode())

    @Test fun `saved idle lane can hand back history and late producers become inert`() = runTest {
        var writes = 0
        var ready = 0
        var owner = -1L
        val lane = HumanContinuationLane(base(), backgroundScope, { writes++; true }, { fail(it.toString()) },
            onIdleSaved = { ready++ })
        lane.send(UIMessage.user("new input")) { snapshot, turn, publish ->
            owner = turn
            publish(snapshot.answer("saved reply"))
        }
        runCurrent()
        val expected = lane.state.value
        assertTrue(ready > 0)
        assertFalse("CAS must reject an equal but stale snapshot", lane.retireForHistoryEdit(expected.copy()))
        assertTrue(lane.retireForHistoryEdit(expected))
        assertFalse(lane.isCurrent(owner))
        lane.publish(owner, expected.answer("late output"))
        lane.stop()
        lane.requestSave()
        val savedCount = writes
        runCurrent()
        assertSame(expected, lane.state.value)
        assertEquals(savedCount, writes)
        assertFalse(lane.retireForHistoryEdit(expected))
        assertThrows(IllegalStateException::class.java) {
            lane.send(UIMessage.user("stale callback")) { _, _, _ -> fail("retired lane cannot generate") }
        }
    }

    @Test fun `failed or blocked persistence never releases history editing`() = runTest {
        val disk = CompletableDeferred<Boolean>()
        var ready = 0
        var succeeds = false
        val lane = HumanContinuationLane(base(), backgroundScope, { if (succeeds) true else disk.await() },
            { fail(it.toString()) }, onIdleSaved = { ready++ })
        lane.send(UIMessage.user("new input")) { c, _, publish -> publish(c.answer("reply")) }
        runCurrent()
        assertFalse(lane.retireForHistoryEdit(lane.state.value))
        disk.complete(false)
        runCurrent()
        assertFalse(lane.retireForHistoryEdit(lane.state.value))
        assertEquals(0, ready)
        succeeds = true
        lane.requestSave()
        runCurrent()
        assertTrue(ready > 0)
        assertTrue(lane.retireForHistoryEdit(lane.state.value))
    }

    @Test fun `completed successor cannot retire while cancelled predecessor still writes`() = runTest {
        val oldFinished = CompletableDeferred<Unit>()
        var ready = 0
        val lane = HumanContinuationLane(base(), backgroundScope, { true }, { fail(it.toString()) },
            onIdleSaved = { ready++ })
        lane.send(UIMessage.user("old")) { c, _, publish ->
            withContext(NonCancellable) { oldFinished.await(); publish(c.answer("late")) }
        }
        runCurrent()
        lane.stop()
        lane.send(UIMessage.user("fresh")) { c, _, publish -> publish(c.answer("new")) }
        runCurrent()
        assertNull(lane.job.value)
        assertEquals(0, ready)
        assertFalse(lane.retireForHistoryEdit(lane.state.value))
        oldFinished.complete(Unit)
        runCurrent()
        assertTrue(ready > 0)
        assertTrue(lane.retireForHistoryEdit(lane.state.value))
        assertEquals("new", lane.state.value.currentMessages.last().toText())
    }

    @Test fun `saving an older revision cannot release a newer unsaved reply`() = runTest {
        val firstWrite = CompletableDeferred<Boolean>()
        val latestWrite = CompletableDeferred<Boolean>()
        var writes = 0
        var ready = 0
        val lane = HumanContinuationLane(base(), backgroundScope,
            { if (++writes == 1) firstWrite.await() else latestWrite.await() }, { fail(it.toString()) },
            onIdleSaved = { ready++ })
        lane.send(UIMessage.user("first")) { c, _, publish -> publish(c.answer("first answer")) }
        runCurrent()
        lane.send(UIMessage.user("second")) { c, _, publish -> publish(c.answer("second answer")) }
        runCurrent()
        firstWrite.complete(true)
        runCurrent()
        assertEquals(2, writes)
        assertEquals(0, ready)
        assertFalse(lane.retireForHistoryEdit(lane.state.value))
        latestWrite.complete(true)
        runCurrent()
        assertTrue(lane.retireForHistoryEdit(lane.state.value))
    }

    @Test fun `an unexecuted tool keeps its approval owner even after history is saved`() = runTest {
        var ready = 0
        val lane = HumanContinuationLane(base(), backgroundScope, { true }, { fail(it.toString()) },
            onIdleSaved = { ready++ })
        lane.send(UIMessage.user("ask")) { c, _, publish -> publish(c.copy(messageNodes = c.messageNodes +
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
                "pending", "write_file", "{}", approvalState = ToolApprovalState.Pending))).toMessageNode())) }
        runCurrent()
        assertEquals(0, ready)
        assertFalse(lane.retireForHistoryEdit(lane.state.value))
    }

    @Test fun `starting sending and saving a continuation never emits routine UI notices`() = runTest {
        val disk = CompletableDeferred<Boolean>()
        val lane = HumanContinuationLane(base(), backgroundScope, { disk.await() }, { fail(it.toString()) })
        assertNull(lane.persistenceWarning.value)
        repeat(2) {
            lane.send(UIMessage.user("send $it")) { c, _, p -> p(c.answer("answer")) }
            runCurrent()
            assertNull(lane.persistenceWarning.value)
        }
        disk.complete(true)
        runCurrent()
        // Internal state remains available to queue control, but is not the UI warning source.
        assertTrue(lane.notice.value.contains("续聊已保存"))
        assertTrue(lane.contextNotice.value.contains("恢复通道"))
        assertNull(lane.persistenceWarning.value)
        lane.stop()
        runCurrent()
        assertNull(lane.persistenceWarning.value)
    }

    @Test fun `only a failed save emits a warning and a confirmed save clears it`() = runTest {
        var saved = false
        val lane = HumanContinuationLane(base(), backgroundScope, { saved }, { fail(it.toString()) })
        lane.send(UIMessage.user("first")) { c, _, p -> p(c.answer("first answer")) }
        runCurrent()
        val warning = lane.persistenceWarning.value
        assertNotNull(warning)
        assertTrue(warning!!.contains("保存仍有问题"))
        assertFalse(warning.contains("恢复通道"))
        assertFalse(warning.contains("续聊已保存"))
        saved = true
        lane.send(UIMessage.user("next")) { c, _, p -> p(c.answer("next answer")) }
        runCurrent()
        assertEquals("next answer", lane.state.value.currentMessages.last().toText())
        assertNull(lane.persistenceWarning.value)
    }

    @Test fun `an outdated save failure cannot emit a warning for a newer snapshot`() = runTest {
        val oldSave = CompletableDeferred<Boolean>()
        val currentSave = CompletableDeferred<Boolean>()
        var writes = 0
        val lane = HumanContinuationLane(base(), backgroundScope,
            { if (++writes == 1) oldSave.await() else currentSave.await() }, { fail(it.toString()) })
        lane.send(UIMessage.user("first")) { c, _, p -> p(c.answer("first answer")) }
        runCurrent()
        lane.send(UIMessage.user("next")) { c, _, p -> p(c.answer("next answer")) }
        runCurrent()
        oldSave.complete(false)
        runCurrent()
        assertEquals(2, writes)
        assertNull(lane.persistenceWarning.value)
        currentSave.complete(true)
        runCurrent()
        assertNull(lane.persistenceWarning.value)
        assertEquals("next answer", lane.state.value.currentMessages.last().toText())
    }

    @Test fun `unknown old side effects require fresh approval without disabling other tools`() {
        val grant = HostToolApproval("write", "revision", "write")
        val write = Tool("write_file", "write", hostApproval = grant, execute = { emptyList() })
        val read = Tool("read_file", "read", execute = { emptyList() })
        val interrupted = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool(toolCallId = "old-call", toolName = "write_file", input = "{}")))
            .finishInterruptedHostTools()
        val guarded = reviewUncertainContinuationTools(listOf(write, read), listOf(interrupted))
        assertTrue(guarded[0].needsApproval(JsonNull))
        assertSame(grant, guarded[0].hostApproval)
        assertSame(read, guarded[1])
        assertFalse(guarded[1].needsApproval(JsonNull))
    }

    @Test fun `disk failure never gates the first or following human request`() = runTest {
        var requests = 0
        val lane = HumanContinuationLane(base(), backgroundScope, { error("disk unavailable") }, { fail(it.toString()) })
        repeat(3) { index ->
            lane.send(UIMessage.user("human $index")) { snapshot, _, publish -> requests++; publish(snapshot.answer("reply $index")) }
            runCurrent()
            assertNull(lane.job.value)
        }
        assertEquals(3, requests)
        assertEquals(7, lane.state.value.messageNodes.size)
        assertTrue(lane.notice.value.contains("保存仍有问题"))
        assertEquals(lane.notice.value, lane.persistenceWarning.value)
    }

    @Test fun `a blocked persistence observer cannot hold a model request or the next send`() = runTest {
        val disk = CompletableDeferred<Boolean>()
        var writes = 0
        var requests = 0
        val lane = HumanContinuationLane(base(), backgroundScope, { writes++; disk.await() }, { fail(it.toString()) })
        repeat(2) { lane.send(UIMessage.user("send $it")) { c, _, p -> requests++; p(c.answer("answer")) }; runCurrent() }
        assertEquals(2, requests)
        assertEquals(1, writes)
        disk.complete(false)
        runCurrent()
    }

    @Test fun `cancelled uncooperative producer cannot overwrite a new turn`() = runTest {
        val releaseOld = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val lane = HumanContinuationLane(base(), backgroundScope, { true }, { fail(it.toString()) })
        lane.send(UIMessage.user("first")) { old, _, publish ->
            entered.complete(Unit)
            withContext(NonCancellable) { releaseOld.await(); publish(old.answer("late old result")) }
        }
        runCurrent()
        assertTrue(entered.isCompleted)
        lane.send(UIMessage.user("wake now")) { c, _, p -> p(c.answer("new reply")) }
        runCurrent()
        assertEquals("new reply", lane.state.value.currentMessages.last().toText())
        releaseOld.complete(Unit)
        runCurrent()
        assertEquals("new reply", lane.state.value.currentMessages.last().toText())
        assertFalse(lane.state.value.currentMessages.any { it.toText() == "late old result" })
        assertNull(lane.job.value)
    }

    @Test fun `model or tool failure does not require a recovery action before another send`() = runTest {
        var failures = 0
        val lane = HumanContinuationLane(base(), backgroundScope, { false }, { failures++ })
        lane.send(UIMessage.user("first")) { _, _, _ -> error("tool or provider failed") }
        runCurrent()
        lane.send(UIMessage.user("are you there")) { c, _, p -> p(c.answer("yes")) }
        runCurrent()
        assertEquals(1, failures)
        assertEquals("yes", lane.state.value.currentMessages.last().toText())
        assertEquals(2, lane.state.value.currentMessages.count { it.role == MessageRole.USER && it.toText() != "old" })
    }

    @Test fun `unfinished old tools are interruption receipts not replay permissions`() = runTest {
        val oldTool = UIMessagePart.Tool(toolCallId = "old-call", toolName = "write_file", input = "{}",
            approvalState = ToolApprovalState.Pending)
        val original = base().copy(messageNodes = listOf(UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(oldTool)).toMessageNode()))
        val lane = HumanContinuationLane(original, backgroundScope, { false }, { fail(it.toString()) })
        lane.send(UIMessage.user("new request")) { snapshot, _, publish ->
            val tool = snapshot.currentMessages.first().getTools().single()
            assertTrue(tool.isExecuted)
            assertTrue(tool.output.toString().contains("tool_approval_cancelled_before_execution"))
            publish(snapshot.answer("new answer"))
        }
        runCurrent()
        assertFalse(original.currentMessages.first().getTools().single().isExecuted)
        assertEquals(original.id, lane.state.value.id)
        assertEquals(original.assistantId, lane.state.value.assistantId)
    }

    @Test fun `same input already visible from failed save is not duplicated`() = runTest {
        val input = UIMessage.user("accepted before a save error")
        val baseline = base().copy(messageNodes = listOf(input.toMessageNode()))
        val lane = HumanContinuationLane(baseline, backgroundScope, { true }, { fail(it.toString()) })
        lane.send(input) { c, _, p -> p(c.answer("reply")) }
        runCurrent()
        assertEquals(1, lane.state.value.currentMessages.count { it.id == input.id })
    }

    @Test fun `stop does not wait for an old job and next human turn still runs`() = runTest {
        val held = CompletableDeferred<Unit>()
        val lane = HumanContinuationLane(base(), backgroundScope, { false }, { fail(it.toString()) })
        lane.send(UIMessage.user("first")) { c, _, p -> withContext(NonCancellable) { held.await(); p(c.answer("late")) } }
        runCurrent()
        lane.stop()
        assertNull(lane.job.value)
        lane.send(UIMessage.user("second")) { c, _, p -> p(c.answer("awake")) }
        runCurrent()
        held.complete(Unit)
        runCurrent()
        assertEquals("awake", lane.state.value.currentMessages.last().toText())
    }

    @Test fun `retrying the latest failed human input does not duplicate it or wait for disk`() = runTest {
        val disk = CompletableDeferred<Boolean>()
        val input = UIMessage.user("try again")
        val original = base().copy(messageNodes = listOf(input.toMessageNode()))
        var requests = 0
        val lane = HumanContinuationLane(original, backgroundScope, { disk.await() }, { fail(it.toString()) })
        assertNull(lane.regenerate(input) { snapshot, _, publish ->
            requests++
            assertEquals(listOf(input), snapshot.currentMessages)
            publish(snapshot.answer("recovered"))
        })
        runCurrent()
        assertEquals(1, requests)
        assertEquals(1, lane.state.value.currentMessages.count { it.id == input.id })
        assertEquals("recovered", lane.state.value.currentMessages.last().toText())
        assertNull(lane.job.value)
        disk.complete(false)
        runCurrent()
    }

    @Test fun `retry selects a new reply branch and preserves the old reply and node`() = runTest {
        val input = UIMessage.user("question")
        val old = UIMessage.assistant("old partial reply")
        val original = base().copy(messageNodes = listOf(input.toMessageNode(), old.toMessageNode()),
            title = "keep title", workspaceCwd = "/workspace/existing")
        val reply = UIMessage.assistant("new reply")
        val lane = HumanContinuationLane(original, backgroundScope, { true }, { fail(it.toString()) })
        assertNull(lane.regenerate(old) { snapshot, _, publish ->
            assertEquals(listOf(input), snapshot.currentMessages)
            publish(snapshot.copy(messageNodes = snapshot.messageNodes +
                reply.copy(parts = listOf(UIMessagePart.Text("new"))).toMessageNode()))
            publish(snapshot.copy(messageNodes = snapshot.messageNodes + reply.toMessageNode()))
        })
        runCurrent()
        val result = lane.state.value
        assertEquals(original.id, result.id)
        assertEquals(original.assistantId, result.assistantId)
        assertEquals(original.title, result.title)
        assertEquals(original.workspaceCwd, result.workspaceCwd)
        assertEquals(original.messageNodes.last().id, result.messageNodes.last().id)
        assertEquals(listOf(old, reply), result.messageNodes.last().messages)
        assertEquals(1, result.messageNodes.last().selectIndex)
        assertEquals(reply, result.currentMessages.last())
    }

    @Test fun `retry failure before a delta preserves the selected original branch`() = runTest {
        val input = UIMessage.user("question")
        val old = UIMessage.assistant("old reply")
        val original = base().copy(messageNodes = listOf(input.toMessageNode(), old.toMessageNode()))
        var failures = 0
        val lane = HumanContinuationLane(original, backgroundScope, { true }, { failures++ })
        assertNull(lane.regenerate(old) { _, _, _ -> error("request rejected") })
        runCurrent()
        assertEquals(original, lane.state.value)
        assertEquals(1, failures)
        lane.send(UIMessage.user("new request")) { snapshot, _, publish -> publish(snapshot.answer("awake")) }
        runCurrent()
        assertEquals("awake", lane.state.value.currentMessages.last().toText())
    }

    @Test fun `tool-bearing retry is a local refusal and next human message still runs`() = runTest {
        val input = UIMessage.user("write a file")
        val pending = UIMessagePart.Tool("write-id", "write_file", "{}", approvalState = ToolApprovalState.Pending)
        val completed = pending.copy(output = listOf(UIMessagePart.Text("written")))
        val shell = UIMessagePart.ServerTool("server-id", "computer", status = ServerToolStatus.COMPLETED)
        val old = UIMessage.assistant("old reply")
        val variants = listOf(
            old.copy(parts = listOf(pending)),
            old.copy(parts = listOf(completed)),
            old.copy(parts = listOf(pending)).finishInterruptedHostTools(),
            old.copy(parts = listOf(shell)),
            old.copy(deletedToolRecords = listOf(DeletedToolRecord(completed, 0, old.createdAt))),
        )
        variants.forEach { reply ->
            val original = base().copy(messageNodes = listOf(input.toMessageNode(), reply.toMessageNode()))
            val lane = HumanContinuationLane(original, backgroundScope, { true }, { fail(it.toString()) })
            assertNotNull(lane.regenerate(input) { _, _, _ -> fail("old tools must not be replayed") })
            assertEquals(original, lane.state.value)
            assertNull(lane.job.value)
            lane.send(UIMessage.user("just talk")) { snapshot, _, publish -> publish(snapshot.answer("awake")) }
            runCurrent()
            assertEquals("awake", lane.state.value.currentMessages.last().toText())
            // The old receipt (including local undo data) is retained, never removed to bypass admission.
            val preserved = lane.state.value.messageNodes[1].currentMessage
            assertEquals(reply.deletedToolRecords, preserved.deletedToolRecords)
            assertEquals(reply.getTools().map { it.toolCallId }, preserved.getTools().map { it.toolCallId })
        }
    }

    @Test fun `an unselected branch tool receipt also prevents implicit retry`() {
        val input = UIMessage.user("question")
        val toolReply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("old-tool", "write_file", "{}", output = listOf(UIMessagePart.Text("done")))))
        val textReply = UIMessage.assistant("visible text")
        val original = base().copy(messageNodes = listOf(input.toMessageNode(),
            toolReply.toMessageNode().copy(messages = listOf(toolReply, textReply), selectIndex = 1)))
        assertTrue(prepareHumanContinuationRegeneration(original, textReply) is HumanContinuationRegeneration.Blocked)
    }

    @Test fun `refusing a tool retry does not cancel the active owner or block a fresh human send`() = runTest {
        val held = CompletableDeferred<Unit>()
        val input = UIMessage.user("perform an action")
        val pending = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("pending", "write_file", "{}")))
        val lane = HumanContinuationLane(base(), backgroundScope, { true }, { fail(it.toString()) })
        var owner = -1L
        val active = lane.send(input) { snapshot, currentOwner, publish ->
            owner = currentOwner
            publish(snapshot.copy(messageNodes = snapshot.messageNodes + pending.toMessageNode()))
            withContext(NonCancellable) { held.await(); publish(snapshot.answer("late old")) }
        }
        runCurrent()
        val before = lane.state.value
        assertNotNull(lane.regenerate(input) { _, _, _ -> fail("must not retry a tool turn") })
        assertSame(active, lane.job.value)
        assertTrue(active.isActive)
        assertTrue(lane.isCurrent(owner))
        assertEquals(before, lane.state.value)
        lane.send(UIMessage.user("human wake")) { snapshot, _, publish -> publish(snapshot.answer("awake")) }
        runCurrent()
        held.complete(Unit)
        runCurrent()
        assertEquals("awake", lane.state.value.currentMessages.last().toText())
        assertFalse(lane.state.value.currentMessages.any { it.toText() == "late old" })
    }

    @Test fun `older missing and complex reply retry cannot truncate history`() {
        val older = UIMessage.user("older")
        val input = UIMessage.user("latest")
        val reply = UIMessage.assistant("reply")
        val original = base().copy(messageNodes = listOf(older.toMessageNode(), input.toMessageNode(), reply.toMessageNode()))
        assertTrue(prepareHumanContinuationRegeneration(original, older) is HumanContinuationRegeneration.Blocked)
        assertTrue(prepareHumanContinuationRegeneration(original, UIMessage.user("missing")) is HumanContinuationRegeneration.Blocked)
        assertTrue(prepareHumanContinuationRegeneration(original.answer("additional reply"), reply) is HumanContinuationRegeneration.Blocked)
        val voice = reply.copy(orbisVoiceCallId = "ended-call")
        assertTrue(prepareHumanContinuationRegeneration(original.copy(messageNodes = listOf(input.toMessageNode(), voice.toMessageNode())), voice)
            is HumanContinuationRegeneration.Blocked)
    }

    @Test fun `retry fences late old producer while preserving its published partial branch`() = runTest {
        val held = CompletableDeferred<Unit>()
        val lane = HumanContinuationLane(base(), backgroundScope, { true }, { fail(it.toString()) })
        val input = UIMessage.user("question")
        val old = UIMessage.assistant("old partial")
        lane.send(input) { snapshot, _, publish ->
            publish(snapshot.copy(messageNodes = snapshot.messageNodes + old.toMessageNode()))
            withContext(NonCancellable) { held.await(); publish(snapshot.answer("late old reply")) }
        }
        runCurrent()
        assertNull(lane.regenerate(input) { snapshot, _, publish -> publish(snapshot.answer("retry reply")) })
        runCurrent()
        held.complete(Unit)
        runCurrent()
        assertEquals("retry reply", lane.state.value.currentMessages.last().toText())
        assertTrue(lane.state.value.messageNodes.last().messages.contains(old))
        assertFalse(lane.state.value.messageNodes.flatMap { it.messages }.any { it.toText() == "late old reply" })
        assertNull(lane.job.value)
    }

    @Test fun `new human message does not wait for retry or disk and late retry cannot overwrite it`() = runTest {
        val held = CompletableDeferred<Unit>()
        val disk = CompletableDeferred<Boolean>()
        val input = UIMessage.user("question")
        val old = UIMessage.assistant("original")
        val original = base().copy(messageNodes = listOf(input.toMessageNode(), old.toMessageNode()))
        val lane = HumanContinuationLane(original, backgroundScope, { disk.await() }, { fail(it.toString()) })
        assertNull(lane.regenerate(old) { snapshot, _, publish ->
            withContext(NonCancellable) { held.await(); publish(snapshot.answer("late retry")) }
        })
        runCurrent()
        lane.send(UIMessage.user("human wake")) { snapshot, _, publish -> publish(snapshot.answer("new answer")) }
        runCurrent()
        assertEquals("new answer", lane.state.value.currentMessages.last().toText())
        held.complete(Unit)
        disk.complete(false)
        runCurrent()
        assertEquals("new answer", lane.state.value.currentMessages.last().toText())
        assertEquals(old, lane.state.value.messageNodes[1].currentMessage)
        assertFalse(lane.state.value.currentMessages.any { it.toText() == "late retry" })
        assertNull(lane.job.value)
    }

    @Test fun `regeneration merge rejects old reply identity reuse and preserves historical prefix`() {
        val input = UIMessage.user("question")
        val old = UIMessage.assistant("original")
        val original = base().copy(messageNodes = listOf(input.toMessageNode(), old.toMessageNode()))
        val plan = prepareHumanContinuationRegeneration(original, old) as HumanContinuationRegeneration.Ready
        assertEquals(original, plan.merge(plan.request))
        assertThrows(IllegalArgumentException::class.java) { plan.merge(original) }
        assertThrows(IllegalArgumentException::class.java) { plan.merge(plan.request.copy(assistantId = Uuid.random())) }
        val transformedPrefix = input.copy(parts = listOf(UIMessagePart.Text("must not overwrite original input")))
        val generated = plan.request.copy(messageNodes = listOf(transformedPrefix.toMessageNode())).answer("new")
        assertEquals(input, plan.merge(generated).currentMessages.first())
        assertEquals(old, original.currentMessages.last())
    }

    @Test fun `a regeneration button is not fresh permission for new local tool side effects`() {
        val grant = HostToolApproval("write", "revision", "write")
        val write = Tool("write_file", "write", hostApproval = grant, execute = { emptyList() })
        val read = Tool("read_file", "read", execute = { emptyList() })
        val guarded = reviewUncertainContinuationTools(listOf(write, read), emptyList(), requireFreshApproval = true)
        assertTrue(guarded.all { it.needsApproval(JsonNull) })
        assertSame(grant, guarded.first().hostApproval)
        assertSame(write.execute, guarded.first().execute)
        assertFalse(write.needsApproval(JsonNull))
    }
}
