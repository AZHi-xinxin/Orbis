package me.rerere.ai.core

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
    // A streaming delta may omit cache usage; an explicitly reported zero is not omission.
    // Recovery journals checksum serialized messages; legacy default bytes must not change.
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val cachedTokensReported: Boolean = cachedTokens > 0,
)

fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    val totalTokens = promptTokens + completionTokens
    // A new input snapshot must never inherit a cache hit from the preceding request.
    // Output-only deltas (notably Claude message_delta) still retain their input snapshot.
    val replacesCache = other.cachedTokensReported || other.cachedTokens > 0 || other.promptTokens > 0
    val cachedTokens = if (replacesCache) {
        other.cachedTokens
    } else {
        this?.cachedTokens ?: 0
    }
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens,
        cachedTokensReported = if (replacesCache) other.cachedTokensReported else this?.cachedTokensReported ?: false,
    )
}
