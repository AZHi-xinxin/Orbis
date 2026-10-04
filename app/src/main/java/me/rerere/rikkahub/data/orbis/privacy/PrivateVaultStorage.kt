package me.rerere.rikkahub.data.orbis.privacy

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal val vaultJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }
/** Check nesting before the serialization parser can recurse on an untrusted rescue/import file. */
internal inline fun <reified T> decodeVaultJson(bytes: ByteArray): T {
    val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    if (!vaultJsonNestingAllowed(text)) throw PrivateVaultException("invalid_format")
    return vaultJson.decodeFromString<T>(text)
}

internal fun vaultJsonNestingAllowed(text: String, maximum: Int = 32): Boolean {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> { depth++; if (depth > maximum) return false }
            '}', ']' -> { depth--; if (depth < 0) return false }
        }
    }
    return depth == 0 && !quoted
}
internal const val MAX_STATE_BYTES = 2 * 1024 * 1024
internal const val MAX_ENVELOPE_BYTES = 3 * 1024 * 1024
internal const val MAX_CIPHER_BYTES = PrivateVaultCrypto.MAX_RECORD_BYTES + 34
internal const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
internal const val MAX_RECORDS = 1024
internal fun vaultId() = UUID.randomUUID().toString()
internal fun validVaultId(value: String): Boolean = value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
internal fun vaultDigest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun vaultBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
internal fun vaultUnbase64(value: String, maximum: Int): ByteArray {
    if (value.length > (maximum.toLong() + 2) / 3 * 4) throw PrivateVaultException("invalid_format")
    val decoded = try { Base64.getDecoder().decode(value) } catch (_: IllegalArgumentException) {
        throw PrivateVaultException("invalid_format")
    }
    if (decoded.size > maximum || vaultBase64(decoded) != value) throw PrivateVaultException("invalid_format")
    return decoded
}

internal fun boundedVaultRead(input: InputStream, maximum: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(maximum, 8192))
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return out.toByteArray()
        if (count == 0) continue
        if (out.size().toLong() + count > maximum) throw PrivateVaultException("size_limit")
        out.write(buffer, 0, count)
    }
}

internal fun rejectVaultLinks(path: Path) {
    var current: Path? = path.toAbsolutePath().normalize()
    while (current != null) {
        if (Files.isSymbolicLink(current)) throw PrivateVaultException("unsafe_path")
        current = current.parent
    }
}

internal fun readVaultFile(file: File, maximum: Int): ByteArray {
    rejectVaultLinks(file.toPath())
    if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() > maximum) {
        throw PrivateVaultException("invalid_format")
    }
    return Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { boundedVaultRead(it, maximum) }
}

internal fun validateVaultEnvelope(envelope: VaultEnvelope, owner: String) {
    if (envelope.version != 1 || !validVaultId(envelope.vaultId) || envelope.ownerDigest != owner ||
        !owner.matches(Regex("[0-9a-f]{64}"))) throw PrivateVaultException("invalid_format")
    if (envelope.deviceKey.isNotEmpty()) vaultUnbase64(envelope.deviceKey, 4096)
    val recovery = vaultUnbase64(envelope.recoveryKey, 66)
    if (recovery.size != 66 || !vaultCipherHeader(recovery, 1)) throw PrivateVaultException("invalid_format")
    val state = vaultUnbase64(envelope.encryptedState, MAX_STATE_BYTES + 34)
    if (!vaultCipherHeader(state, 2)) throw PrivateVaultException("invalid_format")
}

internal fun vaultCipherHeader(bytes: ByteArray, kind: Int) = bytes.size >= 34 &&
    bytes[0] == 0x4f.toByte() && bytes[1] == 0x52.toByte() && bytes[2] == 0x50.toByte() &&
    bytes[3] == 0x56.toByte() && bytes[4] == 1.toByte() && bytes[5] == kind.toByte()

/** Structural validation only: authenticity requires the offline recovery key later. */
fun validateEmergencyVaultCopy(directory: File): Boolean = try {
    val owner = directory.name
    if (!owner.matches(Regex("[0-9a-f]{64}"))) false else {
        rejectVaultLinks(directory.toPath())
        val generation = readVaultFile(File(directory, "CURRENT"), 36).toString(Charsets.US_ASCII)
        if (!validVaultId(generation)) throw PrivateVaultException("invalid_format")
        val gen = File(directory, "generations/$generation")
        val head = readVaultFile(File(gen, "HEAD"), 36).toString(Charsets.US_ASCII)
        if (!validVaultId(head)) throw PrivateVaultException("invalid_format")
        val envelope = decodeVaultJson<VaultEnvelope>(
            readVaultFile(File(gen, "states/$head.vault"), MAX_ENVELOPE_BYTES))
        validateVaultEnvelope(envelope, owner)
        // Reject unexpected entries rather than restoring arbitrary no_backup contents.
        var entries = 0
        var totalBytes = 0L
        directory.walkTopDown().forEach { entry ->
            if (++entries > 20000) throw PrivateVaultException("size_limit")
            rejectVaultLinks(entry.toPath())
            val name = entry.relativeTo(directory).invariantSeparatorsPath
            // Runtime locks and interrupted pointer writes are ignored, never restored as data.
            val ignored = name == ".lock" || Regex("(generations/[0-9a-f-]{36}/)?\\.pointer-[0-9a-f-]{36}").matches(name)
            if (ignored) {
                if (!Files.isRegularFile(entry.toPath(), NOFOLLOW_LINKS) || entry.length() > 64) throw PrivateVaultException("invalid_format")
                return@forEach
            }
            val allowed = name.isEmpty() || name in setOf("CURRENT", "generations", "recovery-required.marker") ||
                Regex("generations/[0-9a-f-]{36}(/(HEAD|states|records))?").matches(name) ||
                Regex("generations/[0-9a-f-]{36}/states/[0-9a-f-]{36}\\.vault").matches(name) ||
                Regex("generations/[0-9a-f-]{36}/records/[0-9a-f-]{36}\\.bin").matches(name)
            if (!allowed) throw PrivateVaultException("invalid_format")
            totalBytes += if (entry.isFile) entry.length() else 0L
            if (totalBytes > 512L * 1024 * 1024) throw PrivateVaultException("size_limit")
            if (entry.isFile && name.endsWith(".bin")) {
                if (!vaultCipherHeader(readVaultFile(entry, MAX_CIPHER_BYTES), 2)) throw PrivateVaultException("invalid_format")
            } else if (entry.isFile && name.endsWith(".vault")) {
                validateVaultEnvelope(decodeVaultJson<VaultEnvelope>(
                    readVaultFile(entry, MAX_ENVELOPE_BYTES)), owner)
            }
        }
        true
    }
} catch (_: Exception) { false }

internal data class VaultSnapshot(val generation: String, val stateId: String, val envelope: VaultEnvelope)

/** Immutable ciphertext first; only tiny HEAD/CURRENT pointers are atomically replaced. */
internal class PrivateVaultStorage(
    base: File, val owner: String, private val beforeCommit: (String) -> Unit,
) {
    val root = File(base, owner).absoluteFile
    private val monitor = monitors.computeIfAbsent(root.path) { Any() }

    fun <T> locked(block: () -> T): T = synchronized(monitor) {
        rejectVaultLinks(root.toPath())
        Files.createDirectories(root.toPath())
        val lockFile = File(root, ".lock").toPath()
        rejectVaultLinks(lockFile)
        FileChannel.open(lockFile, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { block() }
        }
    }

    fun exists(): Boolean {
        val current = File(root, "CURRENT").toPath()
        // An empty linked owner is unsafe, not an absent room that the UI can offer to create.
        rejectVaultLinks(current)
        return Files.exists(current, NOFOLLOW_LINKS)
    }
    fun recoveryRequired() = Files.exists(File(root, "recovery-required.marker").toPath(), NOFOLLOW_LINKS)
    fun clearRecoveryMarker() { Files.deleteIfExists(File(root, "recovery-required.marker").toPath()) }

    fun load(): VaultSnapshot {
        val generation = readVaultFile(File(root, "CURRENT"), 36).toString(Charsets.US_ASCII)
        requireId(generation)
        val stateId = readVaultFile(File(gen(generation), "HEAD"), 36).toString(Charsets.US_ASCII)
        requireId(stateId)
        val envelope = decodeVaultJson<VaultEnvelope>(readVaultFile(
            File(gen(generation), "states/$stateId.vault"), MAX_ENVELOPE_BYTES))
        validateVaultEnvelope(envelope, owner)
        return VaultSnapshot(generation, stateId, envelope)
    }

    fun record(generation: String, version: String): ByteArray {
        requireId(version)
        return readVaultFile(File(gen(generation), "records/$version.bin"), MAX_CIPHER_BYTES)
    }

    fun putRecord(generation: String, version: String, ciphertext: ByteArray) {
        requireId(version)
        if (!vaultCipherHeader(ciphertext, 2) || ciphertext.size > MAX_CIPHER_BYTES) throw PrivateVaultException("invalid_format")
        writeNew(File(gen(generation), "records/$version.bin"), ciphertext)
    }

    fun commit(generation: String, stateId: String, envelope: VaultEnvelope, newGeneration: Boolean = false) {
        requireId(stateId)
        validateVaultEnvelope(envelope, owner)
        val bytes = vaultJson.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_ENVELOPE_BYTES) throw PrivateVaultException("size_limit")
        writeNew(File(gen(generation), "states/$stateId.vault"), bytes)
        beforeCommit("head")
        atomicPointer(File(gen(generation), "HEAD"), stateId)
        if (newGeneration) {
            beforeCommit("current")
            atomicPointer(File(root, "CURRENT"), generation)
        }
    }

    private fun gen(generation: String): File {
        requireId(generation)
        return File(root, "generations/$generation")
    }
    private fun writeNew(file: File, bytes: ByteArray) {
        rejectVaultLinks(file.toPath())
        Files.createDirectories(file.parentFile.toPath())
        FileChannel.open(file.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
    private fun atomicPointer(file: File, value: String) {
        rejectVaultLinks(file.toPath())
        val temporary = File(file.parentFile, ".pointer-${vaultId()}")
        writeNew(temporary, value.toByteArray(Charsets.US_ASCII))
        // Do not fall back to a truncate/copy when atomic rename is unsupported.
        Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
    private fun requireId(id: String) { if (!validVaultId(id)) throw PrivateVaultException("invalid_format") }
    companion object { private val monitors = ConcurrentHashMap<String, Any>() }
}
