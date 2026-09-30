package me.rerere.rikkahub.data.orbis.sentinel

/** Only resumed chat screens count as present. A process restart never invents past absence. */
object OrbisSentinelPresence {
    private val visible = mutableMapOf<String, Int>()
    private val left = mutableMapOf<String, Long>()
    @Synchronized fun enter(conversationId: String) { visible[conversationId] = (visible[conversationId] ?: 0) + 1; left.remove(conversationId) }
    @Synchronized fun leave(conversationId: String, now: Long = System.currentTimeMillis()) {
        val count = visible[conversationId] ?: return
        if (count > 1) visible[conversationId] = count - 1
        else { visible.remove(conversationId); left[conversationId] = now }
    }
    @Synchronized fun leftAt(conversationId: String, observedAt: Long): Long? =
        if (conversationId in visible) null else left.getOrPut(conversationId) { observedAt }
}
