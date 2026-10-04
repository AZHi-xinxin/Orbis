package me.rerere.rikkahub.ui.pages.orbis

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal fun privateRoomTextPage(text: String, offset: Int): Pair<String, Int> {
    var start = offset.coerceIn(0, text.length)
    if (start > 0 && start < text.length && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
    var end = (start + 16_384).coerceAtMost(text.length)
    if (end > start && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return text.substring(start, end) to end
}

/** Exact-size bounded readback. A failed/cancelled export may leave a partial destination, never a success receipt. */
internal fun savePrivateRoomExport(write: (OutputStream) -> Unit, open: () -> OutputStream,
                                  reopen: () -> InputStream, checkCancelled: () -> Unit) {
    val expected = MessageDigest.getInstance("SHA-256")
    var size = 0L
    checkCancelled()
    open().use { target ->
        val counting = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                checkCancelled()
                target.write(b, off, len); expected.update(b, off, len); size = Math.addExact(size, len.toLong())
            }
            override fun flush() = target.flush()
        }
        write(counting); counting.flush()
    }
    val actual = MessageDigest.getInstance("SHA-256")
    var readBytes = 0L
    reopen().use { source ->
        val buffer = ByteArray(32_768)
        while (true) {
            checkCancelled()
            val n = source.read(buffer)
            if (n < 0) break
            check(n > 0) { "private_export_read_stalled" }
            readBytes = Math.addExact(readBytes, n.toLong())
            check(readBytes <= size) { "private_export_size_mismatch" }
            actual.update(buffer, 0, n)
        }
    }
    checkCancelled()
    check(readBytes == size && MessageDigest.isEqual(expected.digest(), actual.digest())) { "private_export_verification_failed" }
}
