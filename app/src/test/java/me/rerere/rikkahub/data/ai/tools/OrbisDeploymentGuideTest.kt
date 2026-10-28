package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.integration.*
import org.junit.Assert.*
import org.junit.Test

class OrbisDeploymentGuideTest {
    @Test fun explicitReopenInvokesUiWithoutRewritingOrDuplicatingTheProposal() = runBlocking {
        val owner=Assistant();var state=Settings(init=false,assistants=listOf(owner))
        val requests=mutableListOf<Pair<ConnectionProposal?,Boolean>>()
        var writes=0
        val tool=createConnectionProposalTool(owner.id.toString(),{state},{ f ->writes++;state=f(state);true}){ p,g ->requests+=p to g }
        fun args(action:String)=buildJsonObject {put("action",action)}
        assertTrue(tool.execute(args("show_guide")).toString().contains("true"))
        assertNull(requests.last().first);assertTrue(requests.last().second)
        tool.execute(buildJsonObject {put("action","propose");put("kind","model_openai");put("name","ST");put("endpoint","https://example.invalid/v1");put("model","st")})
        val original=state;val writeCount=writes
        repeat(2) {tool.execute(args("open_setup"))}
        assertEquals(4,requests.size);assertEquals(original,state);assertEquals(writeCount,writes)
        val before=requests.size
        tool.execute(buildJsonObject {put("action","open_setup");put("id","not-owned")})
        assertEquals(before,requests.size)
    }
    @Test fun workspaceStatusNeverRequestsApprovalOrMutatesSettings() = runBlocking {
        val owner=Assistant();val settings=Settings(assistants=listOf(owner));var checks=0
        val tool=createOrbisSelfConfigurationTool(owner.id.toString(),{settings},{error("no mutation")},{_,_,_->error("no network")},workspaceStatus={checks++;buildJsonObject{put("ok",true)}})
        val args=buildJsonObject{put("action","workspace_status")}
        assertFalse(tool.needsApproval(args));tool.execute(args);assertEquals(1,checks)
    }
    @Test fun guidesAreQueriedAndPaginatedWithDistinctPhoneComputerAndVpsPaths() {
        val help=createOrbisHelpTool(emptyList(),OrbisHelpBuild("org.orbis.agent","test","1","debug"))
        assertFalse(help.description.contains("STBRAIN_MCP_TOKEN"))
        assertTrue(help.description.contains("位置不明先问"))
        val model = me.rerere.ai.provider.Model()
        assertEquals("", help.systemPrompt(model, emptyList()))
        assertFalse(help.parameters().toString().contains("Termux→"))
        val install = orbisManualReadChapter("st_install",emptySet()).toString()
        assertTrue(install.contains("未得到回答前，不创建实例、不安装"))
        assertTrue(install.contains("不等于ST的安装目的地"))
        val phone = orbisManualReadChapter("st_phone",emptySet(),limit=10).toString()
        assertTrue(phone.contains("装好了不等于AI已经能操作"))
        assertTrue(phone.contains("不自动安装、更换或卸载Termux"))
        val first=orbisManualReadChapter("st_install",emptySet())
        assertEquals(3,first.getValue("sections").jsonArray.size)
        assertEquals(3,first.getValue("next_offset").jsonPrimitive.int)
        assertTrue(orbisManualReadChapter("workspace_setup",emptySet(),limit=10).toString().contains("重新安装 Rootfs"))
        assertTrue(orbisManualReadChapter("st_phone",emptySet(),limit=10).toString().contains("不需要用户打开无线调试"))
        assertTrue(orbisManualReadChapter("st_computer",emptySet(),limit=10).toString().contains("ipconfig"))
        assertTrue(orbisManualReadChapter("st_vps",emptySet(),limit=10).toString().contains("不发登录密码"))
        assertTrue(orbisManualChapterIndex("许愿").toString().contains("st_install"))
    }
}
