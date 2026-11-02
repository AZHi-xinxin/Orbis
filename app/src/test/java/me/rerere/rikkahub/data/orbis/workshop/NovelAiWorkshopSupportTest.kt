package me.rerere.rikkahub.data.orbis.workshop

import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.NovelAiImageApi
import org.junit.Assert.*
import org.junit.Test

class NovelAiWorkshopSupportTest {
    private val provider = ProviderSetting.OpenAI(name = "NovelAI", baseUrl = NovelAiImageApi.BASE_URL)
    @Test fun nativeConnectionSupportsKnownModelsAndReferencesWithoutWidgetKit() {
        assertTrue(ImageWorkshop.supports(provider, NovelAiImageApi.model()))
        assertFalse(ImageWorkshop.supports(provider.copy(enabled = false), NovelAiImageApi.model()))
        assertFalse(ImageWorkshop.supports(provider, NovelAiImageApi.model().copy(modelId = "unverified-model")))
        assertTrue(ImageWorkshop.supportsEditing(provider))
        assertFalse(ImageWorkshop.supportsWidgetKit(provider))
        assertTrue(ImageWorkshop.supportsEditing(ProviderSetting.Google()))
    }
}
