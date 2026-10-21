package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class OrbisSelfConfigurationToolTest {
    private val owner = Assistant()
    private val model = Model(modelId = "test-model")
    private val provider = ProviderSetting.OpenAI(models = listOf(model), apiKey = "never-return-this-key")
    private val initial = Settings(assistants = listOf(owner), providers = listOf(provider))
    @Test fun mutationsCanBeRememberedReadOnlyCallsNeverNeedApprovalAndNoKeysEnterReceipts() = runBlocking {
        var settings = initial
        val network = mutableListOf<String>()
        val tool = createOrbisSelfConfigurationTool(owner.id.toString(), { settings },
            { transform -> settings = transform(settings); true },
            { action, _, _ -> network += action; buildJsonObject { put("ok", true); put("status", "queued") } })
        assertTrue(tool.hostApproval!!.rememberable)
        for (action in listOf("inspect", "check_status", "st_atlas_status")) {
            assertFalse(tool.needsApproval(buildJsonObject { put("action", action) }))
        }
        assertTrue(tool.needsApproval(buildJsonObject { put("action", "check") }))
        val select = buildJsonObject { put("action", "select_model"); put("role", "fast"); put("id", model.id.toString()) }
        assertTrue(tool.needsApproval(select))
        val receipt = tool.execute(select).filterIsInstance<UIMessagePart.Text>().single().text
        assertEquals(model.id, settings.fastModelId)
        assertFalse(receipt.contains("never-return-this-key"))
        assertTrue(network.isEmpty())
        val queued = tool.execute(buildJsonObject { put("action", "check"); put("kind", "model"); put("id", model.id.toString()) }).toString()
        assertTrue(queued.contains("queued"))
        assertEquals(listOf("check"), network)
    }
    @Test fun unknownFieldsCredentialsRemovedOwnerAndFailedPersistenceDoNotReportSaved() = runBlocking {
        var settings = initial
        var updates = 0
        val tool = createOrbisSelfConfigurationTool(owner.id.toString(), { settings },
            { _ -> updates++; false }, { _, _, _ -> error("must_not_network") })
        assertTrue(tool.execute(buildJsonObject { put("action", "inspect"); put("api_key", "private") }).toString().contains("unknown_field"))
        assertEquals(0, updates)
        val failed = tool.execute(buildJsonObject { put("action", "select_model"); put("role", "fast"); put("id", model.id.toString()) }).toString()
        assertTrue(failed.contains("not_saved"))
        settings = settings.copy(assistants = emptyList())
        assertFalse(tool.isApprovalCurrent!!())
        assertTrue(tool.execute(buildJsonObject { put("action", "inspect") }).toString().contains("assistant_unavailable"))
    }
    @Test fun memoryChangePreservesSemanticSettingUnlessExplicitlyChanged() = runBlocking {
        var settings = initial.copy(assistants = listOf(owner.copy(orbisSemanticRecall = false)))
        val tool = createOrbisSelfConfigurationTool(owner.id.toString(), { settings },
            { transform -> settings = transform(settings); true }, { _, _, _ -> error("no_network") })
        tool.execute(buildJsonObject { put("action", "memory"); put("mode", "standard"); put("auto_inject", true) })
        assertFalse(settings.assistants.single().orbisSemanticRecall)
        tool.execute(buildJsonObject { put("action", "memory"); put("mode", "standard"); put("auto_inject", true); put("semantic_recall", true) })
        assertTrue(settings.assistants.single().orbisSemanticRecall)
    }

    @Test fun workspacePreparationUsesItsHostCallbackAndAcceptsNoModelSuppliedIdentityOrPath() = runBlocking {
        var prepared = 0
        val tool = createOrbisSelfConfigurationTool(owner.id.toString(), { initial }, { error("no_settings_write_here") },
            { _, _, _ -> error("no_network") }, prepareWorkspace = {
                prepared++; buildJsonObject { put("ok", true); put("status", "workspace_ready") }
            })
        val input = buildJsonObject { put("action", "workspace_prepare") }
        assertTrue(tool.needsApproval(input))
        assertTrue(tool.execute(input).toString().contains("workspace_ready"))
        assertEquals(1, prepared)
        assertTrue(tool.execute(buildJsonObject { put("action", "workspace_prepare"); put("id", "elsewhere") })
            .toString().contains("unknown_field"))
        assertEquals(1, prepared)
    }
}
