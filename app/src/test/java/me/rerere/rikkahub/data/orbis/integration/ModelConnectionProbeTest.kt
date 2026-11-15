package me.rerere.rikkahub.data.orbis.integration

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

class ModelConnectionProbeTest {
    @Test fun probeKeepsConnectionHeadersButUsesItsOwnSessionWithoutSTThreadRouting() {
        val headers=listOf(
            me.rerere.ai.provider.CustomHeader("X-API-Key","fixture"),
            me.rerere.ai.provider.CustomHeader("x-opencode-session","private-chat"),
            me.rerere.ai.provider.CustomHeader("X-ST-Thread-ID","private-chat"),
            me.rerere.ai.provider.CustomHeader("X-Session-ID","private-chat"))
        val result=connectionProbeHeaders(headers,"isolated-probe")
        assertEquals(listOf(headers[0],headers[1].copy(value="isolated-probe")),result)
        assertEquals("private-chat",headers[1].value)
    }
    @Test fun usesExactAdvertisedAliasWithoutBorrowingAnotherAccountOrChangingIdentity() {
        val selected = Model(modelId = "chat", providerOverwrite = ProviderSetting.OpenAI(baseUrl = "https://same.example/v1"))
        val advertised = Model(modelId = "chat--auxiliary-no-memory",
            providerOverwrite = ProviderSetting.OpenAI(baseUrl = "https://wrong.example/v1"))
        val result = connectionProbeModel(selected, listOf(selected, advertised))
        assertEquals(advertised.modelId, result.modelId)
        assertEquals(selected.id, result.id)
        assertEquals(selected.providerOverwrite, result.providerOverwrite)
        assertEquals("chat", selected.modelId)
    }

    @Test fun incompleteGatewayDirectoryCannotFallBackToMainOrAnotherModelsAlias() {
        val failure = runCatching { connectionProbeModel(Model(modelId = "chat"), listOf(
            Model(modelId = "chat"), Model(modelId = "other--auxiliary-no-memory"))) }.exceptionOrNull()
        assertEquals("probe_auxiliary_alias_missing", (failure as ModelConnectionProbeFailure).code)
    }

    @Test fun ordinaryProviderAndAlreadyAuxiliaryModelRetainTheirAdvertisedName() {
        for (id in listOf("direct-model", "chat--auxiliary-no-memory")) {
            val model = Model(modelId = id)
            assertEquals(id, connectionProbeModel(model, listOf(model)).modelId)
        }
        assertEquals("probe_model_not_advertised", (runCatching {
            connectionProbeModel(Model(modelId = "missing"), listOf(Model(modelId = "direct-model")))
        }.exceptionOrNull() as ModelConnectionProbeFailure).code)
    }
}
