package me.rerere.rikkahub.data.model

enum class OrbisComposerPanel(val title: String) {
    CAPABILITIES("＋ 能力"), EMOTIONS("☺ 表情"), VOICE("◖ 语音");
}

fun toggleOrbisComposerPanel(current: OrbisComposerPanel?, requested: OrbisComposerPanel) =
    requested.takeUnless { it == current }

enum class OrbisComposerAction(val glyph: String, val title: String) {
    CAMERA("◉", "拍照"), IMAGE("▧", "图片"), FILE("▫", "文件"),
    SCREEN_SHARE("▣", "屏幕共享"),
    TOOLS("⌁", "MCP / 工具"), CONTEXT("◔", "整理上下文"), EXTENSIONS("✦", "知识 / 自动化");
}

/** Permission/navigation destinations must not leave a capability panel covering the chat. */
fun orbisComposerPanelAfterCapability(current: OrbisComposerPanel?, action: OrbisComposerAction) =
    current.takeUnless { action == OrbisComposerAction.TOOLS || action == OrbisComposerAction.SCREEN_SHARE }

/** Each capability has its own destination; MCP must never fall through to the attachment sheet. */
fun dispatchOrbisCapability(
    action: OrbisComposerAction,
    takePicture: () -> Unit,
    pickImage: () -> Unit,
    pickFile: () -> Unit,
    openMcpSettings: () -> Unit,
    openContext: () -> Unit,
    openExtensions: () -> Unit,
    openScreenShare: () -> Unit,
) = when (action) {
    OrbisComposerAction.CAMERA -> takePicture()
    OrbisComposerAction.IMAGE -> pickImage()
    OrbisComposerAction.FILE -> pickFile()
    OrbisComposerAction.TOOLS -> openMcpSettings()
    OrbisComposerAction.CONTEXT -> openContext()
    OrbisComposerAction.EXTENSIONS -> openExtensions()
    OrbisComposerAction.SCREEN_SHARE -> openScreenShare()
}

data class OrbisQuickEmotion(val id: String, val glyph: String, val label: String, val category: String) {
    // Actual text visible to both people and AI, not a fictitious image/reference.
    val draftText: String get() = "$glyph（$label）"
}

val orbisQuickEmotions = listOf(
    OrbisQuickEmotion("hug", "🫂", "抱一下", "抱抱"),
    OrbisQuickEmotion("heart", "💗", "想你了", "抱抱"),
    OrbisQuickEmotion("hand", "🤝", "我陪着你", "抱抱"),
    OrbisQuickEmotion("plead", "🥺", "可怜巴巴", "搞怪"),
    OrbisQuickEmotion("melt", "🫠", "融化了", "搞怪"),
    OrbisQuickEmotion("peek", "👀", "悄悄看你", "搞怪"),
    OrbisQuickEmotion("work", "🛠️", "继续干活", "干活"),
    OrbisQuickEmotion("done", "✅", "做好啦", "干活"),
    OrbisQuickEmotion("rest", "☕", "歇一会儿", "干活"),
)

data class OrbisDictationUpdate(val text: String? = null, val stopCapture: Boolean = false)

/** Own only this manual recording's draft; late results may never replace newer user edits. */
class OrbisDictationDraft {
    private var revision = 0L
    private var base = ""
    private var lastApplied = ""

    @Synchronized fun begin(draft: String): Long {
        revision++
        base = draft
        lastApplied = draft
        return revision
    }

    @Synchronized fun cancel() { revision++ }

    @Synchronized fun update(ticket: Long, currentDraft: String, transcript: String): OrbisDictationUpdate {
        if (ticket != revision) return OrbisDictationUpdate()
        if (currentDraft != lastApplied) {
            revision++
            return OrbisDictationUpdate(stopCapture = true)
        }
        val spacer = if (base.isBlank() || transcript.isBlank()) "" else " "
        lastApplied = base + spacer + transcript
        return OrbisDictationUpdate(text = lastApplied)
    }
}
