package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.orbis.spaces.OrbisCompanionSpacesStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

class PhotoWallChatImagesTest {
    @get:Rule val temp = TemporaryFolder()
    private val owner = Uuid.random()
    private val pixels = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3)
    private fun store(id: Uuid = owner) = OrbisCompanionSpacesStore(File(temp.root, "orbis-companion-spaces/$id"))
    private fun photo(name: String) = UIMessagePart.Image("file:///private/upload/$name.png")
    private fun human(name: String) = UIMessage.user(name).copy(parts = listOf(photo(name)))
    private fun chat(vararg messages: UIMessage) = Conversation(assistantId = owner, messageNodes = messages.map { it.toMessageNode() })
    private fun code(c: Conversation, m: UIMessage) = chatImageCode(owner, c.id, m.id, (m.parts.first() as UIMessagePart.Image).url)
    private fun args(action: String, code: String? = null, note: String? = null) = buildJsonObject {
        put("action", action); code?.let { put("image_code", it) }; note?.let { put("text", it) }
    }
    private fun result(parts: List<UIMessagePart>) = Json.parseToJsonElement((parts.first() as UIMessagePart.Text).text).jsonObject
    private fun scope(c: Conversation, read: suspend () -> Conversation? = { c }, load: suspend (UIMessagePart.Image) -> ByteArray = { pixels }) =
        PhotoWallChatImages.capture(c, read, load, present = { listOf(it) })

    @Test fun listReadAndSaveThroughRealToolWithReplayAndIndependentCopy() = runTest {
        val message = human("source"); val c = chat(message)
        val source = File(temp.root, "source.jpg").apply { writeBytes(pixels) }
        val tools = createOrbisCompanionSpaceTools(scope(c, load = { source.readBytes() })) { store() }
        val tool = tools.single { it.name == "orbis_photo_wall" }
        val listing = result(tool.execute(args("chat_images")))
        val row = listing.getValue("items").jsonArray.single().jsonObject
        assertEquals(code(c, message), row.getValue("image_code").jsonPrimitive.content)
        assertFalse(listing.toString().contains("/private"))
        assertEquals(message.parts.single(), tool.execute(args("read_chat", code(c, message))).last())
        assertFalse(tool.needsApproval(args("read_chat", code(c, message))))
        assertTrue(tool.needsApproval(args("save_chat", code(c, message))))
        val first = result(tool.execute(args("save_chat", code(c, message), "留住这张")))
        val second = result(tool.execute(args("save_chat", code(c, message), "不得覆盖")))
        assertEquals(first["id"], second["id"]); assertTrue(second.getValue("already_saved").jsonPrimitive.boolean)
        assertEquals("留住这张", store().snapshot().photos.single().note)
        source.delete()
        val saved = store().snapshot().photos.single()
        assertArrayEquals(pixels, store().imageBytes(saved.mediaId).second)
        val parts = executeCompanionSpaceTool(store(), "photo_wall", buildJsonObject { put("action", "read"); put("id", saved.id) })
        assertTrue(parts.last() is UIMessagePart.Image)
    }

    @Test fun humanAndAiAttachmentsListedButNestedToolsAndLiveEventsExcluded() = runTest {
        val user = human("human")
        val ai = UIMessage.assistant("").copy(parts = listOf(photo("ai")))
        val tool = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("frame", "orbis_video_frame_now", "{}", listOf(photo("live")))))
        val event = human("screen").copy(orbisEvent = OrbisEventMetadata("e", "screen_share", "event", 1))
        val workshop = human("generated").copy(orbisEvent = OrbisEventMetadata("w", "image_workshop.job", "event", 1))
        val synthetic = human("synthetic").copy(isSynthetic = true)
        val c = chat(user, ai, tool, event, workshop, synthetic)
        val rows = result(scope(c).execute(store(), args("chat_images"))).getValue("items").jsonArray
        assertEquals(listOf(code(c, workshop), code(c, ai), code(c, user)), rows.map { it.jsonObject.getValue("image_code").jsonPrimitive.content })
        assertEquals(listOf("ai", "ai", "human"), rows.map { it.jsonObject.getValue("sender").jsonPrimitive.content })
        assertFails { scope(c).execute(store(), args("save_chat", code(c, event))) }
        assertEquals(0, store().snapshot().photos.size)
    }

    @Test fun changedOwnerConversationBranchEpochOrTurnCannotSave() = runTest {
        val m = human("one"); val c = chat(m); var current: Conversation? = c; var reads = 0
        val images = scope(c, { current }, { reads++; pixels })
        for (other in listOf(null, c.copy(assistantId = Uuid.random()), c.copy(id = Uuid.random()),
            c.copy(compactionEpoch = c.compactionEpoch + 1), c.copy(messageNodes = emptyList()),
            c.copy(messageNodes = c.messageNodes + human("next").toMessageNode()),
            c.copy(messageNodes = listOf(m.copy(parts = listOf(photo("replaced"))).toMessageNode())))) {
            current = other; assertFails { images.execute(store(), args("save_chat", code(c, m))) }
        }
        assertEquals(0, reads); assertTrue(store().snapshot().photos.isEmpty())
        current = c
        assertFails { images.execute(store(Uuid.random()), args("save_chat", code(c, m))) }
    }

    @Test fun sourceChangesDuringImageLoadDoNotWriteAnything() = runTest {
        val m = human("one"); val c = chat(m); var current = c
        val images = scope(c, { current }, { current = c.copy(messageNodes = emptyList()); pixels })
        assertFails { images.execute(store(), args("save_chat", code(c, m))) }
        assertTrue(store().snapshot().photos.isEmpty()); assertTrue(store().snapshot().media.isEmpty())
    }

    @Test fun unknownCodeArbitraryPathAndInvalidNoteFailBeforeLoading() = runTest {
        val m = human("one"); val c = chat(m); var reads = 0
        val images = scope(c, load = { reads++; pixels })
        for (a in listOf(args("save_chat", "file:///private/token"), args("save_chat", "orbisimg_" + "0".repeat(64)),
            args("save_chat", code(c, m), "x".repeat(10001)),
            JsonObject(args("save_chat", code(c, m)) + ("assistant_id" to JsonPrimitive("other"))))) {
            assertFails { images.execute(store(), a) }
        }
        assertEquals(0, reads); assertTrue(store().snapshot().media.isEmpty())
    }

    @Test fun listingPagesAndScopeLessToolReturnsClearFailure() = runTest {
        val c = chat(*(1..22).map { human("$it") }.toTypedArray())
        val images = scope(c)
        val first = result(images.execute(store(), args("chat_images")))
        assertEquals(20, first.getValue("items").jsonArray.size)
        val last = result(images.execute(store(), buildJsonObject { put("action", "chat_images"); put("offset", 20) }))
        assertEquals(2, last.getValue("items").jsonArray.size); assertEquals(JsonNull, last["next_offset"])
        val tool = createOrbisCompanionSpaceTools { store() }.single { it.name == "orbis_photo_wall" }
        val noChat = result(tool.execute(args("chat_images")))
        assertFalse(noChat.getValue("ok").jsonPrimitive.boolean)
        assertTrue(noChat.getValue("message").jsonPrimitive.content.contains("普通对话"))
    }

    @Test fun fileRootsExcludeTraversalPrivateStoresVideoAndSymlinks() {
        val files = temp.newFolder("files")
        val upload = File(files, "upload/photo.jpg").apply { parentFile.mkdirs(); writeBytes(pixels) }
        assertEquals(upload.canonicalFile, photoWallChatFile(files, upload))
        for (path in listOf("settings/token", "orbis-video-frames/frame.jpg", "upload/../settings/other.jpg", "upload-other/p.jpg")) {
            val bad = File(files, path).apply { parentFile.mkdirs(); writeBytes(pixels) }
            assertThrows(IllegalArgumentException::class.java) { photoWallChatFile(files, bad) }
        }
        val link = File(files, "upload/link.jpg")
        Files.createSymbolicLink(link.toPath(), File(files, "settings/token").toPath())
        assertThrows(IllegalArgumentException::class.java) { photoWallChatFile(files, link) }
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return } catch (_: IllegalStateException) { return }
        fail("Expected rejected image operation")
    }
}
