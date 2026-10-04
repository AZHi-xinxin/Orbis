package me.rerere.rikkahub.data.recovery

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.mcp.serverUrl
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.sync.DatabaseBackup
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.uuid.Uuid

/** Synthetic isolated-runner cache files only. Does not start RikkaHubApp, Koin, models or live databases. */
@RunWith(AndroidJUnit4::class)
class EmergencyRestoreValidationTest {
    private lateinit var directory: File
    private lateinit var cacheRoot: File
    private lateinit var fixtureContext: Context
    private lateinit var roots: Map<String, File>
    private val assistantId = Uuid.parse("dd03122d-adff-4f64-846e-bb83e0242dbd")
    private val conversationId = "5c8cfd70-eb39-4b85-89e5-5bdd32409a07"
    private val mcpDefinitions: List<McpServerConfig> = listOf(
        McpServerConfig.StreamableHTTPServer(
            id = Uuid.parse("1d91e648-7078-472f-bd22-ce41be483ba2"),
            url = "https://synthetic.invalid/mcp",
            commonOptions = McpCommonOptions(name = "synthetic HTTP tools", enable = true,
                headers = listOf("X-Fixture" to "synthetic-value"),
                tools = listOf(McpTool(name = "synthetic_read", description = "fixture read", needsApproval = false),
                    McpTool(name = "synthetic_write", enable = false, needsApproval = false)),
                oauth = McpOAuthState(enabled = true, clientId = "synthetic-client", accessToken = "synthetic-not-a-credential",
                    refreshToken = "synthetic-not-a-refresh-credential", tokenEndpoint = "https://synthetic.invalid/token")),
        ),
        McpServerConfig.SseTransportServer(
            id = Uuid.parse("a56340aa-d733-4c70-93f3-86ae164b324d"),
            url = "https://synthetic.invalid/sse",
            commonOptions = McpCommonOptions(name = "synthetic SSE tools", enable = true,
                tools = listOf(McpTool(name = "synthetic_second", needsApproval = false)),
                oauth = McpOAuthState(enabled = true, accessToken = "synthetic-not-a-credential")),
        ),
    )

    @Before fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
        cacheRoot = instrumentation.targetContext.cacheDir.canonicalFile
        check(cacheRoot.isDirectory || cacheRoot.mkdirs())
        directory = Files.createTempDirectory(cacheRoot.toPath(), "emergency-validation-").toFile().canonicalFile
        check(directory.parentFile == cacheRoot)
        val app = File(directory, "synthetic-app").apply { mkdirs() }
        roots = EmergencyRestore.rootNames.associateWith { File(app, it).apply { mkdirs() } }
        fixtureContext = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir(): File = app
            override fun getFilesDir(): File = roots.getValue("files")
            override fun getNoBackupFilesDir(): File = roots.getValue("no_backup")
            override fun getCacheDir(): File = File(app, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String): File {
                val result = if (File(name).isAbsolute) File(name) else File(roots.getValue("databases"), name)
                check(result.canonicalPath.startsWith(directory.path + File.separator))
                return result
            }
        }
    }

    @After fun tearDown() {
        if (::directory.isInitialized) {
            check(directory.canonicalFile.parentFile == cacheRoot)
            check(directory.name.startsWith("emergency-validation-"))
            check(directory.deleteRecursively())
        }
    }

    private fun write(file: File, text: String) {
        file.parentFile!!.mkdirs()
        file.writeText(text)
    }

    private fun writeSettings() = runBlocking {
        val file = File(roots.getValue("files"), "datastore/settings.preferences_pb")
        val job = SupervisorJob()
        try {
            val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
            data.edit {
                it[intPreferencesKey("data_version")] = 3
                it[stringPreferencesKey("assistants")] = JsonInstant.encodeToString(listOf(
                    Assistant(id = assistantId, name = "synthetic rescue assistant", systemPrompt = "synthetic fixture only")))
                it[stringPreferencesKey("select_assistant")] = assistantId.toString()
                it[booleanPreferencesKey("web_server_enabled")] = true
                it[stringPreferencesKey("mcp_servers")] = JsonInstant.encodeToString(mcpDefinitions)
            }
        } finally { job.cancelAndJoin() }
    }

    private fun createArchive() = File(directory, "source.orbis-emergency.zip").also { archive ->
        EmergencyArchive.create(roots.map { EmergencyArchiveRoot(it.key, it.value) }, archive,
            EmergencyArchiveMetadata(fixtureContext.packageName, "2.6.3", 250))
    }

    private fun transaction() = File(roots.getValue("files").parentFile, "orbis-emergency/transactions/fixture")

    private fun prepare(archive: File) = EmergencyRestore.prepare(archive, transaction(),
        fixtureContext.packageName, 250, roots, {}, { validateEmergencyRestore(fixtureContext, it) })

    private suspend fun readServers(file: File): List<McpServerConfig> {
        val job = SupervisorJob()
        return try {
            val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
            JsonInstant.decodeFromString(data.data.first()[stringPreferencesKey("mcp_servers")]!!)
        } finally { job.cancelAndJoin() }
    }

    private fun assertMcpDefinitionsRetainedButDisarmed(actual: List<McpServerConfig>) {
        assertEquals(mcpDefinitions.size, actual.size)
        actual.zip(mcpDefinitions).forEach { (restored, source) ->
            assertEquals(source.javaClass, restored.javaClass)
            assertEquals(source.id, restored.id)
            assertEquals(source.serverUrl, restored.serverUrl)
            assertEquals(source.commonOptions.name, restored.commonOptions.name)
            assertEquals(source.commonOptions.headers, restored.commonOptions.headers)
            assertFalse(restored.commonOptions.enable)
            assertNull(restored.commonOptions.oauth)
            assertEquals(source.commonOptions.tools.map { it.copy(needsApproval = true) }, restored.commonOptions.tools)
            assertTrue(restored.commonOptions.tools.all { it.needsApproval })
        }
    }

    private data class RecoveryFixture(val archive: File, val messages: String, val attachmentBytes: ByteArray)

    private suspend fun recoverableFixture(): RecoveryFixture {
        writeSettings()
        val attachment = File(roots.getValue("files"), "upload/目录:01/合成附件:02.bin")
        val bytes = ByteArray(97_111) { (it % 251).toByte() }
        attachment.parentFile!!.mkdirs()
        attachment.writeBytes(bytes)
        File(attachment.parentFile, "合成附件_02.bin").writeText("different underscore file")
        write(File(roots.getValue("files"), "workspaces/linux/dpkg/libsample:arm64.list"), "inert raw runtime metadata")
        val messages = JsonInstant.encodeToString(listOf(
            UIMessage.assistant("保留的旧分支"),
            UIMessage.assistant("崩溃前已提交的末条消息").copy(parts = listOf(
                UIMessagePart.Text("崩溃前已提交的末条消息"),
                UIMessagePart.Document(Uri.fromFile(attachment).toString(), attachment.name, "application/octet-stream"),
            )),
        ))
        write(File(roots.getValue("no_backup"), "pending-runtime.json"), "synthetic task must not replay")
        write(File(roots.getValue("shared_prefs"), "old-flags.xml"), "synthetic flags must not reactivate")
        write(File(roots.getValue("databases"), "androidx.work.workdb"), "synthetic worker database excluded")
        val databaseFile = File(roots.getValue("databases"), "rikka_hub")
        val room = AppDatabaseFactory.create(fixtureContext, databaseFile.absolutePath)
        val archive: File
        try {
            val database = room.openHelper.writableDatabase
            database.query("PRAGMA wal_autocheckpoint=0").use { check(it.moveToFirst()) }
            DatabaseBackup.checkpoint(database)
            val checkpointedMain = databaseFile.readBytes()
            room.conversationDao().insert(ConversationEntity(id = conversationId, assistantId = assistantId.toString(),
                title = "synthetic recovered conversation", nodes = "[]", createAt = 100, updateAt = 200,
                chatSuggestions = "[]", isPinned = true))
            room.messageNodeDao().insert(MessageNodeEntity("ffeefc0a-9535-42a6-bea8-e42b4d19c70a", conversationId, 0, messages, 1))
            assertTrue(File(databaseFile.path + "-wal").length() > 0)
            assertArrayEquals("Committed rows must still reside in WAL for this fixture", checkpointedMain, databaseFile.readBytes())
            archive = createArchive() // Open but idle connection; no concurrent writes or background app.
        } finally { room.close() }
        return RecoveryFixture(archive, messages, bytes)
    }

    private fun liveHashes(): Map<String, String> = roots.flatMap { (rootName, root) ->
        if (!root.exists()) emptyList() else root.walkTopDown().filter { it.isFile }.map { file ->
            "$rootName/${file.relativeTo(root).invariantSeparatorsPath}" to
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }.toList()
    }.toMap()

    @Test fun verifiedExternalArchiveRestoresWalSettingsBranchesAndAttachmentIntoEmptyRoots() = runBlocking {
        val fixture = recoverableFixture()
        val external = File(directory, "synthetic-user-selected-download.zip")
        val receipt = EmergencyArchiveExport.saveVerified(fixture.archive, { external.outputStream() }, { external.inputStream() })
        assertEquals(external.length(), receipt.sizeBytes)
        val beforeFreshInstall = File(directory, "retained-synthetic-originals").apply { mkdirs() }
        roots.forEach { (name, root) -> Files.move(root.toPath(), File(beforeFreshInstall, name).toPath()) }
        roots.values.forEach { assertFalse(it.exists()) }
        val plan = prepare(external)
        assertTrue(plan.hadOriginal.values.none { it })
        roots.values.forEach { assertFalse(it.exists()) }
        assertTrue(File(transaction(), "raw/databases/rikka_hub-wal").length() > 0)
        EmergencyRestore.commit(transaction(), roots, {})
        val restored = AppDatabaseFactory.create(fixtureContext, File(roots.getValue("databases"), "rikka_hub").absolutePath)
        try {
            val conversation = restored.conversationDao().getConversationById(conversationId)!!
            assertEquals("synthetic recovered conversation", conversation.title)
            assertEquals(assistantId.toString(), conversation.assistantId)
            assertTrue(conversation.isPinned)
            val node = restored.messageNodeDao().getNodesOfConversation(conversationId).single()
            assertEquals(fixture.messages, node.messages)
            assertEquals(1, node.selectIndex)
            val selected = JsonInstant.decodeFromString<List<UIMessage>>(node.messages)[node.selectIndex]
            val document = selected.parts.filterIsInstance<UIMessagePart.Document>().single()
            assertArrayEquals(fixture.attachmentBytes, File(Uri.parse(document.url).path!!).readBytes())
        } finally { restored.close() }
        assertMcpDefinitionsRetainedButDisarmed(readServers(File(roots.getValue("files"), "datastore/settings.preferences_pb")))
        assertFalse(File(roots.getValue("databases"), "androidx.work.workdb").exists())
        assertTrue(roots.getValue("no_backup").listFiles()!!.isEmpty())
        assertTrue(roots.getValue("shared_prefs").listFiles()!!.isEmpty())
        assertArrayEquals(fixture.attachmentBytes, File(transaction(), "raw/files/upload/目录:01/合成附件:02.bin").readBytes())
        assertEquals("different underscore file", File(roots.getValue("files"), "upload/目录:01/合成附件_02.bin").readText())
        assertEquals("inert raw runtime metadata", File(transaction(), "raw/files/workspaces/linux/dpkg/libsample:arm64.list").readText())
        assertFalse(File(roots.getValue("files"), "workspaces").exists())
        assertEquals("complete", EmergencyArchive.verify(external).status)
    }

    @Test fun cancelledPreparedRestoreKeepsCurrentRootsAndVerifiedRawCopy() = runBlocking {
        val fixture = recoverableFixture()
        val current = liveHashes()
        prepare(fixture.archive)
        EmergencyRestore.rollback(transaction(), roots, {}) // Human chooses “暂不恢复”.
        assertEquals(current, liveHashes())
        assertEquals("ROLLED_BACK", EmergencyRestore.readJournal(transaction()).status)
        assertFalse(File(transaction(), "originals").exists())
        assertTrue(File(transaction(), "raw/databases/rikka_hub-wal").length() > 0)
        assertTrue(File(transaction(), "prepared/databases/rikka_hub").isFile)
    }

    @Test fun preparationCancellationNeverMovesCurrentRootsOrCreatesCommitJournal() = runBlocking {
        val fixture = recoverableFixture()
        val current = liveHashes()
        var checks = 0
        assertThrows(CancellationException::class.java) {
            EmergencyRestore.prepare(fixture.archive, transaction(), fixtureContext.packageName, 250, roots,
                { if (++checks == 3) throw CancellationException("synthetic cancelled preparation") },
                { validateEmergencyRestore(fixtureContext, it) })
        }
        assertEquals(current, liveHashes())
        assertFalse(File(transaction(), EmergencyRestore.JOURNAL).exists())
        assertFalse(File(transaction(), "originals").exists())
        assertTrue(File(transaction(), "raw/databases/rikka_hub").isFile)
    }

    @Test fun interruptedInstallRetainsAllOriginalBytesAndExplicitRollbackReturnsThem() = runBlocking {
        val fixture = recoverableFixture()
        val current = liveHashes()
        val originalDatabase = File(roots.getValue("databases"), "rikka_hub").readBytes()
        prepare(fixture.archive)
        assertThrows(IOException::class.java) {
            EmergencyRestore.commit(transaction(), roots, {}, {
                if (it == "install:files") throw IOException("synthetic power interruption")
            })
        }
        assertEquals("INSTALLING", EmergencyRestore.readJournal(transaction()).status)
        assertArrayEquals(originalDatabase, File(transaction(), "originals/databases/rikka_hub").readBytes())
        EmergencyRestore.rollback(transaction(), roots, {})
        assertEquals(current, liveHashes())
        assertArrayEquals(fixture.attachmentBytes, File(transaction(), "prepared/files/upload/目录:01/合成附件:02.bin").readBytes())
        assertTrue(File(transaction(), "raw/databases/rikka_hub-wal").length() > 0)
        assertEquals("complete", EmergencyArchive.verify(fixture.archive).status)
    }

    @Test fun truncatedArchiveCannotReplaceCurrentDatabaseSettingsOrAttachments() = runBlocking {
        val fixture = recoverableFixture()
        val current = liveHashes()
        val broken = File(directory, "truncated.zip").apply {
            val original = fixture.archive.readBytes()
            writeBytes(original.copyOf(original.size / 2))
        }
        assertThrows(Exception::class.java) { prepare(broken) }
        assertEquals(current, liveHashes())
        assertFalse(File(transaction(), EmergencyRestore.JOURNAL).exists())
        assertFalse(File(transaction(), "originals").exists())
        assertEquals("complete", EmergencyArchive.verify(fixture.archive).status)
    }

    @Test fun validWalAndSettingsRestoreWithoutTouchingLiveDictionaryDuringValidation() = runBlocking {
        writeSettings()
        val originalSettings = File(roots.getValue("files"), "datastore/settings.preferences_pb").readBytes()
        val liveDatabase = File(roots.getValue("databases"), "rikka_hub")
        val room = AppDatabaseFactory.create(fixtureContext, liveDatabase.absolutePath)
        val archive: File
        try {
            val database = room.openHelper.writableDatabase
            database.query("PRAGMA wal_autocheckpoint=0").use { check(it.moveToFirst()) }
            DatabaseBackup.checkpoint(database)
            room.conversationDao().insert(ConversationEntity(id = conversationId, assistantId = assistantId.toString(),
                title = "committed only in WAL", nodes = "[]", createAt = 100, updateAt = 100,
                chatSuggestions = "[]", isPinned = false))
            assertTrue(File(liveDatabase.path + "-wal").length() > 0)
            // No writers run while the synthetic open connection's main file + WAL are copied.
            archive = createArchive()
        } finally { room.close() }

        val sentinel = File(roots.getValue("files"), "simple_dict/keep-me.txt")
        write(sentinel, "live dictionary must not be touched")
        write(File(sentinel.parentFile, "version.txt"), "invalid live sentinel version")
        val liveDatabaseBytes = liveDatabase.readBytes()
        prepare(archive)
        assertEquals("live dictionary must not be touched", sentinel.readText())
        assertEquals("invalid live sentinel version", File(sentinel.parentFile, "version.txt").readText())
        assertArrayEquals(liveDatabaseBytes, liveDatabase.readBytes())
        assertTrue(File(transaction(), "raw/databases/rikka_hub-wal").length() > 0)
        assertFalse(File(transaction(), "prepared/databases/rikka_hub-wal").exists())
        assertFalse(File(transaction(), "prepared/databases/rikka_hub-shm").exists())
        assertArrayEquals(originalSettings, File(roots.getValue("files"), "datastore/settings.preferences_pb").readBytes())
        assertArrayEquals(originalSettings, File(transaction(), "raw/files/datastore/settings.preferences_pb").readBytes())
        assertMcpDefinitionsRetainedButDisarmed(readServers(File(transaction(), "prepared/files/datastore/settings.preferences_pb")))

        EmergencyRestore.commit(transaction(), roots, {})
        val restored = AppDatabaseFactory.create(fixtureContext, liveDatabase.absolutePath)
        try {
            val conversation = restored.conversationDao().getConversationById(conversationId)
            assertEquals("committed only in WAL", conversation?.title)
            assertEquals(assistantId.toString(), conversation?.assistantId)
        } finally { restored.close() }
        val settingsJob = SupervisorJob()
        try {
            val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + settingsJob)) {
                File(roots.getValue("files"), "datastore/settings.preferences_pb")
            }
            val preferences = data.data.first()
            assertEquals(false, preferences[booleanPreferencesKey("web_server_enabled")])
            val assistants = JsonInstant.decodeFromString<List<Assistant>>(preferences[stringPreferencesKey("assistants")]!!)
            assertEquals(assistantId, assistants.single().id)
            assertEquals("synthetic rescue assistant", assistants.single().name)
            assertMcpDefinitionsRetainedButDisarmed(JsonInstant.decodeFromString(preferences[stringPreferencesKey("mcp_servers")]!!))
        } finally { settingsJob.cancelAndJoin() }
        assertEquals("live dictionary must not be touched", File(transaction(), "originals/files/simple_dict/keep-me.txt").readText())
    }

    @Test fun malformedSettingsAbortBeforeLiveMutationAndKeepRawEvidence() {
        write(File(roots.getValue("files"), "datastore/settings.preferences_pb"), "not protobuf")
        write(File(roots.getValue("databases"), "rikka_hub"), "not opened because settings invalid")
        val archive = createArchive()
        val original = File(roots.getValue("files"), "datastore/settings.preferences_pb").readBytes()
        assertThrows(Exception::class.java) { prepare(archive) }
        assertFalse(File(transaction(), EmergencyRestore.JOURNAL).exists())
        assertArrayEquals(original, File(roots.getValue("files"), "datastore/settings.preferences_pb").readBytes())
        assertArrayEquals(original, File(transaction(), "raw/files/datastore/settings.preferences_pb").readBytes())
    }

    @Test fun malformedDatabaseAbortsWithoutReplacingOriginalOrRemovingRawBytes() {
        writeSettings()
        val database = File(roots.getValue("databases"), "rikka_hub")
        write(database, "not a SQLite database")
        val archive = createArchive()
        assertThrows(Exception::class.java) { prepare(archive) }
        assertFalse(File(transaction(), EmergencyRestore.JOURNAL).exists())
        assertEquals("not a SQLite database", database.readText())
        assertEquals("not a SQLite database", File(transaction(), "raw/databases/rikka_hub").readText())
    }
}
