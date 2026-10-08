package me.rerere.rikkahub.data.orbis.memory

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.db.AppDatabase
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** No legacy/global memory lookup, migration, network, tool dispatch or human content browser. */
class OrbisMemoryRepository(private val database: AppDatabase) {
    private val dao get() = database.orbisMemoryDao()
    private val noteJson = Json { encodeDefaults = true }

    suspend fun execute(assistantId: String, request: JsonObject, operationKey: String): JsonObject {
        return try {
            owner(assistantId)
            val action = request.string("action") ?: request.string("op") ?: "store"
            if (request.containsKey("action") && request.containsKey("op") && request.string("op") != action)
                refuse("memory_action_conflict")
            when (action) {
                "read" -> database.withTransaction { read(assistantId, request) }
                "history" -> database.withTransaction { history(assistantId, request) }
                "store", "update", "set_state", "delete", "restore" -> {
                    requireKey(operationKey)
                    mutate(assistantId, request, "tool:$operationKey", action)
                }
                else -> refuse("memory_action_unsupported")
            }
        } catch (failure: MemoryRequestFailure) {
            errorResult(failure.code, failure.field)
        }
    }

    /** Safe to call inside the caller's Room transaction. Disk/DB failures still propagate. */
    suspend fun storeCompaction(assistantId: String, eventId: String, body: String): JsonObject {
        OrbisMemoryBudget.compactionCapacityError(body)?.let { return errorResult(it) }
        return try {
            owner(assistantId)
            requireKey(eventId)
            mutate(assistantId, buildJsonObject {
                put("action", "store"); put("body", body); put("state", "static")
            }, "compaction:$eventId", "store")
        } catch (failure: MemoryRequestFailure) { errorResult(failure.code, failure.field) }
    }

    fun compactionCapacityError(body: String): String? = OrbisMemoryBudget.compactionCapacityError(body)

    suspend fun injectionCandidates(assistantId: String, limit: Int = 50, offset: Int = 0): List<OrbisMemoryInjectionCandidate> {
        owner(assistantId); page(limit, offset, 100)
        return dao.candidates(assistantId, limit, offset).map { row ->
            OrbisMemoryInjectionCandidate(row.id, row.state, row.summary, row.body, row.pinText,
                strings(row.tags), strings(row.keywords), row.important, row.minIntervalTurns,
                row.revision, row.createdAt, row.updatedAt)
        }
    }

    suspend fun eligibleInjectionIds(assistantId: String, ids: List<String>): Set<String> {
        owner(assistantId)
        if (ids.size > 500) refuse("memory_page_limit")
        if (ids.isEmpty()) return emptySet()
        return dao.eligibleInjectionIds(assistantId, ids).toSet()
    }

    suspend fun metadataSnapshot(assistantId: String, limit: Int = 100, offset: Int = 0,
        includeDeleted: Boolean = false): List<OrbisMemoryMetadata> {
        owner(assistantId); page(limit, offset, 500)
        return dao.metadata(assistantId, includeDeleted, limit, offset).map { it.model() }
    }

    suspend fun stats(assistantId: String): OrbisMemoryStats { owner(assistantId); return dao.stats(assistantId) }
    fun observeStats(assistantId: String): Flow<OrbisMemoryStats> { owner(assistantId); return dao.observeStats(assistantId) }

    /** Owns neither the stream nor its destination. A partial failed export is NOT a success. */
    suspend fun export(assistantId: String, output: OutputStream) = withContext(Dispatchers.IO) {
        owner(assistantId)
        database.withTransaction {
            fun write(text: String) { output.write(text.toByteArray(Charsets.UTF_8)) }
            write("{\"schema\":\"orbis_memory_export_v1\",\"assistantId\":${JsonPrimitive(assistantId)},\"notes\":[")
            var after = ""
            var first = true
            while (true) {
                val notes = dao.exportNotes(assistantId, after, 4)
                if (notes.isEmpty()) break
                notes.forEach {
                    if (!first) write(",")
                    write(noteJson.encodeToString(it.model())); first = false; after = it.id
                }
            }
            write("],\"revisions\":[")
            after = ""; first = true
            var afterRevision = 0
            while (true) {
                val revisions = dao.exportRevisions(assistantId, after, afterRevision, 4)
                if (revisions.isEmpty()) break
                revisions.forEach {
                    if (!first) write(",")
                    write(noteJson.encodeToString(it.note.model())); first = false
                    after = it.note.id; afterRevision = it.note.revision
                }
            }
            write("]}")
            output.flush()
        }
    }

    private suspend fun mutate(assistantId: String, request: JsonObject, key: String, action: String): JsonObject =
        database.withTransaction {
            val digest = sha(canonical(request).toString())
            // The assistant scope is part of the PK. Never replay another assistant's receipt.
            val keyHash = sha(key)
            dao.operation(assistantId, keyHash)?.let {
                if (it.payloadHash != digest) refuse("memory_idempotency_conflict")
                return@withTransaction Json.parseToJsonElement(it.resultJson).jsonObject
            }
            val now = System.currentTimeMillis()
            val old = if (action == "store") null else requireNote(assistantId, request.requiredString("id"))
            request.optionalInt("expectedRevision")?.let { expected ->
                if (old?.revision != expected) refuse("memory_revision_conflict")
            }
            var next = when (action) {
                "store" -> {
                    if (request.containsKey("id")) refuse("memory_store_id_is_host_generated")
                    OrbisMemoryNote(id = UUID.randomUUID().toString(), assistantId = assistantId,
                        body = request.requiredString("body"), summary = request.string("summary").orEmpty(),
                        state = request.string("state") ?: "static", tags = request.stringList("tags") ?: emptyList(),
                        keywords = request.stringList("keywords") ?: emptyList(), important = request.optionalBoolean("important") ?: false,
                        minIntervalTurns = request.optionalInt("minIntervalTurns") ?: 0, createdAt = now, updatedAt = now)
                }
                "update" -> checkNotNull(old).model().let { base ->
                    if (base.deleted) refuse("memory_note_deleted")
                    base.copy(body = request.string("body") ?: base.body, summary = request.string("summary") ?: base.summary,
                        tags = request.stringList("tags") ?: base.tags, keywords = request.stringList("keywords") ?: base.keywords,
                        important = request.optionalBoolean("important") ?: base.important,
                        minIntervalTurns = request.optionalInt("minIntervalTurns") ?: base.minIntervalTurns).let {
                        request.string("state")?.let { state -> transition(it, state) } ?: it
                    }
                }
                "set_state" -> checkNotNull(old).model().let {
                    if (it.deleted) refuse("memory_note_deleted")
                    transition(it, request.requiredString("state"))
                }
                "delete" -> checkNotNull(old).model().copy(deleted = true)
                "restore" -> {
                    val current = checkNotNull(old).model()
                    val restored = request.optionalInt("revision")?.let { revision ->
                        dao.revision(assistantId, current.id, revision)?.note?.model()
                            ?: refuse("memory_revision_not_found")
                    } ?: if (current.state == "paused") transition(current, "resume") else current
                    restored.copy(deleted = false, createdAt = current.createdAt)
                }
                else -> refuse("memory_action_unsupported")
            }.let { note ->
                validate(note.copy(summary = note.summary.takeUnless { it.isBlank() }.orEmpty(),
                    updatedAt = now, revision = (old?.revision ?: 0) + 1))
            }
            // Validate the whole post-write pinned set before touching any table.
            val pins = dao.pins(assistantId).filterNot { it.id == next.id }.map { it.text }.toMutableList()
            if (!next.deleted && next.state == "pinned") pins += OrbisMemoryBudget.pinText(next)
            val pinFailure = if (!next.deleted && next.state == "pinned")
                OrbisMemoryBudget.pinEligibilityError(next.summary, next.body) else null
            val promotionFailure = pinFailure ?: if (!OrbisMemoryBudget.pinsFit(pins)) "memory_pin_budget_exceeded" else null
            if (promotionFailure != null) {
                if (action == "store" && next.state == "pinned") {
                    // Store and promotion are distinct outcomes. Preserve the complete new original,
                    // but never claim a failed pin succeeded or silently truncate its content.
                    next = next.copy(state = "static", previousState = "static")
                } else refuse(promotionFailure)
            }
            val entity = next.entity()
            if (old == null) dao.insert(entity) else check(dao.update(entity) == 1)
            dao.insertRevision(OrbisMemoryRevisionEntity(entity))
            val similar = if (action in setOf("store", "update")) similar(assistantId, next) else emptyList()
            val result = buildJsonObject {
                put("ok", true); put("id", next.id); put("revision", next.revision); put("state", next.state)
                put("deleted", next.deleted)
                put("similar", JsonArray(similar.map { Json.encodeToJsonElement(it) }))
                put("merged", false)
                if (action == "store") put("saved", true)
                if (action == "store" && request.string("state") == "pinned") {
                    put("promotionSucceeded", promotionFailure == null)
                    promotionFailure?.let {
                        put("promotionCode", it)
                        put("promotionMessage", "原文已完整保存为静态记忆；${errorMessage(it)}")
                    }
                }
                if (next.state == "conditional" && OrbisMemoryBudget.codepoints(next.body) > OrbisMemoryBudget.MAX_INJECTION_CODEPOINTS &&
                    (next.summary.isEmpty() || OrbisMemoryBudget.codepoints(next.summary) > OrbisMemoryBudget.MAX_CANDIDATE_SUMMARY_CODEPOINTS)) {
                    put("injectionNotice", "memory_short_summary_required_for_injection")
                }
            }
            dao.insertOperation(OrbisMemoryOperationEntity(assistantId, keyHash, digest, result.toString(), now))
            result
        }

    private suspend fun similar(assistantId: String, note: OrbisMemoryNote): List<OrbisMemoryMetadata> {
        // Bounded lexical hint only. Never merges content or changes an existing note.
        val term = (note.keywords + note.tags + listOf(note.summary.ifBlank { note.body }.take(2048)))
            .asSequence().map { it.trim().take(32) }.firstOrNull { it.length >= 2 } ?: return emptyList()
        return dao.similar(assistantId, note.id, term).map { it.model() }
    }

    private suspend fun read(assistantId: String, request: JsonObject): JsonObject {
        if (request.optionalBoolean("history") == true) return history(assistantId, request)
        val includeDeleted = request.optionalBoolean("includeDeleted") ?: request.optionalBoolean("deleted") ?: false
        val id = request.string("id")
        if (id != null) {
            val row = requireNote(assistantId, id)
            if (row.deleted && !includeDeleted) refuse("memory_note_deleted")
            return buildJsonObject { put("ok", true); put("note", noteJson.encodeToJsonElement(row.model())) }
        }
        val limit = request.optionalInt("limit") ?: 20
        val offset = request.optionalInt("offset") ?: 0
        page(limit, offset, 50)
        val query = request.string("query").orEmpty()
        if (query.length > 512) refuse("memory_query_limit")
        return boundedPage(offset, limit) { pageOffset ->
            dao.search(assistantId, query, includeDeleted, 1, pageOffset).firstOrNull()?.model()
        }
    }

    private suspend fun history(assistantId: String, request: JsonObject): JsonObject {
        val id = request.requiredString("id")
        requireNote(assistantId, id) // Not-found is identical for missing and foreign IDs.
        val limit = request.optionalInt("limit") ?: 20
        val offset = request.optionalInt("offset") ?: 0
        page(limit, offset, 50)
        return boundedPage(offset, limit) { pageOffset ->
            dao.history(assistantId, id, 1, pageOffset).firstOrNull()?.note?.model()
        }
    }

    /** Read one row at a time; even large originals cannot inflate a 50-row cursor/list. */
    private suspend fun boundedPage(offset: Int, limit: Int, next: suspend (Int) -> OrbisMemoryNote?): JsonObject {
        val items = mutableListOf<JsonElement>()
        var bytes = 0
        var more = false
        while (items.size < limit) {
            val row = next(offset + items.size) ?: break
            val value = noteJson.encodeToJsonElement(row)
            val size = value.toString().toByteArray(Charsets.UTF_8).size
            // A single complete note is always readable, even if JSON escaping expands it.
            if (items.isNotEmpty() && bytes + size > 2 * 1024 * 1024) { more = true; break }
            items += value; bytes += size
        }
        if (!more && items.size == limit) more = next(offset + items.size) != null
        return buildJsonObject {
            put("ok", true); put("items", JsonArray(items)); put("offset", offset)
            put("nextOffset", if (more) JsonPrimitive(offset + items.size) else JsonNull)
        }
    }

    private suspend fun requireNote(assistantId: String, id: String): OrbisMemoryEntity {
        if (id.length !in 1..128) refuse("memory_note_not_found")
        return dao.get(assistantId, id) ?: refuse("memory_note_not_found")
    }

    private fun transition(note: OrbisMemoryNote, state: String): OrbisMemoryNote = when (state) {
        "paused" -> note.copy(state = state, previousState = if (note.state == "paused") note.previousState else note.state)
        "resume" -> note.copy(state = if (note.state == "paused") note.previousState else note.state)
        "static", "conditional", "pinned" -> note.copy(state = state, previousState = state)
        else -> refuse("memory_state_invalid")
    }

    private fun validate(note: OrbisMemoryNote): OrbisMemoryNote {
        OrbisMemoryBudget.compactionCapacityError(note.body)?.let { refuse(it) }
        if (note.summary.toByteArray(Charsets.UTF_8).size > OrbisMemoryBudget.MAX_SUMMARY_BYTES)
            refuse("memory_capacity_exceeded")
        if (note.state !in STATES || note.previousState !in (STATES - "paused")) refuse("memory_state_invalid")
        if (note.minIntervalTurns !in 0..1_000_000 || note.revision < 1) refuse("memory_interval_invalid")
        listOf(note.tags, note.keywords).forEach { values ->
            if (values.size > 64 || values.any { it.isBlank() || it.toByteArray(Charsets.UTF_8).size > 512 })
                refuse("memory_labels_limit")
        }
        return note
    }

    private fun owner(assistantId: String) {
        if (runCatching { UUID.fromString(assistantId).toString() == assistantId }.getOrDefault(false).not())
            refuse("memory_owner_invalid")
    }

    private fun requireKey(key: String) { if (key.isBlank() || key.length > 512) refuse("memory_operation_key_invalid") }
    private fun page(limit: Int, offset: Int, max: Int) {
        if (limit !in 1..max || offset !in 0..1_000_000) refuse("memory_page_limit")
    }

    private fun OrbisMemoryEntity.model() = OrbisMemoryNote(id, assistantId, body, summary, state, previousState,
        strings(tags), strings(keywords), important, minIntervalTurns, createdAt, updatedAt, revision, deleted)
    private fun OrbisMemoryNote.entity() = OrbisMemoryEntity(assistantId, id, body, summary,
        OrbisMemoryBudget.codepoints(body), OrbisMemoryBudget.codepoints(summary), state, previousState,
        Json.encodeToString(tags), Json.encodeToString(keywords), important, minIntervalTurns, createdAt, updatedAt, revision, deleted)
    private fun OrbisMemoryMetadataRow.model() = OrbisMemoryMetadata(id, state, strings(tags), createdAt, updatedAt, revision, deleted)

    private class MemoryRequestFailure(val code: String, val field: String?) : IllegalArgumentException(code)
    private fun refuse(code: String, field: String? = null): Nothing = throw MemoryRequestFailure(code, field)
    private fun errorResult(code: String, field: String? = null) = buildJsonObject {
        put("ok", false); put("code", code); put("message", errorMessage(code))
        field?.let { put("field", it) }
    }
    private fun errorMessage(code: String): String = when (code) {
        "memory_body_required" -> "请提供非空原文 body；不会保存空记录。"
        "memory_capacity_exceeded" -> "原文最多 512 KiB，摘要最多 64 KiB；请分条保存，内容不会被截断。"
        "memory_pin_summary_required" -> "超过 300 字的原文需要另填摘要才能置顶；原文不会被截断。"
        "memory_pin_budget_exceeded" -> "置顶最多 7 条，含格式开销合计约 350 tokens；请缩短摘要或先将其他置顶改为静态。"
        "memory_field_required" -> "缺少必要字段；请补全 field 指定的字段后重试。"
        "memory_field_invalid" -> "字段类型不正确；请修正 field 指定的字段后重试。"
        "memory_state_invalid" -> "state 只能是 static、conditional、pinned 或 paused；set_state 还支持 resume。"
        "memory_note_not_found" -> "当前助手没有此记录；请先 read 查询正确 id。"
        "memory_note_deleted" -> "记录已软删除；可 restore 恢复，或 read 设置 includeDeleted=true 查看。"
        "memory_revision_not_found" -> "没有此历史版本；请先 history 查询版本号。"
        "memory_revision_conflict" -> "记录已更新；请先 read 确认当前 revision，再提交修改。"
        "memory_idempotency_conflict" -> "此操作标识已用于不同内容；不会覆盖或重复执行，请发起新的操作。"
        "memory_page_limit" -> "分页参数超出范围；read/history 的 limit 为 1–50，offset 为 0–1000000。"
        "memory_query_limit" -> "query 最多 512 个字符；请缩短查询。"
        "memory_labels_limit" -> "tags 和 keywords 各最多 64 项，每项非空且不超过 512 字节。"
        "memory_interval_invalid" -> "minIntervalTurns 应为 0–1000000 的整数。"
        "memory_store_id_is_host_generated" -> "store 不接受自定 id；更新已有记录请使用 update。"
        "memory_action_conflict" -> "action 与 op 不一致；请只提供一致的操作名。"
        "memory_action_unsupported" -> "支持 store、read、update、delete、set_state、restore、history。"
        "memory_owner_invalid" -> "当前助手身份无效；请重新进入助手后重试。"
        "memory_operation_key_invalid" -> "当前操作标识无效；请通过正常工具调用重新发起操作。"
        else -> "本次记忆操作未完成；没有自动重试或截断内容。"
    }
    private fun strings(value: String): List<String> = Json.decodeFromString(value)
    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
    private fun JsonObject.string(key: String): String? {
        val value = get(key) ?: return null
        if (value !is JsonPrimitive || !value.isString) refuse("memory_field_invalid", key)
        return value.content
    }
    private fun JsonObject.requiredString(key: String): String = string(key) ?: refuse("memory_field_required", key)
    private fun JsonObject.optionalInt(key: String): Int? {
        val value = get(key) ?: return null
        if (value !is JsonPrimitive || value.isString) refuse("memory_field_invalid", key)
        return value.intOrNull ?: refuse("memory_field_invalid", key)
    }
    private fun JsonObject.optionalBoolean(key: String): Boolean? {
        val value = get(key) ?: return null
        if (value !is JsonPrimitive || value.isString) refuse("memory_field_invalid", key)
        return value.booleanOrNull ?: refuse("memory_field_invalid", key)
    }
    private fun JsonObject.stringList(key: String): List<String>? {
        val value = get(key) ?: return null
        if (value !is JsonArray) refuse("memory_field_invalid", key)
        return value.map {
            if (it !is JsonPrimitive || !it.isString) refuse("memory_field_invalid", key)
            it.content
        }.distinct()
    }

    companion object { private val STATES = setOf("static", "conditional", "pinned", "paused") }
}
