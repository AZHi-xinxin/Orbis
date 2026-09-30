package me.rerere.asr

internal data class EchoCancellationState(val available: Boolean = false, val active: Boolean = false)

internal interface EchoCancellationEffect {
    fun enable(): Boolean
    fun isEnabled(): Boolean
    fun hasControl(): Boolean
    fun listen(onChange: (() -> Unit)?)
    fun release()
}

internal interface EchoCancellationFactory {
    fun isAvailable(): Boolean
    fun create(audioSessionId: Int): EchoCancellationEffect?
}

/** One capture session, one native effect; unavailable/failed/lost control must never imply duplex. */
internal class EchoCancellationSession(private val factory: EchoCancellationFactory,
    private val onState: (EchoCancellationState) -> Unit) {
    private var effect: EchoCancellationEffect? = null
    private var generation = 0L
    private var available = false
    private var enabled = false

    @Synchronized fun start(requested: Boolean, audioSessionId: Int) {
        close()
        if (!requested || audioSessionId <= 0) return
        val token = generation
        try {
            available = factory.isAvailable()
            if (!available) return
            val created = factory.create(audioSessionId) ?: return
            effect = created
            created.listen { refresh(token, created) }
            enabled = created.enable()
            refresh(token, created)
        } catch (_: Exception) {
            enabled = false
            val old = effect
            effect = null
            runCatching { old?.listen(null) }
            runCatching { old?.release() }
        } finally { publish() }
    }

    @Synchronized private fun refresh(token: Long, expected: EchoCancellationEffect) {
        if (token != generation || effect !== expected) return
        publish()
    }

    private fun publish() {
        val active = enabled && runCatching { effect?.let { it.isEnabled() && it.hasControl() } == true }.getOrDefault(false)
        onState(EchoCancellationState(available, active))
    }

    @Synchronized fun close() {
        generation++
        val old = effect
        effect = null
        enabled = false
        available = false
        runCatching { old?.listen(null) }
        runCatching { old?.release() }
        publish()
    }
}
