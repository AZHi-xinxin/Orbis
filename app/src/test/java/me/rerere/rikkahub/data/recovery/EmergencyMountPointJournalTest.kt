package me.rerere.rikkahub.data.recovery

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmergencyMountPointJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun value() = EmergencyMountJournalValue(
        sourceRoot = EmergencyMountIdentity(1, 2, 10476, 10476, 448, true),
        records = listOf(EmergencyMountRecord(
            "workspaces/ffe7c07b-c272-465b-a908-1e8df6545565/linux/workspace",
            EmergencyMountIdentity(1, 3, 10476, 10476, 0, true))),
    )

    @Test fun intentIsReadableAfterRestartAndRetirementKeepsEvidence() {
        val directory = File(temporary.root, "journal")
        val synced = mutableListOf<File>()
        EmergencyFileMountPointJournal(directory) { synced += it }.create(value())
        assertEquals(listOf(directory, directory.parentFile), synced)
        val restarted = EmergencyFileMountPointJournal(directory) { synced += it }
        assertEquals(value(), restarted.read())
        restarted.clear(value())
        assertNull(restarted.read())
        assertEquals(1, directory.listFiles()!!.count { it.name.startsWith("observed-restored-") })
        restarted.create(value())
        assertEquals(value(), restarted.read())
    }

    @Test fun pendingIntentCannotBeOverwrittenOrClearedAgainstDifferentIdentity() {
        val journal = EmergencyFileMountPointJournal(File(temporary.root, "journal")) {}
        journal.create(value())
        assertThrows(IOException::class.java) { journal.create(value()) }
        assertThrows(IOException::class.java) { journal.clear(value().copy(sourceRoot = value().sourceRoot.copy(inode = 999))) }
        assertEquals(value(), journal.read())
    }

    @Test fun incompleteOrDeepJournalFailsBeforeAnyGrant() {
        val directory = temporary.newFolder("journal")
        val file = File(directory, "pending.json")
        val journal = EmergencyFileMountPointJournal(directory) {}
        file.writeText("{\"schema\":")
        assertThrows(IOException::class.java) { journal.read() }
        file.writeText("{\"x\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}")
        assertThrows(IOException::class.java) { journal.read() }
    }

    @Test fun directorySyncFailureLeavesTheDurableIntentAvailableToNextRescue() {
        val directory = File(temporary.root, "journal")
        val journal = EmergencyFileMountPointJournal(directory) { throw IOException("synthetic sync failure") }
        assertThrows(IOException::class.java) { journal.create(value()) }
        assertEquals(value(), EmergencyFileMountPointJournal(directory) {}.read())
    }
}
