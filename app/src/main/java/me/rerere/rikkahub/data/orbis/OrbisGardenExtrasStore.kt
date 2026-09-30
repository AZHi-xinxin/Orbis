package me.rerere.rikkahub.data.orbis

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class OrbisGardenExtrasSnapshot(val revision: Int, val data: JsonObject)

@Serializable
private data class GardenExtrasDocument(
    val version: Int,
    val module: String,
    val revision: Int,
    val data: JsonObject,
)

internal interface OrbisGardenExtrasPersistence {
    fun read(module: String): ByteArray?
    fun write(module: String, value: ByteArray)
}

/**
 * Small app-private documents for the safe garden WebView modules. This store never opens chat,
 * workspace, provider, credential, or arbitrary user-selected files. Import/export pickers remain
 * separate host operations and must pass their result as bounded data.
 */
class OrbisGardenExtrasStore internal constructor(private val persistence: OrbisGardenExtrasPersistence) {
    constructor(context: Context) : this(AndroidGardenExtrasPersistence(context.applicationContext))

    private val observed = mutableSetOf<String>()
    private val unavailable = mutableSetOf<String>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    suspend fun load(module: String): OrbisGardenExtrasSnapshot = access {
        validateModule(module)
        loadLocked(module)
    }

    /** Compare-and-swap: an old WebView cannot silently replace a newer document. */
    suspend fun save(module: String, expectedRevision: Int, data: JsonObject): OrbisGardenExtrasSnapshot = access {
        validateModule(module)
        require(expectedRevision >= 0) { "garden_extras_invalid_revision" }
        val before = loadLocked(module)
        check(before.revision == expectedRevision) { "garden_extras_revision_changed" }
        check(before.revision < Int.MAX_VALUE) { "garden_extras_revision_exhausted" }
        val next = GardenExtrasDocument(
            version = DOCUMENT_VERSION,
            module = module,
            revision = before.revision + 1,
            data = data,
        )
        val bytes = json.encodeToString(next).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_DOCUMENT_BYTES) { "garden_extras_too_large" }
        try {
            persistence.write(module, bytes)
            val verified = persistence.read(module)
            check(verified != null && verified.contentEquals(bytes)) { "garden_extras_write_unverified" }
        } catch (_: Exception) {
            unavailable += module
            throw IllegalStateException("garden_extras_write_failed")
        }
        observed += module
        unavailable -= module
        OrbisGardenExtrasSnapshot(next.revision, next.data)
    }

    private fun loadLocked(module: String): OrbisGardenExtrasSnapshot {
        val raw = try {
            persistence.read(module)
        } catch (_: Exception) {
            unavailable += module
            throw IllegalStateException("garden_extras_read_failed")
        }
        if (raw == null) {
            check(module !in observed && module !in unavailable) { "garden_extras_storage_disappeared" }
            return OrbisGardenExtrasSnapshot(0, JsonObject(emptyMap()))
        }
        observed += module
        val document = try {
            require(raw.size <= MAX_DOCUMENT_BYTES) { "garden_extras_too_large" }
            json.decodeFromString<GardenExtrasDocument>(decodeGardenExtrasUtf8(raw))
        } catch (_: Exception) {
            unavailable += module
            throw IllegalStateException("garden_extras_invalid_storage")
        }
        check(document.version == DOCUMENT_VERSION && document.module == module && document.revision > 0) {
            "garden_extras_invalid_storage"
        }
        unavailable -= module
        return OrbisGardenExtrasSnapshot(document.revision, document.data)
    }

    private suspend fun <T> access(block: () -> T): T =
        withContext(Dispatchers.IO) { sharedMutex.withLock { block() } }

    companion object {
        internal const val MAX_DOCUMENT_BYTES = GardenBookImport.MAX_LIBRARY_BYTES
        private const val DOCUMENT_VERSION = 1
        internal val ALLOWED_MODULES = setOf("library", "soup")
        private val sharedMutex = Mutex()

        @Volatile private var instance: OrbisGardenExtrasStore? = null
        fun open(context: Context): OrbisGardenExtrasStore = instance ?: synchronized(this) {
            instance ?: OrbisGardenExtrasStore(context.applicationContext).also { instance = it }
        }

        internal fun validateModule(module: String) {
            require(module in ALLOWED_MODULES) { "garden_extras_module_not_allowed" }
        }
    }
}

internal fun decodeGardenExtrasUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()

private class AndroidGardenExtrasPersistence(context: Context) : OrbisGardenExtrasPersistence {
    private val filesRoot = context.filesDir.canonicalFile
    private val root = File(filesRoot, "orbis-garden-extras")

    override fun read(module: String): ByteArray? {
        val target = target(module, createRoot = false) ?: return null
        val atomic = AtomicFile(target)
        val backup = File(target.path + ".bak")
        if (!target.exists() && !backup.exists()) return null
        validateTarget(target)
        validateTarget(backup)
        return atomic.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= OrbisGardenExtrasStore.MAX_DOCUMENT_BYTES) {
                    "garden_extras_too_large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    override fun write(module: String, value: ByteArray) {
        require(value.size <= OrbisGardenExtrasStore.MAX_DOCUMENT_BYTES) { "garden_extras_too_large" }
        val target = checkNotNull(target(module, createRoot = true))
        validateTarget(target)
        validateTarget(File(target.path + ".bak"))
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(value)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            try {
                atomic.failWrite(stream)
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    private fun target(module: String, createRoot: Boolean): File? {
        OrbisGardenExtrasStore.validateModule(module)
        if (!root.exists()) {
            if (!createRoot) return null
            if (!root.mkdirs() && !root.isDirectory) throw IOException("garden_extras_directory_unavailable")
        }
        require(root.isDirectory && root.canonicalFile.parentFile == filesRoot) {
            "garden_extras_directory_invalid"
        }
        return File(root, "$module-v1.json").also(::validateTarget)
    }

    private fun validateTarget(file: File) {
        require(file.canonicalFile.parentFile == root.canonicalFile) { "garden_extras_path_invalid" }
    }
}
