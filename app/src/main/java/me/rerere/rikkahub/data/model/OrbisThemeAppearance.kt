package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.DisplaySetting

/** A second presentation profile. Switching skin never replaces the original wallpaper/settings. */
fun deepSeekDefaultAppearance() = OrbisAppearance(
    backgroundEnabled = true,
    floatingStars = false,
    bubbleOpacity = 1f,
)

fun DisplaySetting.appearanceForStyle(deepSeek: Boolean): OrbisAppearance =
    (if (deepSeek) deepSeekAppearance.copy(chatFlow = orbisAppearance.chatFlow) else orbisAppearance).normalized()

fun DisplaySetting.withAppearanceForStyle(
    deepSeek: Boolean,
    transform: (OrbisAppearance) -> OrbisAppearance,
): DisplaySetting = if (deepSeek) copy(deepSeekAppearance = transform(deepSeekAppearance).normalized())
else copy(orbisAppearance = transform(orbisAppearance).normalized())

fun deepSeekWelcomeText(nickname: String): String = "${nickname.trim().ifBlank { "___" }}，欢迎回家"
