package me.rerere.rikkahub.data.orbis.agentwork

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentTaskToolDeviceTest {
    @Test fun realAndroidFileStoreCreatesAndReopensThroughTheTool() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        val root = File(inst.targetContext.cacheDir, "task-tool-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(inst.targetContext) {
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        try {
            val owner = UUID.randomUUID().toString(); val conversation = UUID.randomUUID().toString()
            val tool = taskTool(agentTasks(context, owner), conversation) { true }
            fun decode(parts: List<UIMessagePart>) = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
            val created = decode(tool.execute(buildJsonObject { put("action", "create"); put("title", "手机操作测试") }))
            assertEquals(created.toString(), JsonPrimitive(true), created["ok"])
            val saved = agentTasks(context, owner).list().single()
            assertEquals(listOf(AgentStep("手机操作测试")), saved.steps)
            val updated = decode(tool.execute(buildJsonObject {
                put("action", "update"); put("id", saved.id); put("revision", saved.revision)
                put("steps", buildJsonArray { add(buildJsonObject { put("title", "手机操作测试"); put("state", "done") }) })
                put("state", "completed"); put("summary", "合成测试核对完成")
            }))
            assertEquals(updated.toString(), JsonPrimitive(true), updated["ok"])
            assertEquals("completed", agentTasks(context, owner).list().single().state)
            assertTrue(agentTasks(context, owner).list().single().receipts.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
