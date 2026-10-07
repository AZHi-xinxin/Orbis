package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/** All data is generated locally; no real export, account or attachment is a fixture. */
internal object ChatGPTSynthetic {
    const val TIME = 1_700_000_000.125
    fun content(text: String = "  synthetic 正文🙂\r\n  ") = buildJsonObject {
        put("content_type", "text"); put("parts", JsonArray(listOf(JsonPrimitive(text))))
    }
    fun message(id: String, role: String = "user", content: JsonObject = content(),
        extra: Map<String, JsonElement> = emptyMap()) = buildJsonObject {
        put("id", "message-$id"); put("author", buildJsonObject { put("role", role) })
        put("create_time", TIME); put("update_time", JsonNull); put("content", content)
        extra.forEach { (k, v) -> put(k, v) }
    }
    fun node(id: String, parent: String? = null, message: JsonObject? = message(id)) = buildJsonObject {
        put("id", id); put("parent", parent?.let(::JsonPrimitive) ?: JsonNull)
        put("message", message ?: JsonNull)
        // Intentionally no children or model_slug: recent exports can omit both.
    }
    fun chat(id: String = "c", nodes: List<JsonObject> = listOf(node("root", message = null),
        node("u", "root"), node("a", "u", message("a", "assistant"))), current: String? = "a",
        extra: Map<String, JsonElement> = emptyMap()) = buildJsonObject {
        put("id", id); put("title", "Synthetic chat"); put("create_time", TIME); put("update_time", TIME)
        put("current_node", current?.let(::JsonPrimitive) ?: JsonNull)
        put("mapping", JsonObject(nodes.associate { (it.getValue("id") as JsonPrimitive).content to it }))
        extra.forEach { (k, v) -> put(k, v) }
    }
    fun branch() = chat(nodes = listOf(node("b", "u", message("b", "assistant", content("B"))),
        node("root", message = null), node("u", "root"), node("a", "u", message("a", "assistant", content("A")))))
    fun file(folder: TemporaryFolder, vararg chats: JsonObject): File = folder.newFile().apply {
        writeText(JsonArray(chats.toList()).toString(), Charsets.UTF_8)
    }
    fun source(raw: JsonObject) = ChatGPTChatArchive.parseConversation(raw)
}

class ChatGPTChatArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun reject(raw: JsonObject) = assertThrows(ArchiveReadException::class.java) {
        ChatGPTChatArchive.inspect(ChatGPTSynthetic.file(temporary, raw))
    }

    @Test fun absentChildrenMetadataAndModelSlugStillPreserveBranchesAndCurrentPath() {
        val record = ChatGPTSynthetic.source(ChatGPTSynthetic.branch())
        val source = record.history
        assertEquals("a", record.currentLeafId)
        assertEquals(listOf(listOf("u", "a"), listOf("u", "b")), source.paths.map { source.pathTo(it).map { m -> m.sourceId } })
        val preview = ChatGPTChatArchive.inspect(ChatGPTSynthetic.file(temporary, ChatGPTSynthetic.branch()))
        assertEquals(2, preview.conversations.size)
        assertEquals("chatgpt_complete_path_current", preview.conversations.first().defaultSelectionReason)
        assertEquals(listOf(2, 2), preview.conversations.map { it.branches.single().messageCount })
    }

    @Test fun currentNonLeafIsAnExplicitPrefixNotGuessedLatestChild() {
        val record = ChatGPTSynthetic.source(ChatGPTSynthetic.chat(current = "u"))
        assertEquals(listOf(listOf("u"), listOf("u", "a")), record.history.paths.map { record.history.pathTo(it).map { m -> m.sourceId } })
    }

    @Test fun currentHiddenOrNullNodeResolvesOnlyItsVisibleAncestor() {
        val hidden = ChatGPTSynthetic.message("h", "assistant", extra = mapOf("metadata" to buildJsonObject {
            put("is_visually_hidden_from_conversation", true)
        }))
        for (message in listOf(null, hidden)) {
            val record = ChatGPTSynthetic.source(ChatGPTSynthetic.chat(nodes = listOf(
                ChatGPTSynthetic.node("u"), ChatGPTSynthetic.node("h", "u", message),
                ChatGPTSynthetic.node("a", "h", ChatGPTSynthetic.message("a", "assistant"))), current = "h"))
            assertEquals("u", record.currentLeafId)
            assertEquals(listOf(listOf("u"), listOf("u", "a")), record.history.paths.map { record.history.pathTo(it).map { m -> m.sourceId } })
        }
    }

    @Test fun separateBranchesWithNullIntermediateNodesNeverMixAnswers() {
        val record = ChatGPTSynthetic.source(ChatGPTSynthetic.chat(nodes = listOf(ChatGPTSynthetic.node("u"),
            ChatGPTSynthetic.node("hole-a", "u", null), ChatGPTSynthetic.node("hole-b", "u", null),
            ChatGPTSynthetic.node("a", "hole-a", ChatGPTSynthetic.message("a", "assistant")),
            ChatGPTSynthetic.node("b", "hole-b", ChatGPTSynthetic.message("b", "assistant")))))
        assertEquals(listOf(listOf("u", "a"), listOf("u", "b")), record.history.paths.map { record.history.pathTo(it).map { m -> m.sourceId } })
    }

    @Test fun noCurrentNodeLeavesEveryBranchAvailableWithoutTimestampGuessing() {
        val raw = JsonObject(ChatGPTSynthetic.branch() + ("current_node" to JsonNull))
        val source = ChatGPTSynthetic.source(raw)
        assertNull(source.currentLeafId)
        assertEquals(2, source.history.paths.size)
        assertTrue(ChatGPTChatArchive.inspect(ChatGPTSynthetic.file(temporary, raw)).conversations.none {
            it.defaultSelectionReason.endsWith("_current")
        })
    }

    @Test fun hiddenAndNullNodesBridgeWithoutBecomingSystemPrompts() {
        val hidden = ChatGPTSynthetic.message("h", "assistant", ChatGPTSynthetic.content("PRIVATE-HIDDEN"),
            mapOf("metadata" to buildJsonObject { put("is_visually_hidden_from_conversation", true) }))
        val raw = ChatGPTSynthetic.chat(nodes = listOf(ChatGPTSynthetic.node("root", message = null),
            ChatGPTSynthetic.node("s", "root", ChatGPTSynthetic.message("s", "system")),
            ChatGPTSynthetic.node("u", "s"), ChatGPTSynthetic.node("h", "u", hidden),
            ChatGPTSynthetic.node("a", "h", ChatGPTSynthetic.message("a", "assistant"))))
        val record = ChatGPTSynthetic.source(raw)
        assertEquals(2, record.excludedMessages)
        assertEquals(listOf("u", "a"), record.history.pathTo(record.history.paths.single()).map { it.sourceId })
        assertFalse(record.history.toString().contains("PRIVATE-HIDDEN"))
    }

    @Test fun conversationIdAliasAndEmptyOrSystemOnlyConversationsAreExplicit() {
        val raw = JsonObject(ChatGPTSynthetic.chat(nodes = emptyList(), current = null).minus("id") + ("conversation_id" to JsonPrimitive("alias")))
        val record = ChatGPTSynthetic.source(raw)
        assertEquals("alias", record.history.sourceId)
        assertEquals(0, record.history.paths.single().messageCount)
        val system = ChatGPTSynthetic.chat(nodes = listOf(ChatGPTSynthetic.node("a", message = ChatGPTSynthetic.message("s", "system"))))
        assertEquals(1, ChatGPTSynthetic.source(system).excludedMessages)
    }

    @Test fun cyclesMissingParentsInvalidCurrentAndWrongNodeIdentitiesFailClosed() {
        reject(ChatGPTSynthetic.chat(nodes = listOf(ChatGPTSynthetic.node("a", "a"))))
        reject(ChatGPTSynthetic.chat(nodes = listOf(ChatGPTSynthetic.node("a", "b"), ChatGPTSynthetic.node("b", "a"))))
        reject(ChatGPTSynthetic.chat(nodes = listOf(ChatGPTSynthetic.node("a", "absent"))))
        reject(ChatGPTSynthetic.chat(current = "absent"))
        reject(ChatGPTSynthetic.chat(extra = mapOf("mapping" to buildJsonObject { put("a", ChatGPTSynthetic.node("wrong")) })))
        // Cycles in entirely hidden components also reject the archive.
        reject(ChatGPTSynthetic.chat(nodes = listOf(ChatGPTSynthetic.node("a", "a", null)), current = null))
    }

    @Test fun duplicateConversationMessageAndJsonKeysAreRejected() {
        val chat = ChatGPTSynthetic.chat()
        assertThrows(ArchiveReadException::class.java) { ChatGPTChatArchive.inspect(ChatGPTSynthetic.file(temporary, chat, chat)) }
        reject(ChatGPTSynthetic.chat(nodes = listOf(ChatGPTSynthetic.node("a"),
            ChatGPTSynthetic.node("b", "a", ChatGPTSynthetic.message("a")))))
        val duplicate = ChatGPTSynthetic.file(temporary, chat).apply {
            writeText(readText().replace("\"title\":\"Synthetic chat\"", "\"title\":\"one\",\"title\":\"two\""))
        }
        assertThrows(ArchiveReadException::class.java) { ChatGPTChatArchive.inspect(duplicate) }
    }

    @Test fun timestampsKeepFractionalSecondsAndNeverInventNowForNulls() {
        val record = ChatGPTSynthetic.source(ChatGPTSynthetic.chat())
        assertEquals(Instant.parse("2023-11-14T22:13:20.125Z"), record.history.createdAt)
        val raw = ChatGPTSynthetic.chat(extra = mapOf("create_time" to JsonNull, "update_time" to JsonNull))
        val absent = ChatGPTSynthetic.source(raw)
        assertEquals(Instant.EPOCH, absent.history.createdAt); assertEquals(2, absent.missingTimestamps)
        for (time in listOf(JsonPrimitive(-1), JsonPrimitive("tomorrow"), JsonPrimitive(253402300800L),
            Json.parseToJsonElement("1e-9999999"))) reject(ChatGPTSynthetic.chat(extra = mapOf("create_time" to time)))
    }

    @Test fun textWhitespacePartsAndMissingMetadataArePreservedExactly() {
        val record = ChatGPTSynthetic.source(ChatGPTSynthetic.chat())
        assertEquals("  synthetic 正文🙂\r\n  ", record.history.messages.getValue("u").parts.single().text)
        val content = buildJsonObject { put("content_type", "text"); put("parts", JsonArray(listOf(JsonPrimitive(" A "), JsonPrimitive("B\n")))) }
        val raw = ChatGPTSynthetic.chat(nodes = listOf(ChatGPTSynthetic.node("a", message = ChatGPTSynthetic.message("a", "assistant", content))))
        assertEquals(listOf(" A ", "B\n"), ChatGPTSynthetic.source(raw).history.messages.getValue("a").parts.map { it.text })
    }

    @Test fun toolsRemainFencedTextAndNoAccountApprovalOrMetadataIsCopied() {
        val raw = ChatGPTSynthetic.chat(nodes = listOf(ChatGPTSynthetic.node("a", message = ChatGPTSynthetic.message("a", "tool",
            ChatGPTSynthetic.content("```\n![remote](https://invalid.example/a)"), mapOf("metadata" to buildJsonObject {
                put("approval_key", "SYNTHETIC-EXCLUDED"); put("model_slug", "SYNTHETIC-EXCLUDED")
            })))), extra = mapOf("account" to JsonPrimitive("SYNTHETIC-EXCLUDED")))
        val body = ChatGPTSynthetic.source(raw).history.messages.getValue("a").parts.single().text
        assertTrue(body.contains("历史工具记录")); assertTrue(body.contains("````")); assertFalse(body.contains("SYNTHETIC-EXCLUDED"))
    }

    @Test fun commonContentKindsAndUnknownVisibleJsonAreNotSilentlyDropped() {
        val contents = listOf("code", "execution_output", "tether_quote", "sonic_webpage", "system_error").map { kind ->
            buildJsonObject { put("content_type", kind); put("text", "synthetic-$kind") }
        } + listOf(buildJsonObject { put("content_type", "thoughts"); put("thoughts", JsonArray(listOf(buildJsonObject { put("content", "thought") }))) },
            buildJsonObject { put("content_type", "reasoning_recap"); put("content", "recap") },
            buildJsonObject { put("content_type", "future_format"); put("new_field", "visible-original") })
        val raw = ChatGPTSynthetic.chat(nodes = contents.mapIndexed { i, content ->
            ChatGPTSynthetic.node("n$i", if (i == 0) null else "n${i - 1}", ChatGPTSynthetic.message("n$i", "assistant", content))
        }, current = "n7")
        val record = ChatGPTSynthetic.source(raw)
        assertEquals(8, record.history.messages.size); assertEquals(1, record.unfamiliarContents)
        assertTrue(record.history.messages.getValue("n5").parts.single().reasoning)
        assertTrue(record.history.messages.getValue("n7").parts.single().text.contains("visible-original"))
    }

    @Test fun mediaAttachmentsAreCountedWithoutReadingPathsOrUrls() {
        val content = buildJsonObject { put("content_type", "multimodal_text"); put("parts", JsonArray(listOf(
            JsonPrimitive("caption"), buildJsonObject { put("content_type", "image_asset_pointer"); put("asset_pointer", "file://SYNTHETIC-NEVER-READ") }))) }
        val raw = ChatGPTSynthetic.chat(nodes = listOf(ChatGPTSynthetic.node("a", message = ChatGPTSynthetic.message("a", content = content,
            extra = mapOf("metadata" to buildJsonObject { put("attachments", JsonArray(listOf(buildJsonObject { put("url", "https://invalid.example/file") }))) })))))
        val record = ChatGPTSynthetic.source(raw)
        assertEquals(2, record.history.messages.getValue("a").attachmentReferences)
        assertFalse(record.history.toString().contains("SYNTHETIC-NEVER-READ"))
        assertTrue(ChatGPTChatArchive.inspect(ChatGPTSynthetic.file(temporary, raw)).warnings.any { "2 处图片" in it })
    }

    @Test fun orderingAndExcludedMetadataDoNotAlterContentVersion() {
        val raw = ChatGPTSynthetic.branch()
        val mapping = raw.getValue("mapping") as JsonObject
        val reordered = JsonObject(raw + ("mapping" to JsonObject(mapping.entries.reversed().associate { it.toPair() })) + ("account" to JsonPrimitive("ignored")))
        assertEquals(ChatGPTSynthetic.source(raw).history.contentVersion, ChatGPTSynthetic.source(reordered).history.contentVersion)
    }

    @Test fun editableContextIsCountedButNeverImportedAsUserInstruction() {
        val record = ChatGPTSynthetic.source(ChatGPTSynthetic.chat(nodes = listOf(ChatGPTSynthetic.node("a",
            message = ChatGPTSynthetic.message("a", content = buildJsonObject {
                put("content_type", "user_editable_context"); put("user_instructions", "SYNTHETIC-SETTINGS-NOT-IMPORTED")
            })))))
        assertEquals(1, record.excludedMessages)
        assertTrue(record.history.messages.isEmpty())
        assertFalse(record.history.toString().contains("SYNTHETIC-SETTINGS"))
    }

    @Test fun futureThoughtStructurePreservesRawContentInsteadOfAnEmptySuccess() {
        val record = ChatGPTSynthetic.source(ChatGPTSynthetic.chat(nodes = listOf(ChatGPTSynthetic.node("a",
            message = ChatGPTSynthetic.message("a", "assistant", buildJsonObject {
                put("content_type", "thoughts"); put("thoughts", JsonArray(listOf(buildJsonObject { put("future_text", "retained") })))
            })))))
        assertEquals(1, record.unfamiliarContents)
        assertTrue(record.history.messages.getValue("a").parts.single().text.contains("retained"))
    }

    @Test fun depthPathsNodeCountAndExpansionBudgetsRejectWithoutRecursion() {
        val deep = (0..ChatGPTChatArchive.MAX_DEPTH).map { ChatGPTSynthetic.node("n$it", if (it == 0) null else "n${it - 1}", null) }
        reject(ChatGPTSynthetic.chat(nodes = deep, current = null))
        val leaves = (0..ChatGPTChatArchive.MAX_PATHS).map { ChatGPTSynthetic.node("n$it") }
        reject(ChatGPTSynthetic.chat(nodes = leaves, current = null))
        val tooMany = buildJsonObject { repeat(ChatGPTChatArchive.MAX_NODES + 1) { put("n$it", JsonNull) } }
        assertThrows(ArchiveReadException::class.java) { ChatGPTChatArchive.parseConversation(ChatGPTSynthetic.chat(extra = mapOf("mapping" to tooMany))) }
        val trunk = (0 until 1000).map { ChatGPTSynthetic.node("n$it", if (it == 0) null else "n${it - 1}") }
        val manyLeaves = (0 until 1024).map { ChatGPTSynthetic.node("leaf$it", "n999") }
        reject(ChatGPTSynthetic.chat(nodes = trunk + manyLeaves, current = null))
    }

    @Test fun utf8BomTrailingGarbageWrongEnvelopeAndZipBytesAreSafe() {
        val text = JsonArray(listOf(ChatGPTSynthetic.chat())).toString()
        for (input in listOf("[]", "{}", text + "trailing", "PK\u0003\u0004not-json")) {
            assertThrows(ArchiveReadException::class.java) { ChatGPTChatArchive.inspect(temporary.newFile().apply { writeText(input) }) }
        }
        assertEquals(1, ChatGPTChatArchive.inspect(temporary.newFile().apply { writeText("\uFEFF$text") }).conversations.size)
        assertThrows(ArchiveReadException::class.java) { ChatGPTChatArchive.inspect(temporary.newFile().apply { writeBytes(byteArrayOf(91, 0xc3.toByte(), 40, 93)) }) }
        assertThrows(ArchiveReadException::class.java) { ArchiveCapacity.requireSize(ChatGPTChatArchive.MAX_ARCHIVE_BYTES + 1, ChatGPTChatArchive.MAX_ARCHIVE_BYTES) }
    }

    @Test fun cancellationPropagatesBeforeFileOrGraphWork() {
        val file = ChatGPTSynthetic.file(temporary, ChatGPTSynthetic.chat())
        assertThrows(CancellationException::class.java) { ChatGPTChatArchive.inspect(file) { throw CancellationException() } }
        assertThrows(CancellationException::class.java) { ChatGPTChatArchive.parseConversation(ChatGPTSynthetic.chat()) { throw CancellationException() } }
    }
}
