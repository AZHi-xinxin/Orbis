package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.*

@Serializable
data class OrbisLowEnergySegment(val startMs: Long, val durationMs: Long)

/** PCM measurements, NOT emotion recognition, speaker identification or semantic inference. */
@Serializable
data class OrbisVoiceAcoustics(
    val durationMs: Long,
    val sampleRate: Int,
    val rmsDbfs: Double,
    val peakDbfs: Double,
    val dynamicRangeDb: Double,
    val lowEnergyPauseCount: Int,
    val lowEnergyPauseMs: Long,
    val pitchHz: List<Double?>,
    val pauseThresholdDbfs: Double = -35.0,
    val minimumPauseMs: Int = 300,
    val lowEnergySegments: List<OrbisLowEnergySegment> = emptyList(),
    val pitchStepMs: Int = 250,
) {
    fun description(transcript: String): String {
        fun n(value: Double) = String.format(Locale.ROOT, "%.1f", value)
        val density = transcript.codePoints().filter { !Character.isWhitespace(it) }.count() * 60000.0 / durationMs.coerceAtLeast(1)
        val validPitch = pitchHz.filterNotNull().sorted()
        return "时长 ${n(durationMs / 1000.0)} 秒；采样率 $sampleRate Hz，单声道 PCM16；" +
            "转写字符密度 ${n(density / 60)} 字符/秒（不是语速判定）；RMS ${n(rmsDbfs)} dBFS，峰值 ${n(peakDbfs)} dBFS；" +
            "活跃帧 P95–P5 幅度范围 ${n(dynamicRangeDb)} dB；低能量段 ${lowEnergyPauseCount} 次、${n(lowEnergyPauseMs/1000.0)} 秒" +
            "（低于 ${n(pauseThresholdDbfs)} dBFS 且持续至少 $minimumPauseMs 毫秒，包括首尾静音）；" +
            (if (validPitch.isEmpty()) "基频无法可靠估计。" else "粗估基频中位数 ${n(validPitch[validPitch.size/2])} Hz，四分之一秒采样的有效点 ${validPitch.size}/${pitchHz.size}。") +
            "这些是本机录音测量，受设备、降噪和环境影响，不代表情绪、身份、意图或真实心理状态。"
    }
}

fun measureOrbisVoiceAcoustics(pcm: ByteArray, sampleRate: Int,
    pauseThresholdDbfs: Double = -35.0, minimumPauseMs: Int = 300): OrbisVoiceAcoustics {
    require(sampleRate in 8000..48000 && pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= sampleRate * 2 * 120)
    require(pauseThresholdDbfs in -70.0..-10.0 && minimumPauseMs in 100..2000)
    val samples = DoubleArray(pcm.size / 2) { i ->
        ((pcm[i*2].toInt() and 255) or (pcm[i*2+1].toInt() shl 8)).toShort().toDouble() / 32768.0
    }
    fun db(value: Double) = if (value <= 0.00001) -100.0 else 20 * log10(value)
    val frames = mutableListOf<Double>()
    val frameSize = sampleRate / 50
    var sum = 0.0; var peak = 0.0; var quietSamples = 0; var pauses = 0; var pauseSamples = 0L
    var processedSamples = 0
    val segments = mutableListOf<OrbisLowEnergySegment>()
    fun finishPause() {
        if (quietSamples.toLong()*1000 >= minimumPauseMs.toLong()*sampleRate) {
            pauses++; pauseSamples += quietSamples
            segments += OrbisLowEnergySegment((processedSamples-quietSamples).toLong()*1000/sampleRate,quietSamples.toLong()*1000/sampleRate)
        }
        quietSamples = 0
    }
    for (start in samples.indices step frameSize) {
        val end = minOf(start + frameSize, samples.size)
        var power = 0.0
        for (i in start until end) { power += samples[i]*samples[i]; peak = max(peak, abs(samples[i])) }
        sum += power
        val rms = db(sqrt(power / (end-start)))
        frames += rms
        if (rms < pauseThresholdDbfs) quietSamples += end-start else finishPause()
        processedSamples = end
    }
    finishPause()
    val active = frames.filter { it >= pauseThresholdDbfs }.sorted()
    val range = if (active.size < 2) 0.0 else active[((active.size-1)*.95).toInt()] - active[((active.size-1)*.05).toInt()]
    // Anti-aliasing block average for a small, bounded autocorrelation workload. Approximate F0
    // at 250 ms intervals, 50 ms windows; reject noise/flat/unvoiced windows, never invent pitch.
    val stride = (sampleRate / 4000).coerceAtLeast(1)
    val rate = sampleRate.toDouble() / stride
    val down = DoubleArray(samples.size/stride) { i -> (0 until stride).sumOf { samples[i*stride+it] } / stride }
    val window = (rate * .05).toInt()
    val pitch = (0 until ceil(samples.size.toDouble()/sampleRate*4).toInt().coerceAtMost(480)).map { index ->
        val start = (index*rate*.25).toInt()
        if (start + window > down.size) null else {
            val mean = (start until start+window).sumOf { down[it] } / window
            val power = (start until start+window).sumOf { (down[it]-mean).pow(2) } / window
            if (db(sqrt(power)) < pauseThresholdDbfs || power < 1e-8) null else {
                val minLag = (rate / 500).toInt().coerceAtLeast(2)
                val maxLag = (rate / 60).toInt().coerceAtMost(window/2)
                val scores = DoubleArray(maxLag+2)
                for (lag in minLag..maxLag+1) {
                    var xy=0.0; var xx=0.0; var yy=0.0
                    for (i in 0 until window-lag) {
                        val x=down[start+i]-mean; val y=down[start+i+lag]-mean
                        xy+=x*y; xx+=x*x; yy+=y*y
                    }
                    scores[lag] = xy / sqrt(xx*yy).coerceAtLeast(1e-12)
                }
                val lag = (minLag+1..maxLag).firstOrNull { scores[it] >= .85 && scores[it] > scores[it-1] && scores[it] >= scores[it+1] }
                lag?.let { rate/it }
            }
        }
    }
    return OrbisVoiceAcoustics(samples.size.toLong()*1000/sampleRate, sampleRate, db(sqrt(sum/samples.size)),
        db(peak), range, pauses, pauseSamples*1000/sampleRate, pitch, pauseThresholdDbfs, minimumPauseMs, segments)
}
