package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** Existing configuration and connection state only. Opening/expanding never runs a connection test. */
@Composable
fun OrbisSettingsPage(vm: SettingVM = koinViewModel(), startAtMcp: Boolean = false) {
    val navigator = LocalNavController.current
    val toaster = LocalToaster.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val settingsStore = koinInject<SettingsStore>()
    // This is the application's shared manager (also used by SettingVM), not a new client.
    val mcpManager = koinInject<McpManager>()
    val connectionStates by mcpManager.syncingStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var dmOpen by remember { mutableStateOf(false) }
    var connectionExpanded by rememberSaveable { mutableStateOf(true) }
    var mcpExpanded by rememberSaveable { mutableStateOf(startAtMcp) }
    var displayExpanded by rememberSaveable { mutableStateOf(false) }
    var speechExpanded by rememberSaveable { mutableStateOf(false) }
    var dataExpanded by rememberSaveable { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = orbisSettingsInitialItem(startAtMcp))

    // Never fall back to a different assistant when the selected identity is missing.
    val selected = if (settings.init) null else settings.assistants.find { it.id == settings.assistantId }
    val selectedId = selected?.id?.toString()
    val selectedLabel = selected?.let { orbisSettingsLabel(it.name, "默认 AI") }
    val selectedUnavailable = if (settings.init) "正在读取设置" else "没有可用的选中配置"
    val model = selected?.let { settings.findModelById(it.chatModelId ?: settings.chatModelId) }
    val provider = model?.findProvider(settings.providers)
    val ttsProvider = if (settings.init) null else settings.ttsProviders.find { it.id == settings.selectedTTSProviderId }
    val canSave = !settings.init && !saving

    // Read the latest store state when the user acts; do not overwrite it from a rendered snapshot.
    fun savePreference(transform: (Settings) -> Settings) {
        if (settings.init || saving) return
        saving = true
        scope.launch {
            try {
                settingsStore.update { current -> if (current.init) current else transform(current) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toaster.show("设置未能完整保存，请重新进入核对。", type = ToastType.Error)
            } finally {
                saving = false
            }
        }
    }
    fun setSpeechRule(rule: OrbisSpeechRule, checked: Boolean) {
        savePreference { current ->
            val display = current.displaySetting
            val next = OrbisSpeechRules(display.autoPlayTTSAfterGeneration, display.ttsOnlyReadQuoted,
                display.ttsOnlyReadOutsideBrackets).withRule(rule, checked)
            current.copy(displaySetting = display.copy(
                autoPlayTTSAfterGeneration = next.autoRead,
                ttsOnlyReadQuoted = next.quotedOnly,
                ttsOnlyReadOutsideBrackets = next.outsideBrackets,
            ))
        }
    }

    OrbisPageSurface {
        val colors = OrbisTheme.colors
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                OrbisPageHeader(
                    title = "系统设置", subtitle = "模型连接、MCP、声音与数据",
                    avatar = { Icon(HugeIcons.Settings03, contentDescription = null) },
                    navigationIcon = { BackButton() }, modifier = Modifier.statusBarsPadding(),
                )
            },
            bottomBar = { OrbisChatDock(currentLabel = "当前 系统设置") },
        ) { innerPadding ->
            LazyColumn(
                state = listState, modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Keep this as a single item: MCP is always index 1 for the existing deep link.
                item("connection") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("SETTINGS", fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.5.sp,
                                fontWeight = FontWeight.Bold, color = colors.onSand)
                            Text("日常高频，复杂折叠", fontSize = 23.sp, lineHeight = 29.sp,
                                fontWeight = FontWeight.Bold, color = colors.ink,
                                modifier = Modifier.semantics { heading() })
                            Text("连接与数据集中管理；外观在聊天侧栏，手机权限在「后台与权限」。",
                                fontSize = 11.sp, lineHeight = 17.sp, color = colors.mutedInk)
                        }
                        OrbisSettingsSection("模型与连接", "自定义接口与当前 AI 的模型", "◉",
                            connectionExpanded, { connectionExpanded = !connectionExpanded }) {
                            if (selected != null && canSave) {
                                ModelSelector(
                                    modelId = selected.chatModelId ?: settings.chatModelId,
                                    providers = settings.providers,
                                    type = ModelType.CHAT,
                                    modifier = Modifier.fillMaxWidth(),
                                    onSelect = { chosen ->
                                        savePreference { current ->
                                            current.copy(assistants = current.assistants.map { assistant ->
                                                if (assistant.id == selected.id) assistant.copy(chatModelId = chosen.id) else assistant
                                            })
                                        }
                                    },
                                )
                            }
                            if (model == null && !settings.init) {
                                OrbisSettingsNote("先添加连接，再添加模型；已有模型可直接点击「选择模型」。")
                            }
                            OrbisSettingsFact("所选 AI", selectedLabel ?: selectedUnavailable)
                            OrbisSettingsFact("聊天模型", model?.let {
                                orbisSettingsLabel(it.displayName.ifBlank { it.modelId }, "已配置模型（名称已隐藏）")
                            } ?: if (settings.init) "正在读取" else "尚未选择")
                            OrbisSettingsFact("连接", provider?.let {
                                orbisSettingsLabel(it.name, "已配置提供商（名称已隐藏）") +
                                    if (it.enabled) " · 配置已开启" else " · 配置已关闭"
                            } ?: "选择模型后显示")
                            OrbisSettingsNote("连接健康：尚未检查")
                            OrbisSettingsLink("自定义连接与模型", "管理已有连接，或填写自己的接口地址和模型",
                                { navigator.navigate(Screen.SettingProvider) })
                            OrbisGatewayModelEntry()
                        }
                OrbisCloudSettingsEntry()
                OrbisIntegrationSettingsEntry(me.rerere.rikkahub.data.orbis.integration.OrbisIntegration.ST_ATLAS)
                OrbisIntegrationSettingsEntry(me.rerere.rikkahub.data.orbis.integration.OrbisIntegration.TECH_HUB)
                OrbisConsultationSettingsEntry()
                    }
                }
                item("mcp") {
                    OrbisSettingsSection("MCP 与工具", "服务状态与 AI 选用", "⌁",
                        mcpExpanded, { mcpExpanded = !mcpExpanded }) {
                        OrbisSettingsNote(selectedLabel?.let {
                            "当前 AI 配置：$it（影响同配置会话）"
                        } ?: selectedUnavailable)
                        if (settings.init) {
                            OrbisSettingsFact("服务列表", "正在读取")
                        } else if (settings.mcpServers.isEmpty()) {
                            OrbisSettingsFact("服务列表", "尚未添加 MCP 服务")
                        } else {
                            settings.mcpServers.forEach { server ->
                                val summary = orbisMcpOverview(server.commonOptions.enable,
                                    selected?.let { server.id in it.mcpServers }, connectionStates[server.id])
                                Surface(color = colors.raisedPanel, shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, colors.border.copy(alpha = .6f))) {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(orbisSettingsLabel(server.commonOptions.name, "未命名服务"),
                                            fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                                        Text(summary.configured + " · " + summary.selected, fontSize = 10.sp,
                                            lineHeight = 15.sp, color = colors.mutedInk)
                                        Text(summary.connection, fontSize = 10.sp, lineHeight = 15.sp, color = colors.mutedInk)
                                    }
                                }
                            }
                        }
                        OrbisSettingsLink("添加、导入与高级管理", "管理服务连接与授权",
                            { navigator.navigate(Screen.SettingMcp) })
                        OrbisSettingsLink("管理当前 AI 的选用", selectedLabel?.let { "所选配置：$it" } ?: selectedUnavailable,
                            selectedId?.let { id -> { navigator.navigate(Screen.AssistantMcp(id)) } })
                        OrbisSettingsLink("云端工具授权 · 可选", "仅用于远端服务；本机共读、花园与海龟汤无需此授权",
                            { navigator.navigate(Screen.OrbisCloudToolCredentials) })
                        OrbisSettingsLink("独立 DM API", "海龟汤主持专用，不沿用主聊天上下文", { dmOpen = true })
                    }
                }
                item("display") {
                    OrbisSettingsSection("聊天偏好", "用量摘要与阅读交互", "◐",
                        displayExpanded, { displayExpanded = !displayExpanded }) {
                        OrbisSettingsLink("打开聊天偏好", "宫格管理发送、滚动、用量与阅读方式",
                            { navigator.navigate(Screen.SettingPreferencesGeneral) })
                    }
                }
                item("speech") {
                    OrbisSettingsSection("语音与朗读", "声音与自动朗读规则", "◖",
                        speechExpanded, { speechExpanded = !speechExpanded }) {
                        OrbisSettingsNote("本 App 全局，影响所有会话")
                        OrbisSettingsFact("朗读服务", if (settings.init) "正在读取" else
                            ttsProvider?.let { orbisSettingsLabel(it.name, "已配置服务（名称已隐藏）") }
                                ?: "未找到已选择的服务")
                        OrbisSettingsFact("默认播放语速", if (settings.init) "正在读取" else
                            orbisSavedPlaybackSpeed(settings.defaultTTSPlaybackSpeed))
                        if (canSave) Select(
                            options = (5..20).map { it / 10f },
                            selectedOption = settings.defaultTTSPlaybackSpeed,
                            onOptionSelected = { speed -> savePreference { it.copy(defaultTTSPlaybackSpeed = speed) } },
                            optionToString = { "${it}×" },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OrbisSettingsToggle("生成结束后自动朗读", "回复完成后自动播放声音",
                            settings.displaySetting.autoPlayTTSAfterGeneration, canSave) { setSpeechRule(OrbisSpeechRule.AUTO_READ, it) }
                        OrbisSettingsDivider()
                        OrbisSettingsToggle("仅朗读引号内内容", "只读被引号包围的文字",
                            settings.displaySetting.ttsOnlyReadQuoted, canSave) { setSpeechRule(OrbisSpeechRule.QUOTED_ONLY, it) }
                        OrbisSettingsDivider()
                        OrbisSettingsToggle("跳过括号内内容", "朗读时略过括号中的文字",
                            settings.displaySetting.ttsOnlyReadOutsideBrackets, canSave) { setSpeechRule(OrbisSpeechRule.OUTSIDE_BRACKETS, it) }
                        OrbisSettingsLink("语音服务与音色 · 高级", "选择朗读服务、音色和语音识别",
                            { navigator.navigate(Screen.SettingSpeech) })
                    }
                }
                item("data") {
                    OrbisSettingsSection("本地数据与诊断", "备份、恢复与请求记录", "◔",
                        dataExpanded, { dataExpanded = !dataExpanded }) {
                        OrbisSettingsLink("备份与恢复", "导出或恢复本机数据",
                            { navigator.navigate(Screen.Backup) })
                        OrbisSettingsNote("当前备份 ZIP 不包含图片表情库和工作区，卸载前请另行备份。")
                        OrbisSettingsLink("请求日志", "查看已有请求记录", { navigator.navigate(Screen.Log) })
                        OrbisSettingsNote("原始日志可能含聊天内容和连接信息，请勿直接公开分享。")
                    }
                }
                item("advanced") {
                    OrbisSettingsSection("高级连接与通知", "更多模型、网络与通知选项", "⋯",
                        advancedExpanded, { advancedExpanded = !advancedExpanded }) {
                        OrbisSettingsLink("默认模型与提示词", "各项功能使用的模型与提示词",
                            { navigator.navigate(Screen.SettingModels) })
                        OrbisSettingsLink("网络搜索", "搜索提供商与选项", { navigator.navigate(Screen.SettingSearch) })
                        OrbisSettingsLink("网页端连接", "网页服务、端口与访问配置", { navigator.navigate(Screen.SettingWeb) })
                        OrbisSettingsLink("网络偏好", "连接与请求的网络设置", { navigator.navigate(Screen.SettingPreferencesNetwork) })
                        OrbisSettingsLink("通知设置", "生成完成与通知显示偏好", { navigator.navigate(Screen.SettingPreferencesNotification) })
                        OrbisSettingsLink("关于与开源许可", "版本信息与保留的第三方许可证", { navigator.navigate(Screen.SettingAbout) })
                    }
                }
            }
        }
    }
    if (dmOpen) OrbisSoupDmSettingsDialog(onDismiss = { dmOpen = false })
}
