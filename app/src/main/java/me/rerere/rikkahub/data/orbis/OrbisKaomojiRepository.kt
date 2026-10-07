package me.rerere.rikkahub.data.orbis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class OrbisKaomoji(val id: String, val label: String, val text: String, val tags: List<String> = emptyList(), val revision: Long = 1)

@Serializable
data class OrbisKaomojiState(val version: Int = 1, val entries: List<OrbisKaomoji> = emptyList())

interface OrbisKaomojiStorage {
    fun read(): String?
    fun write(value: String)
}

/** One device-local library shared by human UI and tools; no chat, persona or cloud access. */
class OrbisKaomojiRepository(private val storage: OrbisKaomojiStorage) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutableState = MutableStateFlow(load())
    val state = mutableState.asStateFlow()
    private var writeBlocked = false

    @Synchronized fun readSnapshot(): OrbisKaomojiState = mutableState.value

    /** A committed snapshot only; uncertain writes must never masquerade as a complete backup. */
    @Synchronized fun snapshotForBackup(): OrbisKaomojiState {
        check(!writeBlocked) { "kaomoji_reload_required" }
        return mutableState.value
    }

    @Synchronized fun reload(): OrbisKaomojiState = load().also { mutableState.value = it; writeBlocked = false }

    @Synchronized fun add(label: String, text: String, tags: List<String> = emptyList()): OrbisKaomoji {
        check(!writeBlocked) { "kaomoji_reload_required" }
        val candidate = normalize(OrbisKaomoji(UUID.randomUUID().toString(), label, text, tags))
        mutableState.value.entries.firstOrNull { it.text == candidate.text }?.let { return it }
        require(mutableState.value.entries.size < MAX_ENTRIES) { "kaomoji_library_full" }
        commit(mutableState.value.copy(entries = mutableState.value.entries + candidate))
        return candidate
    }

    @Synchronized fun update(id: String, expectedRevision: Long, label: String, text: String, tags: List<String> = emptyList()): OrbisKaomoji {
        check(!writeBlocked) { "kaomoji_reload_required" }
        val old = mutableState.value.entries.firstOrNull { it.id == id } ?: error("kaomoji_not_found")
        require(old.revision == expectedRevision && old.revision < Long.MAX_VALUE) { "kaomoji_revision_conflict" }
        val candidate = normalize(old.copy(label = label, text = text, tags = tags, revision = old.revision + 1))
        require(mutableState.value.entries.none { it.id != id && it.text == candidate.text }) { "kaomoji_duplicate_text" }
        commit(mutableState.value.copy(entries = mutableState.value.entries.map { if (it.id == id) candidate else it }))
        return candidate
    }

    /** Explicit human management action only; tools have no deletion operation. */
    @Synchronized fun delete(id: String, expectedRevision: Long) {
        check(!writeBlocked) { "kaomoji_reload_required" }
        val old = mutableState.value.entries.firstOrNull { it.id == id } ?: error("kaomoji_not_found")
        require(old.revision == expectedRevision) { "kaomoji_revision_conflict" }
        commit(mutableState.value.copy(entries = mutableState.value.entries.filterNot { it.id == id }))
    }

    private fun load(): OrbisKaomojiState {
        val raw = storage.read() ?: return OrbisKaomojiState(entries = seeds())
        require(raw.length <= MAX_STORAGE_CHARS) { "kaomoji_storage_too_large" }
        return json.decodeFromString(OrbisKaomojiState.serializer(), raw).also(::validate)
    }

    private fun commit(next: OrbisKaomojiState) {
        validate(next)
        val payload = json.encodeToString(OrbisKaomojiState.serializer(), next)
        require(payload.length <= MAX_STORAGE_CHARS) { "kaomoji_storage_too_large" }
        try {
            storage.write(payload)
            check(storage.read() == payload) { "kaomoji_reload_required" }
            mutableState.value = next
        } catch (error: Exception) {
            // The commit may have reached storage. Never guess or overwrite an uncertain result.
            writeBlocked = true
            throw error
        }
    }

    companion object {
        const val MAX_ENTRIES = 500
        const val MAX_STORAGE_CHARS = 512 * 1024
        const val MAX_TEXT_CHARACTERS = 1200
        fun characterCount(text: String): Int = text.codePointCount(0, text.length)
        fun normalize(value: OrbisKaomoji): OrbisKaomoji {
            require(value.id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && value.revision >= 1) { "kaomoji_invalid_identity" }
            fun validText(text: String, min: Int, max: Int, multiline: Boolean = false) =
                characterCount(text) in min..max && text.codePoints().noneMatch {
                    (Character.isISOControl(it) && !(multiline && it == 10)) || it in 0xD800..0xDFFF
                }
            val label = value.label.trim()
            // Keep indentation: whitespace is part of multi-line text art. Canonicalize line endings only.
            val text = value.text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
            val tags = value.tags.map(String::trim).filter(String::isNotEmpty).distinct()
            require(validText(label, 1, 40) && text.isNotBlank() &&
                validText(text, 1, MAX_TEXT_CHARACTERS, multiline = true)) { "kaomoji_invalid_text" }
            require(tags.size <= 8 && tags.all { validText(it, 1, 20) }) { "kaomoji_invalid_tags" }
            return value.copy(label = label, text = text, tags = tags)
        }
        private fun validate(value: OrbisKaomojiState) {
            require(value.version == 1 && value.entries.size <= MAX_ENTRIES) { "kaomoji_invalid_library" }
            require(value.entries.map { it.id }.distinct().size == value.entries.size && value.entries.map { it.text }.distinct().size == value.entries.size) { "kaomoji_duplicate_entry" }
            value.entries.forEach { require(normalize(it) == it) { "kaomoji_invalid_library" } }
        }
        private fun seeds(): List<OrbisKaomoji> = listOf(
            Triple("开心", "(＾▽＾)", "开心"), Triple("挥手", "(｡･ω･)ﾉﾞ", "问候"),
            Triple("抱抱", "(つ≧▽≦)つ", "拥抱"), Triple("害羞", "(〃▽〃)", "害羞"),
            Triple("思考", "(｡•́︿•̀｡)", "思考"), Triple("疑惑", "(・_・?)", "疑惑"),
            Triple("难过", "(T_T)", "难过"), Triple("加油", "(ง •̀_•́)ง", "加油"),
            Triple("晚安", "(－ω－) zzZ", "晚安"), Triple("感谢", "(人´∀｀)", "感谢"),
            Triple("惊讶", "Σ(°△°|||)", "惊讶"), Triple("看着", "|･ω･)", "观察"),
        ).mapIndexed { index, (label, text, tag) -> OrbisKaomoji("builtin_${index + 1}", label, text, listOf(tag)) }
    }
}
