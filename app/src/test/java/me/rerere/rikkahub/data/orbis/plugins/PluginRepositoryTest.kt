package me.rerere.rikkahub.data.orbis.plugins

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PluginRepositoryTest {
    @get:Rule val temp=TemporaryFolder()
    private fun repo(owner: String="owner")=PluginRepository(File(temp.root,"orbis-plugins/$owner"))
    private fun body(name: String="count")=PluginManifest(name,"文字小尺","看看字数","只处理你传来的文字。","return args.text.length;",
        pluginJson.parseToJsonElement("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}""").jsonObject)

    @Test fun proposalNeedsHumanActivationAndPreservesOldVersion() {
        val r=repo(); val first=r.propose(body(),null,0,100)
        assertFalse(first.enabled);assertNull(first.activeVersion)
        val active=r.activate(first.id,first.revision,1)
        val next=r.propose(body().copy(code="return 7;"),first.id,active.revision,200)
        assertEquals(1,next.activeVersion);assertEquals(2,next.latest().number);assertTrue(next.enabled)
        assertEquals("return args.text.length;",next.active()!!.body.code)
        assertEquals(next,repo().snapshot().entries.single())
    }
    @Test fun staleEditCannotReplaceHumanDecision() {
        val r=repo();val e=r.propose(body(),null,0)
        r.activate(e.id,e.revision,1)
        assertThrows(IllegalStateException::class.java) {r.propose(body().copy(title="旧请求"),e.id,e.revision)}
        assertEquals("文字小尺",r.snapshot().entries.single().latest().body.title)
    }
    @Test fun separateOwnersCannotReadOrMutateEachOther() {
        val e=repo("first").propose(body(),null,0)
        assertTrue(repo("second").snapshot().entries.isEmpty())
        assertThrows(IllegalStateException::class.java) {repo("second").setEnabled(e.id,e.revision,true)}
    }
    @Test fun successCountsExactlyOnceFailureNeverCountsAndStatsDoNotEditRevision() {
        val r=repo();val draft=r.propose(body(),null,0,100)
        val e=r.activate(draft.id,draft.revision,1)
        val now=90L*86_400_000
        val run=r.begin(e.id,e.active()!!.hash,now)
        assertTrue(r.finish(e.id,run.id,"succeeded",10,now=now))
        assertFalse(r.finish(e.id,run.id,"succeeded",10,now=now))
        val fail=r.begin(e.id,e.active()!!.hash,now+1)
        r.finish(e.id,fail.id,"failed",12,"plugin_script_error",now+2)
        val state=r.snapshot().entries.single()
        assertEquals(1L,state.totalUses);assertEquals(1L,state.recentUses(now));assertEquals(0L,state.recentUses(now+30L*86_400_000))
        assertEquals(e.revision,state.revision);assertEquals(100L,state.updatedAt);assertEquals(2,state.runs.size)
    }
    @Test fun disableOrChangedVersionBlocksFrozenTool() {
        val r=repo();val d=r.propose(body(),null,0);val e=r.activate(d.id,d.revision,1)
        r.setEnabled(e.id,e.revision,false)
        assertThrows(IllegalStateException::class.java) {r.begin(e.id,e.active()!!.hash)}
    }
    @Test fun deleteDoesNotResurrectAfterLateReceipt() {
        val r=repo();val d=r.propose(body(),null,0);val e=r.activate(d.id,d.revision,1);val run=r.begin(e.id,e.active()!!.hash)
        r.delete(e.id,e.revision)
        assertFalse(r.finish(e.id,run.id,"succeeded",1));assertTrue(r.snapshot().entries.isEmpty())
    }
    @Test fun interruptedRunIsRecordedAndNeverReplayed() {
        val r=repo();val d=r.propose(body(),null,0);val e=r.activate(d.id,d.revision,1);r.begin(e.id,e.active()!!.hash,1000)
        r.recoverInterrupted(31_001)
        assertEquals("interrupted",r.snapshot().entries.single().runs.single().status)
        assertEquals(0L,r.snapshot().entries.single().totalUses)
    }
    @Test fun frequencyUsesRecentWindowWhileUpdatesUseContentTimestamp() {
        val r=repo();val d=r.propose(body(),null,0,100);val e=r.activate(d.id,d.revision,1)
        val newer=r.propose(body("other"),null,0,200)
        val now=10L*86_400_000;val run=r.begin(e.id,e.active()!!.hash,now);r.finish(e.id,run.id,"succeeded",1,now=now)
        val entries=r.snapshot().entries
        assertEquals(e.id,sortedPlugins(entries,PluginOrder.FREQUENCY,now).first().id)
        assertEquals(newer.id,sortedPlugins(entries,PluginOrder.UPDATED,now).first().id)
    }
    @Test fun immutableVersionHashesAndInvalidStoreAreNotSilentlyReset() {
        val r=repo();r.propose(body(),null,0)
        val path=File(r.root,"plugins.json");val changed=path.readText().replace("return args.text.length;","return 8;");path.writeText(changed)
        assertThrows(IllegalStateException::class.java) {r.snapshot()};assertEquals(changed,path.readText())
    }
    @Test fun rollbackKeepsCountersAndRetainsProtectedVersions() {
        val r=repo();val d=r.propose(body(),null,0);var e=r.activate(d.id,d.revision,1)
        repeat(14) {e=r.propose(body().copy(code="return $it;"),e.id,e.revision)}
        assertTrue(e.versions.size<=10);assertEquals(1,e.activeVersion)
        e=r.activate(e.id,e.revision,e.latest().number)
        assertEquals(1,e.previousVersion)
        e=r.activate(e.id,e.revision,1);assertEquals(1,e.activeVersion)
    }
    @Test fun nameIsUniqueAndStable() {
        val r=repo();val e=r.propose(body(),null,0)
        assertThrows(IllegalStateException::class.java) {r.propose(body(),null,0)}
        assertThrows(IllegalStateException::class.java) {r.propose(body("renamed"),e.id,e.revision)}
    }
    @Test fun filesRejectTraversalAndSymlinksAndKeepPriorContentOnRevocation() {
        val root=temp.newFolder("files");val files=PluginFiles(root)
        files.write("note.txt","before") {}
        assertThrows(IllegalArgumentException::class.java) {files.read("../outside")}
        assertThrows(IllegalStateException::class.java) {files.write("note.txt","after") {error("plugin_disabled")}}
        assertEquals("before",files.read("note.txt"))
        val secret=temp.newFile("secret").also {it.writeText("hidden")}
        Files.createSymbolicLink(File(root,"link.txt").toPath(),secret.toPath())
        assertThrows(IllegalStateException::class.java) {files.read("link.txt")}
    }
    @Test fun grantsCannotAddUndeclaredCapabilityAndDoNotApplyToChangedCode() {
        val r=repo();val e=r.propose(body(),null,0);val grants=PluginGrantStore(temp.newFolder("trust"))
        assertNull(grants.read(e.id,1))
        assertThrows(IllegalArgumentException::class.java) {grants.approve(e.id,e.latest(),setOf("network"),null)}
        grants.approve(e.id,e.latest(),emptySet(),null)
        val next=r.propose(body().copy(code="return 4;"),e.id,e.revision)
        assertNull(grants.read(e.id,next.latest().number));assertNotEquals(next.latest().hash,grants.read(e.id,1)!!.hash)
    }
}
