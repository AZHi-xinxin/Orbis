package me.rerere.rikkahub.data.orbis.workshop

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

class WorkshopMakerPresentationTest {
    private val plan = ProviderSetting.OpenAI(name = "Subscription parent", chatGptAccountId = "test-account")
    private val api = ProviderSetting.OpenAI(name = "Image API", apiKey = "test-private-key")
    private val model = Model(modelId = "gpt-image-2", displayName = "My image model", type = ModelType.IMAGE)

    @Test fun `ordinary image API is explicitly independent`() {
        assertEquals("Image API · My image model · 独立图片 API", workshopMakerLabel(api, model))
    }

    @Test fun `API override under subscription parent is not labeled as subscription`() {
        val label = workshopMakerLabel(plan, model.copy(providerOverwrite = api))
        assertEquals(workshopMakerLabel(api, model), label)
        assertFalse(label.contains(plan.name))
        assertFalse(label.contains("ChatGPT 套餐"))
    }

    @Test fun `subscription override never claims to be an independent image backend`() {
        val label = workshopMakerLabel(api, model.copy(providerOverwrite = plan))
        assertEquals("Subscription parent · My image model · ChatGPT 套餐 · 不支持工坊生图", label)
        assertFalse(label.contains(api.name))
        assertFalse(label.contains("独立图片 API"))
    }

    @Test fun `blank display name falls back to exact configured model ID`() {
        assertEquals("Image API · gpt-image-2 · 独立图片 API", workshopMakerLabel(api, model.copy(displayName = "")))
    }

    @Test fun `labels never serialize credentials or change provider selection`() {
        val configured = model.copy(providerOverwrite = api)
        val label = workshopMakerLabel(plan, configured)
        assertFalse(label.contains(api.apiKey))
        assertFalse(label.contains(checkNotNull(plan.chatGptAccountId)))
        assertSame(api, configured.providerOverwrite)
        assertEquals(model.id, configured.id)
        assertEquals("test-private-key", api.apiKey)
    }

    @Test fun `access notice separates ordering from image billing and forbids automatic fallback`() {
        assertTrue(WORKSHOP_ACCESS_NOTICE.contains("可以给工坊下单"))
        assertTrue(WORKSHOP_ACCESS_NOTICE.contains("不能作为这里的生图后端"))
        assertTrue(WORKSHOP_ACCESS_NOTICE.contains("不包含在 ChatGPT 订阅中"))
        assertTrue(WORKSHOP_ACCESS_NOTICE.contains("不会自动切换到其他 API"))
    }
}
