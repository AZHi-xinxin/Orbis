package me.rerere.rikkahub.data.orbis.plugins

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class PluginHostCapabilitiesTest {
    @get:Rule val temp=TemporaryFolder()
    private fun body()=PluginManifest("probe","测试","简介","测试说明","return {};" )
    @Test fun legacyManifestHashAndGrantRemainValid() {
        val b=body(); val json=pluginJson.encodeToString(b)
        assertFalse(json.contains("beforeResponse"))
        val historical="""{"name":"probe","title":"测试","summary":"简介","introduction":"测试说明","code":"return {};","parameters":{"type":"object","properties":{}},"icon":"moon","palette":"lavender","permissions":[],"networkHosts":[]}"""
        val expected=MessageDigest.getInstance("SHA-256").digest(historical.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected,b.hash())
        assertEquals(b,pluginJson.decodeFromString<PluginManifest>(historical))
    }
    @Test fun automaticHookRequiresExplicitDeclaredPermissionAndChangesHash() {
        assertThrows(IllegalArgumentException::class.java) {body().copy(beforeResponse=true).validate()}
        val hook=body().copy(beforeResponse=true,permissions=listOf("response.check"));hook.validate()
        assertNotEquals(body().hash(),hook.hash());assertTrue(pluginJson.encodeToString(hook).contains("beforeResponse"))
    }
    @Test fun onlyExplicitNativeMethodsAreMapped() {
        assertEquals("memory.write",pluginNativePermission("memory.save"))
        assertEquals("memory.read",pluginNativePermission("memory.search"))
        assertEquals("wake",pluginNativePermission("wake"))
        assertNull(pluginNativePermission("memory.delete"));assertNull(pluginNativePermission("run_tool"))
        assertNull(pluginNativePermission("system.fall"));assertEquals("device.foreground",pluginNativePermission("system.foreground"))
    }
    @Test fun callIntentSurvivesReloadAndInterruptionWithoutInventingSuccess() {
        val root=File(temp.root,"orbis-plugins/owner");val repo=PluginRepository(root)
        val draft=repo.propose(body(),null,0);val e=repo.activate(draft.id,draft.revision,1)
        val run=repo.begin(e.id,e.active()!!.hash,100)
        repo.recordCall(e.id,run.id,1,"notify.send")
        assertEquals("unknown",PluginRepository(root).snapshot().entries.single().runs.single().calls.single().outcome)
        assertThrows(IllegalStateException::class.java) {repo.recordCall(e.id,run.id,1,"notify.send")}
        repo.recoverInterrupted(31_000)
        val ended=PluginRepository(root).snapshot().entries.single().runs.single()
        assertEquals("interrupted",ended.status);assertEquals("unknown",ended.calls.single().outcome)
    }
    @Test fun receiptCompletionIsSingleAndDoesNotChangeVersionRevision() {
        val repo=PluginRepository(File(temp.root,"orbis-plugins/owner"));val draft=repo.propose(body(),null,0)
        val e=repo.activate(draft.id,draft.revision,1);val run=repo.begin(e.id,e.active()!!.hash)
        repo.recordCall(e.id,run.id,1,"memory.save");repo.recordCall(e.id,run.id,1,"memory.save","returned")
        assertEquals(e.revision,repo.snapshot().entries.single().revision)
        assertThrows(IllegalStateException::class.java) {repo.recordCall(e.id,run.id,1,"memory.save")}
    }
    @Test fun checkerFailsClosedKeepsDraftAndOnlyUsesExplicitReplacement() {
        val failed=pluginCheckVerdict(Json.parseToJsonElement("""{"ok":true,"result":{"pass":false,"feedback":"字数不足","text":"丢弃"}}""").jsonObject,"原草稿")
        assertFalse(failed.passed);assertEquals("原草稿",failed.text);assertEquals("字数不足",failed.feedback)
        assertFalse(pluginCheckVerdict(buildJsonObject {put("ok",true)},"原草稿").passed)
        val good=pluginCheckVerdict(Json.parseToJsonElement("""{"ok":true,"result":{"pass":true,"text":"作者改写"}}""").jsonObject,"原草稿")
        assertTrue(good.passed);assertEquals("作者改写",good.text)
    }
    @Test fun githubMetadataDropsLargeBodiesAndNeverDownloadsArtifacts() {
        assertEquals("https://api.github.com/repos/a/b/releases/latest",pluginGithubUrl(buildJsonObject {put("repo","a/b")}))
        assertThrows(IllegalStateException::class.java) {pluginGithubUrl(buildJsonObject {put("repo","a/b/../../secret")})}
        val out=pluginGithubMetadata(200,"""{"tag_name":"v1","body":"secret","assets":[{"browser_download_url":"x"}],"id":2}""","release")
        assertEquals("v1",out["tag_name"]!!.jsonPrimitive.content);assertFalse(out.containsKey("body"));assertFalse(out.containsKey("assets"))
        assertFalse(pluginGithubMetadata(429,"not-json","release")["ok"]!!.jsonPrimitive.boolean)
    }
}
