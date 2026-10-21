package me.rerere.rikkahub.data.orbis.activities

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ActivitiesBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true }
    private fun write(root: File, name: String, value: String) = File(root, name).also {
        it.parentFile.mkdirs(); it.writeText(value)
    }
    @Test fun snapshotUsesCommittedBackupNotIncompleteNewDocument() {
        val root = temporary.newFolder("live"); val out = temporary.newFolder("snapshot")
        val name = "orbis-activities/owner/pet.json"
        val old = json.encodeToString(PetState())
        write(root, name, "partial"); write(root, "$name.bak", old)
        val result = ActivitiesBackup.stageSnapshot(root, out)
        assertEquals(listOf(name), result.map { it.first })
        assertEquals(old, result.single().second.readText())
        assertEquals("partial", File(root, name).readText())
    }
    @Test fun restoreAcceptsFreshOrIdenticalButNeverOverwritesDifferentProgress() {
        val payload = temporary.newFolder("payload"); val live = temporary.newFolder("live")
        val name = "orbis-activities/owner/study.json"
        val old = json.encodeToString(StudyState())
        write(File(payload, "files"), name, old)
        ActivitiesBackup.prepareBeforeJournal(payload, live)
        write(live, name, old)
        ActivitiesBackup.prepareBeforeJournal(payload, live)
        val newer = json.encodeToString(StudyState(progress=listOf(StudyProgress("数学", 1, "新计划"))))
        write(live, name, newer)
        assertThrows(IllegalArgumentException::class.java) { ActivitiesBackup.prepareBeforeJournal(payload, live) }
        assertEquals(newer, File(live, name).readText())
    }
    @Test fun invalidImportedProgressAndUnknownPathsRejectedBeforeJournal() {
        val name = "orbis-activities/owner/study.json"
        val broken = StudyState(progress=listOf(StudyProgress("数学", -1)))
        assertThrows(IllegalStateException::class.java) { ActivitiesBackup.validate(name, json.encodeToString(broken).toByteArray()) }
        assertNull(ActivitiesBackup.maxBytes("orbis-activities/../study.json"))
        assertEquals(listOf("files/$name.bak", "files/$name.new"), ActivitiesBackup.sidecarPaths(setOf("files/$name")))
    }
    @Test fun ownersRemainSeparateAndCancellationDoesNotTouchLiveFiles() {
        val root = temporary.newFolder("owners"); val out = temporary.newFolder("snapshot")
        val bytes = json.encodeToString(PetState())
        write(root, "orbis-activities/first/pet.json", bytes)
        write(root, "orbis-activities/second/pet.json", bytes)
        assertEquals(2, ActivitiesBackup.stageSnapshot(root, out).size)
        val cancelled = temporary.newFolder("cancelled")
        assertThrows(IllegalStateException::class.java) { ActivitiesBackup.stageSnapshot(root, cancelled, { error("cancelled") }) }
        assertEquals(bytes, File(root, "orbis-activities/first/pet.json").readText())
        assertTrue(cancelled.listFiles().orEmpty().isEmpty())
    }
}
