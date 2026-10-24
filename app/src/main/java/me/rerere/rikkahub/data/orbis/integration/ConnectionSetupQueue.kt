package me.rerere.rikkahub.data.orbis.integration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Host-scoped, RAM-only UI requests. No keys, model-controlled route, or history replay. */
data class ConnectionSetupRequest(val owner: String, val conversation: String, val proposal: ConnectionProposal)

class ConnectionSetupQueue {
    private val pending = MutableStateFlow<List<ConnectionSetupRequest>>(emptyList())
    val requests = pending.asStateFlow()

    @Synchronized fun offer(conversation: String, proposal: ConnectionProposal) {
        if (conversation.isBlank() || proposal.status == "rejected") return
        val request = ConnectionSetupRequest(proposal.owner, conversation, proposal)
        pending.value = (pending.value.filterNot {
            it.owner == request.owner && it.conversation == conversation && it.proposal.id == proposal.id
        } + request).takeLast(16)
    }

    @Synchronized fun take(owner: String, conversation: String, current: List<ConnectionProposal>): ConnectionSetupRequest? {
        val scoped = pending.value.filter { it.owner == owner && it.conversation == conversation }
        pending.value = pending.value - scoped.toSet()
        return scoped.lastOrNull { request -> current.any {
            it.owner == owner && it.id == request.proposal.id && it.draft == request.proposal.draft && it.status != "rejected"
        } }
    }

    @Synchronized fun dismiss(owner: String, conversation: String) {
        pending.value = pending.value.filterNot { it.owner == owner && it.conversation == conversation }
    }
}

val connectionSetupQueue = ConnectionSetupQueue()
