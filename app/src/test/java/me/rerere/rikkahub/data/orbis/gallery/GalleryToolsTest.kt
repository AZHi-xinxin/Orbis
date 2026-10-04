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
}
