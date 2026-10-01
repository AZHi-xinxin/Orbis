package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsTopBar as LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsScaffold as Scaffold
import com.lover.connect.ui.components.StarSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_model_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor
            ) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(HugeIcons.AiBrain01, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_model)) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(HugeIcons.AiEditing, null) },
                    label = { Text(stringResource(R.string.setting_model_page_tab_prompt)) }
                )
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { contentPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> ModelSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
                1 -> PromptSettingsPage(settings = settings, vm = vm, contentPadding = contentPadding)
            }
        }
    }
}

@Composable
private fun ModelSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    var archiveChoice by remember { mutableStateOf<Pair<Model, Boolean>?>(null) }
    var archiveError by remember { mutableStateOf<String?>(null) }
    archiveChoice?.let { (model, fallback) ->
        AlertDialog(onDismissRequest = { archiveChoice = null }, title = { Text("启用${if (fallback) "备用" else "主"}归档模型？") },
            text = { Text("通话结束后可将本次通话文字发送给 ${model.displayName} 生成摘要，可能产生模型费用。它不继承聊天人格、工作区或工具；主归档失败时备用最多尝试一次。取消配置可停止以后自动使用，不影响已保存的记录。") },
            confirmButton = { TextButton(onClick = {
                archiveChoice = null
                vm.setVoiceArchiveModel(model.id, fallback) { archiveError = it }
            }) { Text("确认启用") } },
            dismissButton = { TextButton(onClick = { archiveChoice = null }) { Text("取消") } })
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_chat_model),
                description = stringResource(R.string.setting_model_page_chat_model_desc),
                modelId = settings.chatModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(chatModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_fast_model),
                description = stringResource(R.string.setting_model_page_fast_model_desc),
                modelId = settings.fastModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(fastModelId = it.id)) },
                reasoningLevel = settings.fastModelReasoningLevel,
                onUpdateReasoningLevel = {
                    vm.updateSettings(settings.copy(fastModelReasoningLevel = it))
                },
            )
        }
        // Orbis exposes this once, as a star tile in Chat preferences.
        if (!BuildConfig.ORBIS_ENABLED) item {
            SuggestionSettingItem(settings = settings, vm = vm)
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_translate_model),
                description = stringResource(R.string.setting_model_page_translate_model_desc),
                modelId = settings.translateModeId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(translateModeId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_ocr_model),
                description = stringResource(R.string.setting_model_page_ocr_model_desc),
                modelId = settings.ocrModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(ocrModelId = it.id)) },
            )
        }
        item {
            ModelSettingItem(
                title = stringResource(R.string.setting_model_page_compress_model),
                description = stringResource(R.string.setting_model_page_compress_model_desc),
                modelId = settings.compressModelId,
                providers = settings.providers,
                onSelect = { vm.updateSettings(settings.copy(compressModelId = it.id)) },
            )
        }
        if (BuildConfig.ORBIS_ENABLED) item {
            ModelSettingItem(title = "语音通话归档模型",
                description = "独立整理本次已保存的通话，不依赖聊天队列。未设置时仍完整保留原文，显示未归档，不影响继续聊天。",
                modelId = settings.orbisVoiceArchiveModelId, providers = settings.providers,
                onSelect = { archiveChoice = it to false })
            TextButton(enabled = settings.orbisVoiceArchiveModelId != null, onClick = {
                vm.setVoiceArchiveModel(null, false) { archiveError = it }
            }) { Text("不使用主归档模型") }
            ModelSettingItem(title = "语音通话归档备用模型",
                description = "主归档模型失败时尝试一次；两者都失败则保留原文和重新归档入口。不重发通话消息，不重做工具操作。",
                modelId = settings.orbisVoiceArchiveFallbackModelId, providers = settings.providers,
                onSelect = { archiveChoice = it to true })
            TextButton(enabled = settings.orbisVoiceArchiveFallbackModelId != null, onClick = {
                vm.setVoiceArchiveModel(null, true) { archiveError = it }
            }) { Text("不使用备用归档模型") }
            archiveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun SuggestionSettingItem(
    settings: Settings,
    vm: SettingVM,
) {
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.setting_model_page_enable_suggestion)) },
            trailingContent = {
                Switch(
                    checked = settings.enableSuggestion,
                    onCheckedChange = {
                        vm.updateSettings(settings.copy(enableSuggestion = it))
                    }
                )
            },
        )
    }
}

@Composable
internal fun ModelSettingItem(
    title: String,
    description: String,
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    onSelect: (Model) -> Unit,
    reasoningLevel: ReasoningLevel? = null,
    onUpdateReasoningLevel: ((ReasoningLevel) -> Unit)? = null,
) {
    val state = rememberModelListState(
        modelId = modelId,
        providers = providers,
        type = ModelType.CHAT,
    )
    var showFullModelName by remember { mutableStateOf(false) }

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                onClick = { state.open() },
                headlineContent = { Text(title, Modifier.fillMaxWidth().testTag("model-setting-title")) },
                supportingContent = {
                    // ListItem measures trailing content before the headline. A long model name
                    // there can consume its entire width; keep only the fixed arrow in that slot.
                    Text(
                        text = state.currentModel?.displayName
                            ?: stringResource(R.string.model_list_select_model),
                        modifier = Modifier.fillMaxWidth().testTag("model-setting-value").combinedClickable(
                            onClick = { state.open() },
                            onLongClick = { if (state.currentModel != null) showFullModelName = true },
                            onLongClickLabel = "查看完整模型名",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
            if (reasoningLevel != null && onUpdateReasoningLevel != null) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                    trailingContent = {
                        ReasoningButton(
                            reasoningLevel = reasoningLevel,
                            onUpdateReasoningLevel = onUpdateReasoningLevel,
                        )
                    },
                )
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = state, onSelect = onSelect)
    if (showFullModelName) state.currentModel?.let { model ->
        AlertDialog(
            onDismissRequest = { showFullModelName = false },
            title = { Text("完整模型名") },
            text = { SelectionContainer {
                Text("${model.displayName}\n\n模型 ID：${model.modelId}",
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                        .testTag("model-setting-full-name"))
            } },
            confirmButton = { TextButton(onClick = { showFullModelName = false }) { Text("知道了") } },
        )
    }
}
