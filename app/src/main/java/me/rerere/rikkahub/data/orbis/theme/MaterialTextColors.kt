package me.rerere.rikkahub.data.orbis.theme

import kotlinx.serialization.Serializable

internal enum class MaterialTextTarget(val title: String) {
    ASSISTANT("对方文字颜色"), USER("我的文字颜色"), CHROME("输入框与底栏文字颜色")
}

/** Human display preferences, separate from the immutable theme and its material. */
@Serializable internal data class MaterialTextColors(
    val assistant: String? = null,
    val user: String? = null,
    val chrome: String? = null,
) {
    fun validate() = listOfNotNull(assistant, user, chrome).forEach(::themeArgb)
    fun get(target: MaterialTextTarget): String? = when (target) {
        MaterialTextTarget.ASSISTANT -> assistant
        MaterialTextTarget.USER -> user
        MaterialTextTarget.CHROME -> chrome
    }
    fun with(target: MaterialTextTarget, color: String?) = when (target) {
        MaterialTextTarget.ASSISTANT -> copy(assistant = color)
        MaterialTextTarget.USER -> copy(user = color)
        MaterialTextTarget.CHROME -> copy(chrome = color)
    }.also { it.validate() }

    // Translucent materials show the user's wallpaper, so a light text override
    // cannot be contrast-checked against the theme's nominal, opaque tint color.
    // Keep the validated source package unchanged and apply only at display time.
    fun display(body: ThemePackage) = body.copy(
        bubbles = body.bubbles.copy(
            assistant = body.bubbles.assistant.copy(text = assistant ?: body.bubbles.assistant.text),
            user = body.bubbles.user.copy(text = user ?: body.bubbles.user.text),
        ),
        chrome = body.chrome.copy(
            onComposer = chrome ?: body.chrome.onComposer,
            onDock = chrome ?: body.chrome.onDock,
        ),
    )
}

internal fun ThemePackage.defaultTextColor(target: MaterialTextTarget): String = when (target) {
    MaterialTextTarget.ASSISTANT -> bubbles.assistant.text
    MaterialTextTarget.USER -> bubbles.user.text
    MaterialTextTarget.CHROME -> chrome.onComposer ?: colors.text
}

internal fun ThemeState.displayBody(ref: ThemeRef? = active): ThemePackage? = resolve(ref)?.let {
    materialTextColors[ref?.id]?.display(it) ?: it
}
