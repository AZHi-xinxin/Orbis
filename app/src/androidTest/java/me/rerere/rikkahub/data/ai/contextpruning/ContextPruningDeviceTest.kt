package me.rerere.rikkahub.data.ai.contextpruning

import android.app.Application
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** Real Android filesystem; synthetic messages only, never opens the app's conversation database. */
class ContextPruningDeviceTest {
    private lateinit var root: File
    private val owner = Uuid.random().toString()
    private val conversation = Uuid.random().toString()
    @Before fun isolated() {
        val runner = InstrumentationRegistry.getInstrumentation()
        check(runner is IsolatedGenerationLoopRunner)
        check(runner.targetContext.applicationContext.javaClass == Application::class.java)
        root = Files.createTempDirectory(runner.targetContext.cacheDir.toPath(), "pruning-fixture-").toFile()
    }
    @After fun cleanSyntheticFiles() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        check(root.canonicalFile.parentFile == cache && root.name.startsWith("pruning-fixture-"))
        root.deleteRecursively()
    }
    private fun repository(directory: File = File(root, "policy")) =
        ContextPruningRepository(owner, conversation, FileContextPruningStorage(directory, root))
    private fun messages(): List<UIMessage> {
        val time = LocalDateTime(2026, 10, 4, 1, 0)
        fun reply() = UIMessage(role = MessageRole.ASSISTANT, finishedAt = time, parts = listOf(
            UIMessagePart.Reasoning("SYNTHETIC_PRIVATE_REASONING"),
            UIMessagePart.Tool(Uuid.random().toString(), "workspace_read_file", "{}", listOf(UIMessagePart.Text("SYNTHETIC_TOOL_RESULT"))),
            UIMessagePart.Text("keep this final answer")))
        return listOf(UIMessage.user("old"), reply(), UIMessage.user("recent"), reply(), UIMessage.user("now"), reply())
    }
    @Test fun persistedExclusionSurvivesRepositoryReopenAndRestoresExactOriginalWithoutReplay() {
        val original = messages()
        val originalBytes = contextPruningJson.encodeToString(original)
        val repo = repository()
        assertTrue(repo.snapshot().batches.isEmpty())
        assertFalse(File(root, "policy").exists())
        val plan = repo.preview(original, ContextPruningMode.BOTH, 100)
        val batch = repo.apply(original, ContextPruningMode.BOTH, 100, plan.planId)
        val persisted = File(root, "policy/policy.json").readText()
        assertFalse(persisted.contains("SYNTHETIC_PRIVATE_REASONING"))
        assertFalse(persisted.contains("SYNTHETIC_TOOL_RESULT"))
        val reopened = repository()
        assertEquals(listOf(UIMessagePart.Text("keep this final answer")),
            projectContextPruningForRequest(original, reopened.snapshot())[1].parts)
        reopened.restore(batch.id)
        assertEquals(originalBytes, contextPruningJson.encodeToString(projectContextPruningForRequest(original, reopened.snapshot())))
        assertEquals(originalBytes, contextPruningJson.encodeToString(original))
    }
    @Test fun symbolicPolicyAndWrongOwnerFailClosed() {
        val directory = File(root, "policy").apply { mkdir() }
        val target = File(root, "other.json").apply { writeText("{}") }
        Os.symlink(target.absolutePath, File(directory, "policy.json").absolutePath)
        try { repository().snapshot(); fail("symbolic policy must be rejected") } catch (_: Exception) { }
        assertEquals("{}", target.readText())
        val separate = File(root, "wrong-owner").apply { mkdir() }
        File(separate, "policy.json").writeText(contextPruningJson.encodeToString(ContextPruningState(
            assistantId = Uuid.random().toString(), conversationId = conversation)))
        try { repository(separate).snapshot(); fail("wrong owner must be rejected") } catch (_: Exception) { }
    }

    @Test fun appInternalDirectoryLinksAreRejectedAndAndroidAnchoredBackupWorks() {
        val files = File(root, "files").apply { mkdir() }
        val path = "orbis-context-pruning/$owner/$conversation/policy.json"
        val source = File(files, path).apply { parentFile!!.mkdirs() }
        source.writeText(contextPruningJson.encodeToString(ContextPruningState(
            assistantId = owner, conversationId = conversation)))
        val snapshots = ContextPruningBackup.stageSnapshot(files, File(root, "snapshot"), mapOf(conversation to owner))
        assertEquals(source.readText(), snapshots.single().second.readText())
        val redirected = File(root, "redirected")
        Os.symlink(files.absolutePath, redirected.absolutePath)
        try {
            repository(File(redirected, "policy")).snapshot()
            fail("app-owned child symlink must be rejected")
        } catch (_: Exception) { }
        val payload = File(root, "payload").apply { mkdir() }
        Os.symlink(files.absolutePath, File(payload, "files").absolutePath)
        try {
            ContextPruningBackup.validateStaged(payload)
            fail("staged child symlink must be rejected")
        } catch (_: Exception) { }
        assertTrue(source.isFile)
    }
}
