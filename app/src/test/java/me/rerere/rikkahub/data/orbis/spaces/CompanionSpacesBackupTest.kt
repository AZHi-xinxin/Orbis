package me.rerere.rikkahub.data.orbis.spaces

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CompanionSpacesBackupTest {
    @get:Rule val temp = TemporaryFolder()
    private val owner = "1c66025d-e84f-4c0e-9f08-b2ca044be81a"
    private fun store(files: File) = OrbisCompanionSpacesStore(File(files, "orbis-companion-spaces/$owner"))
    private fun image(n: Int) = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), n.toByte(), 0xff.toByte(), 0xd9.toByte())
    @Test fun maxBytesRejectsTraversalAndUnrelatedFiles() {
        assertNotNull(CompanionSpacesBackup.maxBytes("orbis-companion-spaces/$owner/index.json"))
        assertNull(CompanionSpacesBackup.maxBytes("orbis-companion-spaces/../index.json"))
        assertNull(CompanionSpacesBackup.maxBytes("orbis-companion-spaces/$owner/token.env"))
        assertNull(CompanionSpacesBackup.maxBytes("orbis-companion-spaces/$owner/index.json.new"))
    }
    @Test fun snapshotContainsOnlyConsistentIndexAndReferencedMedia() {
        val live = File(temp.root, "live"); val staged = File(temp.root, "payload/files")
        val repo = store(live); val media = repo.addMedia(image(1), "image/jpeg"); repo.addPhoto(media.id, "note")
        File(live, "orbis-companion-spaces/$owner/abandoned.new").writeText("uncommitted")
        val copied = CompanionSpacesBackup.stageSnapshot(live, staged)
        assertEquals(2, copied.size)
        CompanionSpacesBackup.validateStaged(staged.parentFile)
        assertEquals(repo.snapshot(), store(staged).snapshot())
    }
    @Test fun restoreMergesLocalOnlyContentAndMediaWithoutTouchingLive() {
        val live = File(temp.root, "live"); val incoming = File(temp.root, "incoming")
        val payload = File(temp.root, "payload")
        val old = store(live); val oldStory = old.saveHumanStory("old", "old", "letter")
        val oldMedia = old.addMedia(image(1), "image/jpeg"); old.addPhoto(oldMedia.id)
        val fresh = store(incoming); val newStory = fresh.saveHumanStory("new", "new", "letter")
        val newMedia = fresh.addMedia(image(2), "image/jpeg"); fresh.publishPost("ai", "new post", listOf(newMedia.id))
        val before = old.snapshot()
        CompanionSpacesBackup.stageSnapshot(incoming, File(payload, "files"))
        CompanionSpacesBackup.prepareBeforeJournal(payload, live)
        val merged = store(File(payload, "files")).snapshot()
        assertEquals(setOf(oldStory.id, newStory.id), merged.stories.map { it.id }.toSet())
        assertEquals(2, merged.media.size)
        assertEquals(before, old.snapshot())
        CompanionSpacesBackup.validateStaged(payload)
    }
    @Test fun conflictingSameIdRejectsBeforeLiveJournal() {
        val live = File(temp.root, "live"); val payload = File(temp.root, "payload")
        val old = store(live); val story = old.saveHumanStory("story", "prompt", "letter")
        CompanionSpacesBackup.stageSnapshot(live, File(payload, "files"))
        old.writeStoryBody(story.id, "new body", story.revision)
        val before = old.snapshot()
        assertThrows(IllegalArgumentException::class.java) { CompanionSpacesBackup.prepareBeforeJournal(payload, live) }
        assertEquals(before, old.snapshot())
    }
    @Test fun corruptedImageAndUnexpectedStagedFileRejected() {
        val live = File(temp.root, "live"); val payload = File(temp.root, "payload")
        val media = store(live).addMedia(image(1), "image/jpeg")
        CompanionSpacesBackup.stageSnapshot(live, File(payload, "files"))
        val directory = File(payload, "files/orbis-companion-spaces/$owner")
        File(directory, media.filename).writeBytes(image(9))
        assertThrows(IllegalArgumentException::class.java) { CompanionSpacesBackup.validateStaged(payload) }
        File(directory, media.filename).writeBytes(image(1)); File(directory, "secret.txt").writeText("not allowed")
        assertThrows(IllegalArgumentException::class.java) { CompanionSpacesBackup.validateStaged(payload) }
    }
    @Test fun cancelDuringSnapshotDoesNotChangeLiveData() {
        val live = File(temp.root, "live"); val repo = store(live); repo.saveHumanStory("x", "x", "letter")
        val before = repo.snapshot()
        assertThrows(IllegalStateException::class.java) { CompanionSpacesBackup.stageSnapshot(live, File(temp.root, "staging"), checkCancelled = { error("cancel") }) }
        assertEquals(before, repo.snapshot())
    }
}
