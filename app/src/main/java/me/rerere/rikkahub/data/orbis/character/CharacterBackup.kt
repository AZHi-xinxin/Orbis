package me.rerere.rikkahub.data.orbis.character

import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest

/** Ordinary backup's exact, credential-free character namespace. No directory-wide private backup. */
internal object CharacterBackup {
    const val DIRECTORY = "orbis-characters"
    private val entry = Regex("$DIRECTORY/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/(profile\\.json|[a-f0-9]{64}\\.png)")
    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

    fun maxBytes(path: String): Int? = entry.matchEntire(path)?.let {
        if (it.groupValues[2] == "profile.json") CharacterRules.JSON_BYTES else CharacterRules.IMAGE_BYTES
    }

    fun exactFile(root: File, path: String): File {
        require(maxBytes(path) != null) { "character_backup_path" }
        val base = root.canonicalFile
        return File(base, path).also {
            require(it.absoluteFile == it.canonicalFile && (!it.exists() || it.isFile) &&
                it.path.startsWith(base.path + File.separator)) { "character_backup_path" }
        }
    }

    /** Owner IDs are the same IDs serialized in settings, never the current selection or a name match. */
    fun stageSnapshot(noBackupDir: File, destination: File, owners: Set<String>,
        checkCancelled: () -> Unit = {}, beforeFile: (String, Long) -> Unit = { _, _ -> }): List<Pair<String, File>> {
        require(owners.all(CharacterRules::owner)) { "character_backup_owner" }
        val result = mutableListOf<Pair<String, File>>()
        owners.sorted().forEach { owner ->
            checkCancelled()
            CharacterStore(noBackupDir, owner).withBackupSnapshot { profile, assets ->
                val dimensions = dimensions(profile)
                var total = 0L
                assets.forEach { (hash, source) ->
                    checkCancelled()
                    val path = "$DIRECTORY/$owner/$hash.png"
                    val target = exactFile(destination, path)
                    val size = source.length()
                    require(size in 24..CharacterRules.IMAGE_BYTES.toLong()) { "character_backup_invalid" }
                    total += size
                    require(total <= CharacterArchive.TOTAL_BYTES) { "character_backup_invalid" }
                    beforeFile(path, size)
                    copy(source, target, checkCancelled)
                    validateImage(target, hash, requireNotNull(dimensions[hash]), checkCancelled)
                    result += path to target
                }
                val bytes = CharacterStore.json.encodeToString(profile).toByteArray(Charsets.UTF_8)
                require(bytes.size in 1..CharacterRules.JSON_BYTES) { "character_backup_invalid" }
                val path = "$DIRECTORY/$owner/profile.json"
                beforeFile(path, bytes.size.toLong())
                val target = exactFile(destination, path)
                require(!target.exists() && (target.parentFile.isDirectory || target.parentFile.mkdirs()))
                ArchiveCapacity.requireSpace(target.parentFile.usableSpace, bytes.size.toLong())
                FileOutputStream(target).use { it.write(bytes); it.fd.sync() }
                result += path to target
            }
        }
        return result
    }

    fun hasStagedCharacters(payload: File) = File(payload, "no-backup/$DIRECTORY").exists()

    /** Validate before publishing AND again before journalling; no original is moved on rejection. */
    fun validateStaged(payload: File, owners: Set<String>,
        verifyPixels: (File, Int, Int) -> Unit = { _, _, _ -> }, checkCancelled: () -> Unit = {},
        directoryEntries: (File) -> Array<File>? = { it.listFiles() }) {
        val base = File(payload, "no-backup").canonicalFile
        val namespace = File(base, DIRECTORY)
        require(namespace.canonicalFile == namespace) { "character_backup_path" }
        if (Files.notExists(namespace.toPath())) return
        require(namespace.isDirectory) { "character_backup_path" }
        // A null listing is an I/O failure, not an empty character backup.
        requireNotNull(directoryEntries(namespace)) { "character_backup_invalid" }.forEach { directory ->
            checkCancelled()
            require(CharacterRules.owner(directory.name) && directory.name in owners) { "character_backup_owner" }
            require(directory.isDirectory && directory.absoluteFile == directory.canonicalFile) { "character_backup_path" }
            val profileFile = exactFile(base, "$DIRECTORY/${directory.name}/profile.json")
            val profile = readProfile(profileFile)
            val dimensions = dimensions(profile)
            val expected = dimensions.keys.map { "$it.png" }.toSet() + "profile.json"
            val names = requireNotNull(directoryEntries(directory)) { "character_backup_invalid" }.map { it.name }.toSet()
            require(names == expected) { "character_backup_invalid" }
            var total = 0L
            dimensions.forEach { (hash, size) ->
                val image = exactFile(base, "$DIRECTORY/${directory.name}/$hash.png")
                total += image.length()
                require(total <= CharacterArchive.TOTAL_BYTES) { "character_backup_invalid" }
                validateImage(image, hash, size, checkCancelled)
                verifyPixels(image, size.first, size.second)
            }
            // Temporary AI policies never survive a backup import, even in a crafted archive.
            require(profile.presentation.expressionPolicy == null) { "character_backup_invalid" }
        }
    }

    private fun readProfile(file: File): CharacterProfile {
        require(file.isFile && file.length() in 1..CharacterRules.JSON_BYTES.toLong()) { "character_backup_invalid" }
        val bytes = file.inputStream().use { it.characterReadBounded(CharacterRules.JSON_BYTES) }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return CharacterStore.json.decodeFromString<CharacterProfile>(text).also(CharacterRules::validate)
    }

    private fun dimensions(profile: CharacterProfile): Map<String, Pair<Int, Int>> {
        val result = mutableMapOf<String, Pair<Int, Int>>()
        fun add(hash: String, width: Int, height: Int) {
            val old = result.put(hash, width to height)
            require(old == null || old == (width to height)) { "character_backup_invalid" }
        }
        profile.pack?.let { add(it.sha256, it.width, it.height) }
        profile.presentation.assets.forEach { add(it.sha256, it.width, it.height) }
        return result
    }

    private fun validateImage(file: File, hash: String, dimensions: Pair<Int, Int>, checkCancelled: () -> Unit) {
        require(file.isFile && file.length() in 24..CharacterRules.IMAGE_BYTES.toLong()) { "character_backup_invalid" }
        file.inputStream().use { input ->
            val header = ByteArray(24)
            java.io.DataInputStream(input).readFully(header)
            require(header.copyOfRange(0, 8).contentEquals(signature) &&
                header.copyOfRange(8, 16).contentEquals(byteArrayOf(0, 0, 0, 13, 73, 72, 68, 82)) &&
                ByteBuffer.wrap(header, 16, 8).let { it.int to it.int } == dimensions) { "character_backup_invalid" }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            var total = 0L
            val buffer = ByteArray(65536)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= CharacterRules.IMAGE_BYTES) { "character_backup_invalid" }
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "character_backup_invalid" }
    }

    private fun copy(source: File, target: File, checkCancelled: () -> Unit) {
        require(source.absoluteFile == source.canonicalFile && !target.exists()) { "character_backup_path" }
        require(target.parentFile.isDirectory || target.parentFile.mkdirs())
        source.inputStream().use { input -> FileOutputStream(target).use { output ->
            var total = 0L
            val buffer = ByteArray(65536)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= CharacterRules.IMAGE_BYTES) { "character_backup_invalid" }
                ArchiveCapacity.requireSpace(target.parentFile.usableSpace, count.toLong())
                output.write(buffer, 0, count)
            }
            require(total == source.length()) { "character_backup_invalid" }
            output.fd.sync()
        } }
    }
}
