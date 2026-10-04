package me.rerere.asr

import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One PCM16 mono utterance. Energy-based local endpointing, not a neural VAD claim.
 * Frame timing follows samples (not network/wall-clock delays). Idle audio stays in a
 * small pre-roll; no silence-only upload, no artificial model message at a chunk boundary.
 */
internal class LocalVoiceEndpointDetector(
    val sampleRate: Int,
    private val silenceMs: Int = 3_000,
    private val segmentMs: Int = 15_000,
) {
    sealed interface Event {
        data object Started : Event
        data class Segment(val pcm: ByteArray) : Event
        data object Ended : Event
    }

    init {
        require(sampleRate in 8_000..48_000 && sampleRate % 50 == 0)
        require(silenceMs >= 3_000 && segmentMs in 1_000..15_000)
    }
    private val frame = ByteArray(sampleRate / 50 * 2) // 20 ms, including split reads/samples.
    private var frameBytes = 0
    private val preRoll = ArrayDeque<ByteArray>()
    private var audio = ByteArrayOutputStream()
    private var startFrames = 0
    private var silentFrames = 0
    private var noiseFloor = 0.001
    private var started = false
    var ended: Boolean = false
        private set
    private var segmentHasSpeech = false
    private val maxSegmentBytes = sampleRate * 2 * segmentMs / 1_000

    fun accept(bytes: ByteArray, count: Int = bytes.size): List<Event> {
        require(count in 0..bytes.size)
        if (ended) return emptyList()
        val events = mutableListOf<Event>()
        var offset = 0
        while (offset < count && !ended) {
            val size = minOf(frame.size - frameBytes, count - offset)
            bytes.copyInto(frame, frameBytes, offset, offset + size)
            frameBytes += size
            offset += size
            if (frameBytes == frame.size) {
                processFrame(events)
                frameBytes = 0
            }
        }
        return events
    }

    private fun processFrame(events: MutableList<Event>) {
        var squares = 0.0
        for (i in frame.indices step 2) {
            val sample = ((frame[i].toInt() and 255) or (frame[i + 1].toInt() shl 8)).toShort().toDouble()
            squares += sample * sample
        }
        val rms = sqrt(squares / (frame.size / 2)) / 32768.0
        val voiced = rms >= max(0.008, noiseFloor * 3.5)
        if (!started) {
            if (!voiced) noiseFloor = noiseFloor * 0.98 + rms * 0.02
            preRoll.addLast(frame.copyOf())
            while (preRoll.size > 15) preRoll.removeFirst() // 300ms pre-roll, bounded even overnight.
            startFrames = if (voiced) startFrames + 1 else 0
            if (startFrames < 3) return // Ignore isolated clicks; preserve short >=60ms words.
            started = true
            segmentHasSpeech = true
            preRoll.forEach(audio::write)
            preRoll.clear()
            events.add(Event.Started)
        } else {
            audio.write(frame)
            segmentHasSpeech = segmentHasSpeech || voiced
        }
        silentFrames = if (voiced) 0 else silentFrames + 1
        if (silentFrames * 20 >= silenceMs) {
            emitSegment(events)
            ended = true
            events.add(Event.Ended)
        } else if (audio.size() >= maxSegmentBytes) {
            emitSegment(events)
        }
    }

    private fun emitSegment(events: MutableList<Event>) {
        if (segmentHasSpeech && audio.size() > 0) events.add(Event.Segment(audio.toByteArray()))
        audio = ByteArrayOutputStream()
        segmentHasSpeech = false
    }

    /** Explicit stop only. Muting/cancelling must call clear, never manufacture a final utterance. */
    fun finish(): List<Event> {
        if (ended) return emptyList()
        val events = mutableListOf<Event>()
        if (started) {
            // Preserve an incomplete final PCM frame, but never invent a half sample.
            audio.write(frame, 0, frameBytes - frameBytes % 2)
            emitSegment(events)
            events.add(Event.Ended)
        }
        ended = true
        clearBuffers()
        return events
    }

    fun clear() { ended = true; clearBuffers() }
    private fun clearBuffers() {
        frame.fill(0)
        frameBytes = 0
        preRoll.clear()
        audio.reset()
    }
}
