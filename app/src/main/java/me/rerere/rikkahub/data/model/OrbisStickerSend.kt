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
): Boolean = !generating && !submitting && !queued && !pendingTool
