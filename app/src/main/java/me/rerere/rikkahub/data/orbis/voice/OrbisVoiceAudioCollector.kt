package me.rerere.rikkahub.data.orbis.voice

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.model.pcm16MonoWav
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val ORBIS_VOICE_NOTE_MAX_BYTES = 32 * 1024 * 1024
internal data class OrbisVoiceAudio(val bytes: ByteArray, val extension: String)

/** No file is published until the provider completes normally AND supplies its terminal chunk. */
internal suspend fun collectOrbisVoiceAudio(chunks: Flow<AudioChunk>): OrbisVoiceAudio {
    val collector = OrbisVoiceAudioCollector()
    chunks.collect { chunk ->
        currentCoroutineContext().ensureActive()
        collector.append(chunk)
    }
    currentCoroutineContext().ensureActive()
    return collector.finish()
}

internal class OrbisVoiceAudioCollector(private val limit: Int = ORBIS_VOICE_NOTE_MAX_BYTES) {
    private val bytes = ByteArrayOutputStream()
    private var format: AudioFormat? = null
    private var sampleRate: Int? = null
    private var completed = false
    private var rejected = false

    init { require(limit in 45..ORBIS_VOICE_NOTE_MAX_BYTES) }

    fun append(chunk: AudioChunk) {
        require(!rejected) { "之前的语音分片无效，未发送语音条" }
        try { appendChecked(chunk) } catch (error: Throwable) {
            // Some legacy providers catch downstream collector exceptions. Never let a later
            // terminal chunk turn already-rejected partial bytes into a successful voice note.
            rejected = true
            throw error
        }
    }

    private fun appendChecked(chunk: AudioChunk) {
        require(!completed) { "语音服务在结束后仍返回音频" }
        require(format == null || format == chunk.format) { "语音格式中途改变" }
        format = chunk.format
        chunk.sampleRate?.let { rate ->
            require(rate in 8_000..192_000 && (sampleRate == null || sampleRate == rate)) { "语音采样率无效或中途改变" }
            sampleRate = rate
        }
        if (chunk.format == AudioFormat.PCM) {
            // AudioChunk PCM is PCM16LE mono; reject a provider declaring a different encoding.
            chunk.metadata["channels"]?.let { require(it == "1") { "暂不支持多声道 PCM 语音条" } }
            chunk.metadata["bitsPerSample"]?.let { require(it == "16") { "暂不支持此 PCM 位深" } }
        }
        val headerBytes = if (chunk.format == AudioFormat.PCM) 44 else 0
        require(bytes.size().toLong() + chunk.data.size + headerBytes <= limit) { "语音条过大" }
        bytes.write(chunk.data)
        completed = chunk.isLast
    }

    fun finish(): OrbisVoiceAudio {
        require(!rejected) { "语音包含无效分片，未发送语音条" }
        require(completed) { "语音服务未确认生成完成，未发送语音条" }
        require(bytes.size() > 0) { "语音服务没有返回音频" }
        val finalFormat = requireNotNull(format)
        val raw = bytes.toByteArray()
        return when (finalFormat) {
            AudioFormat.PCM -> OrbisVoiceAudio(pcm16MonoWav(raw,
                requireNotNull(sampleRate) { "语音服务未返回 PCM 采样率" }), "wav")
            AudioFormat.WAV -> {
                // Joining whole WAV segments would silently play only the first segment.
                // Accept one complete RIFF container, including one fragmented across chunks.
                require(raw.size >= 44 && raw.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
                    raw.copyOfRange(8, 12).contentEquals("WAVE".toByteArray(Charsets.US_ASCII))) { "语音服务返回了无效 WAV" }
                val declared = ByteBuffer.wrap(raw, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                require(declared + 8 == raw.size.toLong()) { "WAV 不完整或包含多段独立音频，未发送语音条" }
                OrbisVoiceAudio(raw, "wav")
            }
            else -> OrbisVoiceAudio(raw, finalFormat.name.lowercase(java.util.Locale.ROOT))
        }
    }
}
