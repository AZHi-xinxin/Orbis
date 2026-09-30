package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.model.OrbisBudgetReason
import me.rerere.rikkahub.data.model.OrbisCompactionEvent
import me.rerere.rikkahub.data.model.OrbisContextBudget
import me.rerere.rikkahub.data.model.formatOrbisTokenCount
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

/** One entry for threshold settings, compression metadata and the single latest rollback. */
@Composable
internal fun OrbisContextBudgetButton(
    budget: OrbisContextBudget,
    modelLabel: String,
    compactionState: OrbisCompactionUiState = OrbisCompactionUiState(),
    onSaveThreshold: ((Int) -> Unit)? = null,
    onDeleteEvent: ((Uuid) -> Unit)? = null,
    onRollbackLatest: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    onManualContext: (() -> Unit)? = null,
) {
    if (!BuildConfig.ORBIS_ENABLED) return
    var open by rememberSaveable { mutableStateOf(false) }
    val colors = OrbisTheme.colors
    Box(Modifier.size(48.dp).clip(CircleShape)
        .clickable(role = Role.Button, onClickLabel = "打开上下文与压缩") {
            onOpen?.invoke()
            open = true
        }
        .testTag("orbis-context-budget-button")
        .semantics { contentDescription = "上下文，提醒阈值 ${budget.thresholdLabel}，参考用量 ${budget.percentLabel}" },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(44.dp)) {
            val stroke = 3.dp.toPx()
            drawCircle(colors.border, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
            budget.inputRatio?.let { ratio ->
                drawArc(colors.onSand, -90f, (360 * ratio.coerceIn(0.0, 1.0)).toFloat(), false,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(budget.thresholdLabel, fontSize = 7.sp, lineHeight = 9.sp,
                fontWeight = FontWeight.Medium, color = colors.mutedInk)
            Text(budget.percentLabel, fontSize = 10.sp, lineHeight = 12.sp,
                fontWeight = FontWeight.ExtraBold, color = colors.ink)
        }
    }
    if (open) OrbisContextBudgetSheet(
        budget = budget, modelLabel = modelLabel, compactionState = compactionState,
        onSaveThreshold = onSaveThreshold, onDeleteEvent = onDeleteEvent,
        onRollbackLatest = onRollbackLatest, onDismiss = { open = false },
        onManualContext = onManualContext?.let { action -> { open = false; action() } },
    )
}

@Composable
internal fun OrbisContextBudgetSheet(
    budget: OrbisContextBudget,
    modelLabel: String,
    compactionState: OrbisCompactionUiState = OrbisCompactionUiState(),
    onSaveThreshold: ((Int) -> Unit)? = null,
    onDeleteEvent: ((Uuid) -> Unit)? = null,
    onRollbackLatest: (() -> Unit)? = null,
    onManualContext: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var draftThreshold by remember(budget.reminderThresholdTokens) {
        mutableStateOf(budget.reminderThresholdTokens.toString())
    }
    var thresholdDirty by remember { mutableStateOf(false) }
    var thresholdError by remember { mutableStateOf<String?>(null) }
    var lastAttemptedThreshold by remember { mutableStateOf<Int?>(null) }
    val saveThreshold = {
        if (thresholdDirty) {
            val parsed = parseOrbisCompactionThreshold(draftThreshold)
            if (parsed == null) thresholdError = "请输入 0 到 1000000 之间的整数。"
            else {
                thresholdError = null
                thresholdDirty = false
                if (parsed != budget.reminderThresholdTokens) {
                    lastAttemptedThreshold = parsed
                    onSaveThreshold?.invoke(parsed)
                }
            }
        }
    }
    val dismiss = {
        saveThreshold()
        onDismiss()
    }
    OrbisVisualTheme {
        val colors = OrbisTheme.colors
        ModalBottomSheet(onDismissRequest = dismiss, containerColor = colors.panel,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.88f)) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("上下文与压缩", Modifier.weight(1f).semantics { heading() },
                        fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, color = colors.ink)
                    IconButton(onClick = dismiss) { Icon(HugeIcons.Cancel01, "关闭上下文与压缩") }
                }
                OrbisScrollablePanel(modifier = Modifier.weight(1f), windowInsets = WindowInsets(0, 0, 0, 0),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp)) {
                    if (onManualContext != null) BudgetCard {
                        Text("人类手动整理", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("超过模型上限、暂时无法回复时也能使用。你选归档终点，完整原文独立保存；预览并确认后，当前窗口只发送整理说明与保留的后文。",
                            fontSize = 12.sp, lineHeight = 19.sp)
                        Button(onClick = onManualContext, enabled = !compactionState.busy,
                            modifier = Modifier.fillMaxWidth().testTag("orbis-manual-context-open")) {
                            Text("选择范围 · 保留原文整理")
                        }
                        Text("不自动生成摘要、不请求模型，也不会继续发送旧队列。",
                            fontSize = 11.sp, lineHeight = 18.sp, color = colors.mutedInk)
                    }
                    BudgetCard {
                        Text("提醒阈值", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("你定阈值，AI 决定是否与何时压缩。前端只提醒，不代写摘要、不强制压缩。",
                            fontSize = 12.sp, lineHeight = 19.sp)
                        val sliderTokens = parseOrbisCompactionThreshold(draftThreshold) ?: budget.reminderThresholdTokens
                        Slider(value = sliderTokens.toFloat(), valueRange = 0f..1_000_000f, steps = 99,
                            enabled = onSaveThreshold != null,
                            onValueChange = {
                                draftThreshold = (it / 10_000f).roundToInt().times(10_000).toString()
                                thresholdDirty = true
                                thresholdError = null
                            }, onValueChangeFinished = saveThreshold,
                            modifier = Modifier.testTag("orbis-context-threshold-slider")
                                .semantics { contentDescription = "压缩提醒阈值，0 关闭提醒，最大 1M tokens" })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("0 · 提醒关闭", fontSize = 10.sp, color = colors.mutedInk)
                            Text("1M", fontSize = 10.sp, color = colors.mutedInk)
                        }
                        OutlinedTextField(value = draftThreshold,
                            onValueChange = {
                                draftThreshold = it
                                thresholdDirty = true
                                thresholdError = null
                            }, singleLine = true, enabled = onSaveThreshold != null,
                            label = { Text("精确阈值（tokens）") },
                            supportingText = { Text(thresholdError ?: "滑动松手即保存；输入数字后完成或离开输入框即保存。") },
                            isError = thresholdError != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { saveThreshold(); focusManager.clearFocus() }),
                            modifier = Modifier.fillMaxWidth().testTag("orbis-context-threshold-input")
                                .onFocusChanged { if (!it.isFocused) saveThreshold() })
                        Text(if (budget.reminderThresholdTokens == 0)
                            "提醒已关闭。AI 仍可自行调用压缩；没有自动压缩。阈值为 0 时不设置回滚 token 上限。"
                        else "从 ${formatOrbisTokenCount(budget.reminderStartsAtTokens!!)}（阈值 90%）开始，在 AI 被唤醒时中性提醒；超过 ${budget.thresholdLabel} 后加强风险提示，仍由 AI 自行决定。",
                            fontSize = 11.sp, lineHeight = 18.sp, color = colors.mutedInk)
                    }
                    if (compactionState.error != null) Surface(color = colors.sand, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(compactionState.error, color = colors.onSand, fontSize = 12.sp, lineHeight = 18.sp)
                            if (onSaveThreshold != null && lastAttemptedThreshold != null &&
                                lastAttemptedThreshold != budget.reminderThresholdTokens &&
                                parseOrbisCompactionThreshold(draftThreshold) == lastAttemptedThreshold) {
                                TextButton(onClick = { thresholdDirty = true; saveThreshold() },
                                    modifier = Modifier.testTag("orbis-context-threshold-retry")) {
                                    Text("重试保存阈值")
                                }
                            }
                        }
                    }
                    BudgetCard {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(budget.percentLabel, fontSize = 31.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
                            Text(if (budget.reminderThresholdTokens > 0) "参考用量 / 你设的 ${budget.thresholdLabel}" else "提醒关闭，不计算阈值占比",
                                fontSize = 10.sp, lineHeight = 15.sp, color = colors.mutedInk)
                        }
                        budget.inputRatio?.let {
                            LinearProgressIndicator(progress = { it.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
                                color = colors.onSand, trackColor = colors.border)
                        }
                        Text("当前参考：${budget.currentUsageTokens?.let { "${formatOrbisTokenCount(it)} tokens" } ?: "暂无可靠数值"}",
                            fontSize = 12.sp, lineHeight = 18.sp)
                        budget.currentUsageSource?.let { Text(orbisCompactionBasisLabel(it), fontSize = 11.sp, lineHeight = 17.sp, color = colors.mutedInk) }
                        Text(budgetReason(budget.reason), fontSize = 12.sp, lineHeight = 18.sp)
                        Text("提醒阈值不是模型硬上限。附件、工具定义和供应商计数方式可能影响真实占用；估算不是容量保证。",
                            fontSize = 11.sp, lineHeight = 17.sp, color = colors.mutedInk)
                    }
                    OrbisLatestRollbackCard(
                        state = compactionState, thresholdTokens = budget.reminderThresholdTokens,
                        onRollbackLatest = onRollbackLatest,
                    )
                    BudgetCard {
                        Text("压缩历史", Modifier.testTag("orbis-compaction-history").semantics { heading() },
                            fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("记录所属窗口、时间、用量与摘要指纹。历史不能编辑，也不能点选回滚；删除记录不会删除当前聊天。",
                            fontSize = 11.sp, lineHeight = 18.sp, color = colors.mutedInk)
                        if (compactionState.loading) Text("正在读取压缩记录…", fontSize = 12.sp)
                        else if (compactionState.history.isEmpty()) Text("还没有压缩记录。", fontSize = 12.sp)
                        compactionState.history.sortedByDescending { it.createdAtEpochMillis }.forEach { event ->
                            OrbisCompactionEventRow(event, !compactionState.busy, onDeleteEvent)
                        }
                    }
                    BudgetCard {
                        Text("来源与模型参考", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("当前模型：$modelLabel\n内置目录参考上限：${budget.referenceLimit?.let { "$it tokens" } ?: "未知"}。该上限不是服务器或网关确认的容量。",
                            fontSize = 11.sp, lineHeight = 18.sp, color = colors.mutedInk)
                        Text("最近选中 AI 回复完成时间：${budget.completedAt ?: "未知 / 未完成"}（设备记录时间）。\n与当前模型：${if (budget.modelMatches) "标识一致" else "不一致或未知"}。",
                            fontSize = 11.sp, lineHeight = 18.sp, color = colors.mutedInk)
                        val usage = budget.usage
                        Text(if (usage == null) "该回复没有供应商用量记录。" else
                            "该回复保存的输入：${usage.promptTokens} tokens\n输出：${usage.completionTokens} tokens\n缓存输入字段：${usage.cachedTokens} tokens（0 也可能是未返回）",
                            fontSize = 11.sp, lineHeight = 18.sp)
                        Text("缓存仍占上下文，不从输入扣除。压缩后的旧回复用量只作历史证据；当前估算不沿用归档前的用量。压缩只整理当前对话，不触碰 ST、锚点、日记或工作区。",
                            fontSize = 11.sp, lineHeight = 18.sp, color = colors.mutedInk)
                    }
                }
            }
        }
    }
}

@Composable
private fun OrbisLatestRollbackCard(
    state: OrbisCompactionUiState,
    thresholdTokens: Int,
    onRollbackLatest: (() -> Unit)?,
) {
    val event = state.latestRollback ?: return
    val colors = OrbisTheme.colors
    val limitMessage = orbisRollbackLimitMessage(state.projectedRollbackTokens, thresholdTokens)
    BudgetCard {
        Text("撤销本次压缩", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("仅当前窗口的最近一次压缩。撤销会恢复压缩前上下文，并保留之后的新消息；较大的上下文可能再次卡顿。",
            fontSize = 11.sp, lineHeight = 18.sp, color = colors.mutedInk)
        Text("本次：${formatCompactionTime(event.createdAtEpochMillis)}\n恢复后预计：${state.projectedRollbackTokens?.let { "${formatOrbisTokenCount(it)} tokens" } ?: "提交时核验"}",
            fontSize = 12.sp, lineHeight = 19.sp)
        if (limitMessage != null) Text(limitMessage, fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSand)
        if (state.busy) Text("请等本轮回复或正在进行的操作结束。", fontSize = 11.sp, color = colors.mutedInk)
        Button(onClick = { onRollbackLatest?.invoke() },
            enabled = onRollbackLatest != null && !state.busy && event.rollbackAvailable && limitMessage == null,
            modifier = Modifier.fillMaxWidth().testTag("orbis-compaction-rollback-latest")) {
            Text("撤销本次压缩")
        }
    }
}

/** No row click handler: past events can never become arbitrary rollback entry points. */
@Composable
private fun OrbisCompactionEventRow(event: OrbisCompactionEvent, canEdit: Boolean, onDeleteEvent: ((Uuid) -> Unit)?) {
    val colors = OrbisTheme.colors
    Surface(Modifier.fillMaxWidth().testTag("orbis-compaction-event-${event.id}"),
        shape = RoundedCornerShape(12.dp), color = colors.panel, contentColor = colors.ink) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(event.windowTitle.ifBlank { "未命名窗口" }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("${formatCompactionTime(event.createdAtEpochMillis)} · ${if (event.status == "rolled_back") "已撤销" else "已压缩"}",
                fontSize = 11.sp, color = colors.mutedInk)
            Text("${formatOrbisTokenCount(event.beforeTokens)} → ${formatOrbisTokenCount(event.afterTokens)} tokens · 保留 ${event.keptRecent} 条原文",
                fontSize = 12.sp, lineHeight = 19.sp)
            Text("前：${orbisCompactionBasisLabel(event.beforeBasis)}\n后：${orbisCompactionBasisLabel(event.afterBasis)}",
                fontSize = 10.sp, lineHeight = 16.sp, color = colors.mutedInk)
            SelectionContainer {
                Text("SHA-256\n${event.summaryHash}", fontSize = 10.sp, lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace, color = colors.mutedInk)
            }
            if (onDeleteEvent != null) TextButton(onClick = { onDeleteEvent(event.id) }, enabled = canEdit,
                modifier = Modifier.align(Alignment.End).testTag("orbis-compaction-delete-${event.id}")) {
                Text("删除记录", fontSize = 11.sp)
            }
        }
    }
}

private fun formatCompactionTime(epochMillis: Long): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))
}.getOrDefault("时间未知")

@Composable
private fun BudgetCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val colors = OrbisTheme.colors
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = colors.raisedPanel,
        contentColor = colors.ink, border = BorderStroke(1.dp, colors.border.copy(alpha = .6f))) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}

private fun budgetReason(reason: OrbisBudgetReason): String = when (reason) {
    OrbisBudgetReason.READY_REFERENCE -> "比例来自最近供应商输入用量，仅供参考；不是下一次请求的精确占用。"
    OrbisBudgetReason.CURRENT_ESTIMATE -> "这是当前上下文估算值，不是供应商实测值。新回复返回用量后再核对。"
    OrbisBudgetReason.ESTIMATE_PENDING -> "正在计算当前参考用量，不先套用压缩前或旧窗口的数据。"
    OrbisBudgetReason.REMINDERS_DISABLED -> "已关闭阈值提醒；这不影响 AI 自主提交压缩。"
    OrbisBudgetReason.GENERATING -> "正在生成或等待工具，本轮用量尚未稳定，暂不显示比例。"
    OrbisBudgetReason.NO_ASSISTANT -> "当前分支还没有 AI 回复；暂没有可用输入用量。"
    OrbisBudgetReason.MODEL_UNKNOWN -> "当前模型或该消息的模型标识未知，不将旧用量冒充当前值。"
    OrbisBudgetReason.MODEL_CHANGED -> "当前模型与该消息模型不同，不将旧模型用量算作当前占比。"
    OrbisBudgetReason.INCOMPLETE -> "这条回复尚未完成，或已中断；没有可靠完成用量。"
    OrbisBudgetReason.TOOL_CONTINUATION -> "这条回复包含工具调用；旧用量有续轮合并歧义，不能据此计算占比。"
    OrbisBudgetReason.USAGE_MISSING -> "最后一条 AI 回复没有可用输入用量，不冒用更早回复的数据。"
    OrbisBudgetReason.USAGE_INVALID -> "保存的用量字段无效，不能计算占比。"
    OrbisBudgetReason.LIMIT_UNKNOWN -> "模型参考上限未知；可自行设置提醒阈值，它不会扩大真实模型容量。"
}
