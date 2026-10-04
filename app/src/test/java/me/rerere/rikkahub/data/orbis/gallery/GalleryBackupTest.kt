package me.rerere.rikkahub.data.orbis.gallery

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GalleryBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repo(files: File, owner: String = "owner") = GalleryRepository(File(files, "orbis-gallery/$owner"))
    @Test fun backupRoundTripKeepsVersionsAnswersAndNameExcludesDrafts() {
        val live = temporary.newFolder("live"); val source = repo(live)
        source.rename("阿止")
        val form = GalleryQuestionnaire(questions = listOf(GalleryQuestion("q1", "题目")))
        val item = source.save("问卷", "questionnaire", galleryJson.encodeToString(form), "ai")
        val ref = source.submit(item.id, 1, "human", mapOf("q1" to listOf("本人回答")))
        source.save("新版", "questionnaire", galleryJson.encodeToString(form.copy(description = "新版说明")), "ai", item.id, 1)
        source.begin("草稿", "text", "ai")
        val payload = temporary.newFolder("payload"); val files = File(payload, "files")
        val exported = GalleryBackup.stageSnapshot(live, files)
        assertEquals(4, exported.size); assertTrue(exported.none { "draft" in it.first || it.first.endsWith(".new") })
        GalleryBackup.validateStaged(payload)
        val restored = repo(files)
        assertEquals("阿止", restored.snapshot().customName); assertEquals(2, restored.snapshot().items.single().versions.size)
        assertEquals("本人回答", restored.answers(ref.id).answers["q1"]!!.single())
    }
    @Test fun aggregateAdmissionPrecedesCopyAndCancellationIsObserved() {
        val live = temporary.newFolder("live"); repo(live).save("记录", "text", "sample")
        val destination = temporary.newFolder("out")
        assertThrows(IllegalStateException::class.java) { GalleryBackup.stageSnapshot(live, destination, beforeFile = { _, _ -> error("reject") }) }
        assertTrue(destination.listFiles().orEmpty().isEmpty())
        assertThrows(IllegalStateException::class.java) { GalleryBackup.stageSnapshot(live, destination, checkCancelled = { error("cancel") }) }
    }
    @Test fun malformedDynamicPathsAreNeverBackupEntries() {
        listOf("orbis-gallery/../../secret", "orbis-gallery/owner/draft-abc.txt", "orbis-gallery/owner/index.json.new", "orbis-gallery/owner/nested/file.txt", "orbis-gallery/owner/unknown.txt").forEach {
            assertNull(it, GalleryBackup.maxBytes(it))
        }
        assertEquals(GalleryLimits.INDEX_BYTES, GalleryBackup.maxBytes("orbis-gallery/owner/index.json"))
        assertEquals(GalleryLimits.BODY_BYTES, GalleryBackup.maxBytes("orbis-gallery/owner/abc-r1.txt"))
    }
    @Test fun corruptBodyAndUnreferencedEntryRejectStaging() {
        val live = temporary.newFolder("live"); val item = repo(live).save("记录", "text", "correct")
        val payload = temporary.newFolder("payload")
        GalleryBackup.stageSnapshot(live, File(payload, "files"))
        File(payload, "files/orbis-gallery/owner/${item.id}-r1.txt").writeText("bad")
        assertThrows(IllegalArgumentException::class.java) { GalleryBackup.validateStaged(payload) }
    }
    @Test fun restorePreservesExtraLiveRecordsAndRejectsChangedSameId() {
        val live = temporary.newFolder("live"); val repository = repo(live)
        val first = repository.save("一", "text", "same")
        val payload = temporary.newFolder("payload")
        GalleryBackup.stageSnapshot(live, File(payload, "files"))
        repository.save("二", "text", "new")
        GalleryBackup.prepareBeforeJournal(payload, live)
        val index = galleryJson.decodeFromString<GalleryIndex>(File(payload, "files/orbis-gallery/owner/index.json").readText())
        assertEquals(2, index.items.size)
        val conflictedPayload = temporary.newFolder("conflicted")
        GalleryBackup.stageSnapshot(live, File(conflictedPayload, "files"))
        repository.save("一", "text", "changed", id = first.id, expectedRevision = 1)
        assertThrows(IllegalArgumentException::class.java) { GalleryBackup.prepareBeforeJournal(conflictedPayload, live) }
        assertEquals("changed", repository.body(first.id))
    }
}
