package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Local presentation preferences; never a model prompt or an assistant identity. */
@Serializable
data class OrbisAppearance(
    val bubbleStyle: OrbisBubbleStyle = OrbisBubbleStyle.SILK,
    val bubbleOpacity: Float = .92f,
    /** Only the chat composer's backdrop; text and controls retain their own contrast. */
    val composerOpacity: Float = 1f,
    /** False inherits the current assistant's existing image without changing it. */
    val backgroundEnabled: Boolean = false,
    val backgroundStyle: OrbisBackgroundStyle = OrbisBackgroundStyle.PAPER,
    val backgroundImage: String? = null,
    val floatingStars: Boolean = true,
    val reduceMotion: Boolean = false,
    /** Opaque ARGB for chat prose only; null preserves theme-driven text colors. */
    val chatTextColor: Int? = null,
    /** Global external-event detail backdrop only; never fades text or the collapsed control. */
    val eventOpacity: Float = .22f,
    /** Paragraph bubbles and optional action styling; never rewritten into chat text. */
    val chatFlow: OrbisChatFlowSettings = OrbisChatFlowSettings(),
    /** Null preserves the pre-split value above, including an existing customized value. */
    val userBubbleOpacity: Float? = null,
    val assistantBubbleOpacity: Float? = null,
) {
    fun bubbleOpacityForRole(user: Boolean): Float {
        val legacy = bubbleOpacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .92f
        return (if (user) userBubbleOpacity else assistantBubbleOpacity)
            ?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: legacy
    }

    fun normalized(): OrbisAppearance = copy(
        bubbleOpacity = if (bubbleOpacity.isFinite()) bubbleOpacity.coerceIn(0f, 1f) else .92f,
        userBubbleOpacity = userBubbleOpacity?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        assistantBubbleOpacity = assistantBubbleOpacity?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        composerOpacity = if (composerOpacity.isFinite()) composerOpacity.coerceIn(.15f, 1f) else 1f,
        chatTextColor = chatTextColor?.or(0xFF000000.toInt()),
        eventOpacity = if (eventOpacity.isFinite()) eventOpacity.coerceIn(0f, 1f) else .22f,
    )
}

@Serializable
enum class OrbisBubbleStyle {
    @SerialName("silk") SILK,
    @SerialName("glass") GLASS,
    @SerialName("stars") STARS,
    @SerialName("book") BOOK,
}

@Serializable
enum class OrbisBackgroundStyle {
    @SerialName("paper") PAPER,
    @SerialName("blush") BLUSH,
    @SerialName("stars") STARS,
}

/** Conservative animation gate; when false, the backdrop may still draw static stars. */
fun shouldAnimateOrbisStars(
    appearance: OrbisAppearance,
    isVisible: Boolean,
    isResumed: Boolean,
    isPowerSaveMode: Boolean,
    systemAnimationsEnabled: Boolean,
): Boolean = appearance.floatingStars && !appearance.reduceMotion && isVisible &&
    isResumed && !isPowerSaveMode && systemAnimationsEnabled

internal const val ORBIS_IMAGE_MAX_BYTES: Long = 30L * 1024 * 1024

/** Bounded streaming copy; caller owns streams and removes an incomplete destination on failure. */
internal fun copyOrbisImage(input: InputStream, output: OutputStream, maxBytes: Long = ORBIS_IMAGE_MAX_BYTES): Long {
    require(maxBytes > 0) { "invalid_image_limit" }
    val buffer = ByteArray(8192)
    var count = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (count > maxBytes - read) throw IOException("image_too_large")
        output.write(buffer, 0, read)
        count += read
    }
    if (count == 0L) throw IOException("image_empty")
    return count
}
