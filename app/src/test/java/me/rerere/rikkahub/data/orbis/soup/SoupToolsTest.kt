package me.rerere.rikkahub.data.orbis.soup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class SoupToolsTest {
    private suspend fun suspendCall(tool: Tool, raw: String = "{}") =
        Json.parseToJsonElement((tool.execute(Json.parseToJsonElement(raw)).single() as UIMessagePart.Text).text).jsonObject
    private fun call(tool: Tool, raw: String = "{}") = runBlocking { suspendCall(tool, raw) }
    private val id = "00000000-0000-0000-0000-000000000001"
    @Test fun catalogueHasNoFilePrivatePuzzleStartOrResetToolAndAllChangesRequireApproval() {
        val tools = createSoupTools { _, _ -> buildJsonObject {} }
        assertEquals(listOf("orbis_soup_current", "orbis_soup_ask", "orbis_soup_hint", "orbis_soup_submit", "orbis_soup_reveal"), tools.map { it.name })
        assertFalse(tools.first().needsApproval(buildJsonObject {})); assertNull(tools.first().hostApproval)
        tools.drop(1).forEach { assertTrue(it.needsApproval(buildJsonObject {})); assertNotNull(it.hostApproval) }
    }
    @Test fun oneMutatingActionPerTurnDoesNotPreventReadOnlyDiscussion() {
        var calls = 0
        val tools = createSoupTools { _, _ -> calls++; buildJsonObject {} }
        assertTrue(call(tools[1], """{"session_id":"$id","text":"成立吗？"}""")["ok"]!!.jsonPrimitive.boolean)
        assertFalse(call(tools[2], """{"session_id":"$id"}""")["ok"]!!.jsonPrimitive.boolean)
        assertTrue(call(tools[0])["ok"]!!.jsonPrimitive.boolean)
        assertEquals(2, calls)
    }
    @Test fun wrongTurnPrewriteRejectionReturnsPermitForADifferentApprovedStep() {
        val repository = SoupRepository(SoupMemoryStorage(), { 1000L })
        val session = repository.start("soup_sample_001", SoupMode.NORMAL)
        var calls = 0
        val tools = createSoupTools { action, args ->
            calls++
            repository.propose(
                requireNotNull(args.sessionId),
                if (action == "ask") SoupAction.ASK else SoupAction.SUBMIT,
                requireNotNull(args.text),
            )
            buildJsonObject {}
        }
        val rejected = call(tools[1], """{"session_id":"${session.id}","text":"成立吗？"}""")
        assertFalse(rejected["ok"]!!.jsonPrimitive.boolean)
        assertEquals("operation_not_confirmed", rejected["error"]!!.jsonPrimitive.content)
        assertTrue(rejected["note"]!!.jsonPrimitive.content.contains("轮到人类"))
        assertTrue(call(tools[3], """{"session_id":"${session.id}","text":"完整推理"}""")["ok"]!!.jsonPrimitive.boolean)
        assertEquals(2, calls)
        assertNotNull(repository.snapshot().active!!.proposal)
        assertTrue(repository.snapshot().active!!.attempts.isEmpty())
        assertEquals(6, repository.snapshot().active!!.remaining(SoupPlayer.HUMAN))
        assertEquals(6, repository.snapshot().active!!.remaining(SoupPlayer.TEAMMATE))
    }
    @Test fun sameMessageFromUntypedFailureCannotReturnMutationPermit() {
        var calls = 0
        val tools = createSoupTools { _, _ -> calls++; error("soup_other_turn") }
        assertFalse(call(tools[1], """{"session_id":"$id","text":"成立吗？"}""")["ok"]!!.jsonPrimitive.boolean)
        val second = call(tools[3], """{"session_id":"$id","text":"完整推理"}""")
        assertEquals("one_step_per_turn", second["error"]!!.jsonPrimitive.content)
        assertEquals(1, calls)
    }
    @Test fun cancellationKeepsMutationPermitConsumedWithoutAutomaticRetry() {
        var calls = 0
        val tools = createSoupTools { _, _ -> calls++; throw CancellationException("synthetic cancellation") }
        try {
            call(tools[1], """{"session_id":"$id","text":"成立吗？"}""")
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        val second = call(tools[3], """{"session_id":"$id","text":"完整推理"}""")
        assertEquals("one_step_per_turn", second["error"]!!.jsonPrimitive.content)
        assertEquals(1, calls)
    }
    @Test fun concurrentMutationCannotEnterWhileFirstStepIsRunning() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val tools = createSoupTools { _, _ ->
            calls++
            entered.complete(Unit)
            release.await()
            buildJsonObject {}
        }
        val first = async { suspendCall(tools[1], """{"session_id":"$id","text":"成立吗？"}""") }
        entered.await()
        val second = suspendCall(tools[3], """{"session_id":"$id","text":"完整推理"}""")
        assertEquals("one_step_per_turn", second["error"]!!.jsonPrimitive.content)
        assertEquals(1, calls)
        release.complete(Unit)
        assertTrue(first.await()["ok"]!!.jsonPrimitive.boolean)
    }
    @Test fun maliciousExtraFieldsCannotSelectHumanRoleReadSolutionOrChooseNetworkRoute() {
        var calls = 0
        val tools = createSoupTools { _, _ -> calls++; buildJsonObject {} }
        listOf("""{"session_id":"$id","text":"成立吗？","player":"HUMAN"}""", """{"session_id":"$id","text":"成立吗？","url":"https://memory.example"}""", """{"session_id":"../../private","text":"成立吗？"}""").forEach {
            assertFalse(call(tools[1], it)["ok"]!!.jsonPrimitive.boolean)
        }
        assertFalse(call(tools[0], """{"include_solution":true}""")["ok"]!!.jsonPrimitive.boolean)
        assertEquals(0, calls)
    }
    @Test fun transportErrorsNeverExposeCredentialsPrivatePathsOrHostPayloads() {
        val tools = createSoupTools { _, _ -> error("secret-key /private/path secret_solution") }
        val text = call(tools[0]).toString()
        assertFalse(text.contains("secret-key")); assertFalse(text.contains("/private/path")); assertFalse(text.contains("secret_solution"))
    }
}
