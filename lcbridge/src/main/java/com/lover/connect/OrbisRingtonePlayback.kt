package com.lover.connect

import java.net.URI

internal fun isLocalRingtoneUri(raw: String): Boolean = runCatching {
    val uri = URI(raw)
    raw.length <= 8192 && uri.scheme == "content" && !uri.authority.isNullOrBlank() &&
        uri.userInfo == null && uri.fragment == null
}.getOrDefault(false)

internal fun ringtoneCandidates(custom: String?, systemDefaults: List<String?>): List<String> =
    (listOfNotNull(custom?.takeIf(::isLocalRingtoneUri)) + systemDefaults.filterNotNull())
        .filter { it.isNotBlank() }.distinct()

internal interface RingtonePlaybackHandle {
    fun prepare(source: String, onReady: () -> Unit, onError: () -> Unit)
    fun start()
    fun release()
}

/** Serialized by the Android worker. The generation also rejects callbacks from replaced players. */
internal class OrbisRingtonePlayback(private val factory: () -> RingtonePlaybackHandle) {
    private var generation = 0L
    private var handle: RingtonePlaybackHandle? = null
    var isActive = false
        private set

    fun stop() {
        generation++
        isActive = false
        val old = handle
        handle = null
        runCatching { old?.release() }
    }

    fun start(candidates: List<String>, onStarted: () -> Unit, onFailure: () -> Unit) {
        stop()
        val token = generation
        isActive = true
        var index = 0
        var didStart = false
        fun next() {
            if (token != generation) return
            val old = handle
            handle = null
            runCatching { old?.release() }
            if (index >= candidates.size) {
                isActive = false
                generation++
                onFailure()
                return
            }
            val player = try { factory() } catch (_: Exception) { index++; next(); return }
            handle = player
            val source = candidates[index++]
            fun failed() {
                if (token == generation && handle === player) next()
            }
            try {
                player.prepare(source, onReady = {
                    if (token == generation && handle === player) {
                        try {
                            player.start()
                            if (!didStart) { didStart = true; onStarted() }
                        } catch (_: Exception) { failed() }
                    }
                }, onError = ::failed)
            } catch (_: Exception) { failed() }
        }
        next()
    }
}
