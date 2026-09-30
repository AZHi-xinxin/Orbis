package me.rerere.rikkahub.data.orbis

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** A new private namespace: never enumerate old chat uploads, emoji folders, settings or databases. */
private class AndroidStickerStorage(context: Context) : OrbisStickerStorage {
    private val files = context.filesDir.canonicalFile
    private val root = File(files, "orbis-stickers")
    private val images = File(root, "images")
    private val manifest = AtomicFile(File(root, "manifest-v1.json"))

    private fun checkDirectory(create: Boolean = false) {
        stickerCheck(root.canonicalFile == File(files, "orbis-stickers") && images.canonicalFile == File(root, "images"),
            "sticker_storage_invalid")
        if (create) stickerCheck(images.isDirectory || images.mkdirs(), "sticker_storage_unavailable")
        stickerCheck(!root.exists() || root.isDirectory, "sticker_storage_invalid")
        stickerCheck(!images.exists() || images.isDirectory, "sticker_storage_invalid")
    }
    private fun imagePath(id: String, suffix: String): File {
        stickerCheck(OrbisStickerRepository.validId(id), "sticker_invalid_id")
        checkDirectory()
        val file = File(images, id + suffix)
        stickerCheck(file.canonicalFile == file.absoluteFile, "sticker_storage_invalid")
        return file
    }
    override fun readManifest(): String? {
        checkDirectory()
        for (suffix in listOf("", ".bak", ".new")) {
            val file = File(manifest.baseFile.path + suffix)
            stickerCheck(file.canonicalFile == file.absoluteFile, "sticker_storage_invalid")
        }
        if (!manifest.baseFile.exists() && !File(manifest.baseFile.path + ".bak").exists()) return null
        return manifest.openRead().use { readBounded(it, OrbisStickerLimits.MAX_MANIFEST_CHARS).toString(Charsets.UTF_8) }
    }
    override fun writeManifest(text: String) {
        checkDirectory(create = true)
        // Also rejects a substituted manifest/sidecar before opening the AtomicFile for write.
        for (suffix in listOf("", ".bak", ".new")) {
            val file = File(manifest.baseFile.path + suffix)
            stickerCheck(file.canonicalFile == file.absoluteFile, "sticker_storage_invalid")
        }
        val stream = manifest.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            manifest.finishWrite(stream)
        } catch (error: Throwable) {
            try { manifest.failWrite(stream) } catch (_: Throwable) { /* Repository requires reload. */ }
            throw error
        }
        stickerCheck(readManifest() == text, "sticker_storage_reload_required")
    }
    override fun writeImageExclusive(id: String, bytes: ByteArray) {
        checkDirectory(create = true)
        val file = imagePath(id, ".bin")
        // An unfinished exclusive object remains as an unlisted orphan, never reused or auto-deleted.
        stickerCheck(file.createNewFile(), "sticker_storage_invalid")
        FileOutputStream(file, false).use { stream -> stream.write(bytes); stream.fd.sync() }
        stickerCheck(readImage(id).contentEquals(bytes), "sticker_storage_reload_required")
    }
    override fun readImage(id: String): ByteArray {
        val file = imagePath(id, ".bin")
        stickerCheck(file.isFile, "sticker_image_missing")
        return file.inputStream().use { readBounded(it, OrbisStickerLimits.MAX_IMAGE_BYTES) }
    }
    private fun imageFiles(): List<File> {
        checkDirectory()
        if (!images.exists()) return emptyList()
        val entries = images.listFiles() ?: throw OrbisStickerException("sticker_storage_unavailable")
        stickerCheck(entries.size <= OrbisStickerLimits.MAX_NUMBER, "sticker_storage_invalid")
        return entries.map { file ->
            stickerCheck(file.name.matches(Regex("st[0-9]{6}\\.bin")) && file.isFile &&
                file.canonicalFile == file.absoluteFile && OrbisStickerRepository.validId(file.name.removeSuffix(".bin")),
                "sticker_storage_invalid")
            file
        }
    }
    override fun largestImageNumber(): Int = imageFiles().maxOfOrNull { it.name.substring(2, 8).toInt() } ?: 0
    override fun totalImageBytes(): Long = imageFiles().sumOf { it.length() }
    fun fileForId(id: String): File? = imagePath(id, ".bin").takeIf { it.isFile }
}

private fun readBounded(stream: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        stickerCheck(output.size().toLong() + count <= limit, "sticker_image_size")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** Android URI import is user initiated. The model receives only repository metadata, never this API. */
class AndroidStickerRepository internal constructor(context: Context) {
    private val application = context.applicationContext
    private val storage = AndroidStickerStorage(application)
    private val core = OrbisStickerRepository(storage)
    val state = core.state
    val writeBlocked = core.writeBlocked

    suspend fun importImage(uri: Uri, tags: List<String>): OrbisSticker = io {
        stickerCheck(uri.scheme == "content", "sticker_import_invalid")
        OrbisStickerRepository.normalizeTags(tags)
        val bytes = application.contentResolver.openInputStream(uri)?.use {
            readBounded(it, OrbisStickerLimits.MAX_IMAGE_BYTES)
        } ?: throw OrbisStickerException("sticker_import_unavailable")
        val info = OrbisStickerImages.inspect(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val expectedMime = when (info.format) {
            "png" -> "image/png"; "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; else -> "image/webp"
        }
        stickerCheck(bounds.outWidth == info.width && bounds.outHeight == info.height && bounds.outMimeType == expectedMime,
            "sticker_image_invalid")
        // Decode a small sampled first frame, not a full-resolution bitmap. This rejects headers
        // with missing/invalid codec data without allocating a potentially 4096x4096 bitmap.
        var sample = 1
        while (maxOf(info.width, info.height) / sample > 256) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw OrbisStickerException("sticker_image_invalid")
        bitmap.recycle()
        core.importImage(bytes, tags)
    }
    suspend fun updateTags(id: String, tags: List<String>) = io { core.updateTags(id, tags) }
    suspend fun reloadFromStorage() = io { core.reloadFromStorage() }
    suspend fun readSnapshot(): OrbisStickerState = io { core.readSnapshot() }
    suspend fun imageForSend(id: String): OrbisStickerImage = io { core.imageForSend(id) }

    /** UI only. Whitelisted ID -> own immutable file; no caller supplied filename/URL is accepted. */
    fun fileForId(id: String): File? = try {
        if (!OrbisStickerRepository.validId(id) || core.readSnapshot().stickers.none { it.id == id }) null
        else storage.fileForId(id)
    } catch (_: Exception) { null }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: OrbisStickerException) { throw error }
        catch (_: Exception) { throw OrbisStickerException("sticker_storage_unavailable") }
    }
}

/** Same device/app shared library, not a cloud or cross-device singleton. Open on Dispatchers.IO. */
object OrbisStickers {
    @Volatile private var repository: AndroidStickerRepository? = null
    @Synchronized fun open(context: Context): AndroidStickerRepository = try {
        repository ?: AndroidStickerRepository(context.applicationContext).also { repository = it }
    } catch (error: OrbisStickerException) { throw error }
    catch (_: Exception) { throw OrbisStickerException("sticker_storage_unavailable") }
}
