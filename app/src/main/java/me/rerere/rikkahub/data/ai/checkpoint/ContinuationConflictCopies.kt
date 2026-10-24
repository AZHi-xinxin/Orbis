package me.rerere.rikkahub.data.ai.checkpoint

import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Inert recovery evidence, never loaded as an active generation or tool receipt. */
object ContinuationConflictCopies {
    const val DIRECTORY = "orbis-human-continuation-conflicts-v1"
    const val GENERATION_DIRECTORY = "orbis-generation-conflicts-v1"
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    fun name(id: Uuid, bytes: ByteArray): String = "$id--${hash(bytes)}.json"
}
