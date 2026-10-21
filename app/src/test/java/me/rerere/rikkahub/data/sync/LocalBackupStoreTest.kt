package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalBackupStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun privateCheckpointRoundTripKeepsSecretsFlagAndRejectsTraversal() = runBlocking {
        val directory = temporary.newFolder("versions")
        val local = LocalBackupStore(directory)
        val versions = VersionedBackup(local, temporary.root)
        val input = temporary.newFile().apply { writeText("synthetic") }
        val point = versions.publish(input, true, 100)
        assertTrue(point.includesConnectionSecrets)
        assertEquals(listOf(point), versions.list())
        val copy = temporary.newFile(); versions.download(point, copy)
        assertEquals("synthetic", copy.readText())
        assertTrue(runCatching { local.upload("../escape", input) }.isFailure)
        assertTrue(runCatching { local.upload(point.payloadName, input) }.isFailure)
        assertTrue(runCatching { local.download(point.payloadName, copy, 2) }.isFailure)
        File(directory, "writing-incomplete.tmp").writeText("partial")
        assertEquals(listOf(point), versions.list())
    }
}
