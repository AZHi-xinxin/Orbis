package me.rerere.rikkahub.data.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity

/** Only the folder explicitly selected in Android's picker. Never requests all-files access. */
internal class DocumentBackupStore(private val context: Context, tree: String): BackupObjectStore {
    private val resolver = context.contentResolver
    private val treeUri = Uri.parse(tree).also {
        require(it.scheme == "content" && DocumentsContract.isTreeUri(it)) { "backup_folder_permission" }
    }
    private val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    init {
        resolver.query(root, arrayOf(Document.COLUMN_MIME_TYPE), null, null, null).use { cursor ->
            require(cursor != null && cursor.moveToFirst() && cursor.getString(0) == Document.MIME_TYPE_DIR) {
                "backup_folder_permission"
            }
        }
    }
    private data class Entry(val uri: Uri, val name: String, val size: Long?)
    private fun entries(): List<Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        return buildList {
            requireNotNull(resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE), null, null, null)).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    require(++count <= 10_000) { "backup_folder_too_many_files" }
                    if (cursor.getString(2) != Document.MIME_TYPE_DIR && !cursor.isNull(1)) {
                        add(Entry(DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0)),
                            cursor.getString(1), if (cursor.isNull(3)) null else cursor.getLong(3)))
                    }
                }
            }
        }
    }
    private fun find(name: String): Entry? {
        val matches = entries().filter { it.name == checked(name) }
        require(matches.size <= 1) { "backup_folder_ambiguous_name" }
        return matches.singleOrNull()
    }
    private fun checked(name: String): String {
        require(name.matches(Regex("orbis_v1_[a-f0-9-]{36}\\.(payload|complete\\.json)")))
        return name
    }
    override suspend fun listNames(): List<String> {
        return entries().map { it.name }.filter(BackupRestorePoint::isMarker)
    }
    override suspend fun upload(name: String, file: File) {
        checked(name)
        check(find(name) == null) { "backup_version_exists" }
        require(file.length() in 1..BackupRestorePoint.MAX_BYTES)
        // Providers need not support rename. The marker-last protocol makes incomplete files invisible.
        val target = requireNotNull(DocumentsContract.createDocument(resolver, root, "application/octet-stream", name))
        resolver.query(target, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null).use { cursor ->
            require(cursor != null && cursor.moveToFirst() && cursor.getString(0) == name) { "backup_folder_renamed_file" }
        }
        val job = currentCoroutineContext()
        resolver.openOutputStream(target, "wt")!!.use { output ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64*1024); var total = 0L
                while(true) { job.ensureActive(); val n = input.read(buffer); if(n < 0) break
                    total += n; require(total <= BackupRestorePoint.MAX_BYTES); output.write(buffer, 0, n) }
                output.flush()
            }
        }
    }
    override suspend fun download(name: String, file: File, maxBytes: Long) {
        require(maxBytes in 1..BackupRestorePoint.MAX_BYTES)
        val source = requireNotNull(find(name))
        require(source.size == null || source.size in 0..maxBytes)
        val job = currentCoroutineContext()
        resolver.openInputStream(source.uri)!!.use { input -> FileOutputStream(file).use { output ->
            val buffer = ByteArray(64*1024); var total = 0L
            while(true) { job.ensureActive(); val n = input.read(buffer); if(n < 0) break
                total += n; require(total <= maxBytes)
                ArchiveCapacity.requireSpace(file.parentFile!!.usableSpace, n.toLong()); output.write(buffer, 0, n) }
            output.fd.sync()
        } }
    }
    override suspend fun delete(name: String) { find(name)?.let { check(DocumentsContract.deleteDocument(resolver, it.uri)) } }
}
