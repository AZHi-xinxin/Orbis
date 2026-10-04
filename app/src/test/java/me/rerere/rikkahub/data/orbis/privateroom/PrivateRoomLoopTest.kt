package me.rerere.rikkahub.data.orbis.privateroom

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class PrivateRoomLoopTest {
    private fun call(id: String = "one", name: String = "private_write", input: String = "{}") =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(id, name, input)))

    @Test fun finalPrivateTextIsNotReturnedOrStoredInPublicReceipt() = runTest {
        var calls = 0
        val loop = PrivateRoomLoop { _, _ -> calls++; UIMessage.assistant("PRIVATE_SENTINEL_DO_NOT_PUBLISH") }
        val result = loop.run("persona", "public", emptyList()) { true }
        assertEquals(PrivateRoomOutcome.COMPLETED, result)
        assertFalse(result.toString().contains("SENTINEL"))
        assertEquals(1, calls)
    }

    @Test fun toolsAndTheirOutputsRemainOnlyInIsolatedHistory() = runTest {
        var requests = 0
        var writes = 0
        val loop = PrivateRoomLoop { history, _ ->
            if (requests++ == 0) call() else {
                assertEquals("PRIVATE_RESULT", (history.last().getTools().single().output.single() as UIMessagePart.Text).text)
                UIMessage.assistant("done privately")
            }
        }
        val tools = listOf(Tool("private_write", "test", execute = { writes++; listOf(UIMessagePart.Text("PRIVATE_RESULT")) }))
        assertEquals(PrivateRoomOutcome.COMPLETED, loop.run("", "", tools) { true })
        assertEquals(1, writes)
        assertEquals(2, requests)
    }

    @Test fun duplicateToolIdNeverReplaysWrite() = runTest {
        var writes = 0
        val loop = PrivateRoomLoop { _, _ -> call() }
        val tools = listOf(Tool("private_write", "", execute = { writes++; listOf(UIMessagePart.Text("ok")) }))
        assertEquals(PrivateRoomOutcome.INCOMPLETE, loop.run("", "", tools) { true })
        assertEquals(1, writes)
    }

    @Test fun unknownToolCannotEscapeIntoNormalToolFactory() = runTest {
        var writes = 0
        val loop = PrivateRoomLoop { _, _ -> call(name = "workspace_shell") }
        val tools = listOf(Tool("private_write", "", execute = { writes++; emptyList() }))
        assertEquals(PrivateRoomOutcome.INCOMPLETE, loop.run("", "", tools) { true })
        assertEquals(0, writes)
    }

    @Test fun revokedDuringNetworkWaitCannotExecuteReturnedApproval() = runTest {
        var allowed = true
        var writes = 0
        val loop = PrivateRoomLoop { _, _ -> allowed = false; call() }
        val tools = listOf(Tool("private_write", "", execute = { writes++; emptyList() }))
        assertEquals(PrivateRoomOutcome.UNAVAILABLE, loop.run("", "", tools) { allowed })
        assertEquals(0, writes)
    }

    @Test fun cancellationIsNotConvertedIntoACompletedVisit() = runTest {
        val loop = PrivateRoomLoop { _, _ -> throw CancellationException("cancel") }
        try { loop.run("", "", emptyList()) { true }; fail("must propagate cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun providerErrorBodyNeverEscapes() = runTest {
        val loop = PrivateRoomLoop { _, _ -> error("PRIVATE_BODY_TOKEN") }
        assertEquals(PrivateRoomOutcome.INCOMPLETE, loop.run("", "", emptyList()) { true })
    }

    @Test fun timeoutIsBoundedAndDoesNotAutomaticallyRestart() = runTest {
        var calls = 0
        val loop = PrivateRoomLoop { _, _ -> calls++; delay(200_000); UIMessage.assistant("late") }
        assertEquals(PrivateRoomOutcome.INCOMPLETE, loop.run("", "", emptyList()) { true })
        assertEquals(1, calls)
    }

    @Test fun rejectsInvalidOrDeepArgumentsBeforeExecution() {
        assertNull(parsePrivateRoomArguments("[]"))
        assertNull(parsePrivateRoomArguments("not JSON"))
        assertNull(parsePrivateRoomArguments("{\"x\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}"))
        assertNotNull(parsePrivateRoomArguments("{\"text\":\"[[[[]]]]\"}"))
        assertTrue(privateJsonNestingAllowed("{\"text\":" + JsonPrimitive("quote\"[\\").toString() + "}"))
    }
}
