package me.rerere.rikkahub.data.orbis.activities

import android.content.Context
import java.io.File

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
    if (imported == null) return false
    return runCatching {
        val pack = PetArtDownloads.catalog(context).packages.getValue(appearance)
        val modern = File(imported, "cat.webp")
        if (modern.isFile) return@runCatching modern.canonicalFile == modern.absoluteFile &&
            modern.inputStream().use(::petArtSha256) == pack.files.getValue("cat.webp").sha256 &&
            File(imported, "pose.webp").isFile && File(imported, "pose.json").isFile
        val source = File(imported, "cat.png")
        require(source.isFile && source.canonicalFile == source.absoluteFile && source.length() <= 8 * 1024 * 1024)
        source.inputStream().use(::petArtSha256) == pack.legacy["cat.png"] &&
            PetArtDownloads.store(context).ready(appearance)
    }.getOrDefault(false)
}
