package me.rerere.rikkahub.data.orbis.workshop

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting

internal const val WORKSHOP_ACCESS_NOTICE =
    "ChatGPT 套餐里的 GPT 可以给工坊下单，但不能作为这里的生图后端。制作图片须另行配置图片 API；费用按所选服务计算，不包含在 ChatGPT 订阅中。不会自动切换到其他 API。"

/** Display the same effective connection that executes the job, never just its parent label. */
internal fun workshopMakerLabel(parentProvider: ProviderSetting, model: Model): String {
    val effective = model.providerOverwrite ?: parentProvider
    val access = if (effective is ProviderSetting.OpenAI && effective.chatGptAccountId != null) {
        "ChatGPT 套餐 · 不支持工坊生图"
    } else {
        "独立图片 API"
    }
    return "${effective.name} · ${model.displayName.ifBlank { model.modelId }} · $access"
}
