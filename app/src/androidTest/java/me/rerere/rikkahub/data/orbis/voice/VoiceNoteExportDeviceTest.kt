package me.rerere.rikkahub.data.orbis.voice

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class VoiceNoteExportDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().also { check(it is IsolatedGenerationLoopRunner) }.targetContext
    private fun pcm(rate: Int, channels: Int): ByteArray = ByteBuffer.allocate(rate*2*channels*2).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(rate*2) { index -> repeat(channels) { channel -> putShort((sin(2*Math.PI*(440+channel*220)*index/rate)*12000).toInt().toShort()) } }
    }.array()
    private fun wave(rate: Int, channels: Int): File {
        val bytes=pcm(rate,channels)
        val head=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(bytes.size+36);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(channels.toShort());putInt(rate);putInt(rate*channels*2);putShort((channels*2).toShort());putShort(16);put("data".toByteArray());putInt(bytes.size)
        }.array()
        return File.createTempFile("export-fixture-", ".wav",File(context.filesDir,"upload").apply { mkdirs() }).apply { outputStream().use { it.write(head);it.write(bytes) } }
    }
    private fun hash(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun playable(file: File,channels:Int) {
        val extractor=MediaExtractor()
        try { extractor.setDataSource(file.path);val format=extractor.getTrackFormat(0);assertEquals("audio/mpeg",format.getString(MediaFormat.KEY_MIME));assertEquals(channels,format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));extractor.selectTrack(0);assertTrue(extractor.readSampleData(ByteBuffer.allocate(32768),0)>0) } finally { extractor.release() }
        MediaMetadataRetriever().use { it.setDataSource(file.path);val duration=it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong();assertTrue("duration=$duration",duration in 1800..2400) }
        // Decode the produced MP3 independently; a valid container alone could still be silent or clipped.
        val input = MediaExtractor(); var decoder: MediaCodec? = null
        var count = 0L; var square = 0.0; var peak = 0
        try {
            input.setDataSource(file.path); input.selectTrack(0)
            val codec = MediaCodec.createDecoderByType("audio/mpeg").also { decoder = it }
            codec.configure(input.getTrackFormat(0), null, null, 0); codec.start()
            val info = MediaCodec.BufferInfo(); var inDone = false; var outDone = false
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (!outDone && android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!inDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!; buffer.clear()
                        val n = input.readSampleData(buffer, 0)
                        codec.queueInputBuffer(index, 0, maxOf(n, 0), if (n < 0) 0 else input.sampleTime,
                            if (n < 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (n < 0) inDone = true else input.advance()
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    while (buffer.remaining() >= 2) { val sample = buffer.short.toInt(); count++; square += sample.toDouble() * sample; peak = maxOf(peak, kotlin.math.abs(sample)) }
                    outDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
            assertTrue("MP3 decoder did not finish", outDone)
            assertTrue("Missing decoded audio", count > 30000 * channels)
            val rms = kotlin.math.sqrt(square / count)
            assertTrue("Audio level changed: rms=$rms peak=$peak", rms in 6000.0..10000.0 && peak in 9000..16000)
        } finally { decoder?.let { runCatching { it.stop() }; it.release() }; input.release() }

    }
    private fun exportWave(rate:Int,channels:Int)=runBlocking {
        val source=wave(rate,channels);val before=hash(source)
        try {
            val result=VoiceNoteExport.prepare(context,source.toUri().toString())
            try {
                assertTrue(result.temporary);assertTrue(result.file.name.endsWith(".mp3"));playable(result.file,channels)
                val destination=File.createTempFile("export-destination-", ".mp3",context.cacheDir)
                try { VoiceNoteExport.save(context,result,destination.toUri());assertEquals(hash(result.file),hash(destination));playable(destination,channels) } finally { destination.delete() }
            } finally { VoiceNoteExport.discard(context,result);assertFalse(result.file.exists()) }
            assertEquals(before,hash(source))
        } finally { source.delete() }
    }
    @Test fun monoWavBecomesPlayableMp3WithoutChangingOriginal()=exportWave(22050,1)
    @Test fun stereoWavRetainsChannelsAndDuration()=exportWave(44100,2)
    @Test fun existingMp3IsCopiedByteForByteAndNeverDeleted()=runBlocking {
        val source=wave(24000,1);var mp3:File?=null
        try {
            val converted=VoiceNoteExport.prepare(context,source.toUri().toString())
            val original=File.createTempFile("original-mp3-", ".mp3",source.parentFile).also { mp3=it;converted.file.copyTo(it,overwrite=true) }
            VoiceNoteExport.discard(context,converted)
            val before=hash(original);val result=VoiceNoteExport.prepare(context,original.toUri().toString())
            assertFalse(result.temporary);assertEquals(original.canonicalFile,result.file);VoiceNoteExport.discard(context,result)
            assertEquals(before,hash(original));playable(original,1)
        } finally { source.delete();mp3?.delete() }
    }
    @Test fun compressedAacIsDecodedThenEncodedAsRealMp3()=runBlocking {
        val source=wave(22050,1);val aac=File.createTempFile("compressed-fixture-", ".m4a",source.parentFile)
        val encoder=MediaCodec.createEncoderByType("audio/mp4a-latm");val muxer=MediaMuxer(aac.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started=false
        try {
            val format=MediaFormat.createAudioFormat("audio/mp4a-latm",22050,1).apply { setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);setInteger(MediaFormat.KEY_BIT_RATE,64000) }
            encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start()
            val data=pcm(22050,1);var offset=0;var inputEnded=false;var outputEnded=false;var track=-1;val info=MediaCodec.BufferInfo()
            while(!outputEnded) {
                if(!inputEnded) { val i=encoder.dequeueInputBuffer(10_000);if(i>=0) {
                    val buffer=encoder.getInputBuffer(i)!!;buffer.clear();val n=minOf(buffer.remaining(),data.size-offset)
                    buffer.put(data,offset,n);encoder.queueInputBuffer(i,0,n,offset/2*1_000_000L/22050,if(n==0)MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0);offset+=n;if(n==0)inputEnded=true
                } }
                val i=encoder.dequeueOutputBuffer(info,10_000)
                if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){track=muxer.addTrack(encoder.outputFormat);muxer.start();started=true}
                if(i>=0){if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0)muxer.writeSampleData(track,encoder.getOutputBuffer(i)!!,info);outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;encoder.releaseOutputBuffer(i,false)}
            }
        } finally { runCatching { encoder.stop() };encoder.release();if(started)muxer.stop();muxer.release() }
        try { val before=hash(aac);val result=VoiceNoteExport.prepare(context,aac.toUri().toString());try { playable(result.file,1);assertEquals(before,hash(aac)) } finally { VoiceNoteExport.discard(context,result) } }
        finally { source.delete();aac.delete() }
    }
    @Test fun invalidOrRemoteAudioDoesNotFetchOrCreateAnExport()=runBlocking {
        for(url in listOf("https://invalid.example/voice.mp3","file:///etc/hosts","file:///missing/voice.wav")) {
            try { VoiceNoteExport.prepare(context,url);fail("accepted $url") } catch(expected:IllegalArgumentException) { }
        }
    }
}
