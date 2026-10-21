package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessagePart

/** Only fixed, user-facing validation messages use this type; IO diagnostics stay private. */
internal class OrbisStickerSendNotice(val userMessage: String) : Exception()

/** A fast second tap must not become a duplicate even if the first job already failed/finished. */
internal fun isOrbisStickerTapDebounced(nowMillis: Long, acceptedAtMillis: Long?): Boolean =
    acceptedAtMillis != null && nowMillis >= acceptedAtMillis && nowMillis - acceptedAtMillis < 600L

/** No draft mutation and no model-generated reference expansion: only an explicit tile tap. */
internal fun orbisStickerImageParts(imageUrl: String): List<UIMessagePart> {
    require(imageUrl.startsWith("file:"))
    return listOf(UIMessagePart.Image(imageUrl))
}

internal fun canSendOrbisStickerNow(
    generating: Boolean,
    submitting: Boolean,
    queued: Boolean,
    pendingTool: Boolean,
): Boolean = orbisStickerSendUnavailableReason(generating = generating, submitting = submitting,
    queued = queued, pendingTool = pendingTool) == null

/** A pause alone is not an admission decision: the service must persist explicit fresh-input preparation. */
internal fun orbisStickerSendUnavailableReason(
    generating: Boolean = false,
    submitting: Boolean = false,
    queued: Boolean = false,
    pendingTool: Boolean = false,
    editing: Boolean = false,
    voiceUnavailable: Boolean = false,
    recording: Boolean = false,
): String? = when {
    voiceUnavailable -> "通话仍在进行或收尾，请结束后重新点选表情；不会自动补发。"
    recording -> "正在录音或识别，请结束后重新点选表情；不会自动补发。"
    editing -> "正在编辑消息，请完成或取消编辑后再发送表情。"
    generating -> "当前回复尚未结束，请完成或停止本轮后再发送表情。"
    submitting -> "上一条输入正在保存，请稍后重新点选表情。"
    queued -> "还有待发或正在编辑的队列消息，请先处理；表情不会插队或自动补发。"
    pendingTool -> "当前工具操作尚未结束，请处理完成后重新点选表情。"
    else -> null
}
