package me.rerere.rikkahub.data.orbis.voice

import de.sciss.jump3r.mp3.*
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.Closeable
import java.io.OutputStream

/** Pure JVM LAME encoder: no desktop Java Sound APIs or network synthesis. */
internal class VoiceNoteMp3Encoder(rate: Int, private val channels: Int, private val output: OutputStream) : Closeable {
    private val lame = Lame()
    private val flags: LameGlobalFlags
    private val encoded = ByteArray(32768)
    private var finished = false
    init {
        require(rate in 8000..96000 && channels in 1..2) { "暂不支持这条语音的采样率或声道数" }
        val analysis = GainAnalysis(); val bits = BitStream(); val presets = Presets(); val quantize = Quantize()
        val quantizePvt = QuantizePVT(); val tags = VBRTag(); val version = Version()
        val id3 = ID3Tag(); val reservoir = Reservoir(); val takehiro = Takehiro()
        val decoder = MPGLib(); val decoderInterface = Interface(); val common = Common()
        lame.setModules(analysis, bits, presets, quantizePvt, quantize, tags, version, id3, decoder)
        bits.setModules(analysis, decoder, version, tags)
        id3.setModules(bits, version); presets.setModules(lame)
        quantize.setModules(bits, reservoir, quantizePvt, takehiro)
        quantizePvt.setModules(takehiro, reservoir, lame.enc.psy)
        reservoir.setModules(bits); takehiro.setModules(quantizePvt); tags.setModules(lame, bits, version)
        decoder.setModules(decoderInterface, common); decoderInterface.setModules(tags, common)
        flags = lame.lame_init()
        flags.num_channels = channels; flags.in_samplerate = rate
        flags.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.STEREO
        flags.brate = if (channels == 1) 96 else 128; flags.quality = 3
        flags.bWriteVbrTag = false; flags.write_id3tag_automatic = false
        check(lame.lame_init_params(flags) == 0) { "MP3 编码器初始化失败" }
    }
    fun write(samples: ShortArray, count: Int = samples.size) {
        check(!finished); require(count % channels == 0)
        var position = 0
        while (position < count) {
            val frames = minOf(1152, (count-position)/channels)
            val left = IntArray(frames); val right = IntArray(frames)
            for (i in 0 until frames) {
                left[i] = samples[position++].toInt() shl 16
                right[i] = if (channels == 2) samples[position++].toInt() shl 16 else left[i]
            }
            val length = lame.lame_encode_buffer_int(flags, left, right, frames, encoded, 0, encoded.size)
            check(length >= 0) { "MP3 编码失败" }; output.write(encoded, 0, length)
        }
    }
    fun finish() {
        if (finished) return
        val length = lame.lame_encode_flush(flags, encoded, 0, encoded.size)
        check(length >= 0) { "MP3 收尾失败" }; output.write(encoded, 0, length); output.flush(); finished = true
    }
    override fun close() { lame.lame_close(flags) }
}
