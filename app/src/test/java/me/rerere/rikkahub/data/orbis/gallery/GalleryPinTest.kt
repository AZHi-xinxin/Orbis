package me.rerere.rikkahub.data.orbis.gallery

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GalleryPinTest {
    @get:Rule val temp = TemporaryFolder()
    private var clock = 100L
    private fun root(owner: String = "owner") = File(temp.root, "orbis-gallery/$owner")
    private fun repo(owner: String = "owner", beforeCommit: () -> Unit = {}) = GalleryRepository(root(owner), beforeCommit) { clock++ }
    private fun call(action: String, args: String) = executeGalleryTool(repo(), action, Json.parseToJsonElement(args))

    @Test fun oldIndexLoadsUnpinnedAndPinSurvivesReopenAndPendingPublication() {
        val item = repo().save("旧作品", "text", "原文", "ai")
        val index = File(root(), "index.json")
        index.writeText(index.readText().replace(",\"pinned\":false", ""))
        assertFalse(repo().snapshot().items.single().pinned)
        val draft = repo().begin("新版本", "text", "ai", item.id, 1)
        repo().setPinned(item.id, true)
        assertEquals(item.copy(pinned = true), repo().snapshot().items.single())
        val pinnedBytes = index.readBytes()
        repo().setPinned(item.id, true)
        assertArrayEquals(pinnedBytes, index.readBytes())
        repo().append(draft.id, 0, "新正文", "ai")
        val updated = repo().publish(draft.id, "ai")
        assertTrue(updated.pinned)
        assertEquals("原文", repo().body(item.id, 1))
        assertEquals("新正文", repo().body(item.id, 2))
        repo().setPinned(item.id, false)
        assertEquals(updated.copy(pinned = false), repo().snapshot().items.single())
    }

    @Test fun pinnedItemsLeadAiPagesAndReadReflectsCurrentPinWithoutLosingBody() {
        val entries = (0..22).map { repo().save("作品$it", "text", "正文$it", "ai") }
        repo().setPinned(entries[0].id, true); repo().setPinned(entries[2].id, true)
        val first = call("list", "{}")["items"]!!.jsonArray
        assertEquals(listOf(entries[2].id, entries[0].id), first.take(2).map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertTrue(first.take(2).all { it.jsonObject["pinned"]!!.jsonPrimitive.boolean })
        assertTrue(first.drop(2).none { it.jsonObject["pinned"]!!.jsonPrimitive.boolean })
        val second = call("list", """{"offset":20}""")["items"]!!.jsonArray
        val ids = (first + second).map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(23, ids.distinct().size)
        val expected = listOf(entries[2].id, entries[0].id) + entries.reversed().filter { it != entries[0] && it != entries[2] }.map { it.id }
        assertEquals(expected, ids)
        assertEquals(1, call("list", """{"query":"作品0"}""")["items"]!!.jsonArray.size)
        val args = """{"id":"${entries[0].id}","revision":1}"""
        assertTrue(call("read", args)["pinned"]!!.jsonPrimitive.boolean)
        assertEquals("正文0", call("read", args)["utf8_fragment"]!!.jsonPrimitive.content)
        repo().setPinned(entries[0].id, false)
        assertFalse(call("read", args)["pinned"]!!.jsonPrimitive.boolean)
        assertEquals(entries[0].id, call("list", """{"offset":20}""")["items"]!!.jsonArray.last().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test fun pinIsScopedAndAtomicAndCannotReviveDeletedItems() {
        val item = repo().save("作品", "text", "原文", "ai")
        val before = repo().snapshot()
        assertThrows(Exception::class.java) { repo("other").setPinned(item.id, true) }
        assertThrows(Exception::class.java) { repo(beforeCommit = { error("fail") }).setPinned(item.id, true) }
        assertEquals(before, repo().snapshot())
        repo().delete(item.id, 1, "ai")
        assertThrows(Exception::class.java) { repo().setPinned(item.id, true) }
        assertTrue(call("list", "{}")["items"]!!.jsonArray.isEmpty())
    }
}
