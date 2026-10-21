package me.rerere.rikkahub.data.orbis.voice

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

@Serializable
data class CallAcousticConfig(val enabled: Boolean = false, val silence: Boolean = true, val volume: Boolean = true, val pitch: Boolean = false)

@Serializable
data class CallAcousticEvent(val id: String, val callId: String, val kind: String, val startMs: Long, val endMs: Long,
    val quality: String = "valid_capture", val receipt: String? = null) {
    val text: String get() = when(kind) {
        "silence" -> "[通话测量] 在麦克风采集有效的这段时间里，约 ${(endMs-startMs)/1000} 秒未检测到用户发声。机器测量，不是情绪判断。"
        "low_volume" -> "[通话测量] 用户这段有效发声的音量比本通话基线低。机器测量，可能受距离和设备影响。"
        else -> "[通话测量] 用户这段有效发声的粗估音高持续高于本通话基线。机器测量，不是情绪判断。"
    }
}

data class AcousticSample(val rms: Double, val voiced: Boolean, val pitch: Double? = null, val clipped: Boolean = false)

/** Bounded mono PCM16 analysis. No audio survives the caller's short frame. */
internal object CallPcmAnalysis {
    fun analyze(bytes: ByteArray, sampleRate: Int, vad: Boolean?, pitchEnabled: Boolean): AcousticSample {
        require(sampleRate in 8000..48000 && bytes.size in 2..16384 && bytes.size % 2 == 0)
        val size = minOf(bytes.size / 2, sampleRate / 20)
        val step = maxOf(1, sampleRate / 8000)
        val samples = DoubleArray(size / step)
        var sum = 0.0; var clips = 0
        for(i in 0 until size) {
            val value = ((bytes[i*2].toInt() and 255) or (bytes[i*2+1].toInt() shl 8)).toShort().toDouble() / 32768
            sum += value * value
            if(kotlin.math.abs(value) > .99) clips++
            if(i % step == 0 && i / step < samples.size) samples[i/step] = value
        }
        val rms = sqrt(sum / size); val clipped = clips > size / 20
        // ASR VAD is preferred; periodicity is a conservative fallback, not a speech recognizer.
        val needPitch = pitchEnabled || vad == null
        var best = 0.0; var lagBest = 0
        if(needPitch && rms > .003 && !clipped && samples.size >= 160) {
            val rate = sampleRate.toDouble()/step
            val mean = samples.average(); for(i in samples.indices) samples[i] -= mean
            for(lag in (rate/400).toInt()..minOf((rate/65).toInt(), samples.size/2)) {
                var correlation = 0.0; var a = 0.0; var b = 0.0
                for(i in lag until samples.size) { correlation += samples[i]*samples[i-lag]; a += samples[i]*samples[i]; b += samples[i-lag]*samples[i-lag] }
                val score = if(a*b > 1e-12) correlation/sqrt(a*b) else 0.0
                if(score > best) { best = score; lagBest = lag }
            }
        }
        val hz = if(best >= .85 && lagBest > 0) sampleRate.toDouble()/step/lagBest else null
        return AcousticSample(rms, !clipped && rms > .003 && (vad ?: (hz != null)), if(pitchEnabled) hz else null, clipped)
    }
}

/** Elapsed-time windows, no frame log, maximum 120 baseline points + 100 recent points. */
internal class CallAcousticRules(private val callId: String) {
    private data class Frame(val at: Long, val sample: AcousticSample)
    private val recent = ArrayDeque<Frame>()
    private val baseline = ArrayDeque<AcousticSample>()
    private var start = -1L; private var last = -1L; private var quietStart = -1L
    private var silenceSent = false; private var lowSent = false; private var highSent = false
    private var sequence = 0
    private val lastEvent = mutableMapOf<String, Long>()
    fun resetBaseline() { start = -1; baseline.clear(); resetWindow() }
    fun resetWindow() { last = -1; quietStart = -1; recent.clear(); silenceSent = false; lowSent = false; highSent = false }
    fun accept(at: Long, sample: AcousticSample, config: CallAcousticConfig, usable: Boolean): List<CallAcousticEvent> {
        if(!config.enabled || !usable || sample.clipped || !sample.rms.isFinite()) { resetWindow(); return emptyList() }
        if(last >= 0 && (at <= last || at - last > 500)) resetWindow()
        if(start < 0) start = at
        last = at
        val events = mutableListOf<CallAcousticEvent>()
        fun emit(kind: String, from: Long) {
            if(at - (lastEvent[kind] ?: Long.MIN_VALUE/2) < 30_000) return
            lastEvent[kind] = at
            events += CallAcousticEvent("$callId-${++sequence}", callId, kind, from, at)
        }
        if(sample.voiced) { quietStart = -1; silenceSent = false }
        else {
            if(quietStart < 0) quietStart = at
            if(config.silence && !silenceSent && at - quietStart > 8000) { emit("silence", quietStart); silenceSent = true }
        }
        recent.addLast(Frame(at, sample))
        while(recent.size > 100 || recent.first().at < at - 5000) recent.removeFirst()
        if(at-start < 60_000) {
            if(sample.voiced && (baseline.size < 120)) baseline.addLast(sample)
            return events
        }
        if(baseline.size < 30 || recent.isEmpty() || at-recent.first().at < 4500) return events
        val voiced = recent.filter { it.sample.voiced }
        if(voiced.size < 30 || voiced.size < recent.size * .6) { lowSent = false; highSent = false; return events }
        val ratio = voiced.map { it.sample.rms }.average() / baseline.map { it.rms }.average()
        if(ratio > .75) lowSent = false
        if(config.volume && ratio < .6 && !lowSent) { emit("low_volume", recent.first().at); lowSent = true }
        fun median(values: List<Double>) = values.sorted().let { it[it.size/2] }
        val before = baseline.mapNotNull { it.pitch }; val pitches = voiced.mapNotNull { it.sample.pitch }
        if(config.pitch && before.size >= 30 && pitches.size >= 30 && pitches.size >= voiced.size * .8) {
            val pitchRatio = median(pitches) / median(before)
            if(pitchRatio < 1.15) highSent = false
            if(pitchRatio > 1.25 && !highSent) { emit("high_pitch", recent.first().at); highSent = true }
        }
        return events
    }
    internal val retainedFrames get() = recent.size + baseline.size
}
