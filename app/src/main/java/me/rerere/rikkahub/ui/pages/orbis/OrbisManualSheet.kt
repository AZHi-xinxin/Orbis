package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.tools.orbisPublicStaticManual
import me.rerere.rikkahub.data.ai.tools.orbisManualChapters
import me.rerere.rikkahub.data.ai.tools.orbisManualChapterMatches

/** Static help only: no ViewModel, settings, registry construction or tool execution. */
@Composable
fun OrbisManualEntry(modifier: Modifier = Modifier) {
    if (!BuildConfig.ORBIS_ENABLED) return
    var open by rememberSaveable { mutableStateOf(false) }
    val colors = OrbisTheme.colors
    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp)
            .heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "打开这个家的说明书") { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Surface(Modifier.fillMaxWidth(), color = colors.sand, contentColor = colors.onSand,
            shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(HugeIcons.Book03, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("这个家的说明书", fontSize = 12.sp, lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("只读", fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
    }
    if (open) OrbisManualSheet(onDismiss = { open = false })
}

@Composable
private fun OrbisManualSheet(onDismiss: () -> Unit) {
    val manual = remember { orbisPublicStaticManual() }
    val sections = listOf("overview" to "概览", "tools" to "工具", "permissions" to "权限", "limits" to "状态", "guide" to "手册")
    var selected by rememberSaveable { mutableStateOf("overview") }
    var chapterId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(selected, chapterId) { scroll.scrollTo(0) }
    OrbisVisualTheme {
        val colors = OrbisTheme.colors
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = colors.panel,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.88f)) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("这个家的说明书", fontSize = 17.sp, lineHeight = 23.sp,
                            fontWeight = FontWeight.Bold, color = colors.ink,
                            modifier = Modifier.semantics { heading() })
                        Text("本地只读 · 不发起模型调用", fontSize = 11.sp, lineHeight = 15.sp,
                            color = colors.mutedInk)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(HugeIcons.Cancel01, "关闭说明书", tint = colors.mutedInk)
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    sections.forEachIndexed { index, (id, title) ->
                        SegmentedButton(selected = selected == id, onClick = { selected = id },
                            shape = SegmentedButtonDefaults.itemShape(index, sections.size)) {
                            Text(title, fontSize = 12.sp)
                        }
                    }
                }
                OrbisScrollablePanel(
                    modifier = Modifier.weight(1f),
                    scrollState = scroll,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 18.dp),
                ) {
                    ManualCard("先分清说明与实际状态", "本页是静态说明，未检查本轮工具、授权或 MCP 健康。工具目录中的名称不代表本轮已注册，也不代表已获权限。")
                    when (selected) {
                        "overview" -> {
                            ManualCard("你所在的家", manual.getValue("host_ui").jsonPrimitive.content)
                            ManualCard("按需阅读，不必每轮复述", "人类可在这里看说明；AI 可按需使用本轮实际工具列表里的 orbis_help，了解宿主及当轮注册快照。若本轮没有该工具，不要编造调用。说明书不会每轮整本注入，也不替你修改设置。")
                        }
                        "tools" -> manual.getValue("category_catalog").jsonArray.forEach { entry ->
                            val category = entry.jsonObject
                            ManualCard(
                                title = manualLabel(category.getValue("category").jsonPrimitive.content),
                                body = category.getValue("use").jsonPrimitive.content,
                                footnote = "工具目录：" + category.getValue("known_tool_names").jsonArray.joinToString("、") { it.jsonPrimitive.content },
                            )
                        }
                        "permissions" -> ManualFields(manual.getValue("permissions").jsonObject)
                        "limits" -> ManualFields(manual.getValue("limits").jsonObject)
                        "guide" -> {
                            val chapter = orbisManualChapters.firstOrNull { it.id == chapterId }
                            if (chapter == null) {
                                OutlinedTextField(value = query, onValueChange = { if (it.length <= 80) query = it },
                                    label = { Text("搜索用法、错误或工具名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                val matches = remember(query) { orbisManualChapters.filter { orbisManualChapterMatches(it, query.trim()) } }
                                if (matches.isEmpty()) Text("没有匹配章节，可换一个词，如‘课表’‘导入’‘语音’。")
                                matches.forEach { item ->
                                    Surface(shape = RoundedCornerShape(16.dp), color = colors.raisedPanel,
                                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { chapterId = item.id }) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(item.title, fontWeight = FontWeight.SemiBold)
                                            Text(item.uiPath, style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
                                        }
                                    }
                                }
                            } else {
                                TextButton(onClick = { chapterId = null }) { Text("‹ 返回章节目录") }
                                ManualCard(chapter.title, chapter.uiPath, "静态使用说明；没有检查此刻的权限、联网或工具注册。")
                                chapter.sections.forEach { section -> ManualCard(section.title, section.text) }
                            }
                        }
                    }
                    Text("说明版本：" + manual.getValue("manual_version").jsonPrimitive.content,
                        fontSize = 10.sp, lineHeight = 14.sp, color = colors.mutedInk,
                        modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ManualFields(fields: JsonObject) {
    fields.forEach { (key, value) ->
        val text = when (val raw = value.jsonPrimitive.content) {
            "not_integrated" -> "尚未接通，不能按已可用处理。"
            "not_checked" -> "本页未检查连接与服务健康。"
            "not_available" -> "当前没有提供该宿主工具。"
            "available_read_only_in_orbis", "available_read_only_in_orbis_debug" -> "Orbis 已提供本地只读入口，不检查或执行其他工具。"
            "available_local_synthetic_demo_only" -> "已提供本地动态星图演示。"
            "ai_authored_compact_in_current_full_conversation_run; metadata_history; latest_only_rollback; no_auto_summary_or_forced_compaction" -> "当前完整会话支持 AI 自写摘要并直接整理，前端只提醒；可查压缩记录，只能撤销本窗口最近一次，不自动代写或强行压缩。"
            "available_local_rule_bot_in_debug" -> "开发版内置九路五子棋，对手是本地规则程序，不调用聊天模型。"
            "read_only_tool_and_local_ui_same_device_only_not_in_backup_zip" -> "界面与只读工具可查本机战绩；尚未纳入备份 ZIP，不承诺卸载、清除数据或换机后恢复。"
            else -> raw
        }
        ManualCard(manualLabel(key), text)
    }
}

@Composable
private fun ManualCard(title: String, body: String, footnote: String? = null) {
    val colors = OrbisTheme.colors
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = colors.raisedPanel, contentColor = colors.ink,
        border = BorderStroke(1.dp, colors.border.copy(alpha = .6f))) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Text(body, fontSize = 12.sp, lineHeight = 18.sp)
            footnote?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 15.sp),
                color = colors.mutedInk) }
        }
    }
}

/** Presentation labels only. Capability definitions come exclusively from OrbisHelpTool. */
private fun manualLabel(key: String): String = when (key) {
    "local_schedule" -> "本地课表与日程"
    "local_kaomoji" -> "文字颜文字库"
    "companion_spaces" -> "秘密基地、共同空间与照片墙"
    "video_calls" -> "抽帧视频通话"
    "companion_spaces_and_video" -> "新空间与视频使用边界"
    "local_presentation_and_files" -> "外观、附件保护与救援说明"
    "voice_notes" -> "可点击语音条"
    "device_facts" -> "设备事实与本地观察"
    "context_message_limit" -> "消息截取与记忆断层"
    "consultation" -> "咨询室开发状态"
    "conversation_reference" -> "会话参考"
    "workspace" -> "工作区边界"
    "skills" -> "技能"
    "web" -> "联网搜索"
    "javascript" -> "本地 JavaScript"
    "time" -> "设备时间"
    "local_games" -> "本地游戏战绩"
    "clipboard" -> "剪贴板"
    "speech" -> "朗读"
    "ask_user" -> "询问用户"
    "usage" -> "应用使用时长"
    "calendar" -> "日历"
    "this_tool" -> "说明书能做什么"
    "registration_is_not_authorization" -> "注册不等于授权"
    "native_settings_write_tool" -> "AI 直接修改设置"
    "settings_guidance" -> "配置由谁操作"
    "privacy" -> "隐私边界"
    "network" -> "联网边界"
    "native_st_star_map" -> "ST 真实记忆星图连接"
    "native_st_star_map_scope" -> "星盘数据边界"
    "techhub_scope" -> "TechHub 连接与权限"
    "multi_ai_group" -> "独立多 AI 群聊"
    "web_live_sync" -> "Web 实时同步"
    "native_st_star_map_demo_ui" -> "本地动态星图演示"
    "native_st_star_map_demo_scope" -> "星图演示数据"
    "sentinel" -> "哨兵"
    "techhub_native_ui" -> "TechHub 原生页面"
    "ai_game_install_and_match_records" -> "AI 小游戏入库与战绩"
    "native_gomoku" -> "原生五子棋（本地规则对手）"
    "native_game_records" -> "本地战绩（同设备保留，尚未纳入备份 ZIP）"
    "human_manual_ui_entry" -> "人类说明书入口"
    "reversible_context_compression" -> "可撤销上下文整理"
    "compaction_budget" -> "压缩提醒与回滚范围"
    "context_compaction" -> "AI 自主压缩"
    "external_st_connection" -> "外部 ST 连接"
    "notes" -> "不要把入口当作连接证明"
    else -> key
}
