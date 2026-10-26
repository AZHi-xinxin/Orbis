package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.health.*
import java.time.LocalDate

@Composable internal fun OrbisHealthPage(onClose:()->Unit) {
    val context=LocalContext.current
    val repo=remember(context){openHealth(context)}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        OrbisPageSurface { HealthPageContent(repo,onClose,deviceSync={HealthSyncEntry()},importScreenshot={date,done->
            val scope=rememberCoroutineScope()
            var busy by remember{mutableStateOf(false)}
            var error by remember{mutableStateOf<String?>(null)}
            val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->
                if(uri!=null)scope.launch{
                    busy=true;error=null
                    try{
                        val recognized=recognizeHealthScreenshotLayout(context,uri)
                        done(parseHealthScreenshot(recognized),recognized.text)
                    }catch(e:CancellationException){throw e}catch(_:Exception){error="图片未识别成功，可以换一张清晰截图或手动填写。"}finally{busy=false}
                }
            }
            TextButton(enabled=!busy&&validHealthDate(date),onClick={picker.launch("image/*")}){Text(if(busy)"正在本机识别…"else"导入截图")}
            error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }) }
    }
}

private data class HealthEditing(val day:HealthDay,val proposal:HealthProposal?=null,val imported:Map<String,JsonPrimitive>?=null,val ocr:String?=null)

@Composable internal fun HealthPageContent(repo:HealthRepository,onClose:()->Unit,
    deviceSync:@Composable ()->Unit={},
    importScreenshot:@Composable (String,(Map<String,JsonPrimitive>,String)->Unit)->Unit={_,_->}) {
    var state by remember(repo){mutableStateOf(HealthState())}
    var date by remember{mutableStateOf(LocalDate.now().toString())}
    var error by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    var editor by remember{mutableStateOf<HealthEditing?>(null)}
    var historyDate by remember{mutableStateOf<String?>(null)}
    var month by remember{mutableStateOf(false)}
    val signal by healthChanges.collectAsState()
    val scope=rememberCoroutineScope()
    LaunchedEffect(repo,signal){try{state=withContext(Dispatchers.IO){repo.snapshot()}}catch(e:CancellationException){throw e}catch(_:Exception){error="记录暂时无法读取，原文件保留。"}}
    fun task(onSuccess:()->Unit = {}, action:()->Unit){if(busy)return;busy=true;scope.launch{
        try{withContext(Dispatchers.IO){action()};state=withContext(Dispatchers.IO){repo.snapshot()};error=null;onSuccess()}
        catch(e:CancellationException){throw e}catch(_:Exception){error="记录可能已有新修改，未覆盖。请刷新后核对日期、数值和版本。"}finally{busy=false}
    }}
    val selected=state.days.firstOrNull{it.date==date}?:HealthDay(date)
    val end=runCatching{LocalDate.parse(date)}.getOrDefault(LocalDate.now())
    val start=if(month)end.withDayOfMonth(1)else end.minusDays(6)
    val summary=remember(state.days,start,end){healthSummary(state.days,start.toString(),end.toString())}
    val colors=OrbisTheme.colors
    HealthDashboard(state, selected, date, month, busy, error, summary, start, end,
        onClose = onClose, onRefresh = { task {} }, onDate = { date = it }, onMonth = { month = it },
        onEdit = { editor = HealthEditing(selected) }, onHistory = { historyDate = date },
        deviceSync = deviceSync,
        importScreenshot = { importScreenshot(date) { fields, text -> editor = HealthEditing(selected, imported = fields, ocr = text) } },
        onReview = { proposal -> editor = HealthEditing(state.days.firstOrNull { it.date == proposal.date } ?: HealthDay(proposal.date), proposal = proposal) },
        onDismissProposal = { proposal -> task { repo.dismiss(proposal.id) } })
    editor?.let{edit->HealthEditor(edit,saving=busy,saveError=error,onDismiss={if(!busy)editor=null},onSave={patch->
        // Snapshot in editor remains revision-bound if AI updates the same day while it is open.
        task(onSuccess={editor=null}){
            if(edit.proposal!=null)repo.confirm(edit.proposal.id,patch)
            else repo.write(edit.day.date,edit.day.revision,patch,if(edit.imported!=null)"screenshot"else"manual","human",if(edit.imported!=null)"本机截图识别，经本人校对"else"本人手动填写")
        }
    })}
    historyDate?.let{target->AlertDialog(onDismissRequest={historyDate=null},title={Text("$target 修改记录")},
        text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("保留最近 256 次日记录版本。",style=MaterialTheme.typography.bodySmall)
            state.history.filter{it.date==target}.reversed().forEach{day->
                Text("第 ${day.revision} 版 · ${java.time.Instant.ofEpochMilli(day.updatedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()}")
                day.fields.forEach{(key,cell)->Text("${healthFields.first{it.id==key}.label}：${if(cell.value==JsonNull)"已清空"else cell.value.content}",style=MaterialTheme.typography.bodySmall)}
            }
        }},confirmButton={TextButton(onClick={historyDate=null}){Text("完成")}})}
}

private val periodLabels=linkedMapOf("" to "未记录","none" to "非经期","active" to "经期中","pre" to "经前","post" to "经后")
internal fun healthDisplay(key:String,value:JsonPrimitive)=if(key=="periodStatus") periodLabels[value.content]?:value.content else value.content
internal fun healthSource(source:String)=when(source){"manual"->"手动";"screenshot"->"截图校对";"mcp"->"设备或服务";else->"对话事实"}
@Composable private fun HealthEditor(edit:HealthEditing,saving:Boolean,saveError:String?,onDismiss:()->Unit,onSave:(Map<String,JsonPrimitive>)->Unit){
    val base=edit.day.fields.mapValues{it.value.value}
    val seed=base+edit.proposal?.fields.orEmpty()+edit.imported.orEmpty()
    val inputs=remember(edit){mutableStateMapOf<String,String>().apply{healthFields.forEach{field->put(field.id,seed[field.id]?.takeUnless{it==JsonNull}?.content.orEmpty())}}}
    var error by remember{mutableStateOf<String?>(null)}
    val stale=edit.proposal?.expectedRevision?.let{it!=edit.day.revision}==true
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        Surface(Modifier.fillMaxSize(),color=OrbisTheme.colors.page){Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()){
            Text("${edit.day.date} · ${if(edit.imported!=null||edit.proposal!=null)"检查后保存"else"填写记录"}",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(18.dp))
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                item{Text("空白表示未记录。你确认过的字段，助手不能直接覆盖。",style=MaterialTheme.typography.bodySmall)}
                if(stale)item{Text("这份建议提出后，记录又更新了。请保留当前记录，返回后按最新内容手动修改。",color=MaterialTheme.colorScheme.error)}
                edit.ocr?.let{ocr->item{Text(if(edit.imported.isNullOrEmpty())"没有提取出明确数值，可对照识别文字手动填入。"else"已提取 ${edit.imported.size} 项，请检查日期、单位和数字。")};item{Text(ocr.take(4000),style=MaterialTheme.typography.bodySmall)}}
                items(healthFields,key={it.id}){field->
                    if(field.id=="periodStatus") {
                        Text(field.label,style=MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            periodLabels.forEach { (value,label) -> FilterChip(selected=inputs[field.id]==value,
                                onClick={inputs[field.id]=value},label={Text(label)}) }
                        }
                    } else OutlinedTextField(inputs[field.id].orEmpty(),{inputs[field.id]=it},label={Text(field.label)},supportingText={Text(field.unit)},singleLine=field.id!="note",modifier=Modifier.fillMaxWidth())
                }
                (error?:saveError)?.let{message->item{Text(message,color=MaterialTheme.colorScheme.error)}}
            }
            Row(Modifier.fillMaxWidth().padding(14.dp),horizontalArrangement=Arrangement.SpaceBetween){
                TextButton(enabled=!saving,onClick=onDismiss){Text("取消")}
                Button(enabled=!stale&&!saving,onClick={
                    try{
                        val proposed=healthFields.associate{it.id to healthValue(it,inputs[it.id].orEmpty())}
                        val patch=proposed.filter{(key,value)->value!=(base[key]?:JsonNull)||key in edit.proposal?.fields.orEmpty()||key in edit.imported.orEmpty()}
                        require(patch.isNotEmpty()){"health_no_change"};onSave(patch)
                    }catch(_:Exception){error="请检查数值范围、时间格式或经期阶段；没有填写的项目可留空。"}
                }){Text(if(saving)"正在保存…"else"确认保存")}
            }
        }}
    }
}
@Composable private fun HealthStepsChart(days:List<HealthDay>,from:LocalDate,to:LocalDate){
    val records=days.filter{it.date>=from.toString()&&it.date<=to.toString()}.mapNotNull{d->d.fields["steps"]?.value?.doubleOrNull?.let{LocalDate.parse(d.date) to it}}
    val count=java.time.temporal.ChronoUnit.DAYS.between(from,to).toInt()+1
    val peak=records.maxOfOrNull{it.second}?.coerceAtLeast(1.0)?:1.0
    val color=OrbisTheme.colors.accent
    if(records.isEmpty()){Text("暂无步数记录",style=MaterialTheme.typography.bodySmall);return}
    Canvas(Modifier.fillMaxWidth().height(95.dp)){
        val slot=size.width/count
        records.forEach{(date,value)->val index=java.time.temporal.ChronoUnit.DAYS.between(from,date).toInt()
            val x=(index+.5f)*slot
            drawLine(color,Offset(x,size.height),Offset(x,size.height-(size.height*value/peak).toFloat()),strokeWidth=(slot*.6f).coerceAtLeast(1f))}
    }
    Text("${from.dayOfMonth} 日 → ${to.dayOfMonth} 日 · 最高 ${peak.toInt()} 步",style=MaterialTheme.typography.labelSmall)
}
