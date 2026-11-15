package me.rerere.rikkahub.ui.pages.orbis

import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import me.rerere.rikkahub.data.orbis.diagnostics.OrbisFaultDiagnostics
import java.io.File
import java.util.UUID

@Composable internal fun FaultDiagnosticsButton() {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var open by remember {mutableStateOf(false)}
    var recording by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var note by remember {mutableStateOf("")}
    var report by remember {mutableStateOf("")}
    fun work(action:suspend ()->Unit) {
        if(busy)return
        busy=true;note=""
        scope.launch {try {action()} catch(c:CancellationException){throw c}
            catch(_:Exception){note="未能完成，请重试。已有记录仍保留，没有发送报告。"}
            finally {busy=false;recording=OrbisFaultDiagnostics.active(context)}}
    }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {uri->
        if(uri!=null&&report.isNotEmpty())work {
            withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"wt").use {checkNotNull(it).write(report.toByteArray(Charsets.UTF_8))}}
            note="故障报告已保存，可自行发送给维护者。"
        }
    }
    OutlinedButton(onClick={open=true;recording=OrbisFaultDiagnostics.active(context)},modifier=Modifier.testTag("fault-diagnostics-open")) {
        Text("故障记录与导出")
    }
    LaunchedEffect(open) {while(open){recording=OrbisFaultDiagnostics.active(context);delay(1000)}}
    if(open)AlertDialog(onDismissRequest={if(!busy)open=false},title={Text("故障记录与导出")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("闪退、按钮异常时：①开启记录；②返回原位置复现一次；③重开 Orbis 回到这里，导出故障报告。")
            Text("当前：${if(recording)"正在记录，开启后30分钟自动停止" else "未开启或已到期，已有记录仍可导出"}",modifier=Modifier.testTag("fault-diagnostics-state"))
            Text("只记录本应用的退出原因、异常类型、代码调用位置和关键操作阶段。不读取聊天正文或密钥，不录屏，不自动上传。")
            Text("系统可能不提供或已清理退出记录；开启前的详细故障不会补录。没有记录不代表没有故障。")
            Button(enabled=!busy,onClick={val stopping=recording;work {withContext(Dispatchers.IO){if(stopping)OrbisFaultDiagnostics.stop(context) else OrbisFaultDiagnostics.start(context)}
                note=if(stopping)"已停止，已有记录可导出。" else "已开启。现在返回原位置复现一次，再回这里导出。"}},modifier=Modifier.testTag("fault-diagnostics-toggle")) {
                Text(if(recording)"停止详细记录" else "开启30分钟故障记录")
            }
            OutlinedButton(enabled=!busy,onClick={work {
                val file=withContext(Dispatchers.IO) {
                    val text=OrbisFaultDiagnostics.report(context)
                    val directory=File(context.cacheDir,"fault-reports").apply {mkdirs()}
                    directory.listFiles().orEmpty().filter{it.name.startsWith("Orbis-fault-")&&it.lastModified()<System.currentTimeMillis()-7L*86400000}.forEach{it.delete()}
                    File(directory,"Orbis-fault-${System.currentTimeMillis()}-${UUID.randomUUID()}.txt").also{it.writeText(text)}
                }
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type="text/plain";putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newRawUri("Orbis故障报告",uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },"分享 Orbis 故障报告"))
                note="报告已生成；请选择接收人并自行确认发送。"
            }},modifier=Modifier.testTag("fault-diagnostics-share")){Text("导出并分享故障报告")}
            OutlinedButton(enabled=!busy,onClick={work {report=withContext(Dispatchers.IO){OrbisFaultDiagnostics.report(context)};save.launch("Orbis-fault-${System.currentTimeMillis()}.txt")}}){Text("保存报告到文件")}
            TextButton(enabled=!busy,onClick={work {withContext(Dispatchers.IO){OrbisFaultDiagnostics.clear(context)};note="本功能的本地详细记录已清除；已导出的文件及系统退出历史不受影响。"}}){Text("停止并清除本地详细记录")}
            if(busy)Text("正在处理…")
            if(note.isNotEmpty())Text(note)
        }
    },confirmButton={TextButton(enabled=!busy,onClick={open=false}){Text("返回并复现")}})
}
