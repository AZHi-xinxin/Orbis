package me.rerere.rikkahub.data.orbis.gallery

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GalleryToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repo() = GalleryRepository(File(temporary.root, "orbis-gallery/owner"))
    private fun call(action: String, json: String) = executeGalleryTool(repo(), action, Json.parseToJsonElement(json))
    @Test fun registrationIsLazyAndWritesUseRememberableHostApproval() {
        val read = createGalleryTools({ error("not invoked") }, false)
        assertEquals(setOf("orbis_gallery_list", "orbis_gallery_read", "orbis_gallery_answers", "orbis_gallery_answer_list"), read.map { it.name }.toSet())
        read.forEach { assertFalse(it.needsApproval(JsonObject(emptyMap()))); assertNull(it.hostApproval) }
        createGalleryTools({ error("not invoked") }, true).filter { tool -> read.none { it.name == tool.name } }.forEach {
            assertTrue(it.needsApproval(JsonObject(emptyMap()))); assertNotNull(it.hostApproval)
        }
    }
    @Test fun catalogueDoesNotReturnBodyOrAnswersAndResultIsBounded() {
        repeat(21) { repo().save("name$it", "text", "PRIVATE_BODY_".repeat(1000), "ai") }
        val result = call("list", "{}")
        assertEquals(20, result["items"]!!.jsonArray.size); assertEquals(20, result["next_offset"]!!.jsonPrimitive.int)
        assertFalse(result.toString().contains("PRIVATE_BODY")); assertTrue(result.toString().toByteArray().size < 32 * 1024)
        assertEquals(1, call("list", """{"query":"name20"}""")["items"]!!.jsonArray.size)
    }
    @Test fun forgedActorAndInvalidTypesAreRejected() {
        listOf("""{"actor":"human"}""", """{"offset":"0"}""", """{"offset":-1}""", """{"query":true}""").forEach {
            assertThrows(Exception::class.java) { call("list", it) }
        }
        assertThrows(IllegalArgumentException::class.java) { call("begin", """{"title":"x","kind":"text","actor":"human"}""") }
    }
    @Test fun toolWritingPublishesWithoutEchoingHtmlInResult() {
        val draft = call("begin", """{"title":"礼物","kind":"html"}""")["draft_id"]!!.jsonPrimitive.content
        call("append", """{"draft_id":"$draft","expected_bytes":0,"text":"<p>private body</p>"}""")
        val published = call("publish", """{"draft_id":"$draft"}""")
        assertFalse(published.toString().contains("private body"))
        assertEquals("ai", repo().snapshot().items.single().author)
    }
    @Test fun failuresDoNotExposePathsOrSecrets() = runBlocking {
        val tool = createGalleryTools({ error("token secret /data/private") }, false).first()
        val result = (tool.execute(JsonObject(emptyMap())).single() as UIMessagePart.Text).text
        assertFalse(result.contains("secret")); assertFalse(result.contains("/data")); assertTrue(result.contains("\"ok\":false"))
    }

    @Test fun observedEmptyIdAndZeroRevisionCreatesOnlyANewAiDraftThenPublishes() = runBlocking {
        val old = repo().save("existing", "text", "untouched", "ai")
        val begin = createGalleryTools(::repo, true).single { it.name == "orbis_gallery_begin" }
        val args = Json.parseToJsonElement("""{"title":"ORBIS_DEV87_GALLERY_A91F","kind":"text","id":"","expected_revision":0}""")
        assertTrue(begin.needsApproval(args))
        assertNotNull(begin.hostApproval)
        val result = Json.parseToJsonElement((begin.execute(args).single() as UIMessagePart.Text).text).jsonObject
        assertTrue(result["ok"]!!.jsonPrimitive.boolean)
        val draftId = result["draft_id"]!!.jsonPrimitive.content
        val draft = repo().drafts().single()
        assertEquals(draftId, draft.id)
        assertTrue(GalleryLimits.id(draft.itemId))
        assertNotEquals(old.id, draft.itemId)
        assertEquals("ai", draft.actor)
        assertEquals(0, draft.expectedRevision)
        assertEquals(listOf(old), repo().snapshot().items)
        call("append", """{"draft_id":"$draftId","expected_bytes":0,"text":"test body"}""")
        call("publish", """{"draft_id":"$draftId"}""")
        val published = repo().snapshot().items.single { it.id == draft.itemId }
        assertEquals("ORBIS_DEV87_GALLERY_A91F", published.title)
        assertEquals(1, published.current.revision)
        assertEquals("ai", published.author)
        assertEquals("test body", repo().body(published.id))
        assertEquals(old, repo().snapshot().items.single { it.id == old.id })
        assertEquals("untouched", repo().body(old.id))
    }

    @Test fun omittedIdAndEmptyIdWithoutRevisionBothHaveTheSameNewDraftMeaning() {
        for (json in listOf("""{"title":"omitted","kind":"text"}""",
            """{"title":"empty","kind":"text","id":""}""")) {
            val id = call("begin", json)["draft_id"]!!.jsonPrimitive.content
            val draft = repo().drafts().single { it.id == id }
            assertEquals(0, draft.expectedRevision)
            assertTrue(GalleryLimits.id(draft.itemId))
        }
        assertEquals(2, repo().drafts().map { it.itemId }.distinct().size)
        assertTrue(repo().snapshot().items.isEmpty())
    }

    @Test fun emptyIdCannotDiscardAnEditRevisionOrRepairMalformedArguments() {
        val before = repo().save("existing", "text", "original", "ai")
        for (extra in listOf("\"id\":\"\",\"expected_revision\":1", "\"expected_revision\":1",
            "\"id\":null", "\"id\":0", "\"id\":\" \"", "\"id\":\"../bad\"",
            "\"id\":\"\",\"expected_revision\":null", "\"id\":\"\",\"expected_revision\":\"0\"",
            "\"id\":\"\",\"expected_revision\":-1", "\"id\":\"\",\"assistant_id\":\"other\"")) {
            assertThrows(Exception::class.java) { call("begin", """{"title":"bad","kind":"text",$extra}""") }
            assertTrue(repo().drafts().isEmpty())
            assertEquals(listOf(before), repo().snapshot().items)
        }
    }

    @Test fun nonemptyIdStillRequiresTheSameShelfAndCurrentRevision() {
        val owned = repo().save("owned", "text", "v1", "ai")
        val foreignRepo = GalleryRepository(File(temporary.root, "orbis-gallery/other"))
        val foreign = foreignRepo.save("foreign", "text", "private", "ai")
        for ((id, revision) in listOf(owned.id to 0, owned.id to 2, foreign.id to 1, "unknown" to 0)) {
            assertThrows(Exception::class.java) {
                call("begin", """{"title":"edit","kind":"text","id":"$id","expected_revision":$revision}""")
            }
        }
        assertTrue(repo().drafts().isEmpty())
        val draftId = call("begin", """{"title":"updated","kind":"text","id":"${owned.id}","expected_revision":1}""")["draft_id"]!!.jsonPrimitive.content
        call("append", """{"draft_id":"$draftId","expected_bytes":0,"text":"v2"}""")
        call("publish", """{"draft_id":"$draftId"}""")
        val updated = repo().snapshot().items.single()
        assertEquals(owned.id, updated.id)
        assertEquals(2, updated.current.revision)
        assertEquals("v1", repo().body(owned.id, 1))
        assertEquals("v2", repo().body(owned.id))
        assertEquals(listOf(foreign), foreignRepo.snapshot().items)
        assertEquals("private", foreignRepo.body(foreign.id))
    }

    @Test fun emptyIdsRemainInvalidForDestructiveAndExistingItemOperations() {
        val item = repo().save("existing", "text", "untouched", "ai")
        for ((action, args) in listOf(
            "delete" to """{"id":"","expected_revision":1}""",
            "read" to """{"id":"","revision":1}""",
            "answer_begin" to """{"id":"","expected_revision":1}""",
            "answer" to """{"id":"","expected_revision":1,"answers":{}}""",
            "cancel" to """{"draft_id":""}""",
            "publish" to """{"draft_id":""}""",
        )) {
            assertThrows(Exception::class.java) { call(action, args) }
            assertEquals(listOf(item), repo().snapshot().items)
            assertTrue(repo().drafts().isEmpty())
        }
    }
}
