package me.rerere.rikkahub.data.files

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.exportImageFile
import me.rerere.rikkahub.utils.getActivity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class FilesManager(
    private val context: Context,
    private val repository: FilesRepository,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore? = null,
) {
    companion object {
        private const val TAG = "FilesManager"
    }

    private val protection = FileProtection(File(context.filesDir.canonicalFile, FileProtection.PATH))
    private val protectionGuard = Any()
    private val protectionChanges = MutableStateFlow(0L)
    val protectionRevision = protectionChanges.asStateFlow()
    init {
        settingsStore?.let { store -> appScope.launch(Dispatchers.IO) {
            store.settingsFlow.collect { settings ->
                if (!settings.init) synchronized(protectionGuard) {
                    runCatching { protection.rememberAutomatic(protectedAppearancePaths(context.filesDir, settings)) }
                    protectionChanges.value++
                }
            }
        } }
    }
    private fun currentProtectedPaths(): Set<String> {
        val settings = settingsStore?.settingsFlow?.value ?: return emptySet()
        check(!settings.init) { "file_protection_settings_not_ready" }
        return protectedAppearancePaths(context.filesDir, settings)
    }
    suspend fun lockedPaths(): Set<String> = withContext(Dispatchers.IO) {
        synchronized(protectionGuard) {
            val state = protection.rememberAutomatic(currentProtectedPaths())
            (state.automatic + state.overrides.keys).filterTo(mutableSetOf()) { state.isLocked(it) }
        }
    }
    suspend fun setLocked(entity: ManagedFileEntity, locked: Boolean) = withContext(Dispatchers.IO) {
        synchronized(protectionGuard) {
            check(entity.folder == FileFolders.UPLOAD && FileProtection.validPath(entity.relativePath))
            protection.rememberAutomatic(currentProtectedPaths())
            protection.setLocked(entity.relativePath, locked)
            protectionChanges.value++
        }
    }
    private fun isLocked(path: String): Boolean = protection.rememberAutomatic(currentProtectedPaths()).isLocked(path)
    private fun deleteOwnedFile(path: String): Boolean = synchronized(protectionGuard) {
        if (!FileProtection.validPath(path)) return@synchronized false
        val root = context.filesDir.canonicalFile
        val file = File(root, path)
        protection.withAutomaticProtection(currentProtectedPaths()) { state ->
            if (file.absoluteFile != file.canonicalFile || !file.canonicalPath.startsWith(root.path + File.separator) ||
                file == root || file.isDirectory || state.isLocked(path)) false
            else !file.exists() || file.delete()
        }
    }

    suspend fun saveManagedFromUri(
        folder: String,
        uri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
    ): ManagedFileEntity {
        val (resolvedName, resolvedMime) = withContext(Dispatchers.IO) {
            (displayName ?: getFileNameFromUri(uri) ?: "file") to
                (mimeType ?: getFileMimeType(uri) ?: "application/octet-stream")
        }
        return saveNewManagedFile(folder, resolvedName, resolvedMime) { target ->
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Unable to open the selected file")
            input.use {
                target.outputStream().use { output -> it.copyTo(output) }
            }
        }
    }

    suspend fun saveManagedFromBytes(
        folder: String,
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/octet-stream",
    ): ManagedFileEntity = saveNewManagedFile(folder, displayName, mimeType) { it.writeBytes(bytes) }

    private suspend fun saveNewManagedFile(
        folder: String,
        displayName: String,
        mimeType: String,
        write: suspend (File) -> Unit,
    ): ManagedFileEntity {
        val root = context.filesDir.canonicalFile
        val target = File(File(root, folder), FileUtils.buildUuidFileName(displayName, mimeType))
        return persistOwnedManagedFile(root, target, write,
            persist = { createManagedFileEntity(folder, target, displayName, mimeType) },
            removeRecord = { repository.deleteByPath(buildRelativePath(folder, target)) })
    }

    suspend fun saveManagedText(
        folder: String,
        text: String,
        displayName: String = "pasted_text.txt",
        mimeType: String = "text/plain",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        target.writeText(text)
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        )
    }

    fun observe(folder: String = FileFolders.UPLOAD): Flow<List<ManagedFileEntity>> =
        repository.listByFolder(folder)

    suspend fun list(folder: String = FileFolders.UPLOAD): List<ManagedFileEntity> =
        repository.listByFolder(folder).first()

    suspend fun get(id: Long): ManagedFileEntity? = repository.getById(id)

    suspend fun getByRelativePath(relativePath: String): ManagedFileEntity? = repository.getByPath(relativePath)

    fun getFile(entity: ManagedFileEntity): File =
        File(context.filesDir, entity.relativePath)

    fun createChatFilesByContents(uris: List<Uri>): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        uris.forEach { uri ->
            runCatching {
                val sourceName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
                val sourceMime = getFileMimeType(uri)
                val fileName = buildUuidFileName(displayName = sourceName, mimeType = sourceMime)
                val file = dir.resolve(fileName)
                if (!file.exists()) {
                    file.createNewFile()
                }
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Failed to open input stream for $uri")
                inputStream.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val guessedMime = sourceMime ?: guessMimeType(file, sourceName)
                trackManagedFile(
                    folder = FileFolders.UPLOAD,
                    file = file,
                    displayName = sourceName,
                    mimeType = guessedMime
                )
                newUris.add(file.toUri())
            }.onFailure {
                it.printStackTrace()
                Log.e(TAG, "createChatFilesByContents: Failed to save file from $uri", it)
                Logging.log(
                    TAG,
                    "createChatFilesByContents: Failed to save file from $uri ${it.message} | ${it.stackTraceToString()}"
                )
            }
        }
        return newUris
    }

    fun createChatFilesByByteArrays(byteArrays: List<ByteArray>): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        byteArrays.forEach { byteArray ->
            val fileName = buildUuidFileName(displayName = "image.png", mimeType = "image/png")
            val file = dir.resolve(fileName)
            if (!file.exists()) {
                file.createNewFile()
            }
            val newUri = file.toUri()
            file.outputStream().use { outputStream ->
                outputStream.write(byteArray)
            }
            trackManagedFile(
                folder = FileFolders.UPLOAD,
                file = file,
                displayName = "image.png",
                mimeType = "image/png"
            )
            newUris.add(newUri)
        }
        return newUris
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage =
        withContext(Dispatchers.IO) {
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Image -> {
                            if (part.url.startsWith("data:image")) {
                                val sourceByteArray = Base64.decode(part.url.substringAfter("base64,").toByteArray())
                                val bitmap = BitmapFactory.decodeByteArray(sourceByteArray, 0, sourceByteArray.size)
                                val byteArray = FileUtils.compressBitmapToPng(bitmap)
                                val urls = createChatFilesByByteArrays(listOf(byteArray))
                                Log.i(
                                    TAG,
                                    "convertBase64ImagePartToLocalFile: convert base64 img to ${urls.joinToString(", ")}"
                                )
                                part.copy(
                                    url = urls.first().toString(),
                                )
                            } else {
                                part
                            }
                        }

                        else -> part
                    }
                }
            )
        }

    fun deleteChatFiles(uris: List<Uri>) {
        appScope.launch(Dispatchers.IO) {
            uris.mapNotNull { FileProtection.relativeUpload(context.filesDir, it.toString()) }.distinct().forEach { path ->
                if (runCatching { deleteOwnedFile(path) }.getOrDefault(false)) repository.deleteByPath(path)
            }
        }
    }

    suspend fun countChatFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            return@withContext Pair(0, 0)
        }
        val files = dir.listFiles() ?: return@withContext Pair(0, 0)
        val count = files.size
        val size = files.sumOf { it.length() }
        Pair(count, size)
    }

    fun createChatTextFile(text: String): UIMessagePart.Document {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileName = buildUuidFileName(displayName = "pasted_text.txt", mimeType = "text/plain")
        val file = dir.resolve(fileName)
        file.writeText(text)
        trackManagedFile(
            folder = FileFolders.UPLOAD,
            file = file,
            displayName = "pasted_text.txt",
            mimeType = "text/plain"
        )
        return UIMessagePart.Document(
            url = file.toUri().toString(),
            fileName = "pasted_text.txt",
            mime = "text/plain"
        )
    }

    fun getImagesDir(): File {
        val dir = context.filesDir.resolve("images")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun createImageFileFromBase64(base64Data: String, filePath: String): File {
        val data = if (base64Data.startsWith("data:image")) {
            base64Data.substringAfter("base64,")
        } else {
            base64Data
        }

        val byteArray = Base64.decode(data.toByteArray())
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArray)
        return file
    }

    fun listImageFiles(): List<File> {
        val imagesDir = getImagesDir()
        return imagesDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }
            ?.toList()
            ?: emptyList()
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun saveMessageImage(activityContext: Context, image: String) = withContext(Dispatchers.IO) {
        val activity = requireNotNull(activityContext.getActivity()) { "Activity not found" }
        when {
            image.startsWith("data:image") -> {
                val byteArray = Base64.decode(image.substringAfter("base64,").toByteArray())
                val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                activityContext.exportImage(activity, bitmap)
            }

            image.startsWith("file:") -> {
                val file = image.toUri().toFile()
                activityContext.exportImageFile(activity, file)
            }

            image.startsWith("/") -> {
                activityContext.exportImageFile(activity, File(image))
            }

            image.startsWith("http") -> {
                runCatching {
                    val url = URL(image)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connect()

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                        activityContext.exportImage(activity, bitmap)
                    } else {
                        Log.e(
                            TAG,
                            "saveMessageImage: Failed to download image from $image, response code: ${connection.responseCode}"
                        )
                    }
                }.getOrNull()
            }

            else -> error("Invalid image format")
        }
    }

    suspend fun syncFolder(folder: String = FileFolders.UPLOAD): SyncResult = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, folder)
        val diskFiles = if (dir.exists()) {
            dir.listFiles()?.filter { it.isFile }
                ?: return@withContext SyncResult(inserted = 0, removed = 0)
        } else {
            emptyList()
        }

        // 磁盘 -> 数据库：补录尚未登记的文件
        var inserted = 0
        val diskRelativePaths = HashSet<String>()
        diskFiles.forEach { file ->
            val relativePath = "${folder}/${file.name}"
            diskRelativePaths.add(relativePath)
            val existing = repository.getByPath(relativePath)
            if (existing == null) {
                val now = System.currentTimeMillis()
                val displayName = file.name
                val mimeType = guessMimeType(file, displayName)
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = file.lastModified().takeIf { it > 0 } ?: now,
                        updatedAt = now,
                    )
                )
                inserted += 1
            }
        }

        // 数据库 -> 磁盘：清理文件已不存在的孤儿记录
        var removed = 0
        repository.listByFolder(folder).first().forEach { entity ->
            if (entity.relativePath !in diskRelativePaths && !getFile(entity).isFile) {
                removed += repository.deleteByPath(entity.relativePath)
            }
        }

        SyncResult(inserted = inserted, removed = removed)
    }

    suspend fun delete(id: Long, deleteFromDisk: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val entity = repository.getById(id) ?: return@withContext false
        if (entity.folder != FileFolders.UPLOAD || !FileProtection.validPath(entity.relativePath)) return@withContext false
        if (deleteFromDisk && !deleteOwnedFile(entity.relativePath)) return@withContext false
        if (!deleteFromDisk && synchronized(protectionGuard) { isLocked(entity.relativePath) }) return@withContext false
        repository.deleteById(id) > 0
    }

    suspend fun deleteAll(folder: String = FileFolders.UPLOAD): Boolean = withContext(Dispatchers.IO) {
        require(folder == FileFolders.UPLOAD) { "Only flat chat attachments can be cleared here" }
        syncFolder(folder)
        var allDeleted = true
        repository.listByFolder(folder).first().forEach { entity ->
            if (!synchronized(protectionGuard) { isLocked(entity.relativePath) } && !delete(entity.id)) allDeleted = false
        }
        allDeleted
    }

    suspend fun deleteOlderThan(
        folder: String = FileFolders.UPLOAD,
        cutoffMillis: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        require(folder == FileFolders.UPLOAD) { "Only flat chat attachments can be cleared here" }
        var allDeleted = true
        repository.listByFolder(folder).first()
            .filter { it.createdAt < cutoffMillis }
            .forEach { entity ->
                if (!synchronized(protectionGuard) { isLocked(entity.relativePath) } && !delete(entity.id)) allDeleted = false
            }
        allDeleted
    }

    private fun createTargetFile(folder: String, displayName: String, mimeType: String?): File {
        val dir = File(context.filesDir, folder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, FileUtils.buildUuidFileName(displayName = displayName, mimeType = mimeType))
    }

    private fun buildUuidFileName(displayName: String?, mimeType: String?): String =
        FileUtils.buildUuidFileName(displayName, mimeType)

    private suspend fun createManagedFileEntity(
        folder: String,
        file: File,
        displayName: String,
        mimeType: String,
    ): ManagedFileEntity {
        val now = System.currentTimeMillis()
        return repository.insert(
            ManagedFileEntity(
                folder = folder,
                relativePath = buildRelativePath(folder, file),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = file.length(),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    private fun trackManagedFile(folder: String, file: File, displayName: String, mimeType: String) {
        val relativePath = buildRelativePath(folder, file)
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val existing = repository.getByPath(relativePath)
                if (existing != null) {
                    return@runCatching
                }
                val now = System.currentTimeMillis()
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }.onFailure {
                Log.e(TAG, "trackManagedFile: Failed to track file ${file.absolutePath}", it)
                Logging.log(
                    TAG,
                    "trackManagedFile: Failed to track file ${file.absolutePath} ${it.message} | ${it.stackTraceToString()}"
                )
            }
        }
    }

    private fun buildRelativePath(folder: String, file: File): String =
        FileUtils.buildRelativePath(folder, file)

    private fun getRelativePathInFilesDir(file: File): String? =
        FileUtils.getRelativePathInFilesDir(context.filesDir, file)

    fun getFileNameFromUri(uri: Uri): String? =
        FileUtils.getFileNameFromUri(context, uri)

    fun getFileMimeType(uri: Uri): String? =
        FileUtils.getFileMimeType(context, uri)

    private fun guessMimeType(file: File, fileName: String): String =
        FileUtils.guessMimeType(file, fileName)
}

/** Owns only a newly created UUID target. No existing object or unrelated upload is reclaimed. */
internal suspend fun <T> persistOwnedManagedFile(
    root: File,
    target: File,
    write: suspend (File) -> Unit,
    persist: suspend () -> T,
    removeRecord: suspend () -> Unit,
): T {
    val canonicalRoot = root.canonicalFile
    val canonicalTarget = target.canonicalFile
    require(canonicalTarget == target.absoluteFile && canonicalTarget != canonicalRoot &&
        canonicalTarget.toPath().startsWith(canonicalRoot.toPath())) { "Invalid managed file target" }
    var owned = false
    try {
        // The catch is OUTSIDE withContext: cancellation while returning its result must also
        // reclaim the new file/row rather than losing the caller's only reference to the target.
        return withContext(Dispatchers.IO) {
            val parent = requireNotNull(target.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Managed file directory unavailable" }
            check(target.canonicalFile == canonicalTarget && target.createNewFile()) {
                "Managed file target already exists or is unavailable"
            }
            owned = true
            write(target)
            currentCoroutineContext().ensureActive()
            persist()
        }
    } catch (error: Throwable) {
        if (owned) {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    check(target.canonicalFile == canonicalTarget) { "Managed file target changed" }
                    check(!target.exists() || (target.isFile && target.delete())) { "Managed file cleanup failed" }
                    removeRecord()
                }
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
            }
        }
        throw error
    }
}

data class SyncResult(
    val inserted: Int,
    val removed: Int,
)

object FileFolders {
    const val UPLOAD = "upload"
    const val SKILLS = "skills"
    const val FONTS = "fonts"
    const val TOOL_OUTPUTS = "tool_outputs"
}

suspend fun FilesManager.saveUploadFromUri(
    uri: Uri,
    displayName: String? = null,
    mimeType: String? = null,
): ManagedFileEntity = saveManagedFromUri(
    folder = FileFolders.UPLOAD,
    uri = uri,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadFromBytes(
    bytes: ByteArray,
    displayName: String,
    mimeType: String = "application/octet-stream",
): ManagedFileEntity = saveManagedFromBytes(
    folder = FileFolders.UPLOAD,
    bytes = bytes,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadText(
    text: String,
    displayName: String = "pasted_text.txt",
    mimeType: String = "text/plain",
): ManagedFileEntity = saveManagedText(
    folder = FileFolders.UPLOAD,
    text = text,
    displayName = displayName,
    mimeType = mimeType,
)
