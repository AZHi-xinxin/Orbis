package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsScaffold
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsTopBar
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

internal enum class OrbisAssistantProfileSection { IDENTITY, ROLE }

/** Apply only edits made by this editor, against the latest target. Never replace tools/memory/models. */
internal fun mergeOrbisAssistantProfileEdit(
    current: Assistant,
    before: Assistant,
    edited: Assistant,
    section: OrbisAssistantProfileSection,
): Assistant {
    require(current.id == before.id && before.id == edited.id)
    fun <T> changed(old: T, next: T, latest: T): T = if (old != next) next else latest
    return when (section) {
        OrbisAssistantProfileSection.IDENTITY -> current.copy(
            name = changed(before.name, edited.name, current.name),
            useAssistantAvatar = changed(before.useAssistantAvatar, edited.useAssistantAvatar, current.useAssistantAvatar),
            background = changed(before.background, edited.background, current.background),
            backgroundOpacity = changed(before.backgroundOpacity, edited.backgroundOpacity, current.backgroundOpacity),
            useGradientBackground = changed(before.useGradientBackground, edited.useGradientBackground, current.useGradientBackground),
        )
        OrbisAssistantProfileSection.ROLE -> current.copy(
            systemPrompt = changed(before.systemPrompt, edited.systemPrompt, current.systemPrompt),
            allowConversationSystemPrompt = changed(before.allowConversationSystemPrompt, edited.allowConversationSystemPrompt, current.allowConversationSystemPrompt),
            allowConversationPromptInjection = changed(before.allowConversationPromptInjection, edited.allowConversationPromptInjection, current.allowConversationPromptInjection),
            messageTemplate = changed(before.messageTemplate, edited.messageTemplate, current.messageTemplate),
            presetMessages = changed(before.presetMessages, edited.presetMessages, current.presetMessages),
            regexes = changed(before.regexes, edited.regexes, current.regexes),
        )
    }
}

@Composable
fun OrbisAssistantProfilePage(id: String) {
    val parsedId = remember(id) { runCatching { Uuid.parse(id) }.getOrNull() }
    if (parsedId == null) {
        OrbisSettingsScaffold(topBar = { OrbisSettingsTopBar(title = { Text("身份与角色设定") }, navigationIcon = { BackButton() }) }) {
            Text("未找到此 AI 配置。", Modifier.padding(it).padding(16.dp))
        }
        return
    }
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val store = koinInject<SettingsStore>()
    val assistant = settings.assistants.firstOrNull { it.id == parsedId }
    val scope = rememberCoroutineScope()
    var section by rememberSaveable(id) { mutableStateOf(OrbisAssistantProfileSection.IDENTITY) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    fun edit(before: Assistant, edited: Assistant, editedSection: OrbisAssistantProfileSection) {
        if (mergeOrbisAssistantProfileEdit(before, before, edited, editedSection) == before) return
        scope.launch {
            try {
                withContext(NonCancellable) {
                    store.update { latest ->
                        check(!latest.init && latest.assistants.any { it.id == parsedId })
                        latest.copy(assistants = latest.assistants.map { target ->
                            if (target.id == parsedId) mergeOrbisAssistantProfileEdit(target, before, edited, editedSection) else target
                        })
                    }
                }
                error = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "尚未确认保存成功，请重试；其他 AI 的配置未改动。" }
        }
    }
    OrbisSettingsScaffold(topBar = {
        OrbisSettingsTopBar(title = { Text("身份与角色设定") }, navigationIcon = { BackButton() },
            subtitle = { Text("仅此 AI · 影响使用同一 AI 配置的会话") })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (settings.init || assistant == null) {
                Text(if (settings.init) "正在读取 AI 配置…" else "此 AI 已不存在，未写入任何配置。", Modifier.padding(16.dp))
            } else {
                Text(assistant.name.ifBlank { "未命名 AI" }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                PrimaryTabRow(selectedTabIndex = section.ordinal) {
                    Tab(selected = section == OrbisAssistantProfileSection.IDENTITY,
                        onClick = { section = OrbisAssistantProfileSection.IDENTITY }, text = { Text("身份与原背景") })
                    Tab(selected = section == OrbisAssistantProfileSection.ROLE,
                        onClick = { section = OrbisAssistantProfileSection.ROLE }, text = { Text("角色与高级") })
                }
                error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
                Box(Modifier.weight(1f)) {
                    when (section) {
                        OrbisAssistantProfileSection.IDENTITY -> AssistantBasicContent(
                            innerPadding = PaddingValues(vertical = 12.dp), assistant = assistant,
                            providers = settings.providers, tags = settings.assistantTags, vm = vm, identityOnly = true,
                            onUpdate = { edit(assistant, it, OrbisAssistantProfileSection.IDENTITY) },
                            onUpdateTags = { tagIds, tags -> scope.launch {
                                try {
                                    withContext(NonCancellable) { store.update { latest ->
                                        check(!latest.init && latest.assistants.any { it.id == parsedId })
                                        latest.copy(
                                            assistantTags = (latest.assistantTags + tags).associateBy { it.id }.values.toList(),
                                            assistants = latest.assistants.map { target -> if (target.id == parsedId) target.copy(tags = tagIds) else target },
                                        )
                                    } }
                                    error = null
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = "标签尚未确认保存成功，请重试。" }
                            } },
                        )
                        OrbisAssistantProfileSection.ROLE -> AssistantPromptContent(
                            innerPadding = PaddingValues(vertical = 12.dp), assistant = assistant, settings = settings,
                            onUpdate = { edit(assistant, it, OrbisAssistantProfileSection.ROLE) },
                        )
                    }
                }
            }
        }
    }
}
