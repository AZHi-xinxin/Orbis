package me.rerere.rikkahub.data.orbis.spaces

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CompanionSpacesStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val owner = "1c66025d-e84f-4c0e-9f08-b2ca044be81a"
    private val other = "dcc605c7-b98a-440b-bc57-a7c56f4a7908"
    private fun root(id: String = owner) = File(temp.root, "orbis-companion-spaces/$id")
    private fun store(id: String = owner, commit: () -> Unit = {}) = OrbisCompanionSpacesStore(root(id), commit) { 123456L }
    // Minimal image header is sufficient for the storage unit: host importer separately decodes images.
    private fun image(seed: Int = 1) = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), seed.toByte(), 0xff.toByte(), 0xd9.toByte())

    @Test fun emptyReadIsReadOnlyAndOwnerIsRequired() {
        assertEquals(0, store().snapshot().stories.size); assertFalse(root().exists())
        assertThrows(IllegalArgumentException::class.java) { OrbisCompanionSpacesStore(File(temp.root, "orbis-companion-spaces/../secret")) }
    }
    @Test fun promptBodyAndPaperPersistIndependently() {
        val repo = store(); val story = repo.saveHumanStory("番外", "人类原指令", "floral")
        val written = repo.writeStoryBody(story.id, "AI 原样写下的正文 🌙", story.revision)
        assertEquals("人类原指令", written.prompt)
        val revised = repo.saveHumanStory("新标题", "人类新指令", "journal", story.id, written.revision)
        assertEquals(written.body, revised.body)
        assertEquals(revised, store().snapshot().stories.single())
    }
    @Test fun staleEditorsCannotClobberNewerStoryOrPhotoNote() {
        val repo = store(); val story = repo.saveHumanStory("番外", "指令", "letter")
        repo.writeStoryBody(story.id, "新内容", story.revision)
        assertThrows(IllegalArgumentException::class.java) { store().writeStoryBody(story.id, "旧编辑", story.revision) }
        val media = repo.addMedia(image(), "image/jpeg"); val photo = repo.addPhoto(media.id)
        repo.setPhotoNote(photo.id, "新的背面", photo.revision)
        assertThrows(IllegalArgumentException::class.java) { store().setPhotoNote(photo.id, "过期备注", photo.revision) }
    }
    @Test fun assistantsCannotReadWriteDeleteEachOthersObjects() {
        val story = store().saveHumanStory("私有番外", "内容", "letter")
        assertTrue(store(other).snapshot().stories.isEmpty())
        assertThrows(IllegalStateException::class.java) { store(other).writeStoryBody(story.id, "x", 1) }
        assertThrows(IllegalStateException::class.java) { store(other).deleteStory(story.id, 1) }
        val media = store().addMedia(image(), "image/jpeg")
        assertThrows(IllegalStateException::class.java) { store(other).imageBytes(media.id) }
    }
    @Test fun failedAtomicCommitPreservesOriginalIndex() {
        val story = store().saveHumanStory("番外", "原指令", "letter")
        val before = File(root(), "index.json").readBytes()
        assertThrows(IllegalStateException::class.java) { store(commit = { error("simulated failure") }).writeStoryBody(story.id, "不应该出现", 1) }
        assertArrayEquals(before, File(root(), "index.json").readBytes())
        assertEquals("", store().snapshot().stories.single().body)
    }
    @Test fun corruptOrMissingIndexCannotResetExistingShelf() {
        store().saveHumanStory("番外", "不能丢", "letter")
        File(root(), "index.json").writeText("invalid")
        assertThrows(Exception::class.java) { store().saveHumanStory("new", "new", "letter") }
        assertEquals("invalid", File(root(), "index.json").readText())
        File(root(), "index.json").delete(); File(root(), "unknown.image").writeText("preserve")
        assertThrows(IllegalArgumentException::class.java) { store().snapshot() }
    }
    @Test fun socialActorsAndRepliesPersistAndLikesAreIdempotent() {
        val repo = store(); val post = repo.publishPost("human", "今天很好")
        repo.likePost(post.id, "ai", true); repo.likePost(post.id, "ai", true)
        val first = repo.commentPost(post.id, "ai", "我也觉得")
        val response = repo.commentPost(post.id, "human", "那就记住", first.id)
        val saved = store().snapshot().posts.single()
        assertEquals(setOf("ai"), saved.likes); assertEquals(first.id, response.replyTo)
        assertEquals(listOf("ai", "human"), saved.comments.map { it.actor })
        repo.likePost(post.id, "ai", false); assertTrue(repo.snapshot().posts.single().likes.isEmpty())
    }
    @Test fun invalidActorsOrCrossPostReplyRejectedWithoutMutation() {
        val repo = store(); val one = repo.publishPost("human", "1"); val two = repo.publishPost("ai", "2")
        val comment = repo.commentPost(one.id, "human", "x")
        val before = repo.snapshot()
        assertThrows(IllegalArgumentException::class.java) { repo.commentPost(two.id, "ai", "y", comment.id) }
        assertThrows(IllegalArgumentException::class.java) { repo.publishPost("human:someone", "x") }
        assertThrows(IllegalArgumentException::class.java) { repo.deletePost(one.id, "ai") }
        assertEquals(before, repo.snapshot())
    }
    @Test fun photoBackOrderStyleDeletionAndSharedMediaWork() {
        val repo = store(); val media = repo.addMedia(image(), "image/jpeg")
        val first = repo.addPhoto(media.id, "一起的日常"); val second = repo.addPhoto(media.id)
        repo.movePhoto(second.id, 0); repo.setWallStyle("hanging")
        assertEquals(listOf(second.id, first.id), store().snapshot().photos.map { it.id })
        assertEquals("hanging", store().snapshot().wallStyle)
        repo.deletePhoto(first.id); assertTrue(repo.mediaFile(media.id).exists())
        repo.publishPost("human", "也留在共同空间", listOf(media.id)); repo.deletePhoto(second.id)
        assertArrayEquals(image(), repo.imageBytes(media.id).second)
    }
    @Test fun imageSignatureSizeHashAndScopedLookupAreValidated() {
        val repo = store()
        assertThrows(IllegalArgumentException::class.java) { repo.addMedia("not image".toByteArray(), "image/jpeg") }
        val media = repo.addMedia(image(), "image/jpeg")
        assertEquals(media.id, repo.addMedia(image(), "image/jpeg").id)
        assertThrows(IllegalStateException::class.java) { repo.imageBytes("../index.json") }
        File(root(), media.filename).writeBytes(image(2))
        assertThrows(IllegalArgumentException::class.java) { repo.imageBytes(media.id) }
    }
    @Test fun mediaCoverAndPhotoSurviveReopenAndBackupValidation() {
        val repo = store(); val media = repo.addMedia(image(), "image/jpeg")
        repo.setCover(media.id); repo.addPhoto(media.id, "背面")
        validateCompanionSpaceBackup(root().parentFile)
        assertEquals(media.id, store().snapshot().coverMediaId)
        File(root(), "unexpected.txt").writeText("not a shelf file")
        assertThrows(IllegalArgumentException::class.java) { validateCompanionSpaceBackup(root().parentFile) }
    }
    @Test fun videoImportIsIdempotentAndTenPerCallEvenAfterDeletion() = runBlocking {
        val repo = store(); val source = File(temp.root, "frame.jpg").apply { writeBytes(image()) }
        val first = repo.importVideoFrame(source, "镜头", "call-1", "frame-1")
        assertEquals(first.id, store().importVideoFrame(source, "另备注", "call-1", "frame-1").id)
        for (i in 2..10) repo.importVideoFrame(source, "", "call-1", "frame-$i")
        repo.deletePhoto(first.id)
        try { repo.importVideoFrame(source, "", "call-1", "frame-11"); fail("should reject eleventh") } catch (_: IllegalArgumentException) { }
        try { repo.importVideoFrame(source, "", "call-1", "frame-1"); fail("should not resurrect") } catch (_: IllegalStateException) { }
        assertEquals(9, repo.snapshot().photos.size)
        assertNotNull(repo.importVideoFrame(source, "", "call-2", "frame-1"))
        source.delete(); assertArrayEquals(image(), repo.imageBytes(repo.snapshot().photos.first().mediaId).second)
    }
    @Test fun cancelledOrMalformedOperationsLeavePersistedRevisionUnchanged() {
        val repo = store(); val post = repo.publishPost("human", "one")
        val before = repo.snapshot()
        assertThrows(IllegalArgumentException::class.java) { repo.publishPost("ai", "bad", listOf("../../outside")) }
        assertThrows(IllegalArgumentException::class.java) { repo.setWallStyle("unknown") }
        assertThrows(IllegalArgumentException::class.java) { repo.commentPost(post.id, "ai", "") }
        assertEquals(before, repo.snapshot())
    }
}
