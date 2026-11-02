package me.rerere.rikkahub.data.orbis.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal data class MusicImportFile(val uri: Uri, val name: String, val bytes: Long = 0, val title: String = "", val artist: String = "", val durationMs: Long = 0)
internal data class MusicImportResult(val added: Int, val duplicate: Int, val failed: List<String>, val ids: List<String>)
internal class MusicImporter(private val context: Context, private val store: MusicRepository) {
    suspend fun files(uris: List<Uri>): List<MusicImportFile> = withContext(Dispatchers.IO) {
        uris.map { uri ->
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if(it.moveToFirst()) it.getString(0) else null
            } ?: "音乐文件"
            inspect(MusicImportFile(uri, name))
        }
    }
    suspend fun folder(tree: Uri): List<MusicImportFile> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val result = mutableListOf<MusicImportFile>()
        val visited = mutableSetOf<String>()
        fun walk(id: String, depth: Int) {
            job.ensureActive()
            check(depth <= 16 && visited.size < 2000 && result.size < 2000) { "music_folder_too_large" }
            if(!visited.add(id)) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while(c.moveToNext()) {
                    job.ensureActive()
                    val child = c.getString(0); val name = c.getString(1); val mime = c.getString(2)
                    if(mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(child, depth + 1)
                    else if(mime.startsWith("audio/") || name.substringAfterLast('.').lowercase() in extensions + setOf("lrc", "jpg", "png", "webp")) {
                        check(result.size < 2000) { "music_folder_too_large" }
                        result += MusicImportFile(DocumentsContract.buildDocumentUriUsingTree(tree, child), name)
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        result.map { job.ensureActive(); inspect(it) }
    }
    suspend fun import(files: List<MusicImportFile>, progress: (Int, Int) -> Unit): MusicImportResult = withContext(Dispatchers.IO) {
        var added = 0; var duplicates = 0; val failed = mutableListOf<String>(); val ids = mutableListOf<String>()
        val lyrics = files.filter { it.name.endsWith(".lrc", true) }.groupBy { it.name.substringBeforeLast('.').lowercase() }
        val artFiles = files.filter { it.name.substringAfterLast('.').lowercase() in setOf("jpg", "png", "webp") }
        val audio = files.filterNot { it.name.endsWith(".lrc", true) || it in artFiles }
        audio.forEachIndexed { index, source ->
            currentCoroutineContext().ensureActive()
            val temp = File(context.cacheDir, "music-${UUID.randomUUID()}.part")
            try {
                val hash = MessageDigest.getInstance("SHA-256")
                var total = 0L
                context.contentResolver.openInputStream(source.uri)!!.use { input -> temp.outputStream().use { out ->
                    val buffer = ByteArray(65536)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if(n < 0) break
                        total += n; check(total <= 512L * 1024 * 1024) { "单曲超过 512 MB" }
                        hash.update(buffer, 0, n); out.write(buffer, 0, n)
                    }
                } }
                check(total > 0) { "文件为空" }
                val id = hash.digest().joinToString("") { "%02x".format(it) }
                if(store.state.value.tracks.any { it.id == id }) { duplicates++; ids += id }
                else {
                    val retriever = MediaMetadataRetriever()
                    val track = try {
                        retriever.setDataSource(temp.path)
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                        check(duration > 0) { "无法识别音频时长" }
                        val externalArt = artFiles.filter { it.name.substringBeforeLast('.').equals(source.name.substringBeforeLast('.'), true) }.singleOrNull()
                        val art = retriever.embeddedPicture?.takeIf { it.size <= 4 * 1024 * 1024 } ?: externalArt?.let { runCatching { imageBytes(it.uri) }.getOrNull() }
                        val extension = source.name.substringAfterLast('.', "audio").lowercase().takeIf { it in extensions } ?: "audio"
                        val lyricFile = lyrics[source.name.substringBeforeLast('.').lowercase()]?.singleOrNull()
                        val lyric = lyricFile?.let { readLyrics(it.uri) } ?: ""
                        MusicTrack(id, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.take(300)?.ifBlank { null }
                            ?: source.name.substringBeforeLast('.').take(300),
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.take(300) ?: "未知歌手",
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.take(300) ?: "", duration, extension, art != null, lyric).also {
                            if(art != null) { val dest = store.cover(it); dest.parentFile!!.mkdirs(); dest.writeBytes(art) }
                        }
                    } finally { retriever.release() }
                    val dest = store.audio(track); dest.parentFile!!.mkdirs()
                    check(temp.renameTo(dest)) { "无法保存音频" }
                    try { if(store.add(track)) added++ else duplicates++; ids += id }
                    catch(e: Exception) { dest.delete(); store.cover(track).delete(); throw e }
                }
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { failed += "${source.name.take(120)}：${e.message?.take(100) ?: "导入失败"}" }
            finally { temp.delete() }
            progress(index + 1, audio.size)
        }
        MusicImportResult(added, duplicates, failed, ids)
    }
    private fun inspect(source: MusicImportFile): MusicImportFile {
        val size = runCatching { context.contentResolver.openAssetFileDescriptor(source.uri, "r")?.use { it.length.coerceAtLeast(0) } ?: 0 }.getOrDefault(0)
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, source.uri)
            source.copy(bytes = size, title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0)
        } catch(_: Exception) { source.copy(bytes = size) } finally { r.release() }
    }
    suspend fun playlistCover(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = imageBytes(uri); val file = store.playlistCoverFile(id); file.parentFile!!.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try { tmp.writeBytes(bytes); java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); store.playlistCover(id) }
        finally { tmp.delete() }
    }
    private fun imageBytes(uri: Uri): ByteArray {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
            val out = java.io.ByteArrayOutputStream(); val b = ByteArray(8192)
            while(true) { val n = input.read(b); if(n < 0) break; check(out.size() + n <= 4 * 1024 * 1024) { "封面超过 4 MB" }; out.write(b, 0, n) }; out.toByteArray()
        }
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..8192 && options.outHeight in 1..8192) { "封面图片无法读取或尺寸过大" }
        return bytes
    }
    suspend fun lyrics(uri: Uri) = withContext(Dispatchers.IO) { readLyrics(uri) }
    private fun readLyrics(uri: Uri): String = context.contentResolver.openInputStream(uri)!!.use {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while(true) { val n = it.read(buffer); if(n < 0) break; check(out.size() + n <= 800000) { "歌词文件过大" }; out.write(buffer, 0, n) }
        val bytes = out.toByteArray(); check(bytes.size <= 800000) { "歌词文件过大" }
        bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").also { text -> check(text.length <= 200000) { "歌词文件过大" } }
    }
    companion object { private val extensions = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "amr", "mp4") }
}
