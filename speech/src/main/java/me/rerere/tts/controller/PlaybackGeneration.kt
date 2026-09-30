package me.rerere.tts.controller

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicLong

/** Stop/reset invalidates synthesis, completion and cleanup callbacks belonging to an older queue. */
internal class PlaybackGeneration {
    private val value = AtomicLong()
    fun current(): Long = value.get()
    fun advance(): Long = value.incrementAndGet()
    fun isCurrent(token: Long): Boolean = value.get() == token
    fun requireCurrent(token: Long) {
        if (!isCurrent(token)) throw CancellationException("Playback session replaced")
    }
    suspend fun <T> awaitCurrent(token: Long, work: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        requireCurrent(token)
        val value = work()
        currentCoroutineContext().ensureActive()
        requireCurrent(token)
        return value
    }
}
