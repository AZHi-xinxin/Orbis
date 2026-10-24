package me.rerere.rikkahub.data.orbis.body

/** Setup guidance only. Reading it never edits a definition, runtime state or visibility. */
data class BodySetupNotice(val code: String, val title: String, val humanMessage: String,
    val aiNextStep: String, val request: String)

object BodySetup {
    fun defaultDraftMode(state: BodyState): String =
        state.versions.lastOrNull()?.definition?.triggerMode ?: state.draft?.triggerMode ?: "auto"

    fun notice(state: BodyState): BodySetupNotice? {
        val definition = state.versions.lastOrNull()?.definition
        return when {
            definition == null -> BodySetupNotice("needs_definition", "等待 AI 设置自动响应",
                "模块已开启。让 AI 定义自己的指标和触发规则，发布后自行开始运行。",
                "首次定义默认auto。请用自己的规则draft→activate→resume；若已有草稿先核对，不要等待人类点击运行。",
                "请检查身体模块，按你自己的设计完成自动响应规则的定义、发布和启动。")
            definition.triggerMode == "manual" -> BodySetupNotice("manual_mode", "尚未开启自动响应",
                "当前仍由 AI 逐次触发。让 AI 保留已有规则并切换，之后新消息才会自动引起变化。",
                "当前实际模式是manual，自动触发尚未开启。请保留当前维度、事件与历史，pause→draft完整定义并设置triggerMode=auto、补齐自己的keywords/synonyms→activate→resume。每步用最新revision；不要让人类点运行。",
                "请保留现有身体指标和记录，把规则切换为自动响应，补齐你自己的触发词，然后恢复运行。")
            definition.rules.none { (it.keywords + it.synonyms).any(String::isNotBlank) } -> BodySetupNotice(
                "message_triggers_missing", "还没有消息触发词",
                "已经选择自动响应，但还没有可匹配的词句。让 AI 补齐自己的触发规则，普通聊天才能触发变化。",
                "当前auto没有keywords/synonyms，普通新消息不会命中事件。请pause，保留原定义并增加自己的词/近义短语，经draft→activate→resume生效；不要用manual event替代。",
                "请检查身体自动响应，保留已有规则，补齐你自己的关键词或近义词，并恢复运行。")
            !state.running -> BodySetupNotice("auto_paused", "自动响应已暂停",
                "由 AI 在准备好规则后恢复；若处于规则设定的冷静期，需等待结束。",
                "当前auto已暂停。检查草稿和cooldownUntil，完成必要的规则发布后调用resume；冷静期未结束不要反复重试。",
                "请检查身体自动响应为什么暂停，按你的规则在合适的时候恢复运行。")
            else -> null
        }
    }
}
