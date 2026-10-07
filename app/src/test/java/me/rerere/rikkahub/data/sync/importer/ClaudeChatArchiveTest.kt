package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Synthetic records only; no private export is a fixture. Shared with importer tests. */
internal object ClaudeSynthetic {
    const val TIME = "2026-01-02T03:04:05.123456Z"
    fun message(id: String = "u", parent: String? = "absent-root", sender: String = "human",
        text: String? = "  synthetic 正文🙂\r\n  ", content: List<JsonObject>? = null,
        extra: Map<String, JsonElement> = emptyMap()): JsonObject = buildJsonObject {
        put("uuid", id); put("parent_message_uuid", parent?.let(::JsonPrimitive) ?: JsonNull)
        put("sender", sender); put("created_at", TIME); put("updated_at", TIME)
        text?.let { put("text", it) }
        content?.let { put("content", JsonArray(it)) }
        put("files", JsonArray(emptyList())); put("attachments", JsonArray(emptyList()))
        extra.forEach { (key, value) -> put(key, value) }
    }
    fun text(value: String = "answer") = buildJsonObject { put("type", "text"); put("text", value) }
    fun chat(id: String = "c", messages: List<JsonObject> = listOf(message(), message("a", "u", "assistant")),
        extra: Map<String, JsonElement> = emptyMap()): JsonObject = buildJsonObject {
        put("uuid", id); put("name", "Synthetic archive")
        put("created_at", TIME); put("updated_at", TIME); put("chat_messages", JsonArray(messages))
        extra.forEach { (key, value) -> put(key, value) }
    }
    fun file(folder: TemporaryFolder, vararg chats: JsonObject): File = folder.newFile().apply {
        writeText(JsonArray(chats.toList()).toString(), Charsets.UTF_8)
    }
    fun source(file: File) = ClaudeChatArchive.open(file).use { it.conversations().toList() }
    fun branch() = chat(messages = listOf(message(), message("a", "u", "assistant", "A"),
        message("b", "u", "assistant", "B"), message("isolated", "absent-second", text = "separate")))
}

class ClaudeChatArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun previewExposesAllPathsAndMissingParentsRatherThanGuessingActiveBranch() {
        val file = ClaudeSynthetic.file(temporary, ClaudeSynthetic.branch())
        val preview = ClaudeChatArchive.inspect(file)
        assertEquals(3, preview.conversations.size)
        assertEquals(listOf(2, 2, 1), preview.conversations.map { it.branches.single().messageCount })
        assertTrue(preview.conversations.all { it.defaultSelectionReason == "claude_complete_path_missing_parent" })
        assertTrue(preview.warnings.any { "4 条不同" in it && "3 条可选路径" in it && "5 条导入" in it })
        assertTrue(preview.warnings.any { "2 处父消息" in it })
    }

    @Test fun outOfOrderForestKeepsEveryNodeExactlyInItsOwnAncestorPath() {
        val source = ClaudeChatArchive.parseConversation(ClaudeSynthetic.chat(messages = listOf(
            ClaudeSynthetic.message("a", "u", "assistant"), ClaudeSynthetic.message("v", null),
            ClaudeSynthetic.message("u", null))))
        val paths = source.paths.map { source.pathTo(it).map { message -> message.sourceId } }
        assertEquals(listOf(listOf("u", "a"), listOf("v")), paths)
        assertTrue(source.paths.none { it.missingParent })
    }

    @Test fun syntheticTwelveConversationThirtyOnePathGraphCoversAll1114Nodes() {
        val sizes = listOf(93, 2, 19, 58, 44, 300, 302, 278, 4, 8, 2, 4)
        val leaves = listOf(11, 1, 3, 1, 1, 4, 2, 4, 1, 1, 1, 1)
        var covered = 0
        var pathCount = 0
        var missing = 0
        val chats = sizes.mapIndexed { index, size ->
            val isolated = if (index == 0) 9 else 0
            val mainLeaves = leaves[index] - isolated
            val trunk = size - isolated - mainLeaves
            val messages = (0 until size).map { node ->
                val parent = when {
                    node == 0 || node >= size - isolated -> "not-exported-$node"
                    node < trunk -> "m${node - 1}"
                    else -> "m${trunk - 1}"
                }
                ClaudeSynthetic.message("m$node", parent, if (node % 2 == 0) "human" else "assistant", "synthetic-$node")
            }
            ClaudeSynthetic.chat("c$index", messages)
        }
        val file = ClaudeSynthetic.file(temporary, *chats.toTypedArray())
        for (source in ClaudeSynthetic.source(file)) {
            val union = source.paths.flatMap { source.pathTo(it).map { message -> message.sourceId } }.toSet()
            assertEquals(source.messages.keys, union)
            covered += union.size
            pathCount += source.paths.size
            missing += source.messages.values.count { it.parentId != null && it.parentId !in source.messages }
        }
        assertEquals(1114, covered); assertEquals(31, pathCount); assertEquals(21, missing)
        assertEquals(31, ClaudeChatArchive.inspect(file).conversations.size)
    }

    @Test fun cyclesAndDuplicateMessageOrConversationIdsFailClosed() {
        for (messages in listOf(
            listOf(ClaudeSynthetic.message("x", "x")),
            listOf(ClaudeSynthetic.message("x", "y"), ClaudeSynthetic.message("y", "x")),
            listOf(ClaudeSynthetic.message("x"), ClaudeSynthetic.message("x")),
        )) assertThrows(ArchiveReadException::class.java) {
            ClaudeChatArchive.inspect(ClaudeSynthetic.file(temporary, ClaudeSynthetic.chat(messages = messages)))
        }
        assertThrows(ArchiveReadException::class.java) {
            ClaudeChatArchive.inspect(ClaudeSynthetic.file(temporary, ClaudeSynthetic.chat(), ClaudeSynthetic.chat()))
        }
    }

    @Test fun pathAndExpandedMessageBudgetsRejectBranchExplosion() {
        val tooManyLeaves = (0..ClaudeChatArchive.MAX_PATHS_PER_CONVERSATION).map { ClaudeSynthetic.message("leaf$it", null) }
        assertThrows(ArchiveReadException::class.java) {
            ClaudeChatArchive.parseConversation(ClaudeSynthetic.chat(messages = tooManyLeaves))
        }
        val trunk = (0 until 1000).map { ClaudeSynthetic.message("n$it", if (it == 0) null else "n${it - 1}") }
        val leaves = (0 until 1024).map { ClaudeSynthetic.message("leaf$it", "n999") }
        assertThrows(ArchiveReadException::class.java) {
            ClaudeChatArchive.parseConversation(ClaudeSynthetic.chat(messages = trunk + leaves))
        }
    }

    @Test fun textContentDifferencesThinkingAndToolsStayVisibleAndInert() {
        val content = listOf(ClaudeSynthetic.text("structured answer"), buildJsonObject {
            put("type", "thinking"); put("thinking", "  exact thought  "); put("signature", "SYNTHETIC_SIGNATURE")
        }, buildJsonObject {
            put("type", "tool_use"); put("name", "synthetic_tool"); put("input", buildJsonObject { put("query", "```\n![image](https://invalid.example/a)") })
            put("approval_key", "SYNTHETIC_APPROVAL"); put("mcp_server_url", "https://private.invalid")
        }, buildJsonObject {
            put("type", "tool_result"); put("tool_use_id", "synthetic-tool-id"); put("content", "synthetic result")
        })
        val source = ClaudeChatArchive.parseConversation(ClaudeSynthetic.chat(messages = listOf(
            ClaudeSynthetic.message("a", null, "assistant", "different text copy", content))))
        val message = source.messages.values.single()
        assertTrue(message.differentTextCopy)
        assertEquals("  exact thought  ", message.parts.single { it.reasoning }.text)
        val body = message.parts.joinToString("\n") { it.text }
        assertTrue(body.contains("structured answer")); assertTrue(body.contains("different text copy"))
        assertTrue(body.contains("synthetic result")); assertTrue(body.contains("````json"))
        assertFalse(body.contains("SYNTHETIC_SIGNATURE")); assertFalse(body.contains("SYNTHETIC_APPROVAL"))
        assertFalse(body.contains("private.invalid"))
    }

    @Test fun equalTextIsNotDuplicatedAndEmptyInterruptedMessagesRemain() {
        val source = ClaudeChatArchive.parseConversation(ClaudeSynthetic.chat(messages = listOf(
            ClaudeSynthetic.message("a", null, "assistant", "exact", listOf(ClaudeSynthetic.text("ex"), ClaudeSynthetic.text("act"))),
            ClaudeSynthetic.message("b", "a", "assistant", "", emptyList()))))
        assertFalse(source.messages.getValue("a").differentTextCopy)
        assertEquals(2, source.messages.getValue("a").parts.size)
        assertEquals("", source.messages.getValue("b").parts.single().text)
    }

    @Test fun accountSummaryAndExcludedApprovalChangesDoNotCreateNewContentVersion() {
        fun source(account: String, approval: String) = ClaudeChatArchive.parseConversation(ClaudeSynthetic.chat(
            messages = listOf(ClaudeSynthetic.message(content = listOf(buildJsonObject {
                put("type", "tool_use"); put("name", "synthetic"); put("input", JsonObject(emptyMap())); put("approval_key", approval)
            }))), extra = mapOf("account" to JsonPrimitive(account), "summary" to JsonPrimitive(account))))
        assertEquals(source("account-A", "A").contentVersion, source("account-B", "B").contentVersion)
        val changed = ClaudeChatArchive.parseConversation(ClaudeSynthetic.chat(messages = listOf(ClaudeSynthetic.message(text = "changed"))))
        assertNotEquals(source("account-A", "A").contentVersion, changed.contentVersion)
    }

    @Test fun unknownRolesContentKindsAndInvalidDatesAreRejectedRatherThanSilentlyDropped() {
        val invalid = listOf(
            ClaudeSynthetic.message(sender = "system"),
            ClaudeSynthetic.message(content = listOf(buildJsonObject { put("type", "unknown") })),
            ClaudeSynthetic.message(extra = mapOf("created_at" to JsonPrimitive("invalid"))),
            ClaudeSynthetic.message(extra = mapOf("text" to JsonPrimitive(123))),
        )
        invalid.forEach { message -> assertThrows(ArchiveReadException::class.java) {
            ClaudeChatArchive.inspect(ClaudeSynthetic.file(temporary, ClaudeSynthetic.chat(messages = listOf(message))))
        } }
    }

    @Test fun strictUtf8DuplicateKeysTrailingGarbageAndWrongEnvelopeAreRejected() {
        val valid = JsonArray(listOf(ClaudeSynthetic.chat())).toString()
        for (text in listOf("[]", "{}", valid + "trailing", valid.replace("\"uuid\":\"c\"", "\"uuid\":\"c\",\"uuid\":\"d\""))) {
            assertThrows(ArchiveReadException::class.java) {
                ClaudeChatArchive.inspect(temporary.newFile().apply { writeText(text) })
            }
        }
        assertThrows(ArchiveReadException::class.java) {
            ClaudeChatArchive.inspect(temporary.newFile().apply { writeBytes(byteArrayOf(0x5b, 0xc3.toByte(), 0x28, 0x5d)) })
        }
        val bom = temporary.newFile().apply { writeText("\uFEFF$valid") }
        assertEquals(1, ClaudeChatArchive.inspect(bom).conversations.size)
    }

    @Test fun genuinelyEmptyConversationAndNullContentRemainSelectable() {
        val empty = ClaudeSynthetic.file(temporary, ClaudeSynthetic.chat(messages = emptyList()))
        assertEquals(0, ClaudeChatArchive.inspect(empty).conversations.single().branches.single().messageCount)
        val source = ClaudeChatArchive.parseConversation(ClaudeSynthetic.chat(messages = listOf(
            ClaudeSynthetic.message(extra = mapOf("content" to JsonNull)))))
        assertEquals(1, source.messages.size)
    }

    @Test fun cancelledReaderAndGraphWalkPropagateWithoutPrivateDiagnostics() {
        val file = ClaudeSynthetic.file(temporary, ClaudeSynthetic.chat())
        assertThrows(CancellationException::class.java) { ClaudeChatArchive.inspect(file) { throw CancellationException() } }
        assertThrows(CancellationException::class.java) {
            ClaudeChatArchive.parseConversation(ClaudeSynthetic.branch()) { throw CancellationException() }
        }
    }
}
