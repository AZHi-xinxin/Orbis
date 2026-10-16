package me.rerere.rikkahub.data.orbis.character

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.sync.PendingRestore
import me.rerere.rikkahub.data.sync.RestoreFailedException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64

class CharacterBackupTest {
    @get:Rule val temp = TemporaryFolder()
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQAAAABJRU5ErkJggg==")
    private val sha get() = characterSha(png)
    private fun store(root: File = File(temp.root, "live"), id: String = owner) = CharacterStore(root, id)
    private fun install(root: File = File(temp.root, "live"), id: String = owner, greeting: String = "hello") {
        val s = store(root, id)
        s.install(CharacterDraft(CharacterPack(sha256 = sha, width = 1, height = 1), CharacterInteractions(), png,
            CharacterPresentation(greeting = greeting)), s.read().revision)
    }
    private fun staged(): File {
        install()
        val payload = File(temp.root, "payload")
        CharacterBackup.stageSnapshot(File(temp.root, "live"), File(payload, "no-backup"), setOf(owner))
        return payload
    }

    @Test fun snapshotIncludesOnlyCommittedReferencesForKnownOwners() {
        install(); install(id = other)
        val extra = File(temp.root, "live/orbis-characters/$owner/access_token")
        extra.writeText("not portable")
        File(extra.parentFile, "pending-test.tmp").writeText("unfinished")
        File(extra.parentFile, "${"a".repeat(64)}.png").writeBytes(png)
        val files = CharacterBackup.stageSnapshot(File(temp.root, "live"), File(temp.root, "snapshot"), setOf(owner))
        assertEquals(setOf("orbis-characters/$owner/profile.json", "orbis-characters/$owner/$sha.png"), files.map { it.first }.toSet())
        assertTrue(files.all { it.second.isFile })
        assertTrue(extra.exists())
    }

    @Test fun missingProfilesDoNotCreatePhantomOwners() {
        assertTrue(CharacterBackup.stageSnapshot(File(temp.root, "live"), File(temp.root, "snapshot"), setOf(owner)).isEmpty())
        assertFalse(File(temp.root, "live/orbis-characters/$owner").exists())
    }

    @Test fun emptyAndUnconfiguredOwnerDirectoriesRemainValidForSnapshot() {
        val folder = File(temp.root, "live/orbis-characters/$owner")
        assertTrue(folder.mkdirs())
        assertTrue(CharacterBackup.stageSnapshot(File(temp.root, "live"), File(temp.root, "empty"), setOf(owner)).isEmpty())
        File(folder, "pending-test.tmp").writeText("unfinished")
        assertTrue(CharacterBackup.stageSnapshot(File(temp.root, "live"), File(temp.root, "unconfigured"), setOf(owner)).isEmpty())
        assertFalse(File(folder, "profile.json").exists())
    }

    @Test fun unreadableOwnerCannotBeSilentlyOmittedFromSnapshot() {
        val folder = File(temp.root, "live/orbis-characters/$owner")
        assertTrue(folder.mkdirs())
        var consumed = false
        val failure = assertThrows(IllegalArgumentException::class.java) {
            store().withBackupSnapshot(directoryEntries = { null }) { _, _ -> consumed = true }
        }
        assertEquals("character_backup_invalid", failure.message)
        assertFalse(consumed)
        assertFalse(File(folder, "profile.json").exists())
    }

    @Test fun filesInPlaceOfSourceDirectoriesRejectSnapshot() {
        val live = File(temp.root, "live")
        val namespace = File(live, "orbis-characters")
        assertTrue(live.mkdirs())
        namespace.writeText("not a directory")
        assertThrows(IllegalArgumentException::class.java) {
            CharacterBackup.stageSnapshot(live, File(temp.root, "snapshot"), setOf(owner))
        }
        assertTrue(namespace.delete())
        assertTrue(namespace.mkdir())
        File(namespace, owner).writeText("not a directory")
        assertThrows(IllegalArgumentException::class.java) {
            CharacterBackup.stageSnapshot(live, File(temp.root, "snapshot"), setOf(owner))
        }
    }

    @Test fun absentAndEmptyStagedNamespacesRemainValid() {
        val payload = File(temp.root, "payload")
        CharacterBackup.validateStaged(payload, setOf(owner))
        assertTrue(File(payload, "no-backup/orbis-characters").mkdirs())
        CharacterBackup.validateStaged(payload, setOf(owner))
    }

    @Test fun namespaceEnumerationFailureRejectsWholePayload() {
        val payload = staged()
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CharacterBackup.validateStaged(payload, setOf(owner), directoryEntries = { directory ->
                if (directory.name == CharacterBackup.DIRECTORY) null else directory.listFiles()
            })
        }
        assertEquals("character_backup_invalid", failure.message)
        assertTrue(File(payload, "no-backup/orbis-characters/$owner/profile.json").isFile)
    }

    @Test fun ownerEnumerationFailureRejectsWholePayload() {
        val payload = staged()
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CharacterBackup.validateStaged(payload, setOf(owner), directoryEntries = { directory ->
                if (directory.name == owner) null else directory.listFiles()
            })
        }
        assertEquals("character_backup_invalid", failure.message)
        assertArrayEquals(png, File(payload, "no-backup/orbis-characters/$owner/$sha.png").readBytes())
    }

    @Test fun validatesHashesDimensionsAndInvokesPixelDecoder() {
        val payload = staged()
        var calls = 0
        CharacterBackup.validateStaged(payload, setOf(owner), verifyPixels = { _, width, height ->
            assertEquals(1, width); assertEquals(1, height); calls++
        })
        assertEquals(1, calls)
        File(payload, "no-backup/orbis-characters/$owner/$sha.png").appendBytes(byteArrayOf(42))
        assertThrows(IllegalArgumentException::class.java) { CharacterBackup.validateStaged(payload, setOf(owner)) }
    }

    @Test fun foreignOwnerIsRejectedNotMappedToCurrentAssistant() {
        assertThrows(IllegalArgumentException::class.java) { CharacterBackup.validateStaged(staged(), setOf(other)) }
    }

    @Test fun missingAndUnreferencedFilesRejectWholePayload() {
        val payload = staged()
        val folder = File(payload, "no-backup/orbis-characters/$owner")
        File(folder, "access_token").writeText("not allowed")
        assertThrows(IllegalArgumentException::class.java) { CharacterBackup.validateStaged(payload, setOf(owner)) }
        File(folder, "access_token").delete()
        File(folder, "$sha.png").delete()
        assertThrows(IllegalArgumentException::class.java) { CharacterBackup.validateStaged(payload, setOf(owner)) }
    }

    @Test fun onlyExactOwnerProfilesAndPngPathsAreAccepted() {
        listOf("orbis-characters/../token", "orbis-characters/$owner/../token", "orbis-characters/$owner/profile.json.bak",
            "orbis-characters/$owner/widget-bindings.json", "orbis-characters/$owner/${sha.uppercase()}.png",
            "orbis-characters/current/profile.json").forEach { assertNull(CharacterBackup.maxBytes(it)) }
        assertEquals(CharacterRules.JSON_BYTES, CharacterBackup.maxBytes("orbis-characters/$owner/profile.json"))
        assertEquals(CharacterRules.IMAGE_BYTES, CharacterBackup.maxBytes("orbis-characters/$owner/$sha.png"))
    }

    @Test fun contradictoryDimensionsForSameHashAreRejected() {
        val payload = staged()
        val profile = File(payload, "no-backup/orbis-characters/$owner/profile.json")
        val current = CharacterStore.json.decodeFromString<CharacterProfile>(profile.readText())
        profile.writeText(CharacterStore.json.encodeToString(current.copy(pack = current.pack!!.copy(width = 2))))
        assertThrows(IllegalArgumentException::class.java) { CharacterBackup.validateStaged(payload, setOf(owner)) }
    }

    @Test fun safeRestoreReplacesSameOwnerOnlyAndDoesNotReplay() = runBlocking {
        val incoming = File(temp.root, "incoming")
        val live = File(temp.root, "live")
        install(incoming, greeting = "backup"); install(live, greeting = "newer"); install(live, other, "other owner")
        val restore = PendingRestore(File(temp.root, "restore"), File(temp.root, "db"), File(temp.root, "files"),
            validateBeforeJournal = { CharacterBackup.validateStaged(it, setOf(owner)) }, noBackupFilesDir = live)
        val staging = restore.createStagingDirectory()
        CharacterBackup.stageSnapshot(incoming, File(staging, "payload/no-backup"), setOf(owner))
        restore.publish(staging)
        assertTrue(restore.apply {})
        assertEquals("backup", store(live).read().presentation.greeting)
        assertEquals("other owner", store(live, other).read().presentation.greeting)
        assertFalse(restore.apply {})
    }

    @Test fun failedSettingsRestoreRollsCharacterFilesBackWithSameJournal() = runBlocking {
        val incoming = File(temp.root, "incoming")
        val live = File(temp.root, "live")
        install(incoming, greeting = "backup"); install(live, greeting = "current")
        val restore = PendingRestore(File(temp.root, "restore"), File(temp.root, "db"), File(temp.root, "files"),
            validateBeforeJournal = { CharacterBackup.validateStaged(it, setOf(owner)) }, noBackupFilesDir = live)
        val staging = restore.createStagingDirectory()
        CharacterBackup.stageSnapshot(incoming, File(staging, "payload/no-backup"), setOf(owner))
        File(staging, "settings.json").writeText("placeholder")
        restore.publish(staging)
        try { restore.apply { throw IOException("failed") }; fail("restore should fail") }
        catch (_: RestoreFailedException) { }
        assertEquals("current", store(live).read().presentation.greeting)
        assertArrayEquals(png, store(live).imageBytes(store(live).read().pack!!))
    }

    @Test fun oldBackupsWithoutCharactersLeavePrivateCharactersUntouched() = runBlocking {
        install()
        val restore = PendingRestore(File(temp.root, "restore"), File(temp.root, "db"), File(temp.root, "files"),
            noBackupFilesDir = File(temp.root, "live"))
        val staging = restore.createStagingDirectory()
        File(staging, "payload/files/upload").mkdirs()
        File(staging, "payload/files/upload/test").writeText("test")
        restore.publish(staging)
        assertTrue(restore.apply {})
        assertEquals("hello", store().read().presentation.greeting)
    }
}
