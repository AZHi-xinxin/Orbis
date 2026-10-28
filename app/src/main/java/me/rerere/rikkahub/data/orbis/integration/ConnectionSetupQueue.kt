package me.rerere.rikkahub.data.orbis.integration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Host-scoped, RAM-only UI requests. No keys, model-controlled route, or history replay. */
data class ConnectionSetupRequest(val owner: String, val conversation: String, val proposal: ConnectionProposal?, val guide: Boolean = false)
data class ConnectionSetupDismissal(val id: String?, val sequence: Long)

class ConnectionSetupQueue {
    private val pending = MutableStateFlow<List<ConnectionSetupRequest>>(emptyList())
    val requests = pending.asStateFlow()
    private val closed = MutableStateFlow<Map<String, ConnectionSetupDismissal>>(emptyMap())
    val dismissals = closed.asStateFlow()
    private var sequence = 0L

    @Synchronized fun offer(conversation: String, proposal: ConnectionProposal, guide: Boolean = false) {
        if (conversation.isBlank() || !proposal.setupPromptPending) return
        val request = ConnectionSetupRequest(proposal.owner, conversation, proposal, guide)
        pending.value = (pending.value.filterNot {
            it.owner == request.owner && it.conversation == conversation && it.proposal?.id == proposal.id
        } + request).takeLast(16)
    }

    @Synchronized fun offerGuide(owner: String, conversation: String) {
        if (owner.isBlank() || conversation.isBlank()) return
        pending.value = (pending.value.filterNot { it.owner == owner && it.conversation == conversation && it.proposal == null } +
            ConnectionSetupRequest(owner, conversation, null, true)).takeLast(16)
    }

    @Synchronized fun take(owner: String, conversation: String, current: List<ConnectionProposal>): ConnectionSetupRequest? {
        val scoped = pending.value.filter { it.owner == owner && it.conversation == conversation }
        pending.value = pending.value - scoped.toSet()
        return scoped.lastOrNull { request -> request.proposal?.let { proposal -> current.any {
            it.owner == owner && it.id == proposal.id && it.draft == proposal.draft && it.setupPromptPending
        } } ?: request.guide }
    }

    @Synchronized fun dismiss(owner: String, conversation: String) {
        pending.value = pending.value.filterNot { it.owner == owner && it.conversation == conversation }
    }

    /** Close existing sheets/queued requests for this assistant; future explicit opens still work. */
    @Synchronized fun closeSetup(owner: String, id: String? = null) {
        pending.value = pending.value.filterNot { it.owner == owner && (id == null || it.proposal?.id == id) }
        closed.value = (closed.value.filterKeys { it != owner } + (owner to ConnectionSetupDismissal(id, ++sequence)))
            .entries.toList().takeLast(100).associate { it.toPair() }
    }
}

val connectionSetupQueue = ConnectionSetupQueue()
