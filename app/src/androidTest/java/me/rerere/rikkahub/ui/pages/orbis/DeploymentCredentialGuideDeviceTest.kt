package me.rerere.rikkahub.ui.pages.orbis

import android.system.Os
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.*
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.integration.*
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.workspace.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.uuid.Uuid

class DeploymentCredentialGuideDeviceTest {
    @get:Rule val compose=createShellComposeRule()
    @Test fun humanPreparesUpstreamAndReadsOnlyGeneratedTokensWithoutAChatCallback() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=Files.createTempDirectory(context.cacheDir.toPath(),"st-guide-").toFile()
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val workspace=Uuid.random();val model=Model(modelId="synthetic-model")
        val owner=Assistant(workspaceId=workspace,chatModelId=model.id)
        val draft=ConnectionDraft("mcp_http","ST MCP","https://example.invalid/mcp",credentialFile="/workspace/keys.json")
        val proposal=ConnectionProposal(owner.id.toString(),draft)
        val provider=ProviderSetting.OpenAI(models=listOf(model),baseUrl="https://example.invalid/v1",apiKey="synthetic-upstream-key")
        val store=SettingsStore(context,AppScope().also {it.cancel()}).also {
            it.settingsFlow.value=Settings(init=false,assistants=listOf(owner),providers=listOf(provider),connectionProposals=listOf(proposal))
        }
        val manager=WorkspaceManager(File(root,"workspaces"))
        val repo=WorkspaceRepository(db.workspaceDao(),manager,RootfsInstaller(manager),store)
        val shown=mutableStateOf(true);var continued=0
        try {
            repo.createNative(workspace.toString(),"Synthetic")
            repo.writeNativeText(workspace.toString(),draft.credentialFile,
                """{"STBRAIN_GATEWAY_TOKEN":"synthetic-gateway-secret","STBRAIN_MCP_TOKEN":"synthetic-mcp-secret","api_key":"must-not-render"}""",false)
            compose.setContent {MaterialTheme {OrbisVisualTheme(darkTheme=false) {
                if(shown.value) ConnectionCredentialGuide(owner.id.toString(),listOf(proposal),{shown.value=false},{continued++},store,repo)
            }}}
            compose.onNodeWithTag("connection-prepare-upstream").performScrollTo().performClick()
            compose.waitUntil(10000){File(manager.filesDir(workspace.toString()),".orbis-st-upstream.json").isFile}
            val f=File(manager.filesDir(workspace.toString()),".orbis-st-upstream.json")
            assertEquals(384,Os.stat(f.path).st_mode and 511)
            assertTrue(f.readText().contains("synthetic-upstream-key"))
            compose.onNodeWithText("synthetic-upstream-key").assertDoesNotExist()
            compose.onNodeWithText("查看部署密钥").performScrollTo().performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("ST 网关密钥").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("must-not-render").assertDoesNotExist()
            compose.onNodeWithText("synthetic-gateway-secret").assertDoesNotExist()
            compose.onAllNodesWithText("显示")[0].performScrollTo().performClick()
            compose.onNodeWithText("synthetic-gateway-secret").assertIsDisplayed()
            compose.onNodeWithText("已妥善保存，去填写").performClick()
            assertEquals(1,continued)
            compose.onNodeWithText("收起").performClick()
            compose.onNodeWithTag("connection-credential-guide").assertDoesNotExist()
        } finally {compose.runOnIdle {shown.value=false};db.close();root.deleteRecursively()}
    }
}
