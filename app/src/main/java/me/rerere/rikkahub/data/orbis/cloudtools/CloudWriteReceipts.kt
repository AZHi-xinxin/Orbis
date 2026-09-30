package me.rerere.rikkahub.data.orbis.cloudtools

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Installed only by the host around native cloud tools; never accepted from model arguments. */
internal class CloudToolInvocationContext(
    val toolCallId: String,
    val messageId: String,
    val conversationId: String? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CloudToolInvocationContext>
}

@Serializable
internal data class CloudWriteReceipt(val key: String, val fingerprint: String, val requestId: String)
internal interface CloudReceiptPersistence {
    suspend fun read(): List<CloudWriteReceipt>
    suspend fun write(receipts: List<CloudWriteReceipt>)
}
internal data class CloudReceiptClaim(val requestId: String, val rejection: String? = null)

/** Append-only attempt claims. No task content, arguments, addresses or credentials are stored. */
internal class CloudWriteReceipts(private val persistence: CloudReceiptPersistence) {
    private val mutex = Mutex()
    suspend fun claim(key: String, fingerprint: String, requestId: String): CloudReceiptClaim =
        withContext(NonCancellable) {
            mutex.withLock {
                val existing = persistence.read()
                existing.firstOrNull { it.key == key }?.let {
                    return@withLock CloudReceiptClaim(it.requestId,
                        if (it.fingerprint == fingerprint) "already_attempted" else "invocation_changed")
                }
                if (existing.size >= 4096) return@withLock CloudReceiptClaim(requestId, "receipt_capacity")
                persistence.write(existing + CloudWriteReceipt(key, fingerprint, requestId))
                CloudReceiptClaim(requestId)
            }
        }
}

internal class AndroidCloudReceiptPersistence(context: Context) : CloudReceiptPersistence {
    private val file = AtomicFile(File(context.noBackupFilesDir, "orbis-native-cloud-write-receipts.json"))
    override suspend fun read(): List<CloudWriteReceipt> = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext emptyList()
        val bytes = file.openRead().use { it.cloudReadBounded(2 * 1024 * 1024) }
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        Json.decodeFromString(ListSerializer(CloudWriteReceipt.serializer()), text).also { rows ->
            require(rows.size <= 4096 && rows.map { it.key }.toSet().size == rows.size)
            require(rows.all { it.key.matches(Regex("[a-f0-9]{64}")) && it.fingerprint.matches(Regex("[a-f0-9]{64}")) &&
                runCatching { java.util.UUID.fromString(it.requestId).version() == 4 }.getOrDefault(false) })
        }
    }
    override suspend fun write(receipts: List<CloudWriteReceipt>) = withContext(Dispatchers.IO) {
        val bytes = Json.encodeToString(ListSerializer(CloudWriteReceipt.serializer()), receipts).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2 * 1024 * 1024)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }
}

internal fun cloudFingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
