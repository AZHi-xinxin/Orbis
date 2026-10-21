package me.rerere.rikkahub.data.sync

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VersionedBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Remote : BackupObjectStore {
        val objects = linkedMapOf<String, ByteArray>()
        var failUpload = false
        var corruptDownload = false
        val writes = mutableListOf<String>()
        override suspend fun listNames() = objects.keys.toList()
        override suspend fun upload(name: String, file: File) {
            if (failUpload) error("offline")
            writes += name; objects[name] = file.readBytes()
        }
        override suspend fun download(name: String, file: File, maxBytes: Long) {
            val bytes = objects.getValue(name)
            require(bytes.size <= maxBytes)
            file.writeBytes(if(corruptDownload && name.endsWith(".payload")) ByteArray(bytes.size) else bytes)
        }
        override suspend fun delete(name: String) { objects.remove(name) }
    }
    private fun archive() = temporary.newFile().apply { writeText("synthetic archive, not personal data") }
    @Test fun publishedVersionIsVerifiedAndRestorable() = runBlocking {
        val remote = Remote(); val store = VersionedBackup(remote, temporary.root); val input = archive()
        val point = store.publish(input, false, 42)
        assertEquals(listOf(point.payloadName, point.markerName), remote.writes)
        assertEquals(listOf(point), store.list())
        val restored = temporary.newFile(); store.download(point, restored)
        assertArrayEquals(input.readBytes(), restored.readBytes())
    }
    @Test fun interruptedOrCorruptPayloadNeverPublishesMarker() = runBlocking {
        val remote = Remote(); val store = VersionedBackup(remote, temporary.root)
        remote.corruptDownload = true
        assertTrue(runCatching { store.publish(archive(), false) }.isFailure)
        assertTrue(remote.objects.keys.none(BackupRestorePoint::isMarker))
        assertTrue(store.list().isEmpty())
    }
    @Test fun offlineUploadKeepsAllPreviouslyCompletedVersions() = runBlocking {
        val remote = Remote(); val store = VersionedBackup(remote, temporary.root)
        val old = store.publish(archive(), false); val before = remote.objects.keys.toSet()
        remote.failUpload = true
        assertTrue(runCatching { store.publish(archive(), false) }.isFailure)
        assertEquals(before, remote.objects.keys); assertEquals(listOf(old), store.list())
    }
    @Test fun corruptRestoreIsRejectedBeforeAnyCallerCommitsData() = runBlocking {
        val remote = Remote(); val store = VersionedBackup(remote, temporary.root)
        val point = store.publish(archive(), false)
        remote.corruptDownload = true
        val destination = temporary.newFile()
        assertTrue(runCatching { store.download(point, destination) }.isFailure)
        assertFalse(destination.exists()); assertEquals(2, remote.objects.size)
    }
    @Test fun invalidMarkerAndRemoteTraversalAreNotListed() = runBlocking {
        val remote = Remote(); val store = VersionedBackup(remote, temporary.root)
        val point = store.publish(archive(), false)
        remote.objects["orbis_v1_00000000-0000-0000-0000-000000000000.complete.json"] = "{broken".toByteArray()
        remote.objects["../../bad.complete.json"] = byteArrayOf(1)
        assertEquals(listOf(point), store.list())
    }
    @Test fun markerCannotReferToAnotherVersion() = runBlocking {
        val remote = Remote(); val store = VersionedBackup(remote, temporary.root)
        val point = store.publish(archive(), false)
        remote.objects[point.markerName] = Json.encodeToString(point.copy(id="00000000-0000-0000-0000-000000000000")).toByteArray()
        assertTrue(store.list().isEmpty())
    }
    @Test fun pruningKeepsNewestAndNeverDeletesUnrelatedFiles() = runBlocking {
        val remote = Remote(); val store = VersionedBackup(remote, temporary.root)
        remote.objects["backup_old_manual.zip"] = byteArrayOf(7)
        val points = (1..5).map { store.publish(archive(), false, it.toLong()) }
        store.pruneAfterPublished(points.last(), 3)
        assertEquals(points.takeLast(3).reversed(), store.list())
        assertTrue(remote.objects.containsKey("backup_old_manual.zip"))
        assertEquals(7, remote.objects.size)
        assertTrue(runCatching { store.pruneAfterPublished(points.last(), 1) }.isFailure)
    }
    @Test fun oldSettingsDefaultToNoNetworkActivityOrSecretExport() {
        val config = Json.decodeFromString<AutoBackupConfig>("{}")
        config.validate(); assertFalse(config.enabled); assertFalse(config.includeConnectionSecrets)
    }
}
