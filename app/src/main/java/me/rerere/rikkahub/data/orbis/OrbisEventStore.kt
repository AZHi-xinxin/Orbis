package me.rerere.rikkahub.data.orbis

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.SecureRandom

/** Device-local credentials never enter ordinary chat backups or model-visible tool output. */
class OrbisEventStore(context: Context) {
    private val root = context.noBackupFilesDir
    private val data = AtomicFile(File(root, "orbis-event-inbox-v1.json"))
    private val secret = AtomicFile(File(root, "orbis-event-token-v1.txt"))
    val inbox = OrbisEventInbox(read = {
        if (data.baseFile.exists()) data.openRead().bufferedReader().use { it.readText() } else null
    }, write = { text -> writeAtomic(data, text) })

    @Synchronized
    fun authenticate(token: String?): Boolean = OrbisEventInbox.tokenMatches(credential(), token)

    @Synchronized
    private fun credential(): String {
        if (secret.baseFile.exists()) return secret.openRead().bufferedReader().use { it.readText() }.trim()
            .also { check(it.length >= 32) { "event_credential_invalid" } }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        writeAtomic(secret, token)
        return token
    }

    private fun writeAtomic(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try { stream.write(text.toByteArray()); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}
