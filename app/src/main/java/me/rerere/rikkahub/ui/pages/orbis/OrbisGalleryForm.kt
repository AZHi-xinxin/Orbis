package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.gallery.*

private data class FormColors(val paper: Color, val panel: Color, val ink: Color, val accent: Color, val onAccent: Color)
private fun formColors(palette: String) = when (palette) {
    "night" -> FormColors(Color(0xff151e2b), Color(0xff202e40), Color(0xffe7edf4), Color(0xffb6d6f4), Color(0xff172b40))
    "paper" -> FormColors(Color(0xfffaf5ec), Color(0xfffffcf7), Color(0xff493b30), Color(0xff765535), Color.White)
    else -> FormColors(Color(0xfff1f6f7), Color(0xfffffcf5), Color(0xff213c55), Color(0xff376c98), Color.White)
}

@Composable internal fun GalleryReadOnlyQuestions(form: GalleryQuestionnaire, modifier: Modifier, fullScreen: Boolean = false) {
    var index by remember(form) { mutableIntStateOf(0) }
    val pages = remember(form) {
        if (form.pages.isEmpty()) form.questions.chunked(4).mapIndexed { i, q -> GalleryVisiblePage("$i", "第 ${i + 1} 页", "", q) }
        else form.pages.map { p -> GalleryVisiblePage(p.id, p.title, p.description, form.questions.filter { it.page == p.id }) }
    }
    val page = pages[index]
    val colors = formColors(form.style.palette)
    val state = rememberLazyListState()
    LaunchedEffect(index) { state.scrollToItem(0) }
    Column(modifier.background(colors.paper).safeDrawingPadding().imePadding().then(if (fullScreen) Modifier.padding(top = 48.dp) else Modifier).padding(18.dp)) {
        Text(page.title, color = colors.ink, style = MaterialTheme.typography.headlineSmall,
            fontFamily = if (form.style.heading == "serif") FontFamily.Serif else FontFamily.SansSerif)
        Text("由伙伴填写 · 此处预览所有分支题", color = colors.ink, style = MaterialTheme.typography.labelSmall)
        LazyColumn(Modifier.weight(1f), state = state, contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (page.description.isNotBlank()) item { Text(page.description, color = colors.ink) }
            items(page.questions, key = { it.id }) { q -> Surface(color = colors.panel, contentColor = colors.ink, shape = RoundedCornerShape(form.style.cornerRadius.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(q.text + if (q.required) " *" else "", style = MaterialTheme.typography.titleMedium)
                    q.options.forEach { Text("○  $it") }
                    if (q.visibleWhen.isNotEmpty()) Text("条件题：" + q.visibleWhen.joinToString("；") { c ->
                        "${form.questions.first { it.id == c.questionId }.text} 选择 ${c.anyOf.joinToString(" / ")}" }, style = MaterialTheme.typography.bodySmall)
                }
            } }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = index > 0, onClick = { index-- }) { Text("上一页") }
            Text("${index + 1} / ${pages.size}", color = colors.ink)
            TextButton(enabled = index < pages.lastIndex, onClick = { index++ }) { Text("下一页") }
        }
    }
}

@Composable internal fun GalleryHumanForm(form: GalleryQuestionnaire, item: GalleryItem, revision: Int,
    repo: GalleryRepository, disabled: Boolean, modifier: Modifier,
    onTask: (suspend () -> String?) -> Unit, onReturn: (String) -> Unit, fullScreen: Boolean = false) {
    val values = remember(item.id, revision) { mutableStateMapOf<String, List<String>>() }
    var pageIndex by remember(item.id, revision) { mutableIntStateOf(0) }
    var reviewing by remember(item.id, revision) { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var submitted by remember(item.id, revision) { mutableStateOf<GalleryAnswerRef?>(null) }
    val pages = galleryVisiblePages(form, values)
    val page = pages[pageIndex.coerceIn(0, pages.lastIndex)]
    val scroll = rememberLazyListState()
    LaunchedEffect(page.id, reviewing) { scroll.scrollToItem(0) }
    val colors = formColors(form.style.palette)
    val heading = if (form.style.heading == "serif") FontFamily.Serif else FontFamily.SansSerif
    val radius = RoundedCornerShape(form.style.cornerRadius.dp)
    fun update(id: String, answer: List<String>) {
        val active = activeGalleryAnswers(form, values.toMap() + (id to answer))
        values.clear(); values.putAll(active); submitted = null; problem = null
    }
    Box(modifier.background(Brush.verticalGradient(listOf(colors.paper, colors.panel)))) {
        if (form.style.background != "plain") Canvas(Modifier.fillMaxSize()) {
            val tint = colors.accent.copy(alpha = .08f)
            if (form.style.background == "waves") {
                val path = Path().apply { moveTo(0f, size.height * .14f); quadraticTo(size.width * .5f, size.height * .32f, size.width, size.height * .12f)
                    lineTo(size.width, size.height * .18f); quadraticTo(size.width * .5f, size.height * .38f, 0f, size.height * .20f); close() }
                drawPath(path, tint)
            } else repeat(16) { i -> drawCircle(tint, (2 + i % 3).dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * ((i * 37 % 97) / 100f), size.height * ((i * 23 % 91) / 100f))) }
        }
        CompositionLocalProvider(LocalContentColor provides colors.ink) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().then(if (fullScreen) Modifier.padding(top = 48.dp) else Modifier).padding(horizontal = 18.dp)) {
            LazyColumn(Modifier.weight(1f), state = scroll, verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (reviewing) "准备交给你" else item.title, style = MaterialTheme.typography.headlineMedium, fontFamily = heading)
                        Text(if (reviewing) "检查本次填写的答案，也可以返回修改。" else form.description.ifBlank { "慢慢填写，按你的节奏。" }, style = MaterialTheme.typography.bodyMedium)
                        Text(if (reviewing) "你的回答" else "${pageIndex.coerceAtMost(pages.lastIndex) + 1} / ${pages.size}  ·  ${page.title}", color = colors.accent, style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(progress = { if (reviewing) 1f else (pageIndex.coerceAtMost(pages.lastIndex) + 1f) / pages.size }, modifier = Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.accent.copy(alpha = .12f))
                        if (!reviewing && page.description.isNotBlank()) Text(page.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
                val questions = if (reviewing) visibleGalleryQuestions(form, values) else page.questions
                items(questions, key = { it.id }) { q ->
                    Surface(shape = radius, color = colors.panel, contentColor = colors.ink,
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.accent.copy(alpha = .16f))) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(q.text + if (q.required) " *" else "", style = MaterialTheme.typography.titleMedium, fontFamily = heading)
                            if (reviewing) Text(values[q.id].orEmpty().joinToString("；").ifBlank { "未填写" }, style = MaterialTheme.typography.bodyLarge)
                            else if (q.type == "text") OutlinedTextField(values[q.id]?.firstOrNull().orEmpty(), { value ->
                                if (value.toByteArray().size <= GalleryLimits.TEXT_ANSWER_BYTES) update(q.id, listOf(value))
                                else problem = "这道题的文字已达到 64 KiB，请缩短后再填。"
                            }, Modifier.fillMaxWidth(), enabled = !disabled, minLines = 3, placeholder = { Text("想怎么说都可以…") }, shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.ink, unfocusedTextColor = colors.ink, focusedBorderColor = colors.accent, unfocusedBorderColor = colors.accent.copy(alpha = .4f), cursorColor = colors.accent))
                            else q.options.forEach { option ->
                                val isSelected = option in values[q.id].orEmpty()
                                val choose = { update(q.id, if (q.type == "single") listOf(option) else if (isSelected) values[q.id].orEmpty() - option else values[q.id].orEmpty() + option) }
                                Row(Modifier.fillMaxWidth().background(if (isSelected) colors.accent.copy(alpha = .12f) else Color.Transparent, RoundedCornerShape(16.dp))
                                    .border(1.dp, colors.accent.copy(alpha = if (isSelected) .7f else .15f), RoundedCornerShape(16.dp))
                                    .semantics { selected = isSelected }.clickable(enabled = !disabled, role = if (q.type == "single") Role.RadioButton else Role.Checkbox, onClick = choose).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (q.type == "single") RadioButton(isSelected, onClick = null, enabled = !disabled, colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.accent.copy(alpha = .6f)))
                                    else Checkbox(isSelected, onCheckedChange = null, enabled = !disabled, colors = CheckboxDefaults.colors(checkedColor = colors.accent, checkmarkColor = colors.onAccent, uncheckedColor = colors.accent.copy(alpha = .6f)))
                                    Text(option, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
            problem?.let { Text(it, color = if (form.style.palette == "night") Color(0xffffb4ab) else Color(0xff9b2929), style = MaterialTheme.typography.bodySmall) }
            Surface(color = colors.panel, contentColor = colors.ink, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(enabled = reviewing || pageIndex > 0, onClick = { problem = null; if (reviewing) reviewing = false else pageIndex-- }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accent)) { Text(if (reviewing) "返回修改" else "上一页") }
                        Button(enabled = !disabled, onClick = {
                            val ref = submitted
                            if (ref != null) onReturn(galleryAnswerChatText(item.title, ref))
                            else try {
                                validateGalleryAnswers(form, values, if (reviewing) null else page.questions.map { it.id }.toSet())
                                problem = null
                                if (reviewing) confirm = true
                                else if (pageIndex < pages.lastIndex) pageIndex++ else reviewing = true
                            } catch (error: IllegalArgumentException) { problem = galleryErrorText(error) }
                        }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)) {
                            Text(if (submitted != null) "带回聊天" else if (reviewing) "确认提交" else if (pageIndex == pages.lastIndex) "检查答案" else "下一页")
                        }
                    }
                    Text(if (submitted != null) "已保存到格子，带回聊天后由你发送。" else "提交后才会让伙伴看到。退出作品前请完成提交。", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("提交这份答案？") }, text = { Text("仅保存本次可见题目的答案，标记为你本人填写。当前助手可通过格子工具读取；不会自动发送消息。") },
        confirmButton = { TextButton(enabled = !disabled, onClick = { confirm = false; val copy = activeGalleryAnswers(form, values.toMap()); onTask { submitted = withContext(Dispatchers.IO) { repo.submit(item.id, revision, "human", copy) }; "答案已保存，可带回聊天。" } }) { Text("确认提交") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("继续填写") } })
}
