package me.rerere.rikkahub.service

import java.security.MessageDigest

/** Input is used only to fingerprint a batch. Never expose it in diagnostic string output. */
internal class GatewayToolCallIdentity(
    internal val toolCallId: String,
    internal val toolName: String,
    internal val input: String,
) {
    override fun toString(): String = "GatewayToolCallIdentity([redacted])"
}

/**
 * Local evidence for handing a waiting approval back to its exact successor invocation.
 * Approval state and outputs intentionally do not participate: approving a call or receiving
 * a peer's result must not change its identity. Message, model, owner and ordered arguments do.
 * This is not gateway authority; remote operations still require their captured request binding.
 */
internal class GatewayToolBatchIdentity private constructor(
    internal val conversationId: String,
    private val digest: String,
) {
    internal fun matches(other: GatewayToolBatchIdentity): Boolean =
        conversationId == other.conversationId && digest == other.digest

    override fun toString(): String = "GatewayToolBatchIdentity([redacted])"

    companion object {
        fun create(
            conversationId: String,
            assistantId: String,
            modelId: String,
            messageId: String,
            calls: List<GatewayToolCallIdentity>,
        ): GatewayToolBatchIdentity? {
            if (listOf(conversationId, assistantId, modelId, messageId).any { it.isBlank() } ||
                calls.isEmpty() || calls.any { it.toolCallId.isBlank() || it.toolName.isBlank() } ||
                calls.map { it.toolCallId }.distinct().size != calls.size) return null
            val hash = MessageDigest.getInstance("SHA-256")
            fun field(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                // Length framing avoids ambiguous concatenations, including embedded separators.
                hash.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(), bytes.size.toByte()))
                hash.update(bytes)
            }
            field("orbis-gateway-approval-batch/1")
            listOf(conversationId, assistantId, modelId, messageId, calls.size.toString()).forEach(::field)
            calls.forEach { call ->
                field(call.toolCallId)
                field(call.toolName)
                field(call.input)
            }
            return GatewayToolBatchIdentity(conversationId,
                hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
        }
    }
}

/**
 * Ephemeral invocation ownership, separate from the broad human-stop request ledger.
 * Only a normally suspended approval may transfer requests, and only to the next invocation
 * with the exact batch fingerprint. Terminal paths must take their snapshot then [finish].
 * No persistence, network operations, retries, queue releases or tool execution occur here.
 */
internal class GatewayInvocationTracker<T>(
    private val key: (T) -> String,
    private val maxConversations: Int = 512,
    private val maxRequests: Int = 16,
) {
    init {
        require(maxConversations > 0)
        require(maxRequests > 0)
    }

    internal class Invocation<T> internal constructor(
        internal val owner: Any,
        internal val conversationId: String,
        internal val captured: LinkedHashMap<String, T> = linkedMapOf(),
    ) {
        override fun toString(): String = "GatewayInvocation([redacted])"
    }

    private class Slot<T>(
        val active: Invocation<T>? = null,
        val pendingBatch: GatewayToolBatchIdentity? = null,
        val pendingRequests: Map<String, T> = emptyMap(),
    )

    private val lock = Any()
    private val owner = Any()
    private val slots = LinkedHashMap<String, Slot<T>>()

    fun begin(conversationId: String, resumableBatch: GatewayToolBatchIdentity? = null): Invocation<T> =
        synchronized(lock) {
            require(conversationId.isNotBlank())
            val previous = slots.remove(conversationId)
            val invocation = Invocation<T>(owner, conversationId)
            if (resumableBatch?.conversationId == conversationId &&
                previous?.pendingBatch?.matches(resumableBatch) == true) {
                invocation.captured.putAll(previous.pendingRequests)
            }
            slots[conversationId] = Slot(active = invocation)
            while (slots.size > maxConversations) slots.remove(slots.keys.first())
            invocation
        }

    fun remember(invocation: Invocation<T>, request: T): Unit = synchronized(lock) {
        if (!isCurrent(invocation)) return@synchronized
        val requestKey = key(request)
        if (requestKey.isBlank()) return@synchronized
        invocation.captured.remove(requestKey)
        invocation.captured[requestKey] = request
        while (invocation.captured.size > maxRequests) {
            invocation.captured.remove(invocation.captured.keys.first())
        }
    }

    /** The snapshot remains readable after finish; it can only address that old invocation. */
    fun requests(invocation: Invocation<T>): List<T> = synchronized(lock) {
        if (invocation.owner !== owner) emptyList() else invocation.captured.values.toList().asReversed()
    }

    /** Call only after a normal, durable WAITING_APPROVAL return, never after an exception. */
    fun retainForApproval(invocation: Invocation<T>, batch: GatewayToolBatchIdentity): Boolean =
        synchronized(lock) {
            if (!isCurrent(invocation) || batch.conversationId != invocation.conversationId ||
                invocation.captured.isEmpty()) return@synchronized false
            slots[invocation.conversationId] = Slot(pendingBatch = batch,
                pendingRequests = LinkedHashMap(invocation.captured))
            true
        }

    fun finish(invocation: Invocation<T>): Unit = synchronized(lock) {
        // A late failure/cleanup cannot erase a newer invocation or an already retained approval.
        if (isCurrent(invocation)) slots.remove(invocation.conversationId)
    }

    private fun isCurrent(invocation: Invocation<T>): Boolean = invocation.owner === owner &&
        slots[invocation.conversationId]?.active === invocation
}
