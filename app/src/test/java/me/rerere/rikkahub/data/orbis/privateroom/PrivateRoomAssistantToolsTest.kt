package me.rerere.rikkahub.data.orbis.privateroom

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.privacy.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class PrivateRoomAssistantToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owners = mutableSetOf("owner-a", "owner-b")
    private val openedOwners = mutableListOf<String>()
    private val protector = object : PrivateVaultKeyProtector {
        private val keys = mutableMapOf<String, ByteArray>()
        override fun wrap(vaultId: String, dataKey: ByteArray): ByteArray {
            keys[vaultId] = dataKey.copyOf()
            return byteArrayOf(1)
        }
        override fun unwrap(vaultId: String, wrappedKey: ByteArray) =
            keys[vaultId]?.copyOf() ?: throw PrivateVaultException("device_key_unavailable")
    }
    private fun repository(owner: String = "owner-a") = PrivateVaultRepository(temporary.root, owner, protector)
    private fun ready(owner: String = "owner-a"): PrivateVaultRepository = repository(owner).apply {
        create(); confirmRecoverySaved(); setEnabled(true)
    }
    private fun tools(owner: String = "owner-a") = buildPrivateRoomAssistantTools(owner,
        assistantExists = { owner in owners }, openRepository = { openedOwners += it; repository(it) })
    private suspend fun invoke(tools: List<Tool>, suffix: String, input: JsonObject = JsonObject(emptyMap())): JsonObject {
        val output = tools.single { it.name == "orbis_private_room_$suffix" }.execute(input)
        assertEquals(1, output.size)
        return Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
    }
    private suspend fun write(tools: List<Tool>, title: String = "PRIVATE_TITLE_A", body: String = "PRIVATE_BODY_A"): String =
        invoke(tools, "write", buildJsonObject { put("title", title); put("body", body) }).getValue("record_id").jsonPrimitive.content
    private fun JsonObject.reason() = getValue("reason_code").jsonPrimitive.content

    @Test fun guidanceKeepsRealHistoryAndPublicRepliesWithoutClaimingPrivateTextIsPublic() {
        val prompt = tools().single { it.name == "orbis_private_room_visit" }.systemPrompt(Model(), emptyList())
        assertTrue(prompt.contains("正常给人的Text正文仍会显示"))
        assertTrue(prompt.contains("调用之前的Reasoning仍可见"))
        assertTrue(prompt.contains("新一轮没有调用隐私室时，Reasoning照常显示"))
        assertTrue(prompt.contains("普通工具记录不因同轮使用隐私室而隐藏"))
        assertTrue(prompt.contains("真实的工具记录"))
        assertTrue(prompt.contains("只据实际工具回执报告结果"))
        assertTrue(prompt.contains("不要因工具隐藏而省略正常回复"))
        assertTrue(prompt.contains("不要在其中复述私密标题、正文"))
        assertTrue(prompt.contains("不因前端隐藏而要求每轮重新读取"))
        assertTrue(openedOwners.isEmpty())
    }

    @Test fun visitOnlyChecksTheBoundRoomWithoutStartingASessionOrModel() = runBlocking {
        val repo = ready()
        val before = repo.auditEvents()
        val reply = invoke(tools(), "visit")
        assertEquals("ready", reply.getValue("status").jsonPrimitive.content)
        assertEquals("current_assistant", reply.getValue("mode").jsonPrimitive.content)
        assertFalse(reply.getValue("private_content_returned").jsonPrimitive.boolean)
        assertEquals(before, repo.auditEvents())
        assertEquals(listOf("owner-a"), openedOwners)
    }

    @Test fun writeReturnsOnlyAnIdWhileReadAndListRemainAvailableToTheSameAi() = runBlocking {
        val repo = ready()
        val tools = tools()
        val reply = invoke(tools, "write", buildJsonObject { put("title", "PRIVATE_TITLE_A"); put("body", "PRIVATE_BODY_A") })
        assertEquals(setOf("status", "record_id", "private_content_returned"), reply.keys)
        assertFalse(reply.toString().contains("PRIVATE"))
        val id = reply.getValue("record_id").jsonPrimitive.content
        val read = invoke(tools, "read", buildJsonObject { put("record_id", id) })
        assertEquals("PRIVATE_BODY_A", read.getValue("record").jsonObject.getValue("body").jsonPrimitive.content)
        val list = invoke(tools, "list")
        assertEquals(id, list.getValue("records").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(1, repo.status().recordCount)
    }

    @Test fun explicitUpdatePreservesRecordIdWithoutEchoingEitherVersion() = runBlocking {
        val repo = ready()
        val tools = tools()
        val id = write(tools)
        val updated = invoke(tools, "write", buildJsonObject {
            put("record_id", id); put("title", "NEW_PRIVATE_TITLE"); put("body", "NEW_PRIVATE_BODY")
        })
        assertEquals(id, updated.getValue("record_id").jsonPrimitive.content)
        assertFalse(updated.toString().contains("PRIVATE"))
        assertEquals(1, repo.status().recordCount)
        assertEquals("NEW_PRIVATE_BODY", invoke(tools, "read", buildJsonObject { put("record_id", id) })
            .getValue("record").jsonObject.getValue("body").jsonPrimitive.content)
    }

    @Test fun ownerParametersAndOtherOwnersRecordIdsCannotSelectAnotherRoom() = runBlocking {
        val a = ready()
        val b = ready("owner-b")
        val foreign = b.openAiSession("synthetic-b").use { it.writeRecord("PRIVATE_TITLE_B", "PRIVATE_BODY_B") }
        val beforeB = b.auditEvents()
        val tools = tools()
        assertEquals("invalid_arguments", invoke(tools, "list", buildJsonObject { put("owner_id", "owner-b") }).reason())
        assertEquals("invalid_arguments", invoke(tools, "write", buildJsonObject {
            put("assistant_id", "owner-b"); put("title", "bad"); put("body", "bad")
        }).reason())
        val read = invoke(tools, "read", buildJsonObject { put("record_id", foreign.id) })
        assertEquals("record_missing", read.reason())
        assertFalse(read.toString().contains("PRIVATE"))
        assertTrue(openedOwners.all { it == "owner-a" })
        assertEquals(beforeB, b.auditEvents())
        assertEquals(0, a.status().recordCount)
    }

    @Test fun missingOwnerAndAbsentRoomNeverCreateDirectoriesOrOpenAnotherOwner() = runBlocking {
        val owner = "missing-owner"
        assertEquals("assistant_unavailable", invoke(tools(owner), "visit").reason())
        assertTrue(openedOwners.isEmpty())
        assertEquals("room_not_created", invoke(tools(), "write", buildJsonObject {
            put("title", "title"); put("body", "body")
        }).reason())
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun unconfirmedPausedAndDeletedOwnerAreCheckedAgainForEveryAction() = runBlocking {
        val repo = repository().apply { create() }
        val tools = tools()
        assertEquals("recovery_unconfirmed", invoke(tools, "visit").reason())
        repo.confirmRecoverySaved()
        assertEquals("room_paused", invoke(tools, "list").reason())
        repo.setEnabled(true)
        assertEquals("ready", invoke(tools, "visit").getValue("status").jsonPrimitive.content)
        repo.setEnabled(false)
        assertEquals("room_paused", invoke(tools, "write", buildJsonObject { put("title", "x"); put("body", "x") }).reason())
        owners.remove("owner-a")
        assertEquals("assistant_unavailable", invoke(tools, "visit").reason())
        assertEquals(0, repo.status().recordCount)
    }

    @Test fun ownerRemovedWhileOpeningRepositoryPreventsTheAction() = runBlocking {
        val repo = ready()
        val before = repo.auditEvents()
        val tools = buildPrivateRoomAssistantTools("owner-a", { "owner-a" in owners }, {
            owners.remove("owner-a"); repo
        })
        assertEquals("assistant_unavailable", invoke(tools, "write", buildJsonObject { put("title", "x"); put("body", "x") }).reason())
        assertEquals(before, repo.auditEvents())
        assertEquals(0, repo.status().recordCount)
    }

    @Test fun approvalRequiresAnExplicitNonemptyUniqueSubsetAndCannotAcceptStringTrue() = runBlocking {
        val repo = ready()
        val tools = tools()
        val first = write(tools)
        val second = write(tools, "PRIVATE_SECOND", "PRIVATE_SECOND_BODY")
        val request = repo.requestAccess("PRIVATE_PURPOSE")
        val missing = buildJsonObject { put("request_id", request.id); put("approved", true) }
        assertEquals("explicit_scope_required", invoke(tools, "decide", missing).reason())
        assertEquals("explicit_scope_required", invoke(tools, "decide", JsonObject(missing + ("record_ids" to JsonArray(emptyList())))).reason())
        assertEquals("invalid_scope", invoke(tools, "decide", JsonObject(missing + ("record_ids" to JsonArray(listOf(JsonPrimitive(first), JsonPrimitive(first)))))).reason())
        assertEquals("invalid_arguments", invoke(tools, "decide", JsonObject(missing + ("approved" to JsonPrimitive("true")))).reason())
        assertEquals("outside_scope", invoke(tools, "decide", JsonObject(missing + ("record_ids" to JsonArray(listOf(JsonPrimitive(UUID.randomUUID().toString())))))).reason())
        assertFalse(repo.isAccessGranted(request.id))
        val approved = invoke(tools, "decide", JsonObject(missing + ("record_ids" to JsonArray(listOf(JsonPrimitive(first))))))
        assertEquals("approved", approved.getValue("status").jsonPrimitive.content)
        assertFalse(approved.toString().contains("PRIVATE"))
        assertEquals(listOf(first), repo.listGranted(request.id).map { it.id })
        assertFalse(repo.listGranted(request.id).any { it.id == second })
        assertEquals("revoked", invoke(tools, "revoke", buildJsonObject { put("request_id", request.id) }).getValue("status").jsonPrimitive.content)
        assertFalse(repo.isAccessGranted(request.id))
    }

    @Test fun requestsExposeOnlyBoundPendingRequestsAndDenialCannotCarryARecordScope() = runBlocking {
        val repo = ready()
        val tools = tools()
        val id = write(tools)
        val request = repo.requestAccess("PRIVATE_PURPOSE")
        val pending = invoke(tools, "requests").getValue("requests").jsonArray.single().jsonObject
        assertEquals(request.id, pending.getValue("id").jsonPrimitive.content)
        assertEquals("PRIVATE_PURPOSE", pending.getValue("purpose").jsonPrimitive.content)
        assertEquals("invalid_scope", invoke(tools, "decide", buildJsonObject {
            put("request_id", request.id); put("approved", false); put("record_ids", JsonArray(listOf(JsonPrimitive(id))))
        }).reason())
        val denied = invoke(tools, "decide", buildJsonObject { put("request_id", request.id); put("approved", false) })
        assertEquals("denied", denied.getValue("status").jsonPrimitive.content)
        assertFalse(denied.toString().contains("PRIVATE"))
        assertTrue(invoke(tools, "requests").getValue("requests").jsonArray.isEmpty())
        assertFalse(repo.isAccessGranted(request.id))
    }

    @Test fun consultationHasNoRoomToolsAndDoesNotReadStorage() {
        assertTrue(buildPrivateRoomAssistantTools("owner-a", { error("must not inspect") },
            { error("must not open") }, consultationReferenceOnly = true).isEmpty())
    }

    @Test fun fixedFailureReceiptsNeverReflectExceptionMessagesOrUnknownReasonCodes() = runBlocking {
        val secret = "PRIVATE_CONTENT_TOKEN_https://private.invalid/?key=secret"
        val tools = buildPrivateRoomAssistantTools("owner-a", { true }, { throw IllegalStateException(secret) })
        val reply = invoke(tools, "visit")
        assertEquals("operation_failed", reply.reason())
        assertFalse(reply.toString().contains(secret))
        assertFalse(privateAssistantToolFailure(secret).toString().contains(secret))
        assertFalse(reply.getValue("private_content_returned").jsonPrimitive.boolean)
        assertFalse(reply.getValue("automatic_retry").jsonPrimitive.boolean)
    }

    @Test fun cancellationPropagatesWithoutRetryOrPretendingSuccess() = runBlocking {
        var attempts = 0
        val tools = buildPrivateRoomAssistantTools("owner-a", { true }, { attempts++; throw CancellationException() })
        try { invoke(tools, "visit"); fail("must cancel") } catch (_: CancellationException) { }
        assertEquals(1, attempts)
    }
}
