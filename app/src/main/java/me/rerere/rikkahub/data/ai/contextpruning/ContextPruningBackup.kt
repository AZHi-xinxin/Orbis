package me.rerere.rikkahub.data.ai.contextpruning

import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.uuid.Uuid

/** Only the reversible policy is portable. No tool is run and no message body is rewritten. */
internal object ContextPruningBackup {
    const val DIRECTORY = "orbis-context-pruning"
    private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val policyPath = Regex("$DIRECTORY/($UUID_PATTERN)/($UUID_PATTERN)/policy\\.json")

    fun maxBytes(path: String): Int? = if (policyPath.matches(path)) MAX_CONTEXT_PRUNING_BYTES else null

    /** Uses the exported DB's owner map, not a directory walk: deleted/consultation windows stay out. */
    fun stageSnapshot(
        liveFiles: File,
        staging: File,
        conversationOwners: Map<String, String>,
        checkCancelled: () -> Unit = {},
        beforeFile: (String, Long) -> Unit = { _, _ -> },
    ): List<Pair<String, File>> = buildList {
        for ((conversationId, assistantId) in conversationOwners.toSortedMap()) {
            checkCancelled()
            // Legacy imported IDs that are not canonical UUIDs cannot have one of our policies.
            if (!canonicalUuid(conversationId) || !canonicalUuid(assistantId)) continue
            val path = "$DIRECTORY/$assistantId/$conversationId/policy.json"
            val source = exactPolicyFile(liveFiles, path)
            if (!Files.exists(source.toPath(), NOFOLLOW_LINKS)) continue
            val storage = FileContextPruningStorage(source.parentFile!!, liveFiles)
            val bytes = storage.locked {
                val text = storage.read() ?: error("context_pruning_backup_invalid")
                text.toByteArray(Charsets.UTF_8).also { decode(path, it) }
            }
            beforeFile(path, bytes.size.toLong())
            checkCancelled()
            val target = exactPolicyFile(staging, path)
            // The destination is a fresh private staging directory. Reuse atomic policy publication.
            val targetStorage = FileContextPruningStorage(target.parentFile!!, staging)
            targetStorage.locked(write = true) { targetStorage.write(bytes.toString(Charsets.UTF_8)) }
            checkCancelled()
            add(path to target)
        }
    }

    fun validate(path: String, bytes: ByteArray) { decode(path, bytes) }

    fun validateStaged(payload: File) {
        stagedPolicies(payload).forEach { (path, file) -> decode(path, readBounded(file)) }
    }

    fun hasStagedPolicies(payload: File): Boolean = stagedPolicies(payload).isNotEmpty()

    /** Called after staged DB migration and again immediately before the restore journal is written. */
    fun validateOwnership(payload: File, conversationOwners: Map<String, String>) {
        stagedPolicies(payload).forEach { (path, file) ->
            val state = decode(path, readBounded(file))
            require(conversationOwners[state.conversationId] == state.assistantId) {
                "context_pruning_backup_owner"
            }
        }
    }

    /** Restore is monotonic: an old backup cannot hide a batch the human has already restored. */
    fun merge(path: String, current: ByteArray?, incoming: ByteArray): ByteArray {
        val backup = decode(path, incoming)
        if (current == null) return incoming.copyOf()
        val before = decode(path, current)
        val indexed = before.batches.associateBy { it.id }
        val backupIndexed = backup.batches.associateBy { it.id }
        require(backup.batches.all { batch ->
            indexed[batch.id]?.let { local -> local.copy(restored = batch.restored) == batch } != false
        }) { "context_pruning_backup_conflict" }
        val merged = before.copy(batches = before.batches.map { local ->
            if (backupIndexed[local.id]?.restored == true) local.copy(restored = true) else local
        } + backup.batches.filter { it.id !in indexed })
        // Duplicate plan IDs, combined capacity and every mark are checked before any live write.
        val bytes = contextPruningJson.encodeToString(merged).toByteArray(Charsets.UTF_8)
        try { decode(path, bytes) } catch (_: Exception) { error("context_pruning_backup_conflict") }
        return bytes
    }

    /** Startup only. Writes are to staging only; any conflict aborts before the live-file journal. */
    fun prepareBeforeJournal(payload: File, liveFiles: File) {
        stagedPolicies(payload).forEach { (path, source) ->
            val target = exactPolicyFile(liveFiles, path)
            val current = if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) readBounded(target) else null
            val bytes = merge(path, current, readBounded(source))
            // Do not put a .lock in payload: every payload file is installed by PendingRestore.
            val storage = FileContextPruningStorage(source.parentFile!!, payload)
            storage.write(bytes.toString(Charsets.UTF_8))
        }
    }

    /** Also used when replaying the restore journal, so an internal symlink is not a valid target. */
    fun exactPolicyFile(root: File, path: String): File {
        require(policyPath.matches(path)) { "context_pruning_backup_path" }
        val rootPath = root.canonicalFile.toPath().toAbsolutePath().normalize()
        val target = rootPath.resolve(path)
        require(target.startsWith(rootPath) && target != rootPath) { "context_pruning_backup_path" }
        var cursor: java.nio.file.Path? = target
        while (cursor != null) {
            require(!Files.isSymbolicLink(cursor)) { "context_pruning_backup_path" }
            if (Files.exists(cursor, NOFOLLOW_LINKS)) {
                require(if (cursor == target) Files.isRegularFile(cursor, NOFOLLOW_LINKS)
                    else Files.isDirectory(cursor, NOFOLLOW_LINKS)) { "context_pruning_backup_path" }
            }
            if (cursor == rootPath) break
            cursor = cursor.parent
        }
        return target.toFile()
    }

    private fun stagedPolicies(payload: File): List<Pair<String, File>> {
        val files = File(payload, "files")
        val directory = File(files, DIRECTORY)
        requireNoSymlinkDirectory(directory, payload)
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) return emptyList()
        val result = mutableListOf<Pair<String, File>>()
        children(directory).forEach { owner ->
            require(canonicalUuid(owner.name)) { "context_pruning_backup_path" }
            requireNoSymlinkDirectory(owner, payload)
            children(owner).forEach { conversation ->
                require(canonicalUuid(conversation.name)) { "context_pruning_backup_path" }
                requireNoSymlinkDirectory(conversation, payload)
                val entries = children(conversation)
                require(entries.size == 1 && entries.single().name == "policy.json") { "context_pruning_backup_path" }
                val path = "$DIRECTORY/${owner.name}/${conversation.name}/policy.json"
                result += path to exactPolicyFile(files, path)
                require(result.size <= 20_000) { "context_pruning_backup_invalid" }
            }
        }
        return result
    }

    private fun children(directory: File): List<File> = Files.newDirectoryStream(directory.toPath()).use { stream ->
        buildList {
            for (item in stream) {
                require(size < 20_000) { "context_pruning_backup_invalid" }
                add(item.toFile())
            }
        }
    }

    private fun requireNoSymlinkDirectory(directory: File, trustedBase: File) {
        val boundary = trustedBase.canonicalFile.toPath().toAbsolutePath().normalize()
        var cursor: java.nio.file.Path? = anchoredContextPruningPath(directory, trustedBase, boundary)
        while (cursor != null) {
            require(!Files.isSymbolicLink(cursor) && (!Files.exists(cursor, NOFOLLOW_LINKS) ||
                Files.isDirectory(cursor, NOFOLLOW_LINKS))) { "context_pruning_backup_path" }
            if (cursor == boundary) break
            cursor = cursor.parent
        }
    }

    private fun decode(path: String, bytes: ByteArray): ContextPruningState {
        val match = policyPath.matchEntire(path) ?: error("context_pruning_backup_path")
        require(bytes.size in 1..MAX_CONTEXT_PRUNING_BYTES) { "context_pruning_backup_invalid" }
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            require(contextPruningJsonDepthAllowed(text))
            contextPruningJson.decodeFromString<ContextPruningState>(text).also {
                validateContextPruningState(it, match.groupValues[1], match.groupValues[2])
            }
        } catch (_: Exception) { error("context_pruning_backup_invalid") }
    }

    private fun readBounded(file: File): ByteArray {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) &&
            Files.size(file.toPath()) in 1..MAX_CONTEXT_PRUNING_BYTES.toLong()) { "context_pruning_backup_invalid" }
        return Files.newByteChannel(file.toPath(), setOf(NOFOLLOW_LINKS)).use { channel ->
            val bytes = ByteBuffer.allocate(MAX_CONTEXT_PRUNING_BYTES + 1)
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) Unit
            require(bytes.position() in 1..MAX_CONTEXT_PRUNING_BYTES) { "context_pruning_backup_invalid" }
            bytes.array().copyOf(bytes.position())
        }
    }

    private fun canonicalUuid(value: String): Boolean = value.length == 36 &&
        runCatching { Uuid.parse(value).toString() == value }.getOrDefault(false)
}
