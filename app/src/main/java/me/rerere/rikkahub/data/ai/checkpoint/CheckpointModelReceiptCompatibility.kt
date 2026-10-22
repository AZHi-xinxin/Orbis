package me.rerere.rikkahub.data.ai.checkpoint

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** The pre-receipt UIMessage encoder omitted this one newly added nullable default.
 * Used only after the normal digest fails, on strictly decoded and re-encoded DTOs.
 * Never traverse message parts/tool JSON or remove a non-null model receipt.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object CheckpointModelReceiptCompatibility {
    fun node(encodedNode: JsonElement): JsonElement {
        val node = encodedNode as JsonObject
        val messages = node.getValue("messages") as JsonArray
        return JsonObject(node + ("messages" to JsonArray(messages.map { encodedMessage ->
            val message = encodedMessage as JsonObject
            if (message["configuredModelReceipt"] == JsonNull)
                JsonObject(message - "configuredModelReceipt") else message
        })))
    }

    fun recordHash(json: Json, encodedRecord: JsonElement, nodeFields: Set<String>): String {
        val record = encodedRecord as JsonObject
        val legacy = JsonObject(record.mapValues { (key, value) ->
            if (key in nodeFields) JsonArray((value as JsonArray).map(::node)) else value
        })
        val digest = MessageDigest.getInstance("SHA-256")
        json.encodeToStream(legacy, DigestOutputStream(NullOutput, digest))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private object NullOutput : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }
}
