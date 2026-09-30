package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import com.lover.connect.CompanionNativeTools
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.orbis.companiontools.createCompanionTools
import me.rerere.rikkahub.data.orbis.toy.buildOrbisToyTools
import me.rerere.rikkahub.data.orbis.contact.OrbisNotificationSpeechScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool get() = buildCalendarCreateTool(context)

    fun getTools(options: List<LocalToolOption>, speechScope: OrbisNotificationSpeechScope? = null): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (LocalToolOption.CompanionDevice in options || LocalToolOption.CompanionMemory in options) {
            tools.addAll(createCompanionTools(context,
                selectedCompanionToolNames(options, CompanionNativeTools.descriptors().map { it.name }), speechScope))
        }
        if (LocalToolOption.BluetoothToy in options) {
            tools.addAll(buildOrbisToyTools(context))
        }
        if (LocalToolOption.LocalGarden in options) {
            tools.addAll(me.rerere.rikkahub.data.ai.tools.buildOrbisGardenTools(context))
        }
        // The user-imported local library and public game state are available without cloud setup.
        // Merely registering tools performs no reads or model calls. Writes still need opt-in + approval.
        tools.addAll(selectLocalReadingTools(options, me.rerere.rikkahub.data.ai.tools.buildOrbisGardenReadingTools(context)))
        tools.addAll(selectLocalSoupTools(options, me.rerere.rikkahub.data.orbis.soup.createOrbisSoupTools(context)))
        return tools
    }
}

internal fun selectLocalReadingTools(options: List<LocalToolOption>, available: List<Tool>): List<Tool> =
    available.filter { it.name in setOf("orbis_reading_list", "orbis_reading_read_chapter", "orbis_reading_list_annotations") ||
        LocalToolOption.LocalReading in options && it.name == "orbis_reading_annotate" }

internal fun selectLocalSoupTools(options: List<LocalToolOption>, available: List<Tool>): List<Tool> =
    available.filter { it.name == "orbis_soup_current" || LocalToolOption.LocalSoup in options &&
        it.name in setOf("orbis_soup_ask", "orbis_soup_hint", "orbis_soup_submit", "orbis_soup_reveal") }
