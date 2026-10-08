package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll

/** Owns only remote-control work; interrupting it never cancels its caller's generation job. */
internal class InterruptibleQueueControl<K> {
    private val lock = Any()
    private val workers = mutableMapOf<K, MutableSet<Job>>()
    private val resets = mutableMapOf<K, List<Job>>()
    private val interruptions = mutableMapOf<K, List<Job>>()

    suspend fun <T> run(key: K, block: suspend () -> T): T = coroutineScope {
        // coroutineScope creates a child Job even when the caller is NonCancellable.
        val worker = currentCoroutineContext().job
        synchronized(lock) {
            if (key in resets || key in interruptions) throw interrupted()
            workers.getOrPut(key) { mutableSetOf() }.add(worker)
            // The block can return while an attached child is still unwinding. Keep the whole
            // scope registered until its Job completes, not merely until the block returns.
            worker.invokeOnCompletion {
                synchronized(lock) {
                    workers[key]?.let { active ->
                        active.remove(worker)
                        if (active.isEmpty()) workers.remove(key)
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        block()
    }

    /** Installs the registration barrier and captures its workers in the same critical section. */
    fun beginLocalReset(key: K): Boolean {
        val captured = synchronized(lock) {
            if (key in resets) return false
            workers[key].orEmpty().toList().also { resets[key] = it }
        }
        captured.forEach { it.cancel(interrupted()) }
        return true
    }

    /** Waits only for the captured workers, including their NonCancellable cleanup. */
    suspend fun awaitInterrupted(key: K) {
        val captured = synchronized(lock) {
            (resets[key].orEmpty() + interruptions[key].orEmpty()).distinct()
        }
        captured.joinAll()
    }

    /** Caller ends its reset after awaitInterrupted and its local persistence transaction. */
    fun endLocalReset(key: K) {
        synchronized(lock) { resets.remove(key) }
    }

    fun isResetting(key: K): Boolean = synchronized(lock) { key in resets }

    /** A separate persistent-in-RAM gate; release does not release an active local reset. */
    fun interrupt(key: K) {
        val captured = synchronized(lock) {
            (interruptions[key].orEmpty() + workers[key].orEmpty()).distinct().also {
                interruptions[key] = it
            }
        }
        captured.forEach { it.cancel(interrupted()) }
    }

    fun release(key: K) {
        synchronized(lock) { interruptions.remove(key) }
    }

    private fun interrupted() = CancellationException("queue_remote_control_interrupted")
}
