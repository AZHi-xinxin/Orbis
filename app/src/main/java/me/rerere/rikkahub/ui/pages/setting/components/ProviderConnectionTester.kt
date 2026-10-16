package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Connect
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.UiState
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun ProviderConnectionTester(
    internalProvider: ProviderSetting,
    currentModelId: Uuid? = null,
) {
    var showTestDialog by remember { mutableStateOf(false) }
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    IconButton(onClick = { showTestDialog = true }) {
        Icon(HugeIcons.Connect, null)
    }

    if (showTestDialog) {
        var model by remember(internalProvider, currentModelId) {
            mutableStateOf(preferredConnectionTestModel(internalProvider.models, currentModelId))
        }
        var nonStreamingState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        var streamingState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        var toolsState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        var streamingText by remember { mutableStateOf("") }
        var testJob by remember { mutableStateOf<Job?>(null) }
        var busy by remember { mutableStateOf(false) }
        var runningModelName by remember { mutableStateOf("") }
        var notice by remember { mutableStateOf<String?>(null) }
        var chooseModel by remember { mutableStateOf(false) }
        DisposableEffect(internalProvider) { onDispose { testJob?.cancel() } }

        fun dismiss() { testJob?.cancel(); showTestDialog = false }

        fun resetStates() {
            nonStreamingState = UiState.Idle
            streamingState = UiState.Idle
            toolsState = UiState.Idle
            streamingText = ""
        }

        AlertDialog(
            onDismissRequest = ::dismiss,
            title = {
                Text(stringResource(R.string.setting_provider_page_test_connection))
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("测试连接：${internalProvider.name}", style = MaterialTheme.typography.bodySmall)
                    if (busy) Text("本次固定测试：$runningModelName，按顺序检查；取消后不会继续发起下一项。",
                        style = MaterialTheme.typography.bodySmall)
                    else Box {
                        OutlinedButton(onClick = { chooseModel = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("选择测试模型：${model?.displayName ?: "尚未选择"} ▾")
                        }
                        DropdownMenu(expanded = chooseModel, onDismissRequest = { chooseModel = false }) {
                            internalProvider.models.filter { it.type == ModelType.CHAT }.forEach { option ->
                                DropdownMenuItem(text = { Text("${if (option.id == model?.id) "✓ " else ""}${option.displayName}") },
                                    onClick = { model = option; chooseModel = false; resetStates(); notice = null })
                            }
                        }
                    }
                    if (!busy && model?.id != currentModelId) Text("当前助手模型不在此连接时，默认选首个聊天模型；你也可另选。仅测试上方模型，不会更改聊天模型。",
                        style = MaterialTheme.typography.bodySmall)
                    else if (!busy) Text("默认跟随当前助手的聊天模型；本次检查不修改聊天。", style = MaterialTheme.typography.bodySmall)
                    if (internalProvider is ProviderSetting.OpenAI && internalProvider.chatGptAccountId != null)
                        Text("ChatGPT 套餐只提供流式传输；非流式检查是收到完整流后一次展示。", style = MaterialTheme.typography.bodySmall)
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                    TestResultItem(
                        label = stringResource(R.string.setting_provider_page_test_non_streaming),
                        state = nonStreamingState,
                        resultText = (nonStreamingState as? UiState.Success)?.data ?: ""
                    )

                    TestResultItem(
                        label = stringResource(R.string.setting_provider_page_test_streaming),
                        state = streamingState,
                        resultText = streamingText
                    )

                    TestResultItem(
                        label = stringResource(R.string.setting_provider_page_test_tool_call),
                        state = toolsState,
                        resultText = (toolsState as? UiState.Success)?.data ?: ""
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = ::dismiss) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = busy || model != null,
                    onClick = {
                        if (busy) { testJob?.cancel(); return@TextButton }
                        val selectedModel = model ?: return@TextButton
                        val providerSnapshot = internalProvider
                        val provider = providerManager.getProviderByType(providerSnapshot)
                        resetStates()
                        notice = null
                        runningModelName = selectedModel.displayName.ifBlank { selectedModel.modelId }
                        busy = true
                        testJob = scope.launch {
                            try {
                                runConnectionTestSteps(listOf(
                                    {
                                    nonStreamingState = UiState.Loading
                                    val result = provider.generateText(
                                        providerSetting = providerSnapshot,
                                        messages = listOf(
                                            UIMessage.system("You are a helpful assistant"),
                                            UIMessage.user("hello"),
                                        ),
                                        params = TextGenerationParams(
                                            model = selectedModel,
                                            customHeaders = selectedModel.customHeaders,
                                            customBody = selectedModel.customBodies
                                        )
                                    )
                                    val text = result.message.parts
                                        .filterIsInstance<UIMessagePart.Text>()
                                        .joinToString("") { it.text }
                                    nonStreamingState = UiState.Success(text)
                                    },
                                    {
                                    streamingState = UiState.Loading
                                    val flow = provider.streamText(
                                        providerSetting = providerSnapshot,
                                        messages = listOf(
                                            UIMessage.system("You are a helpful assistant"),
                                            UIMessage.user("hello"),
                                        ),
                                        params = TextGenerationParams(
                                            model = selectedModel,
                                            customHeaders = selectedModel.customHeaders,
                                            customBody = selectedModel.customBodies
                                        )
                                    )
                                    flow.collect { chunk ->
                                        if (chunk is StreamChunk.TextDelta) {
                                            streamingText += chunk.text
                                        }
                                    }
                                    streamingState = UiState.Success("")
                                    },
                                    {
                                    toolsState = UiState.Loading
                                    val testTool = Tool(
                                        name = "get_current_time",
                                        description = "Get the current date and time.",
                                        execute = { emptyList() }
                                    )
                                    val result = provider.generateText(
                                        providerSetting = providerSnapshot,
                                        messages = listOf(
                                            UIMessage.system("You are a helpful assistant"),
                                            UIMessage.user("Use the get_current_time tool."),
                                        ),
                                        params = TextGenerationParams(
                                            model = selectedModel,
                                            tools = listOf(testTool),
                                            customHeaders = selectedModel.customHeaders,
                                            customBody = selectedModel.customBodies
                                        )
                                    )
                                    val message = result.message
                                    val toolCall = message.parts
                                        .filterIsInstance<UIMessagePart.Tool>()
                                        .firstOrNull()
                                    check(toolCall?.toolName == testTool.name) { "本次模型未返回预期的工具调用，不能认定工具能力测试通过。" }
                                    val resultText = context.getString(
                                        R.string.setting_provider_page_test_tool_called,
                                        toolCall.toolName,
                                        toolCall.input
                                    ) + "\n已验证工具请求；没有执行真实工具。"
                                    toolsState = UiState.Success(resultText)
                                    }
                                )) { index, error ->
                                    when (index) {
                                        0 -> nonStreamingState = UiState.Error(error)
                                        1 -> streamingState = UiState.Error(error)
                                        else -> toolsState = UiState.Error(error)
                                    }
                                    notice = "本项未通过，后续项目未发送；没有自动重试。"
                                }
                            } catch (cancelled: CancellationException) {
                                if (nonStreamingState is UiState.Loading) nonStreamingState = UiState.Idle
                                if (streamingState is UiState.Loading) streamingState = UiState.Idle
                                if (toolsState is UiState.Loading) toolsState = UiState.Idle
                                notice = "已取消测试；不会继续发送。已收到的测试内容保留。"
                                throw cancelled
                            } finally {
                                busy = false
                                testJob = null
                            }
                        }
                    }
                ) {
                    Text(if (busy) "取消测试" else stringResource(R.string.setting_provider_page_test))
                }
            }
        )
    }
}

@Composable
private fun TestResultItem(
    label: String,
    state: UiState<String>,
    resultText: String
) {
    var showErrorSheet by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(120.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        when (state) {
            is UiState.Idle -> Text(
                text = "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is UiState.Loading -> LinearWavyProgressIndicator(modifier = Modifier.weight(1f))
            is UiState.Success -> Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.green6
                )
                if (resultText.isNotBlank()) {
                    Text(
                        text = resultText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            is UiState.Error -> Text(
                text = state.error.message ?: "Error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.extendColors.red6,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { showErrorSheet = true }
            )
        }
    }

    if (showErrorSheet && state is UiState.Error) {
        val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        val stackTrace = remember(state.error) {
            state.error.stackTraceToString()
        }
        ModalBottomSheet(
            onDismissRequest = { showErrorSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = state.error.message ?: "Error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.red6
                )
                Text(
                    text = stackTrace,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
