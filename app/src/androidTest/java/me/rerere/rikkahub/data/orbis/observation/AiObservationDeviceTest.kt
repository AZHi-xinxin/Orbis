package me.rerere.rikkahub.data.orbis.observation

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class AiObservationDeviceTest {
    private class Fixture : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner)
            check(it.targetContext.applicationContext.javaClass == Application::class.java)
        }
        val root = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "observation-test-").toFile()
        val prefix = root.name
        val context = object : Application() {
            init { attachBaseContext(instrumentation.targetContext) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = error("Persistent database forbidden")
            override fun getSharedPreferences(name: String, mode: Int) =
                instrumentation.targetContext.getSharedPreferences("$prefix-$name", mode)
        }
        val dormant = AppScope().also { it.cancel() }
        val assistant = Assistant(name = "合成助手")
        val settings = SettingsStore(context, dormant).also {
            it.settingsFlow.value = Settings(assistants = listOf(assistant), providers = emptyList(),
                ttsProviders = emptyList(), aiGlobalObservation = true)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val runtime = AiObservationRuntime(context, db, settings)
        suspend fun window(title: String, owner: String = assistant.id.toString(), consultation: String = ""): Uuid {
            val id = Uuid.random()
            db.conversationDao().insert(ConversationEntity(id.toString(), owner, title, "[]", 1, 1000, "[]",
                false, consultationBinding = consultation))
            return id
        }
        override fun close() {
            db.close()
            instrumentation.targetContext.deleteSharedPreferences("$prefix-orbis-observation-v1")
            check(root.canonicalFile.parentFile == instrumentation.targetContext.cacheDir.canonicalFile)
            check(root.walkTopDown().none { Files.isSymbolicLink(it.toPath()) })
            root.deleteRecursively()
        }
    }

    @Test fun reportUsesLightweightMetadataAndMeasuredPresenceWithNoPrivateRooms() = runBlocking {
        Fixture().use { f ->
            val current = f.window("现在")
            val older = f.window("长聊")
            val privateRoom = f.window("私密室", consultation = "{}")
            f.window("其他助手", owner = Uuid.random().toString())
            val human = UIMessage.user("合成请求")
            val meter = ObservationMeter(f.context)
            meter.visible(current.toString(), 1000)
            meter.visible(older.toString(), 30000)
            meter.visible(privateRoom.toString(), 50000)
            meter.regenerate(current.toString(), human.id.toString())
            meter.regenerate(current.toString(), human.id.toString())
            meter.delete(3)
            val report = f.runtime.report(f.settings.settingsFlow.value, f.assistant, current,
                listOf(human), "model-X", false)!!
            val value = Json.parseToJsonElement(report).jsonObject
            assertEquals("长聊", value["使用最久窗口"]!!.jsonPrimitive.content)
            assertEquals(2L, value["同助手窗口数"]!!.jsonPrimitive.long)
            assertEquals(2L, value["本条重刷"]!!.jsonPrimitive.long)
            assertEquals(3L, value["已删消息"]!!.jsonPrimitive.long)
            assertFalse(report.contains("合成请求")); assertFalse(report.contains("私密室"))
            assertEquals("1970-01-01T00:00:01Z", value["本助手最近活跃"]!!.jsonPrimitive.content)
            assertEquals(2L, ObservationMeter(f.context).regenerations(current.toString(), human.id.toString()))
        }
    }

    @Test fun defaultOffLiveOffAndWrongOwnerProduceNoReport() = runBlocking {
        Fixture().use { f ->
            val current = f.window("当前")
            val human = listOf(UIMessage.user("hi"))
            val on = f.settings.settingsFlow.value
            assertNull(f.runtime.report(on.copy(aiGlobalObservation = false), f.assistant, current, human, "model", null))
            f.settings.settingsFlow.value = on.copy(aiGlobalObservation = false)
            assertNull(f.runtime.report(on, f.assistant, current, human, "model", null))
            f.settings.settingsFlow.value = on
            assertNull(f.runtime.report(on, Assistant(), current, human, "model", null))
            assertNull(f.runtime.report(on, f.assistant, null, human, "model", null))
        }
    }
}
