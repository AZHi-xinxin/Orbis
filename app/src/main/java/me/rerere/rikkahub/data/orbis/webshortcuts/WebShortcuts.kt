package me.rerere.rikkahub.data.orbis.webshortcuts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class WebShortcut(val id: String, val url: String, val title: String,
    val source: String, val conversationId: String? = null, val createdAt: Long = System.currentTimeMillis())
@Serializable
internal data class WebShelf(val schema: Int = 1, val entries: List<WebShortcut> = emptyList(),
    val interactiveOrigins: Map<String, String> = emptyMap())
internal data class WebRecommendation(val assistant: String, val conversation: String, val entry: WebShortcut,
    val requestId: String = UUID.randomUUID().toString())

/** Only explicit saved bookmarks are shared. No browsing history, page contents or clicks are stored here. */
internal class WebShortcutRepository(private val file: File) {
    private val lock = locks.getOrPut(file.absolutePath) { Any() }
    val changes: StateFlow<Long> get() = signals.getOrPut(file.absolutePath) { MutableStateFlow(0) }
    fun snapshot(): WebShelf = synchronized(lock) {
        if (!file.exists()) WebShelf() else json.decodeFromString<WebShelf>(file.readText()).also {
            require(it.schema == 1 && it.entries.map { e -> e.id }.distinct().size == it.entries.size)
            it.entries.forEach { e -> require(shortcutUrl(e.url) == e.url && e.source in setOf("human", "ai")) }
        }
    }
    private fun save(state: WebShelf) {
        val parent = checkNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs())
        val temp = File(parent, "${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { it.write(json.encodeToString(state).toByteArray()); it.fd.sync() }
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        signals.getOrPut(file.absolutePath) { MutableStateFlow(0) }.value++
    }
    fun add(url: String, title: String, source: String, conversation: String? = null): WebShortcut = synchronized(lock) {
        require(source in setOf("human", "ai"))
        val normalized = shortcutUrl(url); val old = snapshot()
        old.entries.firstOrNull { it.url == normalized }?.let { return@synchronized it }
        val entry = WebShortcut(UUID.randomUUID().toString(), normalized,
            title.trim().take(200).ifBlank { shortcutOrigin(normalized) }, source, conversation)
        save(old.copy(entries = old.entries + entry)); entry
    }
    fun remove(id: String, human: Boolean) = synchronized(lock) {
        val state = snapshot(); val entry = state.entries.firstOrNull { it.id == id } ?: return@synchronized
        check(human || entry.source == "ai") { "web_shortcut_human_entry" }
        save(state.copy(entries = state.entries.filterNot { it.id == id }))
    }
    /** Called only by the human UI, never by model arguments or a tool result. */
    fun allowInteraction(url: String, mcpId: String?) = synchronized(lock) {
        val state = snapshot(); val origin = shortcutOrigin(url)
        save(state.copy(interactiveOrigins = if (mcpId == null) state.interactiveOrigins - origin
            else state.interactiveOrigins + (origin to mcpId)))
    }
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val locks = ConcurrentHashMap<String, Any>()
        private val signals = ConcurrentHashMap<String, MutableStateFlow<Long>>()
    }
}
internal fun shortcutUrl(value: String): String {
    require(value.none { it.isISOControl() } && value.length <= 16384) { "web_shortcut_url" }
    val url = value.trim().toHttpUrlOrNull() ?: error("web_shortcut_url")
    require(url.scheme in setOf("http", "https") && url.username.isEmpty() && url.password.isEmpty()) { "web_shortcut_url" }
    return url.toString()
}
internal fun shortcutOrigin(value: String): String = shortcutUrl(value).toHttpUrlOrNull()!!.newBuilder()
    .encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
internal fun sharedWebUrl(text: String): String? = Regex("https?://[^\\s<>\"']+").find(text)?.value?.let {
    runCatching { shortcutUrl(it.trimEnd('。', '，', '）', ')', ']', '》')) }.getOrNull()
}
internal object WebRecommendations {
    val pending = MutableStateFlow<Map<String, WebRecommendation>>(emptyMap())
    val windows = MutableStateFlow<Map<String, WebWindowState>>(emptyMap())
    @Synchronized fun show(assistant: String, conversation: String, entry: WebShortcut): WebRecommendation {
        val request = WebRecommendation(assistant, conversation, entry)
        val key = "$assistant/$conversation"
        val previous = windows.value[key] ?: WebWindowState()
        check(previous.pages.any { it.id == entry.id } || previous.pages.size < 8) { "web_shortcut_tabs_full" }
        windows.value = windows.value + (key to previous.copy(
            pages = if (previous.pages.any { it.id == entry.id }) previous.pages else previous.pages + entry,
            selectedId = entry.id, expanded = true))
        pending.value = pending.value + (key to request)
        return request
    }
    @Synchronized fun change(assistant: String, conversation: String, transform: (WebWindowState) -> WebWindowState) {
        val key = "$assistant/$conversation"
        windows.value = windows.value + (key to transform(windows.value[key] ?: WebWindowState()))
    }
    @Synchronized fun close(assistant: String, conversation: String, id: String) = change(assistant, conversation) { old ->
        val pages = old.pages.filterNot { it.id == id }
        old.copy(pages = pages, selectedId = old.selectedId.takeIf { it != id } ?: pages.lastOrNull()?.id,
            expanded = pages.isNotEmpty() && old.expanded)
    }
}

/** Ephemeral presentation only: closing a page never deletes its saved bookmark. */
internal data class WebWindowState(val pages: List<WebShortcut> = emptyList(), val selectedId: String? = null,
    val expanded: Boolean = false, val maximized: Boolean = false)
