package me.rerere.rikkahub.data.orbis.workshop

import me.rerere.ai.provider.*
import org.junit.Assert.*
import org.junit.Test

class SeedreamWorkshopSupportTest {
    private val ark = ProviderSetting.OpenAI(baseUrl = "https://ark.cn-beijing.volces.com/api/v3")
    private val legacy = Model(modelId = "doubao-seedream-4-0-250828")

    @Test fun existingSeedreamSavedAsChatCanBeSelectedWithoutDeletingSettings() {
        assertEquals(ModelType.CHAT, legacy.type)
        assertTrue(ImageWorkshop.supports(ark, legacy))
        assertEquals(ModelType.CHAT, legacy.type)
    }
    @Test fun explicitImageModelsStillWork() {
        assertTrue(ImageWorkshop.supports(ark, legacy.copy(type = ModelType.IMAGE)))
        assertTrue(ImageWorkshop.supports(ProviderSetting.OpenAI(), Model(modelId = "gpt-image-2", type = ModelType.IMAGE)))
    }
    @Test fun videoIsRejectedEvenIfUserMarkedItAsImage() {
        assertFalse(ImageWorkshop.supports(ark, legacy.copy(modelId = "doubao-seedance-2-5-260628", type = ModelType.IMAGE)))
    }
    @Test fun disabledAndSubscriptionProvidersAreNotImageBackends() {
        assertFalse(ImageWorkshop.supports(ark.copy(enabled = false), legacy))
        assertFalse(ImageWorkshop.supports(ark.copy(chatGptAccountId = "synthetic"), legacy.copy(type = ModelType.IMAGE)))
    }
    @Test fun wrongArkBaseDoesNotPromoteChatToImage() {
        assertFalse(ImageWorkshop.supports(ark.copy(baseUrl = ark.baseUrl + "/images/generations"), legacy))
        assertFalse(ImageWorkshop.supports(ProviderSetting.OpenAI(), legacy))
    }
    @Test fun ordinaryChatModelDoesNotBecomeWorkshopBackend() {
        assertFalse(ImageWorkshop.supports(ark, Model(modelId = "doubao-seed-2-0-pro")))
    }
}
