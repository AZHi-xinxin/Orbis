package me.rerere.asr

/** Optional single-session tap. No observer means no audio is copied or retained. */
class ASRPcmObserver {
    private var epoch = 0L
    private var observer: ((ByteArray, Int) -> Unit)? = null

    @Synchronized
    fun set(observer: ((ByteArray, Int) -> Unit)?) {
        epoch++
        this.observer = observer
    }

    @Synchronized
    fun captureEpoch(): Long = epoch

    /** Callback runs on the capture IO thread. Signed PCM16 LE mono byte stream; a block may split a sample. */
    @Synchronized
    fun emit(captureEpoch: Long, buffer: ByteArray, count: Int, sampleRate: Int) {
        if (epoch != captureEpoch) return
        val callback = observer ?: return
        require(count in 0..buffer.size && sampleRate > 0)
        if (count > 0) callback(buffer.copyOf(count), sampleRate)
    }
}
