package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointJournal
import me.rerere.rikkahub.data.ai.checkpoint.ContinuationConflictCopies
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

/** Exact conversation recovery copies, not arbitrary noBackup storage or execution permission.
 * Export preserves bounded ORIGINAL bytes even when damaged; import validates them without
 * merging history or invoking a journal's recover/delete methods. Normal startup still owns that.
 */
internal object ConversationRecoveryBackup {
    const val GENERATION = "orbis-generation-journal-v1"
    const val CONTINUATION = "orbis-human-continuation-v1"
    const val CONFLICTS = ContinuationConflictCopies.DIRECTORY
    const val MAX_BYTES = GenerationCheckpointJournal.MAX_BYTES
    private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val uuid = Regex(UUID)
    private val activePath = Regex("($GENERATION|$CONTINUATION)/($UUID)\\.json")
    private val conflictPath = Regex("($CONFLICTS)/($UUID)--([0-9a-f]{64})\\.json")
    private val namespaces = listOf(GENERATION, CONTINUATION, CONFLICTS)
    private fun matchPath(name: String) = activePath.matchEntire(name) ?: conflictPath.matchEntire(name)
    private val digest = Regex("[0-9a-f]{64}")
    private val json = Json { encodeDefaults = true }
    const val INVALID = "聊天恢复副本校验未通过，本次备份未应用；原聊天、副本和备份包均保留。"
    const val CONFLICT = "本机已有不同的聊天恢复副本，本次备份未应用；两份记录均保留，未覆盖或合并。"
    const val CHANGED = "备份期间聊天数据库仍在更新，本次未生成备份；原聊天和恢复副本未改动，请稍后重试。"

    /** The version reader MUST use one dedicated, non-writing SQLite connection. A Room
     * commit between its snapshot and journal capture can retire the only newer tail.
     * Refuse that mixed snapshot instead of blocking live writers or silently omitting it.
     */
    fun <T> requireStableDatabase(readVersion: () -> Long, capture: () -> T): T {
        val before = readVersion()
        val result = capture()
        check(readVersion() == before) { CHANGED }
        return result
    }

    fun publicError(message: String?): String? = message?.takeIf { it == INVALID || it == CONFLICT }

    fun inNamespace(name: String) = name.substringBefore('/') in namespaces
    fun maxBytes(name: String): Int? = if (matchPath(name) != null) MAX_BYTES else null

    fun exactFile(root: File, name: String): File {
        require(matchPath(name) != null) { INVALID }
        val base = root.canonicalFile // Android's trusted /data/user/0 and /data/data aliases are equivalent.
        val folder = File(base, name.substringBefore('/'))
        val file = File(folder, name.substringAfter('/'))
        require(!Files.isSymbolicLink(folder.toPath()) && folder.canonicalFile.parentFile == base &&
            (!folder.exists() || folder.isDirectory) && !Files.isSymbolicLink(file.toPath()) &&
            file.canonicalFile.parentFile == folder && (!file.exists() || file.isFile)) { INVALID }
        return file
    }

    private fun sidecar(file: File, suffix: String): File = File(file.path + suffix).also {
        require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == file.parentFile &&
            (!it.exists() || it.isFile)) { INVALID }
    }

    /** AtomicFile's legacy .bak is committed; .new alone is not. Reading never renames/deletes it. */
    fun readCommitted(root: File, namespace: String, id: String): ByteArray? {
        val file = exactFile(root, "$namespace/$id.json")
        val backup = sidecar(file, ".bak")
        val source = when {
            Files.exists(backup.toPath(), NOFOLLOW_LINKS) -> backup
            Files.exists(file.toPath(), NOFOLLOW_LINKS) -> file
            else -> return null
        }
        return readBounded(source)
    }

    fun committedSize(root: File, namespace: String, id: String): Long {
        val file = exactFile(root, "$namespace/$id.json")
        val backup = sidecar(file, ".bak")
        return if (backup.exists()) backup.length() else file.length()
    }

    private fun readBounded(file: File): ByteArray {
        require(file.isFile && file.length() in 0..MAX_BYTES.toLong()) { INVALID }
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() <= MAX_BYTES - count) { INVALID }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    fun stageSnapshot(root: File, staging: File, owners: Map<String, String>,
        checkCancelled: () -> Unit = {}, beforeFile: (String, Long) -> Unit = { _, _ -> },
    ): List<Pair<String, File>> = buildList {
        fun stage(name: String, bytes: ByteArray) {
            checkCancelled()
            beforeFile(name, bytes.size.toLong())
            val target = exactFile(staging, name)
            val parent = checkNotNull(target.parentFile)
            check(!target.exists() && (parent.isDirectory || parent.mkdirs())) { INVALID }
            ArchiveCapacity.requireSpace(parent.usableSpace, bytes.size.toLong())
            FileOutputStream(target).use { it.write(bytes); it.fd.sync() }
            add(name to target)
        }
        for (id in owners.keys.sorted()) {
            // Noncanonical legacy/imported IDs cannot be native journal names.
            if (!uuid.matches(id)) continue
            for (namespace in listOf(GENERATION, CONTINUATION)) {
                checkCancelled()
                val bytes = readCommitted(root, namespace, id) ?: continue
                stage("$namespace/$id.json", bytes)
            }
        }
        // Archived conflicts are inert, but may contain the only remaining copy of old turns.
        // Include committed .bak entries, never an uncommitted .new by itself.
        val folder = File(root.canonicalFile, CONFLICTS)
        require(!Files.isSymbolicLink(folder.toPath())) { INVALID }
        if (folder.exists()) {
            require(folder.isDirectory) { INVALID }
            val names = requireNotNull(folder.listFiles()).mapNotNull { file ->
                val name = "$CONFLICTS/${file.name.removeSuffix(".bak")}"
                conflictPath.matchEntire(name)?.takeIf { it.groupValues[2] in owners }?.value
            }.distinct().sorted()
            for (name in names) {
                val key = name.substringAfter('/').removeSuffix(".json")
                stage(name, checkNotNull(readCommitted(root, CONFLICTS, key)) { CHANGED })
            }
        }
    }

    /** Checks container integrity/identity only. A prefix/epoch mismatch remains for native recovery. */
    private fun validate(name: String, bytes: ByteArray, owners: Map<String, String>) {
        try {
            val match = requireNotNull(matchPath(name))
            require(bytes.size in 1..MAX_BYTES)
            if (match.groupValues[1] == CONFLICTS)
                require(match.groupValues[3] == ContinuationConflictCopies.hash(bytes))
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val envelope = json.parseToJsonElement(text).jsonObject
            val record = envelope.getValue("record").jsonObject
            fun string(key: String) = record.getValue(key).jsonPrimitive.content
            fun count(key: String) = record.getValue(key).jsonPrimitive.int.also { require(it >= 0) }
            val id = string("conversationId")
            val human = match.groupValues[1] != GENERATION
            val owner = string(if (human) "assistantId" else "ownerId")
            require(id == match.groupValues[2] && uuid.matches(owner) && owners[id] == owner &&
                uuid.matches(string("runId")) && record.getValue("epoch").jsonPrimitive.long >= 0)
            require(record.getValue("version").jsonPrimitive.int in 1..2)
            require(digest.matches(string("prefixHash")))
            val prefix = count("prefixCount")
            if (human) {
                record["savedAtMillis"]?.jsonPrimitive?.long
                record["roomSnapshotHashes"]?.jsonArray?.let { hashes ->
                    require(hashes.size <= 2 && hashes.all { digest.matches(it.jsonPrimitive.content) })
                }
            } else {
                require(count("suffixCount") <= Int.MAX_VALUE - prefix)
                require(digest.matches(string("suffixHash")) && digest.matches(string("baseTailHash")))
                record.getValue("anchors").jsonArray.forEach { anchor ->
                    require(uuid.matches(anchor.jsonObject.getValue("nodeId").jsonPrimitive.content))
                    require(anchor.jsonObject.getValue("messageIds").jsonArray.all { uuid.matches(it.jsonPrimitive.content) })
                }
                record["tools"]?.jsonArray?.forEach { element ->
                    val tool = element.jsonObject
                    require(tool.getValue("callId").jsonPrimitive.content.length in 1..256 &&
                        tool.getValue("name").jsonPrimitive.content.length in 1..512 &&
                        digest.matches(tool.getValue("inputHash").jsonPrimitive.content) &&
                        tool.getValue("status").jsonPrimitive.content in setOf("STARTED", "COMPLETED"))
                    tool["completedHash"]?.takeUnless { it is JsonNull }?.let { require(digest.matches(it.jsonPrimitive.content)) }
                }
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(record.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            require(envelope.getValue("sha256").jsonPrimitive.content == hash)
            for (key in if (human) listOf("baseTail", "tail") else listOf("tail")) {
                val nodes = json.decodeFromJsonElement<List<MessageNode>>(record.getValue(key))
                require(nodes.map { it.id }.distinct().size == nodes.size)
                require(nodes.all { node -> node.messages.isNotEmpty() && node.selectIndex in node.messages.indices &&
                    node.messages.map { it.id }.distinct().size == node.messages.size })
            }
        } catch (_: Exception) { throw IllegalArgumentException(INVALID) }
    }

    private fun staged(payload: File): List<Pair<String, File>> = buildList {
        val rawRoot = File(payload, "no-backup")
        require(!Files.isSymbolicLink(rawRoot.toPath())) { INVALID }
        val root = rawRoot.canonicalFile
        for (namespace in namespaces) {
            val folder = File(root, namespace)
            require(!Files.isSymbolicLink(folder.toPath())) { INVALID }
            if (!Files.exists(folder.toPath(), NOFOLLOW_LINKS)) continue
            require(folder.isDirectory) { INVALID }
            for (file in requireNotNull(folder.listFiles()) { INVALID }) {
                val name = "$namespace/${file.name}"
                add(name to exactFile(root, name).also { require(it.isFile) { INVALID } })
            }
        }
    }

    fun hasStaged(payload: File): Boolean = staged(payload).isNotEmpty()

    fun validateStaged(payload: File, owners: Map<String, String>) {
        staged(payload).forEach { (name, file) -> validate(name, readBounded(file), owners) }
    }

    /** Before ANY live move, reject differing copies; unrelated IDs and legacy archives are untouched.
     * Recheck at startup, not only when staging: a newer tail may arrive before the human restarts.
     */
    fun requireNoConflicts(payload: File, liveRoot: File) {
        staged(payload).forEach { (name, incoming) ->
            val bytes = readBounded(incoming)
            val target = exactFile(liveRoot, name)
            require(!Files.exists(sidecar(target, ".new").toPath(), NOFOLLOW_LINKS)) { CONFLICT }
            for (existing in listOf(target, sidecar(target, ".bak"))) {
                if (Files.exists(existing.toPath(), NOFOLLOW_LINKS))
                    require(readBounded(existing).contentEquals(bytes)) { CONFLICT }
            }
        }
    }
}
