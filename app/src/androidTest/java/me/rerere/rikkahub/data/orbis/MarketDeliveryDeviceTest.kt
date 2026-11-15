package me.rerere.rikkahub.data.orbis

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.ToolRejectedBeforeExecutionException
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.gallery.openGallery
import me.rerere.rikkahub.data.orbis.market.*
import me.rerere.rikkahub.data.orbis.spaces.OrbisCompanionSpacesStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MarketDeliveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val temporary = TemporaryFolder(instrumentation.targetContext.cacheDir)
    private lateinit var context: Context
    private val owner = UUID.randomUUID().toString()
    private val chat = UUID.randomUUID().toString()
    @Before fun isolate() {
        check(instrumentation is IsolatedGenerationLoopRunner)
        val files = temporary.newFolder("files"); val private = temporary.newFolder("private")
        context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir() = files
            override fun getNoBackupFilesDir() = private
            override fun getApplicationContext(): Context = this
        }
    }
    private fun item(category: String) = buildJsonObject {
        put("id", UUID.randomUUID().toString()); put("state", "published"); put("version", 2)
        put("title", "合成测试设定"); put("prompt", "原设定，不得代改"); put("category", category); put("author", "测试作者")
    }
    @Test fun confirmedVersionAndTargetsAreImmutable() {
        val store = MarketDeliveries(context); val entry = store.create(item("extra"), owner, chat)
        assertTrue(store.accepts(entry.input(), owner, chat))
        assertFalse(store.accepts(entry.input(), UUID.randomUUID().toString(), chat))
        assertFalse(store.accepts(entry.input().copy(text = "replacement"), owner, chat))
        assertEquals(entry, MarketDeliveries(context).read(entry.id))
        assertEquals("原设定，不得代改", OrbisCompanionSpacesStore.open(context, owner).snapshot().stories.single().prompt)
        assertNull(entry.savedRevision)
    }
    @Test fun privateStorySaveRecoversLostAcknowledgementWithoutDuplicate() {
        val store = MarketDeliveries(context); val entry = store.create(item("extra"), owner, chat)
        val saved = store.save(entry.id, "真正的私人正文 🌙")
        assertEquals(2, saved.savedRevision)
        forgetReceipt(saved)
        assertEquals(saved, MarketDeliveries(context).save(entry.id, "真正的私人正文 🌙"))
        assertEquals(1, OrbisCompanionSpacesStore.open(context, owner).snapshot().stories.size)
        assertThrows(IllegalArgumentException::class.java) { store.save(entry.id, "不同的正文") }
    }
    @Test fun galleryLongUnicodeRoundTripAndLostReceipt() {
        val store = MarketDeliveries(context); val entry = store.create(item("grid"), owner, chat)
        assertTrue(openGallery(context, owner).snapshot().items.isEmpty())
        val body = "开头" + "a🌙你好".repeat(9001)
        val saved = store.save(entry.id, body)
        assertEquals(body, openGallery(context, owner).body(entry.id))
        forgetReceipt(saved)
        assertEquals(saved, MarketDeliveries(context).save(entry.id, body))
        assertEquals(1, openGallery(context, owner).snapshot().items.single().versions.size)
    }
    @Test fun editedOrDeletedTargetsAreNeverOverwrittenOrRecreated() {
        val store = MarketDeliveries(context); val entry = store.create(item("extra"), owner, chat)
        val shelf = OrbisCompanionSpacesStore.open(context, owner)
        shelf.writeStoryBody(entry.id, "人类自己的编辑", 1)
        assertThrows(IllegalArgumentException::class.java) { store.save(entry.id, "AI晚到的内容") }
        assertEquals("人类自己的编辑", shelf.snapshot().stories.single().body)
        val grid = store.create(item("grid"), owner, chat)
        openGallery(context, owner).discardDraftByHuman(grid.id)
        assertThrows(IllegalArgumentException::class.java) { store.save(grid.id, "不得重建") }
        assertTrue(openGallery(context, owner).snapshot().items.isEmpty())
    }
    @Test fun withdrawalCannotCreateANewLocalCopy() {
        assertThrows(IllegalArgumentException::class.java) {
            MarketDeliveries(context).create(JsonObject(item("extra") + ("state" to JsonPrimitive("withdrawn"))), owner, chat)
        }
        assertTrue(MarketDeliveries(context).list().isEmpty())
    }
    @Test fun htmlUsesTheExistingGalleryFormatWithoutPublishingToMarket() {
        val store = MarketDeliveries(context); val entry = store.create(item("grid"), owner, chat)
        val html = "<!doctype html><html><body><h1>私人礼物</h1></body></html>"
        store.save(entry.id, html, "html")
        val gallery = openGallery(context, owner)
        assertEquals("html", gallery.snapshot().items.single().kind)
        assertEquals(html, gallery.body(entry.id))
        assertThrows(IllegalArgumentException::class.java) { store.save(entry.id, html, "text") }
    }
    @Test fun actualToolRejectsHtmlStoryBeforeTouchingItsLedgerOrTarget() = runBlocking {
        val store = MarketDeliveries(context); val entry = store.create(item("extra"), owner, chat)
        var permissionChecks = 0
        val tool = marketSaveTool(context, entry) { permissionChecks++; true }
        val failure = runCatching { tool.execute(buildJsonObject {
            put("text", "<html><body>已生成的格子</body></html>"); put("kind", "html")
        }) }.exceptionOrNull()
        assertTrue(failure is ToolRejectedBeforeExecutionException)
        assertEquals(0, permissionChecks)
        assertEquals(entry, store.read(entry.id))
        assertEquals("", OrbisCompanionSpacesStore.open(context, owner).snapshot().stories.single().body)
    }
    @Test fun actualToolSavesHtmlOnlyToConfirmedGalleryWithAReceipt() = runBlocking {
        val store = MarketDeliveries(context); val entry = store.create(item("grid"), owner, chat)
        val body = "<!doctype html><html><body><h1>格子测试</h1></body></html>"
        val args = buildJsonObject { put("text", body); put("kind", "html") }
        val tool = marketSaveTool(context, entry) { true }
        assertEquals(tool.execute(args), tool.execute(args))
        assertEquals(1, store.read(entry.id).savedRevision)
        assertEquals(body, openGallery(context, owner).body(entry.id))
        assertTrue(OrbisCompanionSpacesStore.open(context, owner).snapshot().stories.isEmpty())
    }
    @Test fun revokedPermissionIsKnownNotExecutedAndDoesNotClaimAReceipt() = runBlocking {
        val store = MarketDeliveries(context); val entry = store.create(item("grid"), owner, chat)
        val failure = runCatching { marketSaveTool(context, entry) { false }.execute(buildJsonObject { put("text", "不可保存") }) }.exceptionOrNull()
        assertTrue(failure is ToolRejectedBeforeExecutionException)
        assertEquals(entry, store.read(entry.id))
        assertTrue(openGallery(context, owner).snapshot().items.isEmpty())
    }
    private fun forgetReceipt(value: MarketDelivery) {
        val file = File(context.noBackupFilesDir, "orbis-market/deliveries/${value.id}.json")
        val raw = Json.parseToJsonElement(file.readText()).jsonObject
        marketAtomic(file, JsonObject(raw + ("savedRevision" to JsonNull)).toString().toByteArray())
    }
}
