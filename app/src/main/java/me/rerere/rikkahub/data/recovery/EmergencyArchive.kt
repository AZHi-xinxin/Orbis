package me.rerere.rikkahub.data.recovery

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/**
 * Cold, byte-for-byte rescue transport. No Room, DataStore, application DI, SQL, or settings
 * decoding is used here. The caller must fence ALL application writers before calling create.
 * Hashes detect corruption/change, not authenticity. A valid archive may still contain a broken
 * database; preserving those original bytes is deliberate and is not a claim that it is repaired.
 */
@OptIn(ExperimentalSerializationApi::class)
object EmergencyArchive {
    const val MANIFEST_ENTRY = "emergency-manifest.json"
    const val PAYLOAD_PREFIX = "payload/"
    const val CURRENT_SCHEMA = 2
    val ROOT_NAMES: Set<String> = setOf("databases", "files", "shared_prefs", "no_backup")
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
    private const val MAX_RECORDS = 100_000
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun create(
        roots: List<EmergencyArchiveRoot>,
        destination: File,
        metadata: EmergencyArchiveMetadata,
        onProgress: (EmergencyArchiveProgress) -> Unit = {},
    ): EmergencyArchiveManifest = createInternal(roots, destination, metadata, onProgress) {}

    /** Test seam for concurrent-change/failure injection; production uses the public cold path. */
    internal fun createInternal(
        roots: List<EmergencyArchiveRoot>,
        destination: File,
        metadata: EmergencyArchiveMetadata,
        onProgress: (EmergencyArchiveProgress) -> Unit = {},
        afterCopy: () -> Unit,
    ): EmergencyArchiveManifest {
        requireRoots(roots)
        val output = destination.toPath().toAbsolutePath().normalize()
        val parent = output.parent ?: throw IOException("Backup destination has no parent")
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) throw IOException("Backup directory does not exist")
        if (Files.exists(output, NOFOLLOW_LINKS)) throw IOException("Backup destination already exists")
        val realParent = parent.toRealPath()
        roots.forEach { root ->
            val source = root.directory.canonicalFile.toPath()
            if (realParent.startsWith(source)) throw IOException("Backup destination is inside a source root")
        }
        val before = snapshot(roots, "scan", onProgress)
        val total = before.files.fold(0L) { sum, item -> checkedAdd(sum, item.size) }
        // No arbitrary backup byte limit: stream until the filesystem refuses. Do not assume a
        // compression ratio or allocate the raw source size in memory.
        var copied = 0L
        val entryCount = before.files.size.toLong()
        val records = ArrayList<EmergencyArchiveFileRecord>(before.files.size)
        val temporary = realParent.resolve(".orbis-emergency-${UUID.randomUUID()}.partial")
        try {
            Files.newOutputStream(temporary, CREATE_NEW, WRITE).buffered(BUFFER_SIZE).use { out ->
                ZipOutputStream(out).use { zip ->
                    onProgress(EmergencyArchiveProgress("copy", 0, total, 0, entryCount))
                    before.files.forEachIndexed { index, item ->
                        checkUnchanged(item)
                        zip.putNextEntry(ZipEntry(payloadEntryName(CURRENT_SCHEMA, item.path)))
                        val digest = Files.newInputStream(item.source, READ, NOFOLLOW_LINKS).use { input ->
                            transferDigest(input, zip, item.size) { delta ->
                                copied = checkedAdd(copied, delta)
                                onProgress(EmergencyArchiveProgress("copy", copied, total, index.toLong(), entryCount))
                            }
                        }
                        zip.closeEntry()
                        checkUnchanged(item)
                        records += EmergencyArchiveFileRecord(item.path, item.size, digest, item.modified)
                        onProgress(EmergencyArchiveProgress("copy", copied, total, index + 1L, entryCount))
                    }
                    val manifest = EmergencyArchiveManifest(
                        schemaVersion = CURRENT_SCHEMA,
                        metadata = metadata,
                        roots = before.roots,
                        files = records,
                        directories = before.directories,
                        links = before.links,
                        specialFiles = before.specialFiles,
                    )
                    onProgress(EmergencyArchiveProgress("manifest", 0, 0))
                    validateManifest(manifest)
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    json.encodeToStream(manifest, BoundedOutput(zip, MAX_MANIFEST_BYTES.toLong()))
                    zip.closeEntry()
                }
            }
            afterCopy()
            if (before != snapshot(roots, "rescan", onProgress)) throw IOException("Application data changed during backup")
            // Timestamps alone are insufficient (coarse resolution / same-size rewrites).
            var rehashed = 0L
            onProgress(EmergencyArchiveProgress("rehash", 0, total, 0, entryCount))
            before.files.zip(records).forEachIndexed { index, (source, record) ->
                checkUnchanged(source)
                val digest = Files.newInputStream(source.source, READ, NOFOLLOW_LINKS).use {
                    transferDigest(it, null, record.size) { delta ->
                        rehashed = checkedAdd(rehashed, delta)
                        onProgress(EmergencyArchiveProgress("rehash", rehashed, total, index.toLong(), entryCount))
                    }
                }
                if (digest != record.sha256) throw IOException("Application data changed during backup: ${record.path}")
                checkUnchanged(source)
                onProgress(EmergencyArchiveProgress("rehash", rehashed, total, index + 1L, entryCount))
            }
            if (before != snapshot(roots, "final_scan", onProgress)) throw IOException("Application data changed during verification")
            val verified = verify(temporary.toFile(), onProgress)
            // Close and force the new archive before reporting a published complete backup.
            onProgress(EmergencyArchiveProgress("sync", 0, 0))
            java.nio.channels.FileChannel.open(temporary, WRITE).use { it.force(true) }
            if (Files.exists(output, NOFOLLOW_LINKS)) throw IOException("Backup destination appeared during export")
            // Same-filesystem rename, explicitly WITHOUT replacement. ATOMIC_MOVE is omitted:
            // its specification permits replacing an existing target despite no REPLACE flag.
            // This no-replace move must fail if another export has claimed the destination.
            Files.move(temporary, output)
            onProgress(EmergencyArchiveProgress("complete", total, total, entryCount, entryCount))
            return verified
        } finally {
            // This unique, newly created partial file is the ONLY path export ever deletes.
            Files.deleteIfExists(temporary)
        }
    }

    /** Fully reads every payload, rejects duplicate/unknown/traversal entries, and checks hashes. */
    fun verify(archive: File, onProgress: (EmergencyArchiveProgress) -> Unit = {}): EmergencyArchiveManifest {
        onProgress(EmergencyArchiveProgress("verify", 0, 0))
        return ZipFile(archive).use { zip ->
            val manifest = readManifest(zip)
            verifyMembers(zip, manifest, onProgress)
            manifest
        }
    }

    /**
     * Extract into a NEW isolated directory only. Never call with active app data directories.
     * Links are preserved as metadata in the returned manifest, not recreated. A failure leaves
     * the incomplete staging directory for the caller to label/dispose; nothing is applied live.
     */
    fun extractVerified(archive: File, destination: File,
                        onProgress: (EmergencyArchiveProgress) -> Unit = {}): EmergencyArchiveManifest {
        val manifest = verify(archive, onProgress)
        // Run all host representability checks before creating even the isolated destination.
        // Evidence-only links/special nodes are never materialized on any platform.
        EmergencyArchivePaths.requireMaterializable(manifest.directories + manifest.files.map { it.path })
        val target = destination.toPath().toAbsolutePath().normalize()
        if (Files.exists(target, NOFOLLOW_LINKS)) throw IOException("Extraction destination already exists")
        val parent = target.parent ?: throw IOException("Extraction destination has no parent")
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) throw IOException("Extraction parent does not exist")
        Files.createDirectory(target)
        val root = target.toRealPath()
        ZipFile(archive).use { zip ->
            val reopened = readManifest(zip)
            if (reopened != manifest) throw IOException("Archive changed before extraction")
            verifyEntryNames(zip, manifest)
            manifest.directories.sortedWith(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it })
                .forEach { path ->
                    val directory = resolveSafe(root, path)
                    requireSafeParent(root, directory.parent)
                    Files.createDirectory(directory)
                }
            var extracted = 0L
            val total = manifest.files.fold(0L) { sum, record -> checkedAdd(sum, record.size) }
            val count = manifest.files.size.toLong()
            onProgress(EmergencyArchiveProgress("extract", 0, total, 0, count))
            manifest.files.forEachIndexed { index, record ->
                val output = resolveSafe(root, record.path)
                requireSafeParent(root, output.parent)
                val entry = zip.getEntry(payloadEntryName(manifest.schemaVersion, record.path))
                    ?: throw IOException("Archive is missing a payload")
                val digest = Files.newOutputStream(output, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { out ->
                    zip.getInputStream(entry).use { input -> transferDigest(input, out, record.size) { delta ->
                        extracted = checkedAdd(extracted, delta)
                        onProgress(EmergencyArchiveProgress("extract", extracted, total, index.toLong(), count))
                    } }
                }
                if (digest != record.sha256) throw IOException("Archive checksum mismatch: ${record.path}")
                onProgress(EmergencyArchiveProgress("extract", extracted, total, index + 1L, count))
            }
        }
        return manifest
    }

    private data class SourceFile(
        val path: String, val source: Path, val sourceRoot: Path,
        val size: Long, val modified: Long, val fileKey: String?,
    )

    private data class Snapshot(
        val roots: List<EmergencyArchiveSourceRoot>,
        val files: List<SourceFile>,
        val directories: List<String>,
        val links: List<EmergencyArchiveLinkRecord>,
        val specialFiles: List<EmergencyArchiveSpecialFileRecord>,
    )

    private fun snapshot(roots: List<EmergencyArchiveRoot>, phase: String,
                         onProgress: (EmergencyArchiveProgress) -> Unit): Snapshot {
        val sourceRoots = mutableListOf<EmergencyArchiveSourceRoot>()
        val files = mutableListOf<SourceFile>()
        val directories = mutableListOf<String>()
        val links = mutableListOf<EmergencyArchiveLinkRecord>()
        val specialFiles = mutableListOf<EmergencyArchiveSpecialFileRecord>()
        var recordCount = 0
        onProgress(EmergencyArchiveProgress(phase, 0, 0))
        fun walk(path: Path, name: String, root: Path, depth: Int) {
            if (Thread.currentThread().isInterrupted) throw IOException("Emergency archive operation was interrupted")
            validatePath(name, CURRENT_SCHEMA)
            if (++recordCount > MAX_RECORDS || depth > 128) throw IOException("Backup has too many entries or levels")
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            onProgress(EmergencyArchiveProgress(phase, 0, 0, recordCount.toLong()))
            when {
                attrs.isSymbolicLink -> {
                    // Runtime rootfs links are common. Record their literal targets without ever
                    // opening them; an absolute/out-of-root target is never read or extracted.
                    links += EmergencyArchiveLinkRecord(name, Files.readSymbolicLink(path).toString())
                }
                attrs.isDirectory -> {
                    if (!path.toRealPath().startsWith(root)) throw IOException("Source directory escaped its root")
                    directories += name
                    Files.newDirectoryStream(path).use { entries ->
                        entries.toList().sortedBy { it.fileName.toString() }.forEach { child ->
                            walk(child, "$name/${child.fileName}", root, depth + 1)
                        }
                    }
                }
                attrs.isRegularFile -> {
                    if (!path.toRealPath().startsWith(root)) throw IOException("Source file escaped its root")
                    files += SourceFile(name, path, root, attrs.size(), attrs.lastModifiedTime().toMillis(), attrs.fileKey()?.toString())
                }
                else -> specialFiles += EmergencyArchiveSpecialFileRecord(name, "non_regular_runtime_entry")
            }
        }
        roots.sortedBy { it.name }.forEach { source ->
            val path = source.directory.toPath().toAbsolutePath().normalize()
            val rootAttributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                null
            }
            val present = rootAttributes != null
            if (present && rootAttributes?.isDirectory != true) throw IOException("Source root is not a real directory: ${source.name}")
            val canonical = source.directory.canonicalFile.toPath()
            sourceRoots += EmergencyArchiveSourceRoot(source.name, canonical.toString(), present)
            if (present) walk(path, source.name, path.toRealPath(), 0)
        }
        onProgress(EmergencyArchiveProgress(phase, 0, 0, recordCount.toLong(), recordCount.toLong()))
        return Snapshot(sourceRoots, files, directories, links, specialFiles)
    }

    private fun checkUnchanged(source: SourceFile) {
        if (!source.source.toRealPath().startsWith(source.sourceRoot)) {
            throw IOException("Source file escaped its original root")
        }
        val now = Files.readAttributes(source.source, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!now.isRegularFile || now.size() != source.size ||
            now.lastModifiedTime().toMillis() != source.modified || now.fileKey()?.toString() != source.fileKey
        ) throw IOException("Source changed or became unreadable: ${source.path}")
    }

    private fun requireRoots(roots: List<EmergencyArchiveRoot>) {
        if (roots.size != ROOT_NAMES.size || roots.map { it.name }.toSet() != ROOT_NAMES) {
            throw IOException("All four persistent roots must be specified exactly once")
        }
        val paths = roots.map { it.directory.canonicalFile.toPath() }
        roots.forEach {
            val parent = it.directory.toPath().toAbsolutePath().normalize().parent
                ?: throw IOException("Persistent root has no parent")
            val attributes = Files.readAttributes(parent, BasicFileAttributes::class.java)
            if (!attributes.isDirectory) throw IOException("Persistent root parent does not exist")
        }
        paths.forEachIndexed { i, first -> paths.forEachIndexed { j, second ->
            if (i != j && first.startsWith(second)) throw IOException("Persistent roots overlap")
        } }
    }

    private fun readManifest(zip: ZipFile): EmergencyArchiveManifest {
        val entry = zip.getEntry(MANIFEST_ENTRY) ?: throw IOException("Not an Orbis emergency archive")
        if (entry.isDirectory || entry.size > MAX_MANIFEST_BYTES) throw IOException("Invalid backup manifest")
        val manifest = try {
            zip.getInputStream(entry).use { input ->
                json.decodeFromStream<EmergencyArchiveManifest>(EmergencyJsonInput(input, MAX_MANIFEST_BYTES.toLong()))
            }
        } catch (e: Exception) {
            throw IOException("Invalid backup manifest", e)
        }
        validateManifest(manifest)
        return manifest
    }

    private fun validateManifest(manifest: EmergencyArchiveManifest) {
        if (manifest.schemaVersion !in 1..CURRENT_SCHEMA || manifest.status != "complete") throw IOException("Unsupported or incomplete emergency archive")
        if (manifest.roots.size != ROOT_NAMES.size || manifest.roots.map { it.name }.toSet() != ROOT_NAMES) {
            throw IOException("Invalid persistent roots in archive")
        }
        if (manifest.metadata.packageName.isBlank() || manifest.metadata.versionCode < 0) throw IOException("Invalid archive metadata")
        if (manifest.files.size.toLong() + manifest.directories.size + manifest.links.size + manifest.specialFiles.size > MAX_RECORDS) throw IOException("Archive contains too many entries")
        val paths = HashSet<String>()
        val dirs = manifest.directories.toSet()
        val present = manifest.roots.filter { it.present }.map { it.name }.toSet()
        fun add(path: String, isDirectory: Boolean) {
            validatePath(path, manifest.schemaVersion)
            if (!paths.add(path)) throw IOException("Duplicate archive path: $path")
            if (path.substringBefore('/') !in present) throw IOException("Payload belongs to a missing root")
            if (!isDirectory && '/' !in path) throw IOException("Payload cannot replace a source root")
            if ('/' in path && path.substringBeforeLast('/') !in dirs) throw IOException("Missing payload parent directory")
        }
        manifest.directories.forEach { add(it, true) }
        if (!dirs.containsAll(present)) throw IOException("Missing root directory record")
        var total = 0L
        val payloadNames = HashSet<String>()
        manifest.files.forEach {
            add(it.path, false)
            if (it.size < 0 || !it.sha256.matches(Regex("[0-9a-f]{64}"))) throw IOException("Invalid file checksum or length")
            total = checkedAdd(total, it.size)
            if (!payloadNames.add(payloadEntryName(manifest.schemaVersion, it.path))) throw IOException("emergency_archive_path_payload_collision")
        }
        manifest.links.forEach {
            add(it.path, false)
            if (it.target.length > 16384 || '\u0000' in it.target) throw IOException("Invalid symbolic-link record")
        }
        manifest.specialFiles.forEach {
            add(it.path, false)
            if (it.kind != "non_regular_runtime_entry") throw IOException("Unknown special file metadata")
        }
    }

    private fun verifyEntryNames(zip: ZipFile, manifest: EmergencyArchiveManifest) {
        val expected = manifest.files.mapTo(HashSet()) { payloadEntryName(manifest.schemaVersion, it.path) }.apply { add(MANIFEST_ENTRY) }
        val seen = HashSet<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!seen.add(entry.name) || entry.name !in expected || entry.isDirectory) throw IOException("Unknown or duplicate ZIP entry")
        }
        if (seen != expected) throw IOException("Emergency archive is incomplete")
    }

    private fun verifyMembers(zip: ZipFile, manifest: EmergencyArchiveManifest,
                              onProgress: (EmergencyArchiveProgress) -> Unit) {
        verifyEntryNames(zip, manifest)
        var verified = 0L
        val total = manifest.files.fold(0L) { sum, record -> checkedAdd(sum, record.size) }
        val count = manifest.files.size.toLong()
        onProgress(EmergencyArchiveProgress("verify", 0, total, 0, count))
        manifest.files.forEachIndexed { index, record ->
            val entry = zip.getEntry(payloadEntryName(manifest.schemaVersion, record.path))
            if (entry.size >= 0 && entry.size != record.size) throw IOException("Archive length mismatch: ${record.path}")
            val digest = zip.getInputStream(entry).use { transferDigest(it, null, record.size) { delta ->
                verified = checkedAdd(verified, delta)
                onProgress(EmergencyArchiveProgress("verify", verified, total, index.toLong(), count))
            } }
            if (digest != record.sha256) throw IOException("Archive checksum mismatch: ${record.path}")
            onProgress(EmergencyArchiveProgress("verify", verified, total, index + 1L, count))
        }
    }

    private fun validatePath(path: String, schemaVersion: Int = CURRENT_SCHEMA) {
        if (!EmergencyArchivePaths.isRelative(path, allowColon = schemaVersion >= 2) || path.substringBefore('/') !in ROOT_NAMES
        ) throw IOException("emergency_archive_path_invalid")
    }

    /** Schema is the sole mapping selector. Never use a caller-provided ZIP path or decode escapes. */
    internal fun payloadEntryName(schemaVersion: Int, path: String): String {
        validatePath(path, schemaVersion)
        return when (schemaVersion) {
            1 -> PAYLOAD_PREFIX + path
            2 -> PAYLOAD_PREFIX + digestHex(MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))) + ".bin"
            else -> throw IOException("Unsupported emergency archive schema")
        }
    }

    private fun resolveSafe(root: Path, relative: String): Path {
        validatePath(relative)
        val target = root.resolve(relative).normalize()
        if (!target.startsWith(root) || target == root) throw IOException("Archive path escaped destination")
        return target
    }

    private fun requireSafeParent(root: Path, parent: Path) {
        if (!parent.startsWith(root)) throw IOException("Extraction parent escaped destination")
        var current = root
        if (Files.isSymbolicLink(current)) throw IOException("Extraction root was replaced")
        root.relativize(parent).forEach { segment ->
            current = current.resolve(segment)
            if (!Files.isDirectory(current, NOFOLLOW_LINKS)) throw IOException("Extraction parent is not a real directory")
        }
        if (!parent.toRealPath().startsWith(root)) throw IOException("Extraction parent escaped destination")
    }

    private fun transferDigest(input: InputStream, output: OutputStream?, expectedSize: Long, progress: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw IOException("Emergency archive operation was interrupted")
            val count = input.read(buffer)
            if (count < 0) break
            total = checkedAdd(total, count.toLong())
            if (total > expectedSize) throw IOException("File became larger than its recorded length")
            digest.update(buffer, 0, count)
            output?.write(buffer, 0, count)
            progress(count.toLong())
        }
        if (total != expectedSize) throw IOException("File became shorter than its recorded length")
        return digestHex(digest.digest())
    }

    private fun digestHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        val alphabet = "0123456789abcdef"
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 15])
        }
    }

    private fun checkedAdd(first: Long, second: Long): Long {
        if (first < 0 || second < 0 || first > Long.MAX_VALUE - second) throw IOException("Invalid archive byte count")
        return first + second
    }

    /** Bound structure size without materializing a second manifest String/ByteArray. */
    private class BoundedOutput(private val target: OutputStream, private val limit: Long) : OutputStream() {
        private var count = 0L
        override fun write(value: Int) {
            count = checkedAdd(count, 1)
            if (count > limit) throw IOException("Backup manifest is too large")
            target.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            count = checkedAdd(count, length.toLong())
            if (count > limit) throw IOException("Backup manifest is too large")
            target.write(bytes, offset, length)
        }
    }

}
