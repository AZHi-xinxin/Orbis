package me.rerere.rikkahub.data.orbis.voice

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.asr.ASRController
import me.rerere.asr.ASRStatus

/** Fail-open side channel: two short PCM frames, no microphone, no model requests, no audio control. */
internal class LiveCallAcoustics(
    context: Context, val callId: String,
    private val config: () -> CallAcousticConfig,
    private val usable: () -> Boolean,
) {
    private data class Frame(val pcm: ByteArray, val rate: Int, val vad: Boolean?, val time: Long, val capture: Long, val route: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frames = Channel<Frame>(2, BufferOverflow.DROP_OLDEST)
    private val capture = AtomicLong(); private val route = AtomicLong(); private val lastOffer = AtomicLong(-1000)
    private val repository = OrbisVoiceCallRepository(context)
    private val rules = CallAcousticRules(callId)
    private val lock = Any(); private val pending = ArrayDeque<CallAcousticEvent>()
    private val receipts = linkedMapOf<String, String>(); private var deliveries = 0
    private val mutable = MutableStateFlow<List<CallAcousticEvent>>(emptyList())
    val events = mutable.asStateFlow()
    @Volatile private var closed = false
    private var captureStatusJob: Job? = null
    init { scope.launch {
        var previousRoute = -1L; var previousCapture = -1L; var previousConfig = CallAcousticConfig(); var previousRate = 0
        for(frame in frames) {
            if(closed || frame.capture != capture.get() || frame.route != route.get()) continue
            try {
            val chosen = config()
            if(chosen != previousConfig || previousRoute != frame.route || frame.rate != previousRate) {
                rules.resetBaseline(); synchronized(lock) { pending.clear() }
            } else if(previousCapture != frame.capture) rules.resetWindow()
            previousConfig = chosen; previousRoute = frame.route; previousCapture = frame.capture; previousRate = frame.rate
            if(!chosen.enabled || !usable() || SystemClock.elapsedRealtime() - frame.time > 500) { rules.resetWindow(); continue }
                val sample = CallPcmAnalysis.analyze(frame.pcm, frame.rate, frame.vad, chosen.pitch)
                val found = rules.accept(frame.time, sample, chosen, true)
                for(event in found) {
                    synchronized(lock) {
                        if(mutable.value.size < 200) mutable.value = mutable.value + event
                        if(deliveries < 5) { pending.addLast(event); while(pending.size > 3) pending.removeFirst() }
                    }
                    if(mutable.value.any { it.id == event.id }) withContext(Dispatchers.IO) {
                        runCatching { repository.update(callId) { record ->
                            if(record.status == OrbisVoiceCallStatus.ACTIVE && record.acousticEvents.size < 200 && record.acousticEvents.none { it.id == event.id })
                                record.copy(acousticEvents = record.acousticEvents +
                                    (synchronized(lock) { mutable.value.firstOrNull { it.id == event.id } } ?: event)) else record
                        } }
                    }
                }
            } catch(c: CancellationException) { throw c }
            catch(_: Exception) { rules.resetWindow() }
        }
    } }
    fun routeChanged() { route.incrementAndGet(); synchronized(lock) { pending.clear() } }
    fun bind(recorder: ASRController): ASRController {
        val epoch = capture.incrementAndGet()
        routeChanged() // A new recognizer/capture may have a different route or gain.
        captureStatusJob?.cancel()
        captureStatusJob = scope.launch {
            var previous: ASRStatus? = null
            recorder.state.collect { state ->
                if (epoch != capture.get()) return@collect
                if (previous != state.status && state.status in setOf(ASRStatus.Connecting, ASRStatus.Error)) routeChanged()
                previous = state.status
            }
        }
        recorder.setPcmObserver { bytes, rate ->
            // No suspend/IO/model work on the ASR callback. Exceptions must never escape to capture.
            runCatching {
                if(!closed && epoch == capture.get() && config().enabled && usable() &&
                    recorder.state.value.status == ASRStatus.Listening && bytes.size in 2..16384 && bytes.size % 2 == 0) {
                    val now = SystemClock.elapsedRealtime(); val previous = lastOffer.get()
                    if(now-previous >= 80 && lastOffer.compareAndSet(previous, now)) {
                        val turn = recorder.state.value.voiceTurn
                        val vad = if(turn.itemId == null) null else !turn.speechEnded
                        frames.trySend(Frame(bytes.copyOf(), rate, vad, now, epoch, route.get()))
                    }
                }
            }
        }
        return recorder
    }
    /** Same real message gets the same receipt on retries. No other message/call can consume it twice. */
    fun claim(messageId: String): String? = synchronized(lock) {
        if(closed || !config().enabled) return null
        receipts[messageId]?.let { return it }
        val now = SystemClock.elapsedRealtime()
        val fresh = pending.filter { now-it.endMs in 0..30_000 }.take(3)
        pending.clear()
        if(fresh.isEmpty() || deliveries >= 5) return null
        deliveries++
        val text = fresh.joinToString("\n") { it.text }
        receipts[messageId] = text
        val ids = fresh.map { it.id }.toSet()
        mutable.value = mutable.value.map { if(it.id in ids) it.copy(receipt = messageId) else it }
        scope.launch(Dispatchers.IO) { runCatching { repository.update(callId) { record ->
            record.copy(acousticEvents = record.acousticEvents.map { if(it.id in ids) it.copy(receipt = messageId) else it })
        } } }
        text
    }
    fun close() { closed = true; frames.close(); scope.cancel(); synchronized(lock) { pending.clear(); receipts.clear() } }
}
