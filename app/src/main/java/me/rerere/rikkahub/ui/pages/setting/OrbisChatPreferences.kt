package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.DisplaySetting
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Finish an explicitly started local save even if the user immediately leaves the page. */
internal suspend fun persistOrbisPreferenceChange(write: suspend () -> Unit) {
    currentCoroutineContext().ensureActive()
    withContext(NonCancellable) { write() }
}

/** A field-level transform: do not save a stale copy of all settings from a rendered card. */
internal enum class OrbisChatPreference(val title: String, val description: String) {
    USAGE("消息用量", "在消息下方显示用量摘要"),
    ENTER("回车发送", "键盘回车发送；关闭时回车换行"),
    JUMPER("消息定位条", "快速跳到聊天中的其他消息"),
    JUMPER_LEFT("定位条靠左", "点亮放在左侧；关闭放在右侧"),
    AUTO_SCROLL("跟随新回复", "生成回复时自动向下滚动"),
    LOADING("图形加载提示", "其他页面的加载样式；聊天使用 ∞"),
    BLUR("背景模糊", "启用支持页面的模糊效果"),
    HAPTIC("生成触感", "回复生成时提供轻微振动"),
    SKIP_CROP("图片直接使用", "选图时跳过裁剪步骤"),
    LONG_TEXT("长文转附件", "粘贴较长文字时转为文本附件"),
    VOLUME_SCROLL("音量键滚动", "用音量键上下浏览消息"),
}

internal fun DisplaySetting.orbisPreferenceValue(preference: OrbisChatPreference): Boolean = when (preference) {
    OrbisChatPreference.USAGE -> showTokenUsage
    OrbisChatPreference.ENTER -> sendOnEnter
    OrbisChatPreference.JUMPER -> showMessageJumper
    OrbisChatPreference.JUMPER_LEFT -> messageJumperOnLeft
    OrbisChatPreference.AUTO_SCROLL -> enableAutoScroll
    OrbisChatPreference.LOADING -> useAppIconStyleLoadingIndicator
    OrbisChatPreference.BLUR -> enableBlurEffect
    OrbisChatPreference.HAPTIC -> enableMessageGenerationHapticEffect
    OrbisChatPreference.SKIP_CROP -> skipCropImage
    OrbisChatPreference.LONG_TEXT -> pasteLongTextAsFile
    OrbisChatPreference.VOLUME_SCROLL -> enableVolumeKeyScroll
}

internal fun DisplaySetting.withOrbisPreference(preference: OrbisChatPreference, checked: Boolean): DisplaySetting = when (preference) {
    OrbisChatPreference.USAGE -> copy(showTokenUsage = checked)
    OrbisChatPreference.ENTER -> copy(sendOnEnter = checked)
    OrbisChatPreference.JUMPER -> copy(showMessageJumper = checked)
    OrbisChatPreference.JUMPER_LEFT -> copy(messageJumperOnLeft = checked)
    OrbisChatPreference.AUTO_SCROLL -> copy(enableAutoScroll = checked)
    OrbisChatPreference.LOADING -> copy(useAppIconStyleLoadingIndicator = checked)
    OrbisChatPreference.BLUR -> copy(enableBlurEffect = checked)
    OrbisChatPreference.HAPTIC -> copy(enableMessageGenerationHapticEffect = checked)
    OrbisChatPreference.SKIP_CROP -> copy(skipCropImage = checked)
    OrbisChatPreference.LONG_TEXT -> copy(pasteLongTextAsFile = checked)
    OrbisChatPreference.VOLUME_SCROLL -> copy(enableVolumeKeyScroll = checked)
}

internal fun orbisChatPreferenceColumns(widthDp: Float, fontScale: Float): Int = when {
    widthDp < 340f || fontScale >= 1.45f -> 1
    widthDp >= 640f && fontScale <= 1.15f -> 3
    else -> 2
}
