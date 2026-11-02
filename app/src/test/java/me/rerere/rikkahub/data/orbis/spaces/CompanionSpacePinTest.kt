package me.rerere.rikkahub.data.orbis.spaces

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.executeCompanionSpaceTool
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CompanionSpacePinTest {
    @get:Rule val temp = TemporaryFolder()
    private val owner = "1c66025d-e84f-4c0e-9f08-b2ca044be81a"
    private var clock = 100L
    private fun root(id: String = owner) = File(temp.root, "orbis-companion-spaces/$id")
    private fun repo(id: String = owner, beforeCommit: () -> Unit = {}) = OrbisCompanionSpacesStore(root(id), beforeCommit) { clock++ }
    private fun call(args: String) = Json.parseToJsonElement((executeCompanionSpaceTool(repo(), "secret_base", Json.parseToJsonElement(args)).single() as UIMessagePart.Text).text).jsonObject

    @Test fun oldStoryLoadsUnpinnedAndBothHumanAndAiEditsPreservePin() {
        val old = repo().saveHumanStory("故事", "原指令", "letter")
        val index = File(root(), "index.json")
        index.writeText(index.readText().replace(",\"pinned\":false", ""))
        assertFalse(repo().snapshot().stories.single().pinned)
        repo().setStoryPinned(old.id, true)
        assertEquals(old.copy(pinned = true), repo().snapshot().stories.single())
        val pinnedSnapshot = repo().snapshot()
        repo().setStoryPinned(old.id, true)
        assertEquals(pinnedSnapshot, repo().snapshot())
        val written = repo().writeStoryBody(old.id, "AI正文", old.revision)
        assertTrue(written.pinned); assertEquals(old.prompt, written.prompt)
        val edited = repo().saveHumanStory("新标题", "新指令", "midnight", old.id, written.revision)
        assertTrue(edited.pinned); assertEquals(written.body, edited.body)
        repo().setStoryPinned(old.id, false)
        assertEquals(edited.copy(pinned = false), repo().snapshot().stories.single())
    }

    @Test fun aiSeesPinOnEveryReadAndPinnedItemsPrecedePagination() {
        val entries = (0..22).map { repo().saveHumanStory("故事$it", "原指令$it", "letter") }
        repo().setStoryPinned(entries.first().id, true)
        val first = call("""{"action":"list"}""")["items"]!!.jsonArray
        val second = call("""{"action":"list","offset":20}""")["items"]!!.jsonArray
        assertEquals(entries.first().id, first.first().jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(first.first().jsonObject["pinned"]!!.jsonPrimitive.boolean)
        assertEquals(23, (first + second).map { it.jsonObject["id"]!!.jsonPrimitive.content }.distinct().size)
        val id = entries.first().id
        val read = call("""{"action":"read","id":"$id"}""")
        assertTrue(read["pinned"]!!.jsonPrimitive.boolean)
        assertTrue(read["content"]!!.jsonPrimitive.content.contains("原指令0"))
        val write = call("""{"action":"write","id":"$id","text":"新正文","expected_revision":1}""")
        assertTrue(write["story"]!!.jsonObject["pinned"]!!.jsonPrimitive.boolean)
        repo().setStoryPinned(id, false)
        assertFalse(call("""{"action":"read","id":"$id"}""")["pinned"]!!.jsonPrimitive.boolean)
    }

    @Test fun pinIsAtomicAndScopedAndNeverResurrectsDeletedStory() {
        val story = repo().saveHumanStory("故事", "原指令", "letter")
        val before = repo().snapshot()
        assertThrows(Exception::class.java) { repo("dcc605c7-b98a-440b-bc57-a7c56f4a7908").setStoryPinned(story.id, true) }
        assertThrows(Exception::class.java) { repo(beforeCommit = { error("fail") }).setStoryPinned(story.id, true) }
        assertEquals(before, repo().snapshot())
        repo().deleteStory(story.id, 1)
        assertThrows(Exception::class.java) { repo().setStoryPinned(story.id, true) }
        assertTrue(repo().snapshot().stories.isEmpty())
    }
}
