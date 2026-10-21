package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.activities.*

@Composable
internal fun OrbisPetPage(owner: String, ownerName: String, enabled: Boolean, onToggle: (Boolean) -> Unit, onClose: () -> Unit, conversationId: String? = null) {
    var settings by rememberSaveable(owner) { mutableStateOf(false) }
    CompanionWindow(onClose) {
        if (enabled) PetHome(owner, ownerName, onClose, { settings=true }, conversationId)
        else {
            CompanionHeading("我们的小窝", "和${ownerName}一起", onClose, {settings=true})
            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("小窝还没开启",style=MaterialTheme.typography.headlineSmall)
                Text("开启后再读取宠物档案；原有名字、身份和照料记录都会保留。")
                Button(onClick={onToggle(true)}) {Text("打开小窝")}
            }
        }
        if(settings) CompanionSettings("小窝设置",enabled,onToggle,{settings=false},"关闭能力后保留原档案。宠物照料与聊天独立，关闭本页即可回到对话。")
    }
}

@Composable
private fun PetHome(owner: String, ownerName:String, onClose:()->Unit, onSettings:()->Unit, conversationId: String?) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var repository by remember(owner) { mutableStateOf<PetRepository?>(null) }
    var message by remember(owner) { mutableStateOf<String?>(null) }
    var busy by remember(owner) { mutableStateOf(false) }
    var importingPack by remember(owner) { mutableStateOf(false) }
    var packMessage by remember(owner) { mutableStateOf<String?>(null) }
    var pendingAppearance by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var appearance by rememberSaveable(owner) { mutableStateOf("cat-tabby-v1") }
    var name by rememberSaveable(owner) { mutableStateOf("") }
    var tick by remember { mutableLongStateOf(0L) }
    var ai by remember(owner) { mutableStateOf(false) }
    var packReady by remember(owner, appearance) { mutableStateOf(false) }
    LaunchedEffect(owner) {
        try { repository = withContext(Dispatchers.IO) { OrbisActivities.pet(context, owner).also { it.reload() } } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "宠物档案暂时无法读取；没有改动档案，可以返回继续聊天。" }
    }
    // Runs only while this enabled page is visible, never a service or a chat-generation queue.
    LaunchedEffect(owner) { while (true) { delay(60_000); tick++ } }
    val repo = repository
    val saved = repo?.state?.collectAsStateWithLifecycle()?.value
    val current = remember(saved, tick) { saved?.let { PetRules.advance(it, System.currentTimeMillis()) } }
    val selected = current?.pet?.appearance ?: appearance
    LaunchedEffect(selected, tick) { packReady = withContext(Dispatchers.IO) { PetAssets.ready(context, selected) } }
    fun action(block: () -> Unit) { if (!busy) scope.launch {
        busy = true; message = null
        try { withContext(Dispatchers.IO) { block() }; tick++ }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "这次操作未保存，原档案未替换。可关闭本页继续聊天。" }
        finally { busy = false }
    } }
    val packPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pendingAppearance ?: selected
        pendingAppearance = null
        // Cancellation is not an error. Freeze the selected species at launch so a
        // returning picker cannot import into a subsequently selected identity.
        if (uri != null && !busy) {
            busy = true; importingPack = true; packMessage = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { PetAssets.import(context, it, target) }
                            ?: error("pack_unavailable")
                    }
                    tick++
                    packMessage = "${PetCatalog.find(target).label}图片包已导入。"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    packMessage = when (e.message) {
                        "pet_pack_appearance_mismatch" -> "这个图片包与所选宠物不一致，请选择对应的 ZIP。"
                        "pack_unavailable" -> "暂时无法读取这个文件，请重新选择；原图片和宠物档案未改动。"
                        "different_pet_pack" -> "已有不同版本的图片，原图片已保留。"
                        else -> "图片包未导入成功，可重新选择完整的 ZIP；宠物档案未改动。"
                    }
                } finally { importingPack = false; busy = false }
            }
        }
    }
    message?.let { Row { Text(it, Modifier.weight(1f)); TextButton(onClick = { message = null }) { Text("关闭") } } }
    if (repo == null || current == null) { if(message == null) Text("正在读取宠物档案…"); return }
    if (current.pet != null) {
        if (ai) OrbisPetAi(owner, repo, { ai = false }, conversationId)
        PetInteractiveHome(current, busy, if(packReady) PetAssets.directory(context,selected) else null, ownerName, onClose, onSettings, onAi = { ai = true }) { id, decoration ->
            action { repo.act(id,UUID.randomUUID().toString(),decoration); PetAiRuntime.enqueue(context, owner, id) }
        }
        return
    }
    CompanionHeading("领养中心","和${ownerName}一起",onClose,onSettings)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
    if (current.pet == null) {
        Text("领养中心", style = MaterialTheme.typography.titleMedium)
        var family by rememberSaveable { mutableStateOf("cat") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("cat" to "猫", "dog" to "狗", "bird" to "鸟", "reptile" to "爬宠").forEach { (id, label) ->
                FilterChip(enabled = !busy, selected = family == id, onClick = { family = id; appearance = PetCatalog.appearances.first { it.family == id }.id; packMessage = null }, label = { Text(label) })
            }
        }
        PetCatalog.appearances.filter { it.family == family }.chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { pet -> FilterChip(enabled = !busy, selected = selected == pet.id, onClick = { appearance = pet.id; packMessage = null }, label = { Text(pet.label) }, modifier = Modifier.weight(1f)) }
        } }
    }
    run {
        Box(Modifier.fillMaxWidth().aspectRatio(.75f), contentAlignment = Alignment.BottomCenter) {
            val root = if(packReady) PetAssets.directory(context, selected) else null
            AsyncImage(PetAssets.model(selected, if (current.messy) "messy.png" else "tidy.png", root), "宠物房间", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            AsyncImage(PetAssets.model(selected, "cat.png", root), PetCatalog.find(selected).label,
                Modifier.fillMaxWidth(.58f).fillMaxHeight(.56f).padding(bottom = 32.dp), contentScale = ContentScale.Fit)
            if (current.sleepingUntil > System.currentTimeMillis()) Text("休息中 · 仍可照料", Modifier.align(Alignment.TopCenter).background(MaterialTheme.colorScheme.surface.copy(alpha=.8f)).padding(8.dp))
        }
    }
    run {
        Text("已内置全部宠物与房间图片，可直接领养；原来导入的图片仍会优先使用。")
        OutlinedButton(enabled = !busy, onClick = {
            pendingAppearance = selected; packMessage = null
            packPicker.launch(arrayOf("application/zip", "application/octet-stream"))
        }) { Text(if (importingPack) "正在导入图片包…" else "导入所选宠物资源包") }
    }
    if (importingPack) LinearProgressIndicator(Modifier.fillMaxWidth())
    packMessage?.let { Row(verticalAlignment = Alignment.CenterVertically) {
        Text(it, Modifier.weight(1f)); TextButton(onClick = { packMessage = null }) { Text("关闭") }
    } }
    if (current.pet == null) {
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("给它取个名字") }, modifier = Modifier.fillMaxWidth())
        Text("确认领养后，这位助手不再更换宠物。备份会保留名字、身份和照料记录。")
        Button(enabled = !busy && name.isNotBlank(), onClick = { action { repo.adopt(name, selected) } }) { Text("确认领养") }
    }
    }
}

@Composable
internal fun OrbisStudyPage(owner: String, ownerName: String, enabled: Boolean, onToggle: (Boolean) -> Unit, onClose: () -> Unit) {
    var settings by rememberSaveable(owner) { mutableStateOf(false) }
    CompanionWindow(onClose) {
        if (enabled) StudyHome(owner, ownerName, onClose) {settings=true}
        else {
            CompanionHeading("我们的书桌","ORBIS · 一起学习",onClose,{settings=true},ownerName)
            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("准备好就从这里开始",style=MaterialTheme.typography.headlineSmall)
                Text("开启后可以继续原来的练习、导入题库，也可以让助手出题和批改。")
                Button(onClick={onToggle(true)}) {Text("打开学习机")}
            }
        }
        if(settings) CompanionSettings("书桌设置",enabled,onToggle,{settings=false},"题库、计划、草稿、错题与专注记录归这位助手所有，助手可读取并继续出题、判卷。不读取私人记忆；关闭能力后保留原档案。")
    }
}

@Composable
private fun StudyHome(owner: String, ownerName: String, onClose:()->Unit, onSettings:()->Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val edits = remember(owner) { Mutex() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var repo by remember(owner) { mutableStateOf<StudyRepository?>(null) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    var busy by remember(owner) { mutableStateOf(false) }
    var pendingEdits by remember(owner) { mutableIntStateOf(0) }
    var selected by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var tab by rememberSaveable(owner) { mutableStateOf("desk") }
    var visibleCount by rememberSaveable(owner) { mutableIntStateOf(25) }
    var importing by rememberSaveable(owner) { mutableStateOf(false) }
    var importText by rememberSaveable(owner) { mutableStateOf("") }
    var preview by remember(owner) { mutableStateOf<StudyImportPreview?>(null) }
    LaunchedEffect(owner) { try { repo = withContext(Dispatchers.IO) { OrbisActivities.study(context, owner).also { it.reload() } } }
        catch(e: CancellationException) { throw e }
        catch (_: Exception) { error = "学习档案暂时不可读，没有改写；不影响聊天。" } }
    fun action(block: () -> Unit) { scope.launch(start = CoroutineStart.UNDISPATCHED) {
        pendingEdits++; busy = true; error = null
        try { withContext(NonCancellable) { edits.withLock { withContext(Dispatchers.IO) { block() } } } }
        catch(e: CancellationException) { throw e }
        catch (_: Exception) { error = "本次操作未保存。请检查题目格式或重新打开学习机，原档案保留。" }
        finally { pendingEdits--; busy = pendingEdits>0 }
    } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) action {
        val source = context.contentResolver.openInputStream(uri)?.use { it.bounded(1024 * 1024).toString(Charsets.UTF_8) } ?: error("source_missing")
        importText = source; preview = StudyImport.preview(source); importing = true
    } }
    error?.let { Row { Text(it, Modifier.weight(1f)); TextButton(onClick = { error = null }) { Text("关闭") } } }
    val repository = repo ?: return
    val state by repository.state.collectAsStateWithLifecycle()
    val wrongBySubject = remember(state) { studyWrongQuestions(state).groupBy { it.subject } }
    val session = state.sessions.find { it.id == selected }
    var questionIndex by rememberSaveable(owner,selected) { mutableIntStateOf(0) }
    val index = questionIndex.coerceIn(0,(session?.attempts?.lastIndex ?: 0).coerceAtLeast(0))
    StudyRoomLayout(tab,selected!=null,importing,ownerName,onClose,onSettings,{focusManager.clearFocus();tab=it;selected=null;importing=false;preview=null},
        pageKey=session?.attempts?.getOrNull(index)?.questionId,
        practiceActions=if(session!=null&&!importing) {{
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(enabled=index>0,onClick={focusManager.clearFocus();questionIndex=index-1}) { Text("上一题") }
                TextButton(enabled=index<session.attempts.lastIndex,onClick={focusManager.clearFocus();questionIndex=index+1}) { Text("下一题") }
            }
        }} else null) {
    if (importing) {
        Text("先预览，再导入", style = MaterialTheme.typography.titleMedium)
        Text("JSON 格式：subject、plan、questions；题目包含 kind、prompt、options、answers。Markdown 用示例格式，答案区与题目分开保存。")
        OutlinedTextField(importText, { importText = it; preview = null }, Modifier.fillMaxWidth(), label = { Text("导入原文") }, maxLines = 12)
        Row { TextButton(onClick = { importText = StudyImport.example; preview = null }) { Text("填入示例") }
            TextButton(onClick = { preview = StudyImport.preview(importText) }) { Text("预览检查") }
            TextButton(onClick = { importing = false; preview = null }) { Text("取消") } }
        preview?.issues?.forEach { Text(it) }
        preview?.document?.let { doc ->
            Text("${doc.subject} · ${doc.questions.size} 题")
            doc.questions.forEachIndexed { index, q -> Text("${index+1}. [${q.kind}] ${q.prompt}", style = MaterialTheme.typography.bodySmall) }
            Button(enabled = !busy, onClick = { action {
                val revision = repository.progress(doc.subject).revision
                val session = repository.commitQuestions(doc.subject, revision, UUID.randomUUID().toString(), doc.questions, doc.plan)
                selected = session.id; importing = false; preview = null
            } }) { Text("确认写入新题目与计划") }
        }
        return@StudyRoomLayout
    }
    if (session == null) {
        val statistics = remember(state, tab) { StudyStatistics.from(state, System.currentTimeMillis(), java.time.ZoneId.systemDefault()) }
        if(tab=="desk") {
            val resume = state.sessions.lastOrNull { it.submittedAt==null } ?: state.sessions.lastOrNull()
            StudyDeskSummary(ownerName,statistics,resume,
                onResume={if(resume!=null) selected=resume.id else tab="library"},
                onWrong={tab="wrong"},onFocus={tab="focus"})
        } else if(tab=="focus") {
            StudyFocusPanel(repository,state)
        } else if(tab=="history") {
            StudyFootprints(ownerName,statistics,state)
        } else if(tab=="wrong") {
            Text("错题本",style=MaterialTheme.typography.titleLarge)
            if(wrongBySubject.isEmpty()) Text("暂时没有错题，待批改不算答错。")
            state.progress.forEach { progress ->
                val wrong=wrongBySubject[progress.subject].orEmpty()
                if(wrong.isNotEmpty()) {
                    StudyPaper {
                    Text("${progress.subject} · ${wrong.size} 题")
                    wrong.take(visibleCount).forEach { q -> Text("[${studyKind(q.kind)}] ${q.prompt}",style=MaterialTheme.typography.bodyMedium) }
                    if(wrong.size>visibleCount) TextButton(onClick={visibleCount+=25}) { Text("显示更多错题") }
                    OutlinedButton(enabled=!busy,onClick={action {selected=repository.reviewWrong(progress.subject).id}}) { Text("独立练习这些错题") }
                    }
                }
            }
        } else {
        Row { TextButton(onClick = { importing = true }) { Text("粘贴题库") }
            TextButton(onClick = { picker.launch(arrayOf("application/json", "text/*")) }) { Text("导入文件") } }
        if (state.sessions.isEmpty()) Text("还没有练习。可以让助手出题，也可以导入题库。")
        state.progress.forEach { progress ->
            StudyPaper {
            Text("${progress.subject} · 计划", style = MaterialTheme.typography.titleSmall); Text(progress.plan.ifBlank { "尚未填写" })
            val wrong = wrongBySubject[progress.subject].orEmpty()
            Text("错题 ${wrong.size} 道 · 独立练习连续答对三次后移出错题本")
            if (wrong.isNotEmpty()) OutlinedButton(enabled = !busy, onClick = { action { selected = repository.reviewWrong(progress.subject).id } }) { Text("复习 ${progress.subject} 错题") }
            }
        }
        state.sessions.asReversed().take(visibleCount).forEach { s ->
            OutlinedButton(onClick = { selected = s.id }, modifier = Modifier.fillMaxWidth()) {
                Text("${s.subject} · ${s.questionIds.size} 题 · " + if (s.submittedAt == null) "继续草稿" else if (s.attempts.any { it.judgements.isEmpty() }) "待批改" else "查看成绩")
            }
        }
        if(state.sessions.size>visibleCount) TextButton(onClick={visibleCount+=25}) { Text("显示更早的练习") }
        }
    } else {
        Text(session.subject, style = MaterialTheme.typography.titleLarge)
        TextButton(onClick={focusManager.clearFocus();selected=null;tab="desk"}) { Text("返回书桌，保留作答") }
        val attempt=session.attempts[index]
        key(session.id, attempt.questionId) {
            StudyPaper {
            val q = state.questions.single { it.id == attempt.questionId }
            Text("${studyKind(q.kind)} · ${index+1} / ${session.attempts.size}",style=MaterialTheme.typography.labelLarge)
            Text("${index + 1}. ${q.prompt}", style = MaterialTheme.typography.titleMedium)
            var localAnswer by remember(session.id, attempt.questionId, session.submittedAt) { mutableStateOf(attempt.answer) }
            fun answer(value: List<String>) { localAnswer = value; action { repository.draft(session.id, q.id, value) } }
            if (q.kind in setOf("single", "multiple")) q.options.forEach { option ->
                val checked = option in localAnswer
                Row(Modifier.fillMaxWidth().clickable(enabled = session.submittedAt == null) { answer(if(q.kind == "single") listOf(option) else if(checked) localAnswer - option else localAnswer + option) }, verticalAlignment = Alignment.CenterVertically) {
                    if(q.kind == "single") RadioButton(checked, onClick = null) else Checkbox(checked, onCheckedChange = null)
                    Text(option)
                }
            } else OutlinedTextField(localAnswer.firstOrNull().orEmpty(), { answer(listOf(it)) }, Modifier.fillMaxWidth(), enabled = session.submittedAt == null, label = { Text("你的答案（自动保存草稿）") })
            if (session.submittedAt != null) {
                val judgement = attempt.judgements.lastOrNull()
                Text(judgement?.let { "${it.score} 分 · ${it.comment}" } ?: "待批改：不会因为超时或断网记成零分。")
                val answerKey = state.keys.single { it.questionId == q.id }
                Text("参考答案：${answerKey.answers.joinToString(" / ")}")
                if (answerKey.explanation.isNotBlank()) Text(answerKey.explanation)
                attempt.judgements.dropLast(1).forEach { Text("历史批改：${it.score} 分 · ${it.comment}", style = MaterialTheme.typography.bodySmall) }
                if (!attempt.flagged) TextButton(onClick = { action { repository.flag(session.id, q.id) } }) { Text("标记坏题（不计入错题本）") } else Text("已标记为坏题")
            }
            HorizontalDivider()
            }
        }
        if(session.submittedAt == null) Button(enabled = !busy, onClick = { action { repository.submit(session.id) } }) { Text("提交答卷") }
        else Text("需要批改或复核时，让助手读取学习机待批答卷；不会自动发起对话或占用聊天队列。")
    }
    }
}
