package me.rerere.rikkahub.data.orbis.screenshare

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.CustomHeader
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test

class ScreenShareModelPolicyTest {
    private val model = Model(modelId = "synthetic-vision")
    @Test fun unknownGatewayCannotFallBackToMainConversationModel() {
        val provider = ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:18000/v1", models = listOf(model))
        try { selectScreenShareHelperModel(model, listOf(provider)); fail("No authority for helper lane") }
        catch (_: ScreenShareHelperUnavailable) { }
    }
    @Test fun exactSameProviderAuxiliaryAliasIsUsed() {
        val alias = Model(modelId = model.modelId + "--auxiliary-no-memory")
        val provider = ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:18000/v1", models = listOf(model, alias))
        assertEquals(alias, selectScreenShareHelperModel(model, listOf(provider)))
    }
    @Test fun officialProviderCanUseIsolatedToolFreeRequest() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1", models = listOf(model))
        assertEquals(model, selectScreenShareHelperModel(model, listOf(provider)))
    }
    @Test fun LookalikeEndpointCannotClaimOfficialProvider() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com.example.test/v1", models = listOf(model))
        try { selectScreenShareHelperModel(model, listOf(provider)); fail("Not official host") }
        catch (_: ScreenShareHelperUnavailable) { }
    }

    private val vision = Model(modelId = "synthetic-ocr", inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
    private fun settings(ocr: Model = vision, enabled: Boolean = true) = Settings(ocrModelId = ocr.id,
        providers = listOf(ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:18000/v1", models = listOf(model)),
            ProviderSetting.Claude(baseUrl = "https://synthetic-ocr.example.test", enabled = enabled, models = listOf(ocr))))

    @Test fun textMainCanStartWithExplicitThirdPartyOcr() {
        val settings = settings()
        validateScreenShareModel(model, settings)
        assertEquals(vision, screenShareHelperRoute(model, settings).model)
        assertTrue(screenShareOcrRoute(settings).provider is ProviderSetting.Claude)
    }

    @Test fun missingDisabledAndTextOnlyOcrGiveSpecificSetupErrors() {
        for ((settings, phrase) in listOf(settings().copy(ocrModelId = kotlin.uuid.Uuid.random()) to "选择",
            settings(enabled = false) to "停用", settings(vision.copy(inputModalities = listOf(Modality.TEXT))) to "图片输入")) {
            try { validateScreenShareModel(model, settings); fail("invalid OCR") }
            catch (error: ScreenShareSetupException) { assertTrue(error.message.orEmpty().contains(phrase)) }
        }
    }

    @Test fun nativeVisionNeedsNoOcrAndKeepsDirectImageSupport() {
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(vision))))
        validateScreenShareModel(vision, settings)
    }

    @Test fun explicitOcrUsesMatchingAuxiliaryAliasAndRejectsForeignOverride() {
        val alias = vision.copy(id = kotlin.uuid.Uuid.random(), modelId = vision.modelId + "--auxiliary-no-memory")
        val settings = settings().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(vision, alias))))
        assertEquals(alias, screenShareOcrRoute(settings).model)
        val bad = alias.copy(providerOverwrite = ProviderSetting.OpenAI(baseUrl = "https://foreign.example.test"))
        try { screenShareOcrRoute(settings.copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(vision, bad))))); fail("different endpoint") }
        catch (_: ScreenShareSetupException) { }
    }

    @Test fun helperKeepsConnectionHeadersButHasNoChatIdentityToolsOrPersona() {
        val configured = vision.copy(customHeaders = listOf(CustomHeader("X-Vendor-Feature", "image"),
            CustomHeader("X-ST-Session", "private-chat"), CustomHeader("X-Conversation-ID", "private-chat")))
        val route = screenShareOcrRoute(settings(configured))
        val request = screenShareHelperRequest(route, "synthetic-screen", "data:image/jpeg;base64,AA==")
        assertEquals(listOf(CustomHeader("X-Vendor-Feature", "image")), request.params.customHeaders)
        assertTrue(request.params.tools.isEmpty())
        assertNull(request.params.sessionId)
        assertNull(request.params.orbisConversationId)
        assertEquals(0, request.params.maxAutomaticContinuations)
        assertEquals(2, request.messages.size)
        assertTrue(request.messages.last().parts.any { it is me.rerere.ai.ui.UIMessagePart.Image })
        assertFalse(screenShareHelperRequest(route, "summary", null).messages.any { m -> m.parts.any { it is me.rerere.ai.ui.UIMessagePart.Image } })
    }
}
