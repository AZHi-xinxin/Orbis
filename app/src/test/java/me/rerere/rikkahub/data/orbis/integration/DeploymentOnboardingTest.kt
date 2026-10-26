package me.rerere.rikkahub.data.orbis.integration

import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class DeploymentOnboardingTest {
    private val owner = Assistant(workspaceId = Uuid.random())
    @Test fun newGatewayUsesToolsAndExplicitImagesButUpdatingNamePreservesHumanCapabilities() {
        val d = ConnectionDraft("model_openai", "ST", "https://example.invalid/v1", model="st")
        val settings = Settings(assistants=listOf(owner), providers=emptyList())
        val proposed=ConnectionProposalRules.propose(settings, owner.id.toString(), d)
        val saved=ConnectionProposalRules.approve(proposed,proposed.connectionProposals.single().id,"synthetic-key",true)
        val model=saved.providers.single().models.single()
        assertTrue(ModelAbility.TOOL in model.abilities)
        assertFalse(Modality.IMAGE in model.inputModalities)
        val editedProvider = (saved.providers.single() as ProviderSetting.OpenAI).copy(models = listOf(model.copy(
            providerOverwrite = ProviderSetting.OpenAI(baseUrl="https://other.invalid/v1"))))
        val edited = saved.copy(providers=listOf(editedProvider))
        assertFalse(ConnectionProposalRules.matchesInstalled(edited, edited.connectionProposals.single()))
        val denied = ConnectionProposalRules.propose(edited,owner.id.toString(),d.copy(name="renamed"))
        assertEquals("changed_in_settings",denied.connectionProposals.single().status)
        val human=model.copy(abilities=emptyList(),inputModalities=listOf(Modality.TEXT,Modality.IMAGE),outputModalities=listOf(Modality.IMAGE))
        val renamed=ConnectionProposalRules.connectionModel(d.copy(name="renamed"), model.id,human)
        assertEquals(human.abilities,renamed.abilities);assertEquals(human.inputModalities,renamed.inputModalities)
        assertEquals(human.outputModalities,renamed.outputModalities)
        val explicit=ConnectionProposalRules.connectionModel(d.copy(supportsTools=false,supportsImages=true),model.id)
        assertFalse(ModelAbility.TOOL in explicit.abilities);assertTrue(Modality.IMAGE in explicit.inputModalities)
    }
    @Test fun readinessChecksActualRootfsAndDoesNotMistakeApprovalForDisabled() {
        val w=WorkspaceEntity("id","本助手","root","READY",0,0)
        assertFalse(workspaceReadiness(w,false).getValue("shell_ready").jsonPrimitive.boolean)
        val ready=workspaceReadiness(w,true)
        assertTrue(ready.getValue("shell_ready").jsonPrimitive.boolean)
        assertTrue(ready.getValue("permissions").jsonObject.getValue("shell_requires_approval").jsonPrimitive.boolean)
        assertFalse(ready.getValue("execution_performed").jsonPrimitive.boolean)
        assertTrue(workspaceReadiness(null,false).toString().contains("workspace_prepare"))
        assertTrue(workspaceReadiness(w.copy(shellStatus="INSTALLING"),false).toString().contains("不要重复"))
    }
    @Test fun nativeCredentialParserReadsOnlyTwoGeneratedKeysAndRejectsShellOrBrokenInput() {
        val gateway="synthetic-gateway-0001"; val mcp="synthetic-mcp-0002"
        val parsed=parseDeploymentCredentials("STBRAIN_GATEWAY_TOKEN='$gateway'\nSTBRAIN_MCP_TOKEN=$mcp\nSTBRAIN_UPSTREAM_API_KEY=ignored")
        assertEquals(gateway,parsed.gateway);assertEquals(mcp,parsed.mcp)
        assertFalse(parsed.toString().contains(gateway));assertFalse(parsed.humanExport().contains("ignored"))
        val json=buildJsonObject {put("STBRAIN_GATEWAY_TOKEN",gateway);put("STBRAIN_MCP_TOKEN",mcp)}.toString()
        assertEquals(mcp,parseDeploymentCredentials(json).mcp)
        listOf("", json.repeat(1000), "STBRAIN_GATEWAY_TOKEN=$gateway\nSTBRAIN_GATEWAY_TOKEN=$gateway\nSTBRAIN_MCP_TOKEN=$mcp",
            "STBRAIN_GATEWAY_TOKEN=$(echo unsafe-value)\nSTBRAIN_MCP_TOKEN=$mcp",json.replace(mcp,gateway)).forEach {
            assertTrue(runCatching {parseDeploymentCredentials(it)}.isFailure)
        }
        listOf("/etc/secret", "/workspace/../secret", "/workspace/a//b").forEach { path ->
            assertTrue(runCatching { ConnectionDraft("mcp_http","ST","https://example.invalid/mcp",credentialFile=path).normalized() }.isFailure)
        }
    }
    @Test fun upstreamIsHumanOnlyBoundToOwnerAndUsesEffectiveProviderWithoutReturningKeysInInspect() {
        val model=Model(modelId="synthetic-model")
        val provider=ProviderSetting.OpenAI(models=listOf(model),baseUrl="https://example.invalid/v1",apiKey="synthetic-private-key")
        val s=Settings(assistants=listOf(owner.copy(chatModelId=model.id)),providers=listOf(provider))
        val value=deploymentUpstream(s,owner.id.toString())
        assertEquals(owner.workspaceId.toString(),value.workspace)
        assertTrue(value.privateJson().contains("synthetic-private-key"))
        assertFalse(value.toString().contains("synthetic-private-key"))
        assertFalse(AiConfigurationRules.inspect(s,owner.id.toString()).toString().contains("synthetic-private-key"))
        assertTrue(runCatching {deploymentUpstream(s,"other")}.isFailure)
        assertTrue(runCatching {deploymentUpstream(s.copy(providers=listOf(provider.copy(apiKey="one,two"))),owner.id.toString())}.isFailure)
    }
    @Test fun dismissedProposalCanReopenButNeverInAnotherChatAndGenericGuideWorksBeforeAnyProposal() {
        val queue=ConnectionSetupQueue()
        val p=ConnectionProposal("owner",ConnectionDraft("mcp_http","ST","https://example.invalid/mcp"))
        queue.offer("chat",p);assertNotNull(queue.take("owner","chat",listOf(p)))
        queue.dismiss("owner","chat");queue.offer("chat",p)
        assertNull(queue.take("other","chat",listOf(p)));assertNotNull(queue.take("owner","chat",listOf(p)))
        queue.offerGuide("owner","chat");assertNull(queue.take("owner","other",emptyList()))
        val guide=queue.take("owner","chat",emptyList())!!;assertTrue(guide.guide);assertNull(guide.proposal)
    }
}
