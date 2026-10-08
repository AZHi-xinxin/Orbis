package me.rerere.rikkahub.data.sync.importer

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.sync.DatabaseBackup
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * Operator-supplied COPY only, explicitly opted in and SHA-256 pinned before opening the ZIP.
 * Use IsolatedGenerationLoopRunner and the dev package; no app startup, settings store or tools.
 * Expected chat content comes from raw JSON, independently of the production message decoder.
 * Success output is counts only. Failures discard private exception messages and causes.
 */
@RunWith(AndroidJUnit4::class)
class RikkaChatCompatibilityArchiveTest {
    private lateinit var root: File
    private lateinit var baseCache: File
    private lateinit var context: Context
    private lateinit var sourceZip: File
    private lateinit var sourceHash: ByteArray
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var importer: RikkaChatImporter
    private lateinit var scope: AppScope
    private var phase = "setup"
    private var strictLegacyDecodeSuccess = 0
    private var strictLegacyDecodeFail = 0
    private var imageReferences = 0
    private var missingReferences = 0
    private var historicalToolParts = 0
    private val restoredUploads = mutableMapOf<String, String>()
    private val targetAssistant = Uuid.random()
    private val existing = Conversation(
        id = Uuid.random(), assistantId = targetAssistant, title = "compatibility-existing-marker",
        messageNodes = listOf(MessageNode(messages = listOf(UIMessage.user("synthetic-existing-body")))),
        createAt = Instant.ofEpochMilli(1000), updateAt = Instant.ofEpochMilli(2000),
    )
    private val configMarker = "synthetic-settings-and-prompt-must-stay-unchanged".toByteArray()

    @Before fun setup() = runBlocking {
        // Do not inspect even the input path unless the operator explicitly opts in.
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Compatibility archive acceptance requires explicit opt-in",
            arguments.getString("orbisCompatibilityImportCheck") == "true")
        privateCheck {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation is IsolatedGenerationLoopRunner) { "isolated runner required" }
            val base = instrumentation.targetContext
            check(base.applicationContext.javaClass == Application::class.java) { "plain application required" }
            check(base.packageName == "org.orbis.agent.dev") { "isolated target required" }
            val targetHash = arguments.getString("orbisCompatibilityImportSha256")?.lowercase(Locale.ROOT)
            check(targetHash in ACCEPTED_HASHES) { "explicit accepted fixture hash required" }
            baseCache = base.cacheDir.canonicalFile
            val inputDirectory = File(baseCache, INPUT_DIRECTORY)
            check(!Files.isSymbolicLink(inputDirectory.toPath()) &&
                inputDirectory.canonicalFile.parentFile == baseCache) { "fixed input directory required" }
            sourceZip = File(inputDirectory, "input.zip")
            check(!Files.isSymbolicLink(sourceZip.toPath()) && sourceZip.isFile &&
                sourceZip.canonicalFile.parentFile == inputDirectory.canonicalFile) { "fixed input copy missing" }
            sourceHash = digest(sourceZip)
            check(hex(sourceHash) == targetHash) { "input copy does not match requested fixture" }
            root = Files.createTempDirectory(baseCache.toPath(), ROOT_PREFIX).toFile().canonicalFile
            context = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = ownedDirectory("files")
                override fun getCacheDir(): File = ownedDirectory("cache")
                override fun getDatabasePath(name: String): File {
                    val file = if (File(name).isAbsolute) File(name) else File(root, "db/$name")
                    check(isOwned(file)) { "database outside isolated directory" }
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    return file
                }
            }
            database = AppDatabaseFactory.create(context)
            scope = AppScope()
            val files = FilesManager(context, FilesRepository(database.managedFileDao()), scope)
            repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
                database.favoriteDao(), database, files, MessageFtsManager(database))
            importer = RikkaChatImporter(context, repository, files)
            repository.insertConversation(existing)
            val marker = File(context.filesDir, "datastore/settings.preferences_pb")
            check(marker.parentFile!!.mkdirs())
            marker.writeBytes(configMarker)
        }
    }

    @After fun cleanup() {
        var failed = false
        if (::database.isInitialized) runCatching { database.close() }.onFailure { failed = true }
        if (::scope.isInitialized) scope.cancel()
        if (::sourceHash.isInitialized) runCatching {
            check(sourceHash.contentEquals(digest(sourceZip)))
        }.onFailure { failed = true }
        if (::root.isInitialized) runCatching {
            check(root.parentFile == baseCache && root.name.startsWith(ROOT_PREFIX))
            check(root != sourceZip.parentFile.canonicalFile)
            // Only the exact temporary tree created above can be removed; retain the input copy.
            root.walkTopDown().forEach { check(isOwned(it) && !Files.isSymbolicLink(it.toPath())) }
            check(root.deleteRecursively())
        }.onFailure { failed = true }
        assertTrue("Compatibility cleanup or input-integrity check failed; no broader cleanup attempted", !failed)
    }

    @Test fun privateArchivePreservesChatProjectionThroughPreviewAndSelectedImport() = runBlocking {
        privateCheck {
            phase = "independent chat projection"
            val staging = ownedDirectory("expected-snapshot")
            val snapshot = RikkaChatArchive.extract(sourceZip, staging)
            check(staging.listFiles().orEmpty().all {
                it.name in setOf("rikka_hub.db", "rikka_hub.db-wal", "upload")
            }) { "non-chat archive entry extracted" }
            // Normalize only this copy. Neither a DB version nor a Room identity decides compatibility.
            DatabaseBackup.normalize(context, snapshot)
            val expected = RikkaChatSnapshotReader.open(context, snapshot).use { reader ->
                reader.conversations().map { chat ->
                    ExpectedChat(chat, reader.nodes(chat.id).map { node ->
                        // Evidence about the old path, never an assertion that it must fail.
                        if (runCatching { JsonInstant.decodeFromString<List<UIMessage>>(node.messages) }.isSuccess)
                            strictLegacyDecodeSuccess++ else strictLegacyDecodeFail++
                        val branches = JsonInstant.parseToJsonElement(node.messages) as? JsonArray
                            ?: error("expected message array")
                        ExpectedNode(node, branches.map { it as? JsonObject ?: error("expected message object") })
                    })
                }
            }
            val nodeCount = expected.sumOf { it.nodes.size }
            val messageCount = expected.sumOf { chat -> chat.nodes.sumOf { it.messages.size } }
            val uploadCount = File(staging, "upload").listFiles().orEmpty().count { it.isFile }
            assertEquals(6, expected.size)
            assertEquals(14, nodeCount)
            assertEquals(14, messageCount)
            assertEquals(5, uploadCount)
            assertEquals(nodeCount, strictLegacyDecodeSuccess + strictLegacyDecodeFail)
            val beforeFiles = fileDigests()
            val beforeRows = managedRows()

            phase = "preview"
            val preview = importer.inspect(sourceZip)
            check(preview.fingerprint == hex(sourceHash)) { "preview fingerprint mismatch" }
            assertEquals(expected.size, preview.conversations.size)
            expected.zip(preview.conversations).forEach { (source, shown) ->
                check(shown.sourceId == source.chat.id && shown.title == source.chat.title)
                check(shown.createdAt.toEpochMilli() == source.chat.createAt &&
                    shown.updatedAt.toEpochMilli() == source.chat.updateAt)
                assertEquals(source.nodes.size, shown.totalNodes)
                assertEquals(source.nodes.sumOf { it.messages.size }, shown.messageCount)
                assertEquals(source.nodes.count { it.messages.size > 1 }, shown.branchPointCount)
                check(shown.defaultLeafId == "all" && shown.defaultSelectionReason == "all_branches_preserved")
                check(shown.branches.size == 1 && shown.branches.single().leafId == "all" &&
                    shown.branches.single().messageCount == shown.messageCount)
            }
            assertEquals(1, repository.countConversations())
            check(beforeRows == managedRows() && beforeFiles == fileDigests()) { "preview changed target files" }
            assertTargetUntouched()

            phase = "selected import"
            val selections = preview.conversations.map { it.sourceId }.toSet()
            val first = importer.importSelected(sourceZip, targetAssistant, selections, preview.fingerprint)
            assertEquals(expected.size, first.imported)
            assertEquals(messageCount, first.messages)
            assertEquals(0, first.skipped)
            assertEquals(0, first.failed)
            check(first.failures.isEmpty())
            val importedSnapshots = expected.map { source ->
                val imported = repository.getConversationById(rikkaImportId("conversation", source.chat.id))
                    ?: error("imported conversation missing")
                check(imported.assistantId == targetAssistant && imported.title == source.chat.title)
                check(imported.createAt.toEpochMilli() == source.chat.createAt &&
                    imported.updateAt.toEpochMilli() == source.chat.updateAt)
                check(imported.customSystemPrompt == null &&
                    imported.orbisPrompt == Conversation(assistantId = targetAssistant, messageNodes = emptyList()).orbisPrompt &&
                    imported.modeInjectionIds.isEmpty() && imported.lorebookIds.isEmpty() &&
                    imported.workspaceCwd == null && imported.folderId == null && imported.chatSuggestions.isEmpty() &&
                    !imported.isPinned && imported.consultation == null) { "source configuration migrated" }
                val nonemptyNodes = source.nodes.filter { it.messages.isNotEmpty() }
                assertEquals(nonemptyNodes.size, imported.messageNodes.size)
                nonemptyNodes.zip(imported.messageNodes).forEach { (oldNode, newNode) ->
                    check(newNode.id == rikkaImportId("node", "${source.chat.id}/${oldNode.node.id}"))
                    assertEquals(oldNode.node.selectIndex, newNode.selectIndex)
                    assertEquals(oldNode.messages.size, newNode.messages.size)
                    oldNode.messages.zip(newNode.messages).forEachIndexed { branchIndex, (raw, restored) ->
                        verifyMessage(raw, restored, source.chat.id, oldNode.node.id, branchIndex, staging)
                    }
                }
                imported
            }
            // Five ZIP upload entries are not five references. Count actual recoverable references.
            assertEquals(2, imageReferences)
            assertEquals(restoredUploads.size + missingReferences, first.attachmentReferences)
            assertEquals(restoredUploads.size, managedRows().size)
            assertEquals(1 + expected.size, repository.countConversations())
            assertTargetUntouched()
            val firstFiles = fileDigests()
            val firstRows = managedRows()

            phase = "duplicate selected import"
            val repeatPreview = importer.inspect(sourceZip)
            check(repeatPreview == preview) { "preview changed without source changes" }
            val second = importer.importSelected(sourceZip, targetAssistant, selections, repeatPreview.fingerprint)
            assertEquals(0, second.imported)
            assertEquals(expected.size, second.skipped)
            assertEquals(0, second.failed)
            assertEquals(0, second.messages)
            assertEquals(0, second.attachmentReferences)
            check(second.failures.isEmpty())
            assertEquals(1 + expected.size, repository.countConversations())
            check(firstRows == managedRows() && firstFiles == fileDigests()) { "duplicate import changed files" }
            importedSnapshots.forEach { check(repository.getConversationById(it.id) == it) }
            assertTargetUntouched()
            check(sourceHash.contentEquals(digest(sourceZip))) { "input copy changed" }

            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nCompatibility chat import: windows=${first.imported}, nodes=$nodeCount, " +
                    "messages=$messageCount, archiveUploads=$uploadCount, imageReferences=$imageReferences, " +
                    "restoredAttachments=${restoredUploads.size}, missingAttachments=$missingReferences, " +
                    "historicalToolParts=$historicalToolParts, duplicateAdded=${second.imported}, " +
                    "duplicateSkipped=${second.skipped}, strictLegacyDecodeSuccess=$strictLegacyDecodeSuccess, " +
                    "strictLegacyDecodeFail=$strictLegacyDecodeFail\n")
            })
        }
    }

    private data class ExpectedNode(val node: RikkaChatSnapshotReader.Node, val messages: List<JsonObject>)
    private data class ExpectedChat(val chat: RikkaChatSnapshotReader.Chat, val nodes: List<ExpectedNode>)

    private fun verifyMessage(raw: JsonObject, restored: UIMessage, chatId: String, nodeId: String,
        branchIndex: Int, staging: File) {
        val sourceId = raw.stringOrNull("id")?.let { Uuid.parse(it) }
            ?: rikkaImportId("missing-source-message", "$nodeId/$branchIndex")
        check(restored.id == rikkaImportId("message", "$chatId/$sourceId"))
        val role = raw.requiredString("role").uppercase(Locale.ROOT)
        check(restored.role == when (role) {
            "USER" -> MessageRole.USER
            "ASSISTANT", "TOOL", "SYSTEM", "DEVELOPER" -> MessageRole.ASSISTANT
            else -> error("unsupported fixture role")
        }) { "message role changed" }
        val createdAt = sourceDate(raw["createdAt"])
        check(restored.createdAt == (createdAt ?: LocalDateTime(1970, 1, 1, 0, 0)))
        check(restored.finishedAt == sourceDate(raw["finishedAt"]))
        check(restored.annotations.isEmpty() && restored.modelId == null && restored.usage == null &&
            restored.translation == null && restored.orbisEvent == null && restored.orbisQuote == null &&
            restored.orbisUserMessageTime == null && restored.orbisVoiceCallId == null &&
            restored.orbisVoiceCallKind == null && restored.deletedToolRecords.isEmpty() &&
            !restored.privateRoomContentHidden && !restored.privateRoomPendingPresentation) {
            "non-chat message metadata migrated"
        }
        if (role == "SYSTEM" || role == "DEVELOPER") {
            check(restored.parts.size == 1 &&
                (restored.parts.single() as? UIMessagePart.Text)?.text == SYSTEM_PLACEHOLDER)
        } else {
            val parts = raw["parts"] as? JsonArray ?: error("expected parts array")
            val restoredParts = if (role == "TOOL") {
                check((restored.parts.firstOrNull() as? UIMessagePart.Text)?.text == "[历史工具消息；仅保留内容，未执行]")
                restored.parts.drop(1)
            } else restored.parts
            assertEquals(parts.size, restoredParts.size)
            parts.zip(restoredParts).forEach { (source, target) ->
                verifyPart(source as? JsonObject ?: error("expected part object"), target, chatId, staging,
                    restored.createdAt)
            }
        }
        // The import cannot leave approvals, executable tool records or server-tool lifecycle objects.
        check(restored.parts.all { it is UIMessagePart.Text || it is UIMessagePart.Reasoning ||
            it is UIMessagePart.Image || it is UIMessagePart.Audio || it is UIMessagePart.Video ||
            it is UIMessagePart.Document }) { "historical tool remained executable" }
        restored.parts.forEach { part ->
            // A missing upload placeholder is made by the attachment mapper, not the source decoder.
            if (part is UIMessagePart.Text && part.text == "[原备份未包含可恢复附件]" && part.metadata == null)
                return@forEach
            val metadata = part.metadata ?: error("import marker missing")
            check(metadata["import_source"] == JsonPrimitive("rikka_chat_v1"))
            check(metadata.keys.all { it in setOf("import_source", "timestamp_fallback", "source_id_generated",
                "unsupported_content") }) { "untrusted part metadata migrated" }
            check(metadata["timestamp_fallback"] == if (createdAt == null) JsonPrimitive(true) else null)
            check(metadata["source_id_generated"] == if (raw.stringOrNull("id") == null) JsonPrimitive(true) else null)
        }
    }

    private fun verifyPart(raw: JsonObject, restored: UIMessagePart, chatId: String, staging: File,
        messageDate: LocalDateTime) {
        when (raw.stringOrNull("type")?.lowercase(Locale.ROOT)) {
            "text" -> check(restored is UIMessagePart.Text && restored.text == raw.requiredString("text"))
            "reasoning" -> {
                check(restored is UIMessagePart.Reasoning && restored.reasoning == raw.requiredString("reasoning"))
                val created = sourceInstant(raw["createdAt"])
                    ?: java.time.LocalDateTime.parse(messageDate.toString()).toInstant(ZoneOffset.UTC)
                check(Instant.parse(restored.createdAt.toString()) == created)
                check(restored.finishedAt?.let { Instant.parse(it.toString()) } ==
                    (sourceInstant(raw["finishedAt"]) ?: created))
            }
            "image", "audio", "video", "document" -> {
                val kind = raw.requiredString("type").lowercase(Locale.ROOT)
                if (kind == "image") imageReferences++
                val oldUrl = raw.requiredString("url")
                val sourceFile = RikkaChatArchive.uploadName(oldUrl)?.let { File(staging, "upload/$it") }
                    ?.takeIf { it.isFile }
                if (oldUrl.startsWith("https://") || oldUrl.startsWith("http://")) {
                    check(mediaUrl(restored) == oldUrl)
                } else if (sourceFile == null) {
                    missingReferences++
                    check(restored is UIMessagePart.Text && restored.text == "[原备份未包含可恢复附件]")
                    return
                } else {
                    val newUrl = mediaUrl(restored) ?: error("restored media URL missing")
                    val uri = URI(newUrl)
                    check(uri.scheme == "file" && uri.authority.isNullOrEmpty())
                    val newFile = File(uri)
                    check(isOwned(newFile) && newFile.canonicalFile.parentFile == File(context.filesDir, "upload").canonicalFile)
                    check(digest(sourceFile).contentEquals(digest(newFile))) { "attachment bytes changed" }
                    val prior = restoredUploads.putIfAbsent("$chatId/${sourceFile.name}", newUrl)
                    check(prior == null || prior == newUrl) { "repeated attachment reference split" }
                }
                check(when (kind) {
                    "image" -> restored is UIMessagePart.Image
                    "audio" -> restored is UIMessagePart.Audio
                    "video" -> restored is UIMessagePart.Video
                    else -> restored is UIMessagePart.Document && restored.fileName == (raw.stringOrNull("fileName") ?: "历史附件") &&
                        restored.mime == (raw.stringOrNull("mime") ?: "application/octet-stream")
                }) { "attachment kind changed" }
            }
            "tool", "tool_call", "tool_result", "server_tool" -> {
                historicalToolParts++
                check(restored is UIMessagePart.Text) { "tool history not inert text" }
                // Independently project visible raw payloads, rather than comparing decoded objects.
                val expected = buildString {
                    append("[历史工具记录：${raw.stringOrNull("toolName") ?: "未命名工具"}；仅保留文字，未执行]")
                    listOf("input" to "输入", "arguments" to "参数", "output" to "输出", "content" to "结果")
                        .forEach { (key, label) ->
                            raw[key]?.takeUnless { it == JsonNull }?.let { append("\n$label:\n${visibleToolPayload(it)}") }
                        }
                }
                check(restored.text == expected) { "historical tool text changed" }
            }
            else -> {
                val body = listOf("text", "reasoning", "content").mapNotNull { raw.stringOrNull(it) }.joinToString("\n")
                val expected = if (body.isEmpty()) UNKNOWN_PLACEHOLDER else "$body\n$UNKNOWN_PLACEHOLDER"
                check(restored is UIMessagePart.Text && restored.text == expected)
                check(restored.metadata?.get("unsupported_content") == JsonPrimitive(true))
            }
        }
    }

    private fun visibleToolPayload(value: JsonElement): String = when (value) {
        JsonNull -> ""
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString("\n") { visibleToolPayload(it) }
        is JsonObject -> if (value.stringOrNull("type") == null) value.toString() else {
            val text = listOf("text", "reasoning", "content").mapNotNull { value.stringOrNull(it) }.joinToString("\n")
            when {
                text.isNotEmpty() -> text
                value.stringOrNull("url") != null -> "[历史工具附件；请在原备份查看]"
                else -> "[非文本历史工具内容；请在原备份查看]"
            }
        }
    }

    private fun sourceDate(value: JsonElement?): LocalDateTime? {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return runCatching { LocalDateTime.parse(text) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDateTime().toString()) }.getOrNull()
    }

    private fun sourceInstant(value: JsonElement?): Instant? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun mediaUrl(part: UIMessagePart): String? = when (part) {
        is UIMessagePart.Image -> part.url
        is UIMessagePart.Audio -> part.url
        is UIMessagePart.Video -> part.url
        is UIMessagePart.Document -> part.url
        else -> null
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val value = this[key] ?: return null
        if (value == JsonNull) return null
        check(value is JsonPrimitive && value.isString) { "fixture string field has wrong type" }
        return value.content
    }
    private fun JsonObject.requiredString(key: String): String = stringOrNull(key) ?: error("fixture string missing")

    private suspend fun assertTargetUntouched() {
        check(repository.getConversationById(existing.id) == existing) { "synthetic existing chat changed" }
        check(File(context.filesDir, "datastore/settings.preferences_pb").readBytes().contentEquals(configMarker)) {
            "target settings marker changed"
        }
    }

    private fun managedRows(): List<Pair<Long, String>> = database.openHelper.readableDatabase
        .query("SELECT id, relative_path FROM managed_files ORDER BY id").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1)) }
        }

    private fun fileDigests(): Map<String, List<Byte>> = context.filesDir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(context.filesDir).path to digest(it).toList() }

    private fun ownedDirectory(name: String): File = File(root, name).also {
        check(isOwned(it)); check(it.isDirectory || it.mkdirs())
    }
    private fun isOwned(file: File): Boolean = file.canonicalPath == root.path ||
        file.canonicalPath.startsWith(root.path + File.separator)

    private fun digest(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest()
    }
    private fun hex(hash: ByteArray): String = hash.joinToString("") { "%02x".format(it) }

    private suspend fun privateCheck(block: suspend () -> Unit) {
        try { block() } catch (failure: Throwable) {
            throw AssertionError("Compatibility archive acceptance failed during $phase (${failure.javaClass.simpleName}); private details suppressed")
        }
    }

    private companion object {
        const val INPUT_DIRECTORY = "orbis-compatibility-import-acceptance"
        const val ROOT_PREFIX = "orbis-compatibility-chat-test-"
        const val SYSTEM_PLACEHOLDER = "[此处为原备份的系统设定；仅聊天导入未包含其正文]"
        const val UNKNOWN_PLACEHOLDER = "[原备份含暂不支持的内容；已保留可识别文字，其余内容请查看原备份]"
        val ACCEPTED_HASHES = setOf(
            "64af277634ca053ae349823fd1dbfbf687fa15825b71aa6789f0c1bbcfb80477",
            "a09d70fef058a82b256317a8da4d72fd53116d8b65966d8cd192757ecabcf13c",
        )
    }
}
