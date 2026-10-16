package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.ai.McpPicker
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.orbis.orbisSettingsAssistant
import me.rerere.rikkahub.ui.pages.orbis.orbisSettingsLabel
import me.rerere.rikkahub.ui.pages.orbis.orbisSettingsWithMcpSelection
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

@Composable
fun AssistantMcpPage(id: String) {
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val assistant = orbisSettingsAssistant(settings, id)
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var saving by remember(id) { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.assistant_page_tab_mcp))
                        Text(assistant?.let { "AI 配置：${orbisSettingsLabel(it.name, "默认 AI")}" }
                            ?: if (settings.init) "正在读取配置" else "原 AI 配置已不可用",
                            style = MaterialTheme.typography.bodyMedium)
                    }
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
        val layoutDirection = LocalLayoutDirection.current
        if (assistant == null) {
            Text("不会修改其他 AI 的工具选用，请返回原对话核对配置。", Modifier.padding(innerPadding).padding(16.dp))
        } else McpPicker(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                top = innerPadding.calculateTopPadding(),
                end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            assistant = assistant,
            servers = settings.mcpServers,
            onUpdateAssistant = { proposed ->
                if (!saving && proposed.id == assistant.id) {
                    saving = true
                    scope.launch {
                        try {
                            store.update { current -> orbisSettingsWithMcpSelection(current, id, proposed) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            toaster.show("MCP 选用未能保存，请重新进入核对。", type = ToastType.Error)
                        } finally {
                            saving = false
                        }
                    }
                }
            }
        )
    }
}
