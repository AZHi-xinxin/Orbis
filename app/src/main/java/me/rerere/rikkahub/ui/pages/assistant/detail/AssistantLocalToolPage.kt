package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import com.lover.connect.ui.components.StarSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.approval.RememberedToolApprovals
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.openUsageAccessSettings
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantLocalToolContent(
            innerPadding = innerPadding,
            assistant = assistant,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val permissionRequiredText =
        stringResource(R.string.assistant_page_local_tools_screen_time_permission_required)

    val calendarPermissionState = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.READ_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_read)) },
                usage = { Text(stringResource(R.string.permission_calendar_read_desc)) },
                required = true
            ),
            PermissionInfo(
                permission = Manifest.permission.WRITE_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_write)) },
                usage = { Text(stringResource(R.string.permission_calendar_write_desc)) },
                required = true
            ),
        )
    )
    PermissionManager(permissionState = calendarPermissionState)

    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        if (enabled && option == LocalToolOption.ScreenTime && !context.hasUsageStatsPermission()) {
            toaster.show(message = permissionRequiredText, type = ToastType.Warning)
            context.openUsageAccessSettings()
        }
        if (enabled && option == LocalToolOption.Calendar && !calendarPermissionState.allPermissionsGranted) {
            calendarPermissionState.requestPermissions()
            return
        }
        val newLocalTools = if (enabled) {
            assistant.localTools + option
        } else {
            assistant.localTools - option
        }
        onUpdate(assistant.copy(localTools = newLocalTools))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AssistantMessageTimeCard(assistant = assistant, onUpdate = onUpdate)
        CardGroup {
            item(headlineContent = { Text("宠物区") },
                supportingContent = { Text("开启本地照料及伙伴工具。关闭不联网、不加载图片；已领养宠物仍按离开时长变化，可先送去寄养。") },
                trailingContent = { Switch(assistant.orbisPetEnabled, { onUpdate(assistant.copy(orbisPetEnabled = it)) }) })
            item(headlineContent = { Text("学习机") },
                supportingContent = { Text("题库、答卷和进度按助手独立保存；关闭不注册学习工具，不影响聊天。") },
                trailingContent = { Switch(assistant.orbisStudyEnabled, { onUpdate(assistant.copy(orbisStudyEnabled = it)) }) })
            item(headlineContent = { Text("模型信息连续性") },
                supportingContent = { Text("简短告知本轮选用模型及是否切换；不额外联网、不等待核对。不代表服务商底层权重鉴定。") },
                trailingContent = { Switch(assistant.orbisModelContinuityEnabled, { onUpdate(assistant.copy(orbisModelContinuityEnabled = it)) }) })
        }
        CardGroup {
            item(headlineContent = { Text("允许 AI 撤回自己的消息") },
                supportingContent = { Text("限最近 20 条 AI 回复，不计人类消息。撤回后你只看到提示，AI 仍记得原文；不能撤回你的消息。") },
                trailingContent = { Switch(LocalToolOption.MessageRecall in assistant.localTools,
                    { toggleLocalTool(LocalToolOption.MessageRecall, it) }) })
            item(
                headlineContent = { Text("允许 AI 清理旧思考与工具记录") },
                supportingContent = { Text("仅本对话已完成旧轮，先预览再经批准清理；原文可恢复，正文、附件和当前工具链不删除。下一轮请求生效，不依赖网关。清理回执只显示简短状态，不反复套娃。") },
                trailingContent = { Switch(checked = LocalToolOption.ContextPruning in assistant.localTools,
                    onCheckedChange = { toggleLocalTool(LocalToolOption.ContextPruning, it) }) },
            )
            item(
                headlineContent = { Text("人类健康记录") },
                supportingContent = { Text("允许当前助手读写你在本机的人类记录。调用时所选记录随工具结果交给当前模型；不自动注入、不进云备份。你修正过的字段需要本人确认才可覆盖。") },
                trailingContent = { Switch(checked = LocalToolOption.HumanHealth in assistant.localTools,
                    onCheckedChange = { toggleLocalTool(LocalToolOption.HumanHealth, it) }) },
            )
            item(
                headlineContent = { Text("本机愿望") },
                supportingContent = { Text("让伙伴给已有本机记忆附上想做的事。星图只显示未完成数量；关闭后工具与自动浮现均停止。") },
                trailingContent = { Switch(checked = LocalToolOption.LocalDesire in assistant.localTools,
                    onCheckedChange = { toggleLocalTool(LocalToolOption.LocalDesire, it) }) },
            )
            item(
                headlineContent = { Text("AI 自定义美化") },
                supportingContent = { Text("让当前助手创作主题草稿。到侧栏“自定义美化”预览后应用，可回退；关闭不注册工具。") },
                trailingContent = { Switch(checked = LocalToolOption.ThemeDesign in assistant.localTools,
                    onCheckedChange = { toggleLocalTool(LocalToolOption.ThemeDesign, it) }) },
            )
            item(
                headlineContent = { Text("格子 · 允许 AI 创作与填写问卷") },
                supportingContent = { Text("AI 默认可按需读取自己格子的作品与已提交答案。开启后可经批准写入、修改、删除自己创作的作品，或回答给 AI 的问卷；支持此次允许 / 以后允许。未提交的人类答案不可读取。正文不自动进上下文。") },
                trailingContent = { Switch(checked = LocalToolOption.LocalGallery in assistant.localTools,
                    onCheckedChange = { toggleLocalTool(LocalToolOption.LocalGallery, it) }) },
            )
        }
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.JavascriptEngine),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.JavascriptEngine, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.TimeInfo),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.TimeInfo, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Clipboard),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Clipboard, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Tts),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Tts, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.AskUser),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.AskUser, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_time_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_time_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.ScreenTime),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.ScreenTime, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_calendar_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_calendar_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Calendar),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Calendar, it) }
                    )
                }
            )
        }
        RememberedToolApprovals(assistant.id.toString())
    }
}

/** Visible independently of the legacy memory page and the time-query tool. */
@Composable
internal fun AssistantMessageTimeCard(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
) {
    CardGroup {
        item(
            headlineContent = { Text("每条消息的发送时间") },
            supportingContent = {
                Text("仅对当前 AI 生效。开启后，新的人类消息会附带发送时的日期、时间和时区给 AI，聊天正文不变。旧消息不补时间；关闭后不再附带。无需开启下方时间查询工具，也不会启用记忆功能。第三方网关自行添加的时间不受此开关控制。")
            },
            trailingContent = {
                Switch(
                    checked = assistant.enableUserMessageTime,
                    onCheckedChange = { onUpdate(assistant.copy(enableUserMessageTime = it)) },
                    modifier = Modifier.testTag("assistant-user-message-time-toggle").semantics {
                        contentDescription = "每条消息的发送时间"
                    },
                )
            },
        )
    }
}
