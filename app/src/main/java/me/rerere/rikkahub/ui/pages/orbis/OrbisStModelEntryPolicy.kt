package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.ai.provider.ProviderSetting

/** Local public presentation only; no URL/key/model mutation or endpoint guessing. */
internal data class OrbisStModelEntryChoice(val providerId: String, val name: String, val configuredModels: Int)

internal fun orbisStModelEntryChoices(providers: List<ProviderSetting>): List<OrbisStModelEntryChoice> {
    val counts = providers.groupingBy { it.id }.eachCount()
    return providers.filterIsInstance<ProviderSetting.OpenAI>()
        .filter { it.enabled && counts[it.id] == 1 }
        .map { provider -> OrbisStModelEntryChoice(
            provider.id.toString(),
            provider.name.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(80).ifBlank { "未命名连接" },
            provider.models.size,
        ) }
}

internal fun orbisStModelEntryTarget(choices: List<OrbisStModelEntryChoice>, selected: String?): String? =
    choices.singleOrNull { it.providerId == selected }?.providerId

internal const val ORBIS_ST_MODEL_DIRECTORY_NOTE =
    "进入模型页会读取所选服务的模型目录，不会自动更改当前模型。添加后，只有你点击选择模型才切换当前所选 AI。"
internal const val ORBIS_ST_MODEL_ROUTE_NOTE =
    "只显示 ST 服务端已配置的模型线路；手机填写模型名不会创建新线路。带 --auxiliary-no-memory 的型号是无记忆辅助线路，不用于日常 ST 主聊天。"
