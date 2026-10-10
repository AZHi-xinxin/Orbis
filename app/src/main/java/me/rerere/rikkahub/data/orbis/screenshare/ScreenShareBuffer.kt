package me.rerere.rikkahub.data.orbis.screenshare

import kotlin.math.abs

internal data class ScreenShareFrame(val id: String, val capturedAt: Long, val jpeg: ByteArray, val signature: IntArray)

internal class ScreenShareBuffer {
    private val frames = ArrayDeque<ScreenShareFrame>()
    private val pinned = mutableMapOf<String, Int>()
    @Synchronized fun add(frame: ScreenShareFrame, force: Boolean = false): Boolean {
        require(frame.jpeg.size in 1..2_000_000)
        val previous = frames.lastOrNull()
        if (!force && previous != null && !changed(previous.signature, frame.signature)) return false
        frames.addLast(frame)
        while (frames.size > 8 || frames.sumOf { it.jpeg.size } > 8_000_000) {
            val evicted = frames.firstOrNull { it.id !in pinned } ?: break
            frames.remove(evicted); evicted.jpeg.fill(0)
        }
        return frames.any { it.id == frame.id }
    }
    @Synchronized fun get(id: String): ScreenShareFrame? = frames.firstOrNull { it.id == id }?.let { it.copy(jpeg = it.jpeg.copyOf()) }
    @Synchronized fun back(index: Int): ScreenShareFrame? = frames.reversed().getOrNull(index)?.let { it.copy(jpeg = it.jpeg.copyOf()) }
    @Synchronized fun pin(id: String): Boolean {
        if (frames.none { it.id == id }) return false
        pinned[id] = (pinned[id] ?: 0) + 1
        return true
    }
    @Synchronized fun unpin(id: String) { pinned[id]?.let { if (it <= 1) pinned.remove(id) else pinned[id] = it - 1 } }
    @Synchronized fun clearUnpinned() {
        val discarded = frames.filter { it.id !in pinned }
        discarded.forEach { it.jpeg.fill(0) }; frames.removeAll(discarded.toSet())
    }
    @Synchronized fun clear() { frames.forEach { it.jpeg.fill(0) }; frames.clear(); pinned.clear() }
    @Synchronized fun size(): Int = frames.size
    companion object {
        fun changed(before: IntArray, after: IntArray): Boolean = before.size != after.size || before.isEmpty() ||
            before.indices.count { abs(before[it] - after[it]) > 12 } >= maxOf(1, before.size / 50)
    }
}

internal fun screenSharePermitted(owner: String, conversation: String, session: String,
    currentOwner: String?, currentConversation: String?, currentSession: String?, enabled: Boolean): Boolean =
    enabled && session == currentSession && owner == currentOwner && conversation == currentConversation
