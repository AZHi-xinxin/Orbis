package me.rerere.rikkahub.ui.pages.orbis

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate
import java.time.YearMonth
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.orbis.activities.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*

internal fun studyDuration(millis: Long): String {
    val seconds = millis / 1000
    return if(seconds >= 3600) "${seconds / 3600}小时${seconds % 3600 / 60}分钟" else "${seconds / 60}分${seconds % 60}秒"
}
internal fun studyKind(kind: String) = when(kind) {
    "single" -> "单选题"; "multiple" -> "多选题 · 可多选"; "fill" -> "填空题"; "written" -> "主观题"; else -> kind
}

@Composable
internal fun StudyRoomLayout(tab:String,practice:Boolean,importing:Boolean,ownerName:String,onClose:()->Unit,onSettings:()->Unit,onTab:(String)->Unit,
    pageKey:Any?=null,practiceActions:(@Composable ()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxSize().testTag("study-destination")) {
        val title=when {importing->"带一份题库来";practice->"一题一页，慢慢来";else->when(tab) {"library"->"我们的题库";"wrong"->"容易混淆的地方";"history"->"学习留下的足迹";"focus"->"安静的一会儿";else->"我们的书桌"}}
        CompanionHeading(title,"ORBIS · 和${ownerName}一起学习",onClose,onSettings,ownerName)
        key(tab,practice,importing,pageKey) {
            Column(Modifier.weight(1f).fillMaxWidth().testTag("study-content").verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=6.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
        }
        // Keep paging reachable above the IME on compact screens. It must not
        // compete with the focused answer field's bring-into-view scrolling.
        practiceActions?.invoke()
        CompanionNavigation(studyTabs,if(practice||importing) "library" else tab,onTab)
    }
}

@Composable
internal fun StudyDeskSummary(ownerName: String, statistics: StudyStatistics, resume: StudySession?, onResume: () -> Unit, onWrong:()->Unit={}, onFocus:()->Unit={}) {
    val colors=MaterialTheme.colorScheme
    Surface(color=colors.secondaryContainer, shape=RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=16.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("和${ownerName}一起",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            Text(buildAnnotatedString {
                append("连续学习 ");withStyle(SpanStyle(fontSize=36.sp)) {append(statistics.consecutiveDays.toString())};append(" 天")
            },fontSize=18.sp,lineHeight=42.sp)
            Text("累计 ${statistics.cumulativeDays} 天 · "+if(statistics.studiedToday) "今天的学习已留下记录" else "今天还没留下学习记录",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                val today=LocalDate.now()
                (6 downTo 0).forEach { back -> val day=today.minusDays(back.toLong()); val done=day.toString() in statistics.studyDays
                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(if(back==0) "今" else listOf("一","二","三","四","五","六","日")[day.dayOfWeek.value-1],style=MaterialTheme.typography.labelSmall)
                        Surface(Modifier.padding(top=4.dp).size(26.dp),shape=CircleShape,
                            color=if(done&&back!=0) colors.primary else colors.surface,
                            border=if(back==0) BorderStroke(1.dp,colors.primary) else null) {
                            Box(contentAlignment=Alignment.Center) {Text(if(done) "✓" else "·",fontSize=13.sp,color=if(done&&back!=0) colors.onPrimary else colors.primary)}
                        }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(top=10.dp,bottom=4.dp)) {
        Box(Modifier.background(colors.surfaceContainerHighest,RoundedCornerShape(8.dp,8.dp,20.dp,20.dp)).padding(bottom=6.dp)) {
            Surface(shape=RoundedCornerShape(8.dp,8.dp,18.dp,18.dp),color=colors.surface) {
                Column(Modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,top=24.dp,bottom=18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    Text("接着上次的地方",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                    Text(resume?.subject ?: "我们的第一份练习",style=MaterialTheme.typography.titleLarge)
                    val answered=resume?.attempts?.count { attempt -> attempt.answer.any {it.isNotBlank()} } ?: 0
                    Text(resume?.let { "已作答 $answered / ${it.questionIds.size} 题 · "+if(it.submittedAt==null) "草稿已保留" else "答案与评语已保存" }
                        ?: "让助手出题，或带一本自己的题库来。",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                    LinearProgressIndicator(progress={if(resume==null||resume.attempts.isEmpty()) 0f else answered.toFloat()/resume.attempts.size},
                        modifier=Modifier.fillMaxWidth().padding(vertical=3.dp).height(4.dp),trackColor=colors.secondaryContainer)
                    Button(onClick=onResume,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                        Text(if(resume==null) "打开题库" else if(resume.submittedAt==null) "继续练习" else "回看这份练习")
                        Spacer(Modifier.width(10.dp));Icon(HugeIcons.ArrowRight01,null,Modifier.size(18.dp))
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.TopCenter).offset(y=(-6).dp).rotate(-4f).size(60.dp,15.dp).background(colors.primary.copy(alpha=.25f)))
    }
    Column {
        CompanionEntryRow("再看看容易混淆的地方","错题本 · 保留原答案与解析",HugeIcons.Book02,onWrong)
        CompanionEntryRow("陪自己专注一会儿","已记录 ${studyDuration(statistics.focusMillis)} · 可暂停",HugeIcons.Time02,onFocus)
    }
    if(statistics.pendingCount>0) Text("还有 ${statistics.pendingCount} 题等助手批改，学习记录已经留下。",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
    Text("今天做一点就好，不用补昨天的份。",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant,
        modifier=Modifier.fillMaxWidth().padding(vertical=4.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
internal fun StudyPaper(content:@Composable ColumnScope.()->Unit) {
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}

@Composable
internal fun StudyFootprints(ownerName:String,statistics:StudyStatistics,state:StudyState) {
    val colors=MaterialTheme.colorScheme
    var month by remember {mutableStateOf(YearMonth.now())}
    val today=LocalDate.now()
    StudyPaper {
        Text("和${ownerName}一起留下的日子",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
        Text("累计 ${statistics.cumulativeDays} 天 · 连续 ${statistics.consecutiveDays} 天",style=MaterialTheme.typography.titleMedium)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("${month.year} 年 ${month.monthValue} 月",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            IconButton(onClick={month=month.minusMonths(1)}) {Icon(HugeIcons.ArrowLeft01,"上个月",Modifier.size(20.dp))}
            IconButton(onClick={month=month.plusMonths(1)},enabled=month<YearMonth.from(today)) {Icon(HugeIcons.ArrowRight01,"下个月",Modifier.size(20.dp))}
        }
        Row(Modifier.fillMaxWidth()) {listOf("一","二","三","四","五","六","日").forEach {Text(it,Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}}
        val offset=month.atDay(1).dayOfWeek.value-1
        val cells=((offset+month.lengthOfMonth()+6)/7)*7
        (0 until cells step 7).forEach { start ->
            Row(Modifier.fillMaxWidth()) {(start until start+7).forEach {slot ->
                val day=slot-offset+1
                Box(Modifier.weight(1f).heightIn(min=38.dp),contentAlignment=Alignment.Center) {
                    if(day in 1..month.lengthOfMonth()) {
                        val date=month.atDay(day);val studied=date.toString() in statistics.studyDays
                        Surface(shape=RoundedCornerShape(10.dp),color=if(studied) colors.secondaryContainer else Color.Transparent,
                            border=if(date==today) BorderStroke(1.dp,colors.primary) else null) {
                            Text(day.toString(),Modifier.padding(horizontal=7.dp,vertical=5.dp),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }}
        }
        Text("绿色是留下答卷的日子，空白不代表没有努力。",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
    }
    StudyPaper {
        Text("已记录专注",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
        Text(studyDuration(statistics.focusMillis),style=MaterialTheme.typography.headlineSmall)
        Text("${statistics.finishedFocusCount} 次已结束 · 只计算主动计时",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
    }
    Text("专注的片段",style=MaterialTheme.typography.titleMedium)
    var count by remember {mutableIntStateOf(20)}
    if(state.focusSessions.isEmpty()) Text("还没有计时记录。下次愿意的时候，留一段安静的时间。",style=MaterialTheme.typography.bodyMedium,color=colors.onSurfaceVariant)
    state.focusSessions.asReversed().take(count).forEach {StudyFocusRecord(it)}
    if(state.focusSessions.size>count) TextButton(onClick={count+=20}) {Text("显示更早的专注记录")}
    if(statistics.legacyDatesEstimated) Text("旧练习日期按当前时区推算；不会据此推算旧专注时长。",style=MaterialTheme.typography.bodySmall)
    Text("题库、答卷、错题和专注记录，助手都能读取；不会自动发起聊天。",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
}

@Composable
private fun StudyFocusRecord(f:StudyFocusSession) {
    val colors=MaterialTheme.colorScheme
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Box(Modifier.padding(top=7.dp).size(8.dp).background(colors.primary,CircleShape))
            Box(Modifier.padding(top=5.dp).width(1.dp).height(58.dp).background(colors.outlineVariant))
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(Instant.ofEpochMilli(f.startedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM.dd · HH:mm 开始")),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            StudyPaper {
                Text(f.subject.ifBlank {"自主专注"},style=MaterialTheme.typography.titleMedium)
                Text("${studyDuration(f.activeMillis)} · "+if(f.finishedAt==null) "尚未结束" else "已结束",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            }
        }
    }
}

/** Screen-owned timer. Every active run checkpoints and flushes when this page or app leaves foreground. */
@Composable
internal fun StudyFocusPanel(repository: StudyRepository, state: StudyState) {
    val scope=rememberCoroutineScope(); val lifecycle=LocalLifecycleOwner.current.lifecycle
    val current=state.focusSessions.lastOrNull { it.finishedAt==null }
    var subject by remember { mutableStateOf("") }
    var startRequested by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val clock=remember(current?.id) { StudyFocusClock(current?.activeMillis ?: 0, SystemClock::elapsedRealtime) }
    val saves=remember(current?.id) { Mutex() }
    var running by remember(current?.id) { mutableStateOf(false) }
    var display by remember(current?.id) { mutableLongStateOf(current?.activeMillis ?: 0) }
    suspend fun persist(finish: Boolean=false) {
        val id=current?.id ?: return
        try { withContext(NonCancellable) { saves.withLock {
            // Clock belongs to the UI thread; serialize captures before switching to disk I/O.
            val total=clock.value()
            withContext(Dispatchers.IO) { repository.checkpointFocus(id,total,finish) }
        } } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error="这段计时暂未保存，已保存记录仍在；可再次保存，不影响聊天。" }
    }
    LaunchedEffect(current?.id,startRequested) {
        if(current!=null && startRequested) {
            if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { clock.resume(); running=true }
            startRequested=false
        }
    }
    LaunchedEffect(current?.id) {
        if(current==null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lastSaved=clock.value()
            try {
                while(isActive) {
                    display=clock.value();running=clock.running
                    if(display-lastSaved >= 15_000) { persist();lastSaved=display }
                    if(display>=StudyFocusSession.MAX_MILLIS) { clock.pause();running=false;persist(true) }
                    delay(250)
                }
            } finally {
                clock.pause();running=false;display=clock.value();persist()
            }
        }
    }
    Text("留一段安静的时间",style=MaterialTheme.typography.titleLarge)
    Text("人类主动计时；离开学习页或应用退到后台即暂停。助手能读取已保存的专注明细，不会自动唤醒。",style=MaterialTheme.typography.bodySmall)
    if(current==null) OutlinedTextField(subject,{subject=it.take(80)},label={Text("这次学什么（可选）")},modifier=Modifier.fillMaxWidth())
    else Text(current.subject.ifBlank { "自主专注" })
    Surface(Modifier.fillMaxWidth().padding(vertical=8.dp),shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(horizontal=20.dp,vertical=32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Icon(HugeIcons.Time02,null,Modifier.size(28.dp),tint=MaterialTheme.colorScheme.primary)
            Text(studyDuration(display),style=MaterialTheme.typography.headlineLarge)
            Text(if(running) "此刻，专心做一件事" else "跟着自己的节奏来",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    error?.let { Text(it);TextButton(onClick={error=null}) { Text("关闭提示") } }
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Button(enabled=!busy,onClick={
            if(current==null) scope.launch {
                busy=true;error=null
                try { withContext(NonCancellable+Dispatchers.IO) { repository.startFocus(subject) };startRequested=true }
                catch (_: Exception) { error="这次计时尚未开始，原记录保留。" }
                finally { busy=false }
            } else if(clock.running) { clock.pause();running=false;scope.launch { persist() } }
            else { clock.resume();running=true }
        }) { Text(if(current==null) "开始专注" else if(running) "暂停" else "继续") }
        if(current!=null) OutlinedButton(enabled=!busy,onClick={clock.pause();running=false;scope.launch { busy=true;persist(true);busy=false }}) { Text("结束并保存") }
    }
    if(current!=null&&!running) Text("已暂停；只累计真正运行的时间。",style=MaterialTheme.typography.bodySmall)
    Text("本次最长24小时，到时停止计时；暂停与重进不会补算离开时间。",style=MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("已记录 ${studyDuration(state.focusSessions.sumOf { it.activeMillis })}",style=MaterialTheme.typography.titleMedium)
    var count by remember { mutableIntStateOf(20) }
    state.focusSessions.asReversed().take(count).forEach { f ->
        StudyFocusRecord(f)
    }
    if(state.focusSessions.size>count) TextButton(onClick={count+=20}) { Text("显示更早的专注记录") }
}
