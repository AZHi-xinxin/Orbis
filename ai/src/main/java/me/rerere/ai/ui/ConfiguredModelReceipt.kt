package me.rerere.ai.ui

import kotlinx.serialization.Serializable

/** Local successful-request metadata, never a claim about a remote provider's actual weights. */
@Serializable
data class ConfiguredModelReceipt(val assistantId: String, val conversationId: String,
    val providerId: String, val modelId: String, val providerLabel: String)
