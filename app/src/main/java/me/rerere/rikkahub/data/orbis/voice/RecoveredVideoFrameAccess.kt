package me.rerere.rikkahub.data.orbis.voice

import java.io.File
import kotlinx.coroutines.Deferred

/** All external frame reads wait for the SAME cold-start repair, before acquiring store locks. */
internal class RecoveredVideoFrameAccess(
    private val store: OrbisVideoFrameStore,
    private val recovery: Deferred<*>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun list(owner: String, callId: String? = null): List<OrbisVideoFrameCall> {
        recovery.await()
        return store.list(owner, callId, now())
    }

    suspend fun read(owner: String, callId: String, frameId: String): ByteArray {
        recovery.await()
        return store.readJpeg(owner, callId, frameId, now())
    }

    suspend fun retain(owner: String, callId: String, frameId: String, import: suspend (File) -> String): String {
        recovery.await()
        return store.retain(owner, callId, frameId, now(), import)
    }
}
