package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.group.OrbisGroupReadException
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisGroupReadToolsTest {
    private val groupId = Uuid.random().toString()
    private class Fixture {
        var lists = 0
        var reads = 0
        var listedLimit = 0
        var listedAfter: String? = null
        var readArguments: List<Any?> = emptyList()
        var failure: Exception? = null
        val tools = createOrbisGroupReadTools(
            listGroups = { limit, after ->
                lists++; listedLimit = limit; listedAfter = after
                failure?.let { throw it }
                buildJsonObject { put("ok", true) }
            },
            readGroup = { id, limit, before, own ->
                reads++; readArguments = listOf(id, limit, before, own)
                failure?.let { throw it }
                buildJsonObject { put("ok", true) }
            },
        )
        val list = tools.first { it.name == ORBIS_GROUP_LIST_TOOL }
        val read = tools.first { it.name == ORBIS_GROUP_READ_TOOL }
    }
    private suspend fun Tool.call(args: JsonElement): JsonObject =
        Json.parseToJsonElement((execute(args).single() as UIMessagePart.Text).text).jsonObject
    private fun args(vararg entries: Pair<String, JsonElement>) = JsonObject(mapOf(*entries))

    @Test fun catalogueConstructionDoesNotReadOrInjectAnything() {
        val fixture = Fixture()
        assertEquals(listOf(ORBIS_GROUP_LIST_TOOL, ORBIS_GROUP_READ_TOOL), fixture.tools.map { it.name })
        assertEquals(0, fixture.lists); assertEquals(0, fixture.reads)
    }

    @Test fun defaultsAndCursorsArePassedExactly() = runTest {
        val fixture = Fixture()
        fixture.list.call(buildJsonObject {})
        assertEquals(10, fixture.listedLimit); assertNull(fixture.listedAfter)
        fixture.read.call(args("group_id" to JsonPrimitive(groupId)))
        assertEquals(listOf(groupId, 20, null, false), fixture.readArguments)
        fixture.read.call(args("group_id" to JsonPrimitive(groupId), "limit" to JsonPrimitive(50), "before_sequence" to JsonPrimitive(71), "only_own" to JsonPrimitive(true)))
        assertEquals(listOf(groupId, 50, 71L, true), fixture.readArguments)
        fixture.list.call(args("limit" to JsonPrimitive(20), "after_group_id" to JsonPrimitive(groupId)))
        assertEquals(20, fixture.listedLimit); assertEquals(groupId, fixture.listedAfter)
    }

    @Test fun callerCannotSelectAnotherAssistantProviderOrPrivateConversation() = runTest {
        for (key in listOf("assistant_id", "member_id", "provider_id", "conversation_id", "token", "include_private", "write_st")) {
            val fixture = Fixture()
            val result = fixture.read.call(args("group_id" to JsonPrimitive(groupId), key to JsonPrimitive("forged")))
            assertEquals("unknown_parameter", result["error"]!!.jsonPrimitive.content)
            assertEquals(0, fixture.reads)
        }
    }

    @Test fun invalidLimitsAreNotCoercedOrPassedToStorage() = runTest {
        for (bad in listOf(JsonPrimitive(0), JsonPrimitive(51), JsonPrimitive(-1), JsonPrimitive("20"), JsonPrimitive(1.2), JsonNull, JsonPrimitive(true))) {
            val fixture = Fixture()
            assertEquals(JsonPrimitive(false), fixture.read.call(args("group_id" to JsonPrimitive(groupId), "limit" to bad))["ok"])
            assertEquals(0, fixture.reads)
        }
        val fixture = Fixture()
        assertEquals(JsonPrimitive(false), fixture.list.call(args("limit" to JsonPrimitive(21)))["ok"])
        assertEquals(0, fixture.lists)
    }

    @Test fun invalidIdentifiersAreRejectedBeforeStorage() = runTest {
        for (bad in listOf(JsonNull, JsonPrimitive("../private.db"), JsonPrimitive(""), JsonPrimitive(10), JsonPrimitive("https://example.invalid"))) {
            val fixture = Fixture()
            assertEquals(JsonPrimitive(false), fixture.read.call(args("group_id" to bad))["ok"])
            assertEquals(0, fixture.reads)
        }
        val fixture = Fixture()
        assertEquals("invalid_group_id", fixture.read.call(buildJsonObject {})["error"]!!.jsonPrimitive.content)
    }

    @Test fun invalidCursorAndOwnFilterNeverReachStorage() = runTest {
        for (bad in listOf(JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive("12"), JsonPrimitive(1.5), JsonNull, JsonPrimitive("99999999999999999999999999999"))) {
            val fixture = Fixture()
            assertEquals("invalid_before_sequence", fixture.read.call(args("group_id" to JsonPrimitive(groupId), "before_sequence" to bad))["error"]!!.jsonPrimitive.content)
            assertEquals(0, fixture.reads)
        }
        for (bad in listOf(JsonPrimitive("true"), JsonPrimitive(1), JsonNull)) {
            val fixture = Fixture()
            assertEquals("invalid_only_own", fixture.read.call(args("group_id" to JsonPrimitive(groupId), "only_own" to bad))["error"]!!.jsonPrimitive.content)
            assertEquals(0, fixture.reads)
        }
    }

    @Test fun nonObjectArgumentsAreSafeFailures() = runTest {
        val fixture = Fixture()
        for (bad in listOf(JsonNull, JsonPrimitive("{}"), JsonArray(emptyList()))) {
            assertEquals("invalid_parameters", fixture.read.call(bad)["error"]!!.jsonPrimitive.content)
        }
        assertEquals(0, fixture.reads)
    }

    @Test fun genericErrorsDoNotLeakPathsKeysOrExceptionMessages() = runTest {
        val fixture = Fixture()
        fixture.failure = IllegalStateException("/private/config secret-api-key")
        val result = fixture.list.call(buildJsonObject {})
        assertEquals("group_records_unavailable", result["error"]!!.jsonPrimitive.content)
        assertFalse(result.toString().contains("secret-api-key"))
        assertEquals(JsonPrimitive(true), result["read_only"])
        assertEquals("none", result["instruction_authority"]!!.jsonPrimitive.content)
    }

    @Test fun safeMembershipFailuresRemainDistinctFromSuccess() = runTest {
        val fixture = Fixture()
        fixture.failure = OrbisGroupReadException("group_not_found_or_not_a_member")
        val result = fixture.read.call(args("group_id" to JsonPrimitive(groupId)))
        assertEquals("group_not_found_or_not_a_member", result["error"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), result["ok"])
    }

    @Test fun cancellationPropagatesRatherThanPretendingAReadSucceeded() = runTest {
        val fixture = Fixture()
        fixture.failure = CancellationException("cancel")
        try { fixture.list.call(buildJsonObject {}); fail("Expected cancellation") }
        catch (_: CancellationException) { }
    }
}
