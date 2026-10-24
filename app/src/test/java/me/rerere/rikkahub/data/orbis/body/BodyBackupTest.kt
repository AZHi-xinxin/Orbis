package me.rerere.rikkahub.data.orbis.body

import java.io.File
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.orbis.activities.ActivitiesBackup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BodyBackupTest {
    @get:Rule val tmp = TemporaryFolder()
    private val path = "orbis-activities/owner/body.json"
    @Test fun bodySnapshotIsBackedUpAndRestoreConflictPreservesLocalState() {
        val live = tmp.newFolder("live"); val payload = tmp.newFolder("payload")
        val source = File(live, path); source.parentFile.mkdirs()
        val original = BodyRules.json.encodeToString(BodyState()); source.writeText(original)
        val staged = ActivitiesBackup.stageSnapshot(live, File(payload, "files"))
        assertEquals(listOf(path), staged.map { it.first })
        ActivitiesBackup.validateStaged(payload); ActivitiesBackup.prepareBeforeJournal(payload, live)
        val newer = BodyRules.json.encodeToString(BodyState(revision = 3)); source.writeText(newer)
        assertThrows(IllegalArgumentException::class.java) { ActivitiesBackup.prepareBeforeJournal(payload, live) }
        assertEquals(newer, source.readText())
    }
    @Test fun invalidBodySnapshotAndTraversalAreRejected() {
        assertNull(ActivitiesBackup.maxBytes("orbis-activities/../body.json"))
        assertThrows(IllegalStateException::class.java) { ActivitiesBackup.validate(path, "broken".toByteArray()) }
    }
}
