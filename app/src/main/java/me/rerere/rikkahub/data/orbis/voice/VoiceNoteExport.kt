package me.rerere.rikkahub.data.orbis.voice

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class ExportedVoice(val file: File, val temporary: Boolean)

internal object VoiceNoteExport {
    private fun original(context: Context, url: String): File {
        val uri = url.toUri()
        require(uri.scheme == "file" && uri.authority.isNullOrEmpty()) { "这条语音尚未保存在本机" }
        val file = File(uri.path ?: "").canonicalFile
        val root = File(context.filesDir, "upload").canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file.isFile && file.length() > 0) { "找不到这条语音的本机文件" }
        return file
    }
    suspend fun prepare(context: Context, url: String): ExportedVoice = withContext(Dispatchers.IO) {
        val file = original(context, url)
        val extractor = MediaExtractor()
        var output: File? = null
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("这条文件没有可导出的音频")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (format.getString(MediaFormat.KEY_MIME) == "audio/mpeg") return@withContext ExportedVoice(file, false)
            val folder = File(context.cacheDir, "voice-export").apply { mkdirs() }
            // Only our expired conversion cache, never the original voice attachments.
            folder.listFiles().orEmpty().filter { System.currentTimeMillis()-it.lastModified()>86_400_000L }.forEach { it.delete() }
            val converted = File.createTempFile("voice-", ".mp3", folder).also { output = it }
            converted.outputStream().use { encode(extractor, format, it) }
            currentCoroutineContext().ensureActive()
            check(converted.length() > 0) { "语音转换未完成" }
            ExportedVoice(converted, true)
        } catch (error: Throwable) { output?.delete(); throw error }
        finally { extractor.release() }
    }
    private suspend fun encode(extractor: MediaExtractor, format: MediaFormat, out: OutputStream) {
        var encoder: VoiceNoteMp3Encoder? = null
        var decoder: MediaCodec? = null
        var rate = 0; var channels = 0; var encoding = AudioFormat.ENCODING_PCM_16BIT
        fun configure(f: MediaFormat) {
            val nextRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); val nextChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            check(encoder == null || (rate == nextRate && channels == nextChannels)) { "语音中途改变了采样格式，无法完整导出" }
            rate = nextRate; channels = nextChannels
            encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            if (encoder == null) encoder = VoiceNoteMp3Encoder(rate, channels, out)
        }
        fun feed(buffer: ByteBuffer) {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val sampleBytes = when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> 2
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                AudioFormat.ENCODING_PCM_8BIT -> 1
                else -> error("暂不支持这条语音的 PCM 格式")
            }
            require(buffer.remaining() % (channels * sampleBytes) == 0) { "语音采样不完整" }
            val samples = ShortArray(buffer.remaining()/sampleBytes) {
                when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> (buffer.float.coerceIn(-1f,1f)*32767).toInt().toShort()
                    AudioFormat.ENCODING_PCM_8BIT -> (((buffer.get().toInt() and 255)-128) shl 8).toShort()
                    else -> buffer.short
                }
            }
            encoder!!.write(samples)
        }
        try {
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            if (mime == "audio/raw") {
                configure(format);val buffer = ByteBuffer.allocate(1024*1024)
                while (true) {
                    currentCoroutineContext().ensureActive();buffer.clear()
                    val size = extractor.readSampleData(buffer, 0);if (size < 0) break
                    buffer.position(0);buffer.limit(size);feed(buffer);extractor.advance()
                }
            } else {
                val codec = MediaCodec.createDecoderByType(mime).also { decoder = it }
                codec.configure(format, null, null, 0);codec.start()
                val info = MediaCodec.BufferInfo();var inputEnded = false;var outputEnded = false
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    if (!inputEnded) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!;buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) { codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded = true }
                            else { codec.queueInputBuffer(index,0,size,extractor.sampleTime,0);extractor.advance() }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info,10_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) configure(codec.outputFormat)
                    if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                if (encoder == null) configure(codec.outputFormat)
                                val buffer = codec.getOutputBuffer(index)!!;buffer.position(info.offset);buffer.limit(info.offset+info.size);feed(buffer)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index,false) }
                    }
                }
            }
            checkNotNull(encoder) { "音频中没有有效采样" }.finish()
        } finally { encoder?.close();decoder?.let { runCatching { it.stop() };it.release() } }
    }
    suspend fun save(context: Context, prepared: ExportedVoice, destination: Uri) = withContext(Dispatchers.IO) {
        val file = prepared.file.canonicalFile
        val roots = listOf(File(context.filesDir,"upload").canonicalFile, File(context.cacheDir,"voice-export").canonicalFile)
        require(roots.any { file.toPath().startsWith(it.toPath()) } && file.isFile) { "导出文件已失效，请重新长按语音条" }
        context.contentResolver.openOutputStream(destination,"wt")?.use { output ->
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n) }
            }
        } ?: error("无法写入所选位置")
    }
    fun discard(context: Context, prepared: ExportedVoice) {
        if (prepared.temporary && prepared.file.canonicalFile.toPath().startsWith(File(context.cacheDir,"voice-export").canonicalFile.toPath())) prepared.file.delete()
    }
}
