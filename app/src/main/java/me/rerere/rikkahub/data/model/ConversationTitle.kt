package me.rerere.rikkahub.data.model

/** Same visible-character rule as the approved Orbis v0.5 prototype. */
fun normalizeConversationTitle(value: String): String {
    val title = value.trim()
    require(title.isNotEmpty() && title.codePointCount(0, title.length) <= 60 &&
        title.none { it.code < 32 || it.code == 127 }) {
        "窗口名称需要 1–60 个可见字符"
    }
    return title
}

/** Never reconstruct a Conversation from a lightweight history row. */
fun Conversation.withManualTitle(value: String): Conversation = copy(title = normalizeConversationTitle(value))

/** Mirror a successful database CAS only while the live session still has the original title. */
fun Conversation.withGeneratedTitleIfUnchanged(expectedTitle: String, generatedTitle: String): Conversation =
    if (title == expectedTitle) copy(title = generatedTitle) else this
