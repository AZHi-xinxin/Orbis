package me.rerere.rikkahub.data.orbis

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.CompanionMemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.companiontools.COMPANION_MEMORY_TOOL_NAMES
import me.rerere.rikkahub.data.orbis.companiontools.createCompanionTools
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic memory only, in an owned random cache directory, never the user's filesDir. */
@RunWith(AndroidJUnit4::class)
class CompanionMemoryDeviceTest {
    private lateinit var directory: File
    private lateinit var testContext: Context
    private lateinit var cacheRoot: File

    @Before fun createSyntheticDirectory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        // Instrumentation executes as the target UID, not the APK's test-package UID.
        // Like the existing LC fixture, use only our random target cache subdirectory.
        val owner = instrumentation.targetContext
        check(owner.packageName == "org.orbis.agent.dev")
        cacheRoot = owner.cacheDir.canonicalFile
        directory = File(cacheRoot, "stage22-memory-${UUID.randomUUID()}").canonicalFile
        check(directory.parentFile == cacheRoot && !directory.exists())
        check(directory.mkdir()) { "Could not create the owned synthetic memory cache directory" }
        testContext = object : ContextWrapper(owner) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSystemService(name: String): Any? = error("Memory must not access device services")
        }
    }

    @After fun removeOnlyOwnedFixture() {
        if (::directory.isInitialized) {
            check(directory.canonicalFile.parentFile == cacheRoot && directory.name.startsWith("stage22-memory-"))
            check(directory.deleteRecursively())
        }
    }

    @Test fun nativeAdapterAndUiStoreShareAndroidAtomicFileWithoutStartingService() = runBlocking {
        val uiStore = CompanionMemoryStore(directory)
        uiStore.save("before", "synthetic existing value")
        val tools = createCompanionTools(testContext, COMPANION_MEMORY_TOOL_NAMES).associateBy { it.name }
        val saved = tools.getValue("companion_save_memory").execute(buildJsonObject {
            put("key", "new"); put("value", "synthetic native value")
        })
        assertTrue(Json.parseToJsonElement((saved.single() as UIMessagePart.Text).text).jsonObject.getValue("ok").jsonPrimitive.boolean)
        assertTrue(uiStore.read().contains("synthetic native value"))
        val read = tools.getValue("companion_read_memory").execute(buildJsonObject { put("key", "before") })
        assertTrue((read.single() as UIMessagePart.Text).text.contains("synthetic existing value"))
        assertEquals(setOf("lc_memory.json"), directory.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun separateInstancesDoNotLoseConcurrentSavesOnAndroidFilesystem() = runBlocking {
        (1..12).map { index -> async(Dispatchers.IO) {
            CompanionMemoryStore(directory).save("synthetic-$index", "value-$index")
        } }.awaitAll()
        val json = Json.parseToJsonElement(CompanionMemoryStore(directory).exportJson()!!.decodeToString()).jsonObject
        assertEquals(12, json.size)
        (1..12).forEach { assertEquals("value-$it", json.getValue("synthetic-$it").jsonPrimitive.content) }
    }

    @Test fun invalidExistingFileIsNotErasedByNewSave() {
        val file = File(directory, "lc_memory.json")
        file.writeText("{broken synthetic input", Charsets.UTF_8)
        val before = file.readBytes()
        assertTrue(runCatching { CompanionMemoryStore(directory).save("new", "value") }.isFailure)
        assertArrayEquals(before, file.readBytes())
    }
}
