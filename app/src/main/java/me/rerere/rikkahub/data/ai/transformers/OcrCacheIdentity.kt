package me.rerere.rikkahub.data.ai.transformers

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** V2 keys never contain a file path, prompt, endpoint, header or credential in plaintext. */
internal object OcrCacheIdentity {
    fun key(
        file: File,
        assistantScope: String,
        prompt: String,
        modelConfiguration: String,
        providerConfiguration: String,
        checkActive: () -> Unit = {},
    ): String {
        require(file.isFile && file.canRead()) { "ocr_image_unavailable" }
        val digest = MessageDigest.getInstance("SHA-256")
        // Length framing prevents different field boundaries from producing the same input.
        listOf("ocr-cache-v2", assistantScope, prompt, modelConfiguration, providerConfiguration).forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return "ocr-v2:" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun cacheableText(text: String): String? = text.takeUnless {
        it.isBlank() || it.trim() == "[Image]" || it.trim().startsWith("[ERROR, OCR failed")
    }
}
