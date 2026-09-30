package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsTopBar as LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import me.rerere.rikkahub.ui.pages.setting.OrbisSettingsScaffold as Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkspacePage(vm: WorkspaceVM = koinViewModel()) {
    val navController = LocalNavController.current
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val selected = settings.assistants.find { it.id == settings.assistantId }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var bindTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("工作区与技能") },
                subtitle = { Text("手机里的文件与执行空间") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(HugeIcons.Add01, contentDescription = null)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("capabilities") {
                Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("让 AI 有一个可以动手的地方", style = MaterialTheme.typography.titleMedium)
                        Text("先创建工作区，再绑定当前 AI。文件可导入、编辑、导出；安装工作区 Linux 环境后，可使用读写文件和 Shell 工具。Shell 默认逐次确认，不会因打开本页自动运行。", style = MaterialTheme.typography.bodySmall)
                        Text("只操作 Orbis 的授权工作区，不等于 Root、整机文件访问或后台常驻 Agent。连接电脑仍需在系统设置配置受控 MCP；不开放反向调试端口。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { navController.navigate(Screen.Skills) }) { Text("管理技能") }
                        val bound = workspaces.find { it.id == selected?.workspaceId?.toString() }
                        Text(if (bound == null) "当前 AI 尚未绑定工作区" else "当前 AI 已绑定：${bound.name}")
                        if (bound != null && selected != null) TextButton(onClick = { vm.bindToAssistant(null, selected.id) }) { Text("解除当前绑定（不删除文件）") }
                    }
                }
            }
            if (workspaces.isEmpty()) {
                item {
                    EmptyWorkspaceState()
                }
            }

            items(workspaces, key = { it.id }) { workspace ->
                WorkspaceCard(
                    workspace = workspace,
                    onRename = { editTarget = workspace },
                    onDelete = { deleteTarget = workspace },
                    onOpen = { navController.navigate(Screen.WorkspaceDetail(workspace.id)) },
                    onBind = if (selected != null && selected.workspaceId?.toString() != workspace.id) { { bindTarget = workspace } } else null,
                )
            }
        }
    }

    bindTarget?.let { workspace ->
        AlertDialog(onDismissRequest = { bindTarget = null }, title = { Text("将工作区交给当前 AI") },
            text = { Text("绑定「${workspace.name}」后，当前 AI 可通过工作区工具读写其中的文件。Shell 是否可用取决于环境安装与批准规则；不会自动执行命令或扩大到其他 App 的私有文件。") },
            confirmButton = { TextButton(onClick = { selected?.let { vm.bindToAssistant(workspace, it.id) }; bindTarget = null }) { Text("确认绑定") } },
            dismissButton = { TextButton(onClick = { bindTarget = null }) { Text("取消") } })
    }
    error?.let { message -> AlertDialog(onDismissRequest = { vm.error.value = null }, title = { Text("工作区未完成操作") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { vm.error.value = null }) { Text("知道了") } }) }

    if (showAddDialog) {
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_create),
            initialName = "",
            existingNames = workspaces.map { it.name.trim() }.toSet(),
            onDismiss = { showAddDialog = false },
            onConfirm = { name ->
                vm.create(name)
                showAddDialog = false
            },
        )
    }

    editTarget?.let { workspace ->
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_rename),
            initialName = workspace.name,
            existingNames = workspaces.filter { it.id != workspace.id }.map { it.name.trim() }.toSet(),
            onDismiss = { editTarget = null },
            onConfirm = { name ->
                vm.rename(workspace, name)
                editTarget = null
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.workspace_page_delete),
        confirmText = stringResource(R.string.common_delete),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            deleteTarget?.let { vm.delete(it) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.workspace_page_delete_confirm))
    }
}

@Composable
private fun EmptyWorkspaceState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.File02,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_page_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_page_empty_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorkspaceCard(
    workspace: WorkspaceEntity,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onBind: (() -> Unit)?,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HugeIcons.File02,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = workspace.name,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = workspace.shellStatus.toShellStatusLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(HugeIcons.MoreVertical, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_rename)) },
                            leadingIcon = { Icon(HugeIcons.Edit01, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            if (onBind != null) TextButton(onClick = onBind, modifier = Modifier.padding(horizontal = 12.dp)) { Text("绑定当前 AI") }
        }
    }
}

@Composable
private fun EditWorkspaceDialog(
    title: String,
    initialName: String,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val trimmedName = name.trim()
    val isDuplicate = trimmedName.isNotEmpty() && trimmedName in existingNames

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.workspace_page_name)) },
                singleLine = true,
                isError = isDuplicate,
                supportingText = if (isDuplicate) {
                    { Text(stringResource(R.string.workspace_page_name_duplicate)) }
                } else null,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmedName) },
                enabled = name.isNotBlank() && !isDuplicate,
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
