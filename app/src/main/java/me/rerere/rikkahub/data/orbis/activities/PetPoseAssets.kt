package me.rerere.rikkahub.data.orbis.activities

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal val BUNDLED_PET_POSE_APPEARANCES = setOf(
    "cat-black-v1", "dog-shiba-v1", "bird-budgie-v1", "reptile-leopard-gecko-v1",
)

/**
 * The first shipping release imported the same official PNG into filesDir. An
 * imported directory alone must not disable its later animation upgrade. Only
 * byte-identical official art may reuse the bundled poses; custom art keeps its
 * identity and original pixels.
 */
internal fun canUseBundledPetPoses(context: Context, appearance: String, imported: File?): Boolean {
    if (appearance !in BUNDLED_PET_POSE_APPEARANCES) return false
    if (imported == null) return true
    return runCatching {
        val source = File(imported, "cat.png")
        require(source.isFile && source.canonicalFile == source.absoluteFile && source.length() <= 8 * 1024 * 1024)
        source.inputStream().use(::petArtDigest).contentEquals(
            context.assets.open("orbis-pets/$appearance.png").use(::petArtDigest),
        )
    }.getOrDefault(false)
}

private fun petArtDigest(input: InputStream): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return digest.digest()
        total += count
        require(total <= 8 * 1024 * 1024)
        digest.update(buffer, 0, count)
    }
}
