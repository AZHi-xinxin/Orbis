package me.rerere.ai.ui

import kotlinx.serialization.Serializable

/** Local request evidence, not provider billing or message content. No transcript is duplicated. */
@Serializable
data class OrbisPreparedContextUsage(
    val tokens: Long,
    val preparedAtMillis: Long,
    val contextEpoch: Long,
    val assistantId: String,
    val modelId: String,
    val configurationDigest: String,
    val historyDigest: String,
)
