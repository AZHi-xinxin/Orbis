package me.rerere.rikkahub.data.ai.contextpruning

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.uuid.Uuid

object AndroidContextPruning {
    private val instances = ConcurrentHashMap<String, ContextPruningRepository>()

    fun open(context: Context, assistantId: Uuid, conversationId: Uuid): ContextPruningRepository {
        val directory = File(context.applicationContext.filesDir, "orbis-context-pruning/$assistantId/$conversationId")
        return instances.getOrPut(directory.absolutePath) {
            ContextPruningRepository(assistantId.toString(), conversationId.toString(),
                FileContextPruningStorage(directory, context.applicationContext.filesDir))
        }
    }
}

/** Dedicated small policy file; it never rewrites the conversation, its attachments or backups. */
internal class FileContextPruningStorage(directory: File, trustedBase: File) : ContextPruningStorage {
    companion object { private val locks = ConcurrentHashMap<String, ReentrantLock>() }
    // Android can expose system-owned aliases above Context.filesDir/cacheDir. Resolve only the
    // caller-supplied trusted base, never a business child which may itself be an unsafe link.
    private val boundary = trustedBase.canonicalFile.toPath().toAbsolutePath().normalize()
    private val root = anchoredContextPruningPath(directory, trustedBase, boundary)
    private val monitor = locks.getOrPut(root.toString()) { ReentrantLock() }
    private val state = root.resolve("policy.json")

    override fun <T> locked(write: Boolean, block: () -> T): T = monitor.withLock {
        checkPath()
        if (write) { Files.createDirectories(root); checkPath() }
        // Merely viewing a never-used conversation does not create policy directories/files.
        if (!Files.exists(root, NOFOLLOW_LINKS)) return@withLock block()
        check(Files.isDirectory(root, NOFOLLOW_LINKS)) { "context_pruning_unreadable" }
        val lock = root.resolve(".lock")
        check(!Files.isSymbolicLink(lock)) { "context_pruning_unreadable" }
        FileChannel.open(lock, CREATE, WRITE, NOFOLLOW_LINKS).use { channel -> channel.lock().use { block() } }
    }

    override fun read(): String? {
        checkPath()
        if (!Files.exists(state, NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(state, NOFOLLOW_LINKS) && Files.size(state) <= MAX_CONTEXT_PRUNING_BYTES) { "context_pruning_unreadable" }
        return Files.newByteChannel(state, setOf(NOFOLLOW_LINKS)).use { channel ->
            val size = channel.size()
            check(size in 0..MAX_CONTEXT_PRUNING_BYTES.toLong()) { "context_pruning_unreadable" }
            val bytes = ByteBuffer.allocate((size + 1).toInt())
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) Unit
            check(bytes.position() <= MAX_CONTEXT_PRUNING_BYTES && bytes.hasRemaining()) { "context_pruning_unreadable" }
            bytes.flip()
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(bytes).toString()
        }
    }

    override fun write(value: String) {
        checkPath()
        val bytes = value.toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_CONTEXT_PRUNING_BYTES) { "context_pruning_capacity_reached" }
        Files.createDirectories(root)
        checkPath()
        // locked(write=true) already holds the dedicated process lock, including the first write.
        val temporary = root.resolve(".pending-${Uuid.random()}")
        try {
            FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            check(!Files.isSymbolicLink(state)) { "context_pruning_unreadable" }
            Files.move(temporary, state, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            // Exact app-generated temporary only; never delete the old published policy.
            Files.deleteIfExists(temporary)
        }
    }

    private fun checkPath() {
        var item: java.nio.file.Path? = root
        while (item != null) {
            check(!Files.isSymbolicLink(item)) { "context_pruning_unreadable" }
            if (Files.exists(item, NOFOLLOW_LINKS)) check(Files.isDirectory(item, NOFOLLOW_LINKS)) { "context_pruning_unreadable" }
            if (item == boundary) break
            item = item.parent
        }
    }
}

/** Keep the relative child lexical until it has been checked with NOFOLLOW_LINKS. */
internal fun anchoredContextPruningPath(
    directory: File,
    trustedBase: File,
    boundary: java.nio.file.Path = trustedBase.canonicalFile.toPath().toAbsolutePath().normalize(),
): java.nio.file.Path {
    val lexicalBase = trustedBase.toPath().toAbsolutePath().normalize()
    val child = directory.toPath().toAbsolutePath().normalize()
    val relative = when {
        child.startsWith(lexicalBase) -> lexicalBase.relativize(child)
        child.startsWith(boundary) -> boundary.relativize(child)
        else -> error("context_pruning_unreadable")
    }
    return boundary.resolve(relative)
}
