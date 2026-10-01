package me.rerere.rikkahub.data.orbis.voice

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.model.ORBIS_VOICE_NOTE_MAX_TEXT_CHARS
import me.rerere.rikkahub.data.model.orbisVoiceNoteMetadata
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting

class OrbisVoiceNotes(private val context: Context, private val files: FilesManager) {
    suspend fun save(bytes: ByteArray, extension: String, transcript: String, original: String = transcript,
        durationMs: Long = 0): UIMessagePart.Audio {
        var owned: ManagedFileEntity? = null
        try { return withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty() && bytes.size <= ORBIS_VOICE_NOTE_MAX_BYTES) { "语音文件为空或过大" }
        require(extension in setOf("wav", "mp3", "ogg", "aac", "opus"))
        val mime = when (extension) { "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "ogg", "opus" -> "audio/ogg"; else -> "audio/aac" }
        val saved = files.saveManagedFromBytes(FileFolders.UPLOAD, bytes, "voice-note.$extension", mime).also { owned = it }
        val file = files.getFile(saved)
        val measured = if (durationMs > 0) durationMs else runCatching {
            MediaMetadataRetriever().use { it.setDataSource(file.absolutePath)
                it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
        }.getOrDefault(0L)
        currentCoroutineContext().ensureActive()
        UIMessagePart.Audio(file.toUri().toString(), orbisVoiceNoteMetadata(transcript, original, measured))
        } } catch (error: Throwable) {
            // Also catch cancellation during the dispatcher return, before the caller owns the URL.
            owned?.let { saved ->
                try { withContext(NonCancellable + Dispatchers.IO) { files.delete(saved.id) } }
                catch (cleanupError: Throwable) { error.addSuppressed(cleanupError) }
            }
            throw error
        }
    }

    suspend fun synthesize(provider: TTSProviderSetting, text: String): UIMessagePart.Audio {
        require(text.isNotBlank() && text.length <= ORBIS_VOICE_NOTE_MAX_TEXT_CHARS) { "语音条正文需为 1–$ORBIS_VOICE_NOTE_MAX_TEXT_CHARS 字符" }
        val audio = withTimeout(180_000) {
            withContext(Dispatchers.IO) {
                collectOrbisVoiceAudio(TTSManager(context).generateSpeech(provider, TTSRequest(text)))
            }
        }
        return save(audio.bytes, audio.extension, text)
    }
}
