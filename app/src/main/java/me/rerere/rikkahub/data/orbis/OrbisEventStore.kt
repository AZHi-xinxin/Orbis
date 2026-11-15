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
    private val touchSecret = AtomicFile(File(root, "orbis-touch-token-v1.txt"))
    val inbox = OrbisEventInbox(read = {
        if (data.baseFile.exists()) data.openRead().bufferedReader().use { it.readText() } else null
    }, write = { text -> writeAtomic(data, text) })

    @Synchronized
    fun authenticate(token: String?): Boolean = OrbisEventInbox.tokenMatches(credential(), token)

    /** Human UI only. This token cannot read conversation context or submit arbitrary events. */
    fun touchCredentialForHuman(): String = synchronized(touchLock) {
        readTouchCredential() ?: newTouchCredential()
    }

    fun rotateTouchCredentialForHuman(): String = synchronized(touchLock) { newTouchCredential() }

    fun authenticateTouch(token: String?): Boolean = synchronized(touchLock) {
        token != null && readTouchCredential()?.let { OrbisEventInbox.tokenMatches(it, token) } == true
    }

    private fun readTouchCredential(): String? = if (touchSecret.baseFile.exists())
        touchSecret.openRead().bufferedReader().use { it.readText() }.trim().also {
            check(it.matches(Regex("[0-9a-f]{64}"))) { "touch_credential_invalid" }
        } else null

    private fun newTouchCredential(): String {
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        writeAtomic(touchSecret, token)
        check(readTouchCredential() == token) { "touch_credential_write_unverified" }
        return token
    }

    companion object { private val touchLock = Any() }

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
