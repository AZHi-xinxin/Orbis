package me.rerere.tts.controller

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Each media segment uses its utterance's frozen effects, including after a seek/flush. */
@OptIn(UnstableApi::class)
internal class VoiceMixAudioProcessor(private val snapshot: () -> VoiceMixerConfig) : BaseAudioProcessor() {
    private var dsp: VoiceMixDsp? = null
    private var failed = false
    private var config = VoiceMixerConfig()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.sampleRate !in 8_000..192_000 ||
            inputAudioFormat.channelCount !in 1..8
        ) return AudioProcessor.AudioFormat.NOT_SET // Optional effects must not reject playable audio.
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate, inputAudioFormat.channelCount.coerceAtLeast(2), C.ENCODING_PCM_16BIT,
        )
    }

    override fun onFlush() {
        config = snapshot()
        dsp = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.sampleRate in 8_000..192_000 && inputAudioFormat.channelCount in 1..8
        ) VoiceMixDsp(inputAudioFormat.sampleRate, inputAudioFormat.channelCount) else null
        failed = false
    }

    override fun onReset() {
        dsp = null
        failed = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val processor = dsp
        if (processor == null) {
            replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
            return
        }
        val start = inputBuffer.position()
        val frameBytes = processor.inputChannels * 2
        val frames = inputBuffer.remaining() / frameBytes
        val output = replaceOutputBuffer(frames * processor.outputChannels * 2).order(ByteOrder.LITTLE_ENDIAN)
        if (!failed) {
            try {
                processor.process(inputBuffer, output, config)
            } catch (_: RuntimeException) {
                // Recover the same input block in place. No retry, extra synthesis or media restart.
                failed = true
                inputBuffer.position(start)
                output.clear()
            }
        }
        if (failed) {
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) {
                if (processor.inputChannels == 1) {
                    val value = inputBuffer.short
                    output.putShort(value).putShort(value)
                } else repeat(processor.inputChannels) { output.putShort(inputBuffer.short) }
            }
        }
        output.flip()
    }
}
