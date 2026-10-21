package me.rerere.rikkahub.data.orbis.memory

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Rebuildable vectors only, never canonical notes or a backup. No user/query text is written. */
internal class MemoryVectorCache(private val root: File) {
    private fun path(owner: String, id: String) = File(File(root, memoryDigest(owner)), memoryDigest(id) + ".vec")
    fun read(owner: String, id: String, fingerprint: String, count: Int): List<FloatArray>? = try {
        val file = path(owner, id)
        if (file.length() !in 1..(count.toLong() * 512 * 4 + 1024)) null else
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readUTF() != LocalMemoryEmbedding.CACHE_VERSION || input.readUTF() != fingerprint ||
                    input.readInt() != count) null else {
                    val vectors = List(count) { FloatArray(512) { input.readFloat() } }
                    if (input.read() != -1 || vectors.any { v -> v.any { !it.isFinite() } ||
                            kotlin.math.abs(v.sumOf { it.toDouble() * it } - 1.0) > 0.02 }) null else vectors
                }
            }
    } catch (_: Exception) { null }

    fun write(owner: String, id: String, fingerprint: String, vectors: List<FloatArray>) {
        val target = path(owner, id)
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        val temporary = File.createTempFile("vector-", ".pending", target.parentFile)
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeUTF(LocalMemoryEmbedding.CACHE_VERSION); output.writeUTF(fingerprint)
                output.writeInt(vectors.size)
                vectors.forEach { vector -> require(vector.size == 512); vector.forEach(output::writeFloat) }
            }
            java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }
}
