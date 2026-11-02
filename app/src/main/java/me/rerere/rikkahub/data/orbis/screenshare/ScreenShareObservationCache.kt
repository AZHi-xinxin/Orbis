package me.rerere.rikkahub.data.orbis.screenshare

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Short lived text only. Access is checked even on a hit; clear invalidates in-flight results. */
internal class ScreenShareObservationCache<K>(private val limit: Int = 8) {
    private val mutex = Mutex()
    private val values = linkedMapOf<K, String>()
    private var epoch = 0L

    @Synchronized fun clear() { epoch++; values.clear() }

    suspend fun get(key: K, checkAccess: () -> Unit, describe: suspend () -> String): String = mutex.withLock {
        currentCoroutineContext().ensureActive()
        checkAccess()
        val generation = synchronized(this) { epoch }
        synchronized(this) { values[key] }?.let { return@withLock it }
        val result = describe().trim().take(6000)
        currentCoroutineContext().ensureActive()
        checkAccess()
        check(result.isNotBlank()) { "screen_description_empty" }
        synchronized(this) {
            check(generation == epoch) { "screen_description_revoked" }
            values[key] = result
            while (values.size > limit) values.remove(values.keys.first())
        }
        result
    }
}
