package me.rerere.rikkahub.data.orbis.observation

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.memory.latestMemoryHuman
import java.time.Instant
import kotlin.uuid.Uuid

/** Opt-in metadata only; never reads message bodies or sends additional model/network requests. */
class AiObservationRuntime(private val context: Context, private val db: AppDatabase,
    private val settings: SettingsStore) {
    suspend fun report(snapshot: Settings, assistant: Assistant, conversation: Uuid?,
        messages: List<UIMessage>, model: String, modelChanged: Boolean?): String? {
        if (snapshot.init || !snapshot.aiGlobalObservation || conversation == null) return null
        return try {
            withTimeoutOrNull(250) {
                withContext(Dispatchers.IO) {
                    if (!settings.settingsFlow.value.aiGlobalObservation) return@withContext null
                    val dao = db.conversationDao()
                    val owner = assistant.id.toString()
                    val header = dao.getSummaryOfAssistant(conversation.toString(), owner) ?: return@withContext null
                    val stats = ObservationMeter(context)
                    val longest = stats.durations().sortedByDescending { it.second }.firstNotNullOfOrNull { (id, _) ->
                        dao.getObservationTitle(id)
                    }
                    val lastActive = dao.observationLastActive(owner)
                    observationReport(ObservationFacts(
                        header.title.ifBlank { "未命名" }, assistant.name.ifBlank { "未命名" }, model,
                        modelChanged, stats.regenerations(conversation.toString(), latestMemoryHuman(messages)?.id?.toString()),
                        stats.deleted(), db.orbisCompactionDao().observationCount(owner, conversation.toString()),
                        longest, dao.observationWindowCount(owner), snapshot.assistants.size,
                        lastActive?.let { Instant.ofEpochMilli(it).toString() }, observationTts(snapshot),
                        observationToolModels(snapshot),
                    ))
                }
            }
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) { null }
    }
}

/** Small counters, no content or credentials. Collection is gated at each production caller. */
internal class ObservationMeter(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("orbis-observation-v1", Context.MODE_PRIVATE)
    fun regenerate(window: String, human: String) = add("r:$window:$human", 1)
    fun delete(count: Long) { if (count > 0) add("deleted", count) }
    fun visible(window: String, millis: Long) { if (millis > 0) add("v:$window", millis.coerceAtMost(60_000)) }
    fun deleted(): Long = prefs.getLong("deleted", 0)
    fun regenerations(window: String, human: String?): Long =
        if (human == null) 0 else prefs.getLong("r:$window:$human", 0)
    fun durations(): List<Pair<String, Long>> = prefs.all.entries.mapNotNull { (key, value) ->
        if (key.startsWith("v:") && value is Long && value > 0) key.removePrefix("v:") to value else null
    }
    private fun add(key: String, amount: Long) = synchronized(lock) {
        val previous = prefs.getLong(key, 0).coerceAtLeast(0)
        prefs.edit().putLong(key, previous + amount.coerceAtMost(Long.MAX_VALUE - previous)).apply()
    }
    companion object { private val lock = Any() }
}
