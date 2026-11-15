package me.rerere.rikkahub.data.orbis.plugins

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginBackupTest {
    @get:Rule val temp=TemporaryFolder()
    private fun setup(root: File): Pair<PluginRepository,PluginEntry> {
        val repo=PluginRepository(File(root,"orbis-plugins/owner"))
        val body=pluginJson.decodeFromString<PluginManifest>(PLUGIN_EXAMPLE)
        val e=repo.propose(body,null,0)
        return repo to repo.activate(e.id,e.revision,1)
    }
    @Test fun exportRetainsCodeStatsFilesButNeverEnablesImportedCode() {
        val live=temp.newFolder("live");val (repo,e)=setup(live)
        PluginFiles(File(repo.root,"data/${e.id}")).write("note.txt","synthetic note") {}
        val run=repo.begin(e.id,e.active()!!.hash);repo.finish(e.id,run.id,"succeeded",1)
        val payload=temp.newFolder("payload");val destination=File(payload,"files")
        val files=PluginBackup.stageSnapshot(live,destination)
        assertEquals(2,files.size);PluginBackup.validateStaged(payload)
        val copy=PluginRepository(File(destination,"orbis-plugins/owner")).snapshot().entries.single()
        assertFalse(copy.enabled);assertEquals(1L,copy.totalUses);assertEquals(e.active()!!.hash,copy.active()!!.hash)
        assertEquals("synthetic note",File(destination,"orbis-plugins/owner/data/${e.id}/note.txt").readText())
    }
    @Test fun replacementRestoreStripsEnableAndRunningState() {
        val payload=temp.newFolder("payload");val (r,e)=setup(File(payload,"files"))
        r.begin(e.id,e.active()!!.hash)
        PluginBackup.stripRestoredPermissions(payload)
        val restored=r.snapshot().entries.single()
        assertFalse(restored.enabled);assertEquals("interrupted",restored.runs.single().status)
    }
    @Test fun backupRejectsTraversalAndUnexpectedTrustFiles() {
        assertNull(PluginBackup.maxBytes("orbis-plugins/owner/../../private"))
        assertNull(PluginBackup.maxBytes("orbis-plugins/owner/grants.json"))
        assertNull(PluginBackup.maxBytes("orbis-plugins/owner/data/id/..foo"))
    }
    @Test fun restoreRejectsOrphanFilesBeforeApplyingAnything() {
        val payload=temp.newFolder("orphan");val root=File(payload,"files")
        setup(root)
        File(root,"orbis-plugins/owner/data/unknown/note.txt").apply {parentFile.mkdirs();writeText("orphan")}
        assertThrows(IllegalArgumentException::class.java) {PluginBackup.validateStaged(payload)}
    }
    @Test fun mergeRefusesChangedVersionsWithoutTouchingLiveData() {
        val live=temp.newFolder("live");val (r,e)=setup(live)
        val payload=temp.newFolder("payload");val files=File(payload,"files")
        PluginBackup.stageSnapshot(live,files)
        r.propose(e.latest().body.copy(code="return 3;"),e.id,e.revision)
        val before=File(r.root,"plugins.json").readBytes()
        assertThrows(IllegalArgumentException::class.java) {PluginBackup.prepareBeforeJournal(payload,live)}
        assertArrayEquals(before,File(r.root,"plugins.json").readBytes())
    }
}
