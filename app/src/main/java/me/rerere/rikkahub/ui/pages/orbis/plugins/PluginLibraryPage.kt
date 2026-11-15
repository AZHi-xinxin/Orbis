package me.rerere.rikkahub.ui.pages.orbis.plugins

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.appearanceForStyle
import me.rerere.rikkahub.data.orbis.plugins.*
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.orbis.*
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable internal fun PluginLibraryPage(owner: String, ownerName: String, conversationId: String?, close: () -> Unit) {
    val context=LocalContext.current
    val settingsStore=koinInject<SettingsStore>(); val workspaces=koinInject<WorkspaceRepository>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val host=remember(owner) { PluginHost(context.applicationContext,owner,settingsStore,workspaces) }
    val change by host.repository.changes.collectAsStateWithLifecycle()
    var data by remember(owner) { mutableStateOf<PluginState?>(null) }
    var approved by remember(owner) { mutableStateOf(emptySet<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var opened by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var order by rememberSaveable(owner) { mutableStateOf(PluginOrder.FREQUENCY) }
    var busy by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<Pair<String,Int>?>(null) }
    var showVersions by remember { mutableStateOf(false) }
    var showRecords by remember { mutableStateOf(false) }
    var showCode by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    val selected=settings.assistants.firstOrNull { it.id.toString()==owner }
    val enabled=selected?.localTools?.contains(LocalToolOption.PersonalPlugins)==true
    val opacity=settings.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current).normalized().bubbleOpacity
    LaunchedEffect(owner,change) {
        try {
            withContext(Dispatchers.IO) {
                host.repository.recoverInterrupted()
                val snapshot=host.repository.snapshot()
                snapshot to snapshot.entries.filter { host.approved(it) }.map { it.id }.toSet()
            }.let { data=it.first; approved=it.second }
        } catch(e: CancellationException) { throw e } catch(e: Exception) { error=pluginErrorText(pluginError(e)) }
    }
    fun perform(block: suspend () -> Unit) {
        if(busy) return
        scope.launch { busy=true
            try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { error=pluginErrorText(pluginError(e)) }
            finally { busy=false }
        }
    }
    val entry=data?.entries?.firstOrNull { it.id==opened }
    val libraryScroll=rememberLazyListState()
    val detailScroll=key(opened) { rememberLazyListState() }
    fun back() { if(opened!=null) { opened=null; showCode=false } else close() }
    Dialog(onDismissRequest=::back,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        BackHandler(onBack=::back)
        OrbisInterfaceTheme {
            OrbisPageSurface {
                val colors=OrbisTheme.colors
                Scaffold(containerColor=Color.Transparent,topBar={
                    OrbisPageHeader(title=if(entry==null) "插件区" else "插件详情",subtitle="内容归属：$ownerName",
                        navigationIcon={ TextButton(onClick=::back) { Text("‹",fontSize=30.sp) } },
                        modifier=Modifier.statusBarsPadding())
                },bottomBar={ OrbisChatDock(currentLabel="当前 工具",ownerAssistantId=owner,conversationId=conversationId) }) { insets ->
                    BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
                        val columns=if(maxWidth>=350.dp && LocalDensity.current.fontScale<=1.3f) 2 else 1
                        LazyColumn(Modifier.fillMaxSize().testTag("personal-plugin-page"),state=if(entry==null) libraryScroll else detailScroll,contentPadding=PaddingValues(16.dp),
                            verticalArrangement=Arrangement.spacedBy(14.dp)) {
                            if(entry==null) {
                                item("permission") {
                                    Surface(shape=RoundedCornerShape(20.dp),color=colors.panel) {
                                        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("允许 AI 编写和使用插件",color=colors.ink,fontSize=15.sp)
                                                Text("每个新版本由你确认后启用",color=colors.mutedInk,fontSize=12.sp)
                                            }
                                            Switch(enabled,{ value -> perform { settingsStore.update { current -> current.copy(assistants=current.assistants.map {
                                                if(it.id.toString()==owner) it.copy(localTools=if(value) (it.localTools+LocalToolOption.PersonalPlugins).distinct()
                                                    else it.localTools-LocalToolOption.PersonalPlugins) else it
                                            }) } } },enabled=!busy && selected!=null,modifier=Modifier.testTag("personal-plugins-enabled"))
                                        }
                                    }
                                }
                                item("sort") {
                                    Text("${data?.entries?.size ?: 0} 个插件 · ${data?.entries?.count { it.enabled && it.id in approved } ?: 0} 个已启用",color=colors.mutedInk,fontSize=13.sp)
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        FilterChip(order==PluginOrder.FREQUENCY,{order=PluginOrder.FREQUENCY},label={Text("按使用频率")},modifier=Modifier.weight(1f))
                                        FilterChip(order==PluginOrder.UPDATED,{order=PluginOrder.UPDATED},label={Text("按更新时间")},modifier=Modifier.weight(1f))
                                    }
                                    Text(if(order==PluginOrder.FREQUENCY) "近 30 天成功使用次数" else "插件内容最近更新",color=colors.mutedInk,fontSize=11.sp)
                                }
                                if(data==null) item { Text("正在读取插件…",color=colors.mutedInk) }
                                else if(data!!.entries.isEmpty()) item {
                                    Surface(shape=RoundedCornerShape(24.dp),color=colors.panel) {
                                        Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                            PluginArtwork("star",pluginTint("gold"))
                                            Text("这里会收好 AI 写的个人工具",fontSize=18.sp,color=colors.ink)
                                            Spacer(Modifier.height(10.dp))
                                            Text(if(enabled) "可以回聊天告诉伙伴想做什么。它提交插件后，你会在这里看到介绍和待确认的版本。"
                                                else "开启上方开关后，伙伴才能编写和调用个人插件。你可以随时关闭。",color=colors.mutedInk,fontSize=14.sp,lineHeight=22.sp)
                                        }
                                    }
                                }
                                val rows=sortedPlugins(data?.entries.orEmpty(),order,System.currentTimeMillis()).chunked(columns)
                                items(rows,key={it.first().id}) { row ->
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                        row.forEach { plugin -> PluginCard(plugin,plugin.id in approved,opacity,Modifier.weight(1f)) { opened=plugin.id } }
                                        repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                                item { Text("介绍由 AI 填写 · 使用记录由 Orbis 统计",Modifier.fillMaxWidth(),fontSize=11.sp,color=colors.mutedInk) }
                            } else {
                                item("intro") {
                                    val body=entry.latest().body
                                    PluginStationery(body.palette,opacity) {
                                        PluginArtwork(body.icon,pluginTint(body.palette))
                                        Text(body.title,fontSize=25.sp,fontWeight=FontWeight.SemiBold)
                                        Text("$ownerName 写给你的介绍",fontSize=12.sp)
                                        Spacer(Modifier.height(10.dp))
                                        SelectionContainer { Text(body.introduction,fontSize=15.sp,lineHeight=25.sp) }
                                    }
                                }
                                if(entry.activeVersion!=entry.latest().number || entry.id !in approved) item("pending") {
                                    FilledTonalButton(onClick={review=entry.id to entry.latest().number},enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("plugin-review")) {
                                        Text(if(entry.activeVersion==null) "查看并启用这个插件" else "查看新版本 · v${entry.latest().number}")
                                    }
                                    if(entry.active()!=null) Text("上方展示最新介绍，当前使用 v${entry.activeVersion}；确认前保留原版本。",fontSize=12.sp,color=colors.mutedInk)
                                }
                                item("statistics") {
                                    Surface(shape=RoundedCornerShape(20.dp),color=colors.tintedPanel) {
                                        Row(Modifier.fillMaxWidth().padding(vertical=18.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
                                            PluginStat("累计使用",entry.totalUses); PluginStat("近 30 天",entry.recentUses(System.currentTimeMillis()))
                                        }
                                    }
                                }
                                item("enabled") {
                                    Surface(shape=RoundedCornerShape(16.dp),color=colors.panel) {
                                        Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("允许使用",color=colors.ink)
                                                if(!enabled) Text("个人插件总开关已关闭",fontSize=11.sp,color=colors.mutedInk)
                                                else if(entry.id !in approved) Text("请先确认版本和权限",fontSize=11.sp,color=colors.mutedInk)
                                            }
                                            Switch(entry.enabled && entry.id in approved,{ value -> perform { host.setEnabled(entry.id,entry.revision,value) } },
                                                enabled=!busy && entry.activeVersion!=null && entry.id in approved,modifier=Modifier.testTag("plugin-enabled"))
                                        }
                                    }
                                }
                                item { Text("最近更新 · ${pluginDate(entry.updatedAt)}",color=colors.mutedInk,fontSize=12.sp) }
                                item { PluginRow("使用记录") {showRecords=true} }
                                item { PluginRow("权限与版本") {showVersions=true} }
                                item { PluginRow(if(showCode) "收起代码" else "查看代码") {showCode=!showCode} }
                                if(showCode) item {
                                    Surface(shape=RoundedCornerShape(14.dp),color=colors.panel) {
                                        SelectionContainer { Text(entry.latest().body.code,Modifier.padding(14.dp),fontFamily=FontFamily.Monospace,fontSize=12.sp,color=colors.ink) }
                                    }
                                }
                                item { TextButton(onClick={confirmDelete=true},enabled=!busy) { Text("删除插件",color=MaterialTheme.colorScheme.error) } }
                            }
                        }
                    }
                }
            }
            error?.let { message -> AlertDialog(onDismissRequest={error=null},title={Text("插件区")},text={Text(message)},confirmButton={TextButton(onClick={error=null}) {Text("知道了")}}) }
            if(confirmDelete && entry!=null) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("删除“${entry.latest().body.title}”？")},
                text={Text("删除后将停止使用，并移除插件版本、使用记录和这个插件自己的本机文件。其他插件与聊天保留。")},
                confirmButton={TextButton(onClick={confirmDelete=false; perform {host.delete(entry.id,entry.revision);opened=null}},enabled=!busy) {Text("删除")}},
                dismissButton={TextButton(onClick={confirmDelete=false}) {Text("取消")}})
            if(showRecords && entry!=null) PluginRecords(entry,owner) {showRecords=false}
            if(showVersions && entry!=null) AlertDialog(onDismissRequest={showVersions=false},title={Text("权限与版本")},text={
                Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    entry.versions.sortedByDescending {it.number}.forEach { version ->
                        Text("v${version.number}${if(version.number==entry.activeVersion) " · 当前版本" else ""}",fontWeight=FontWeight.SemiBold)
                        Text(pluginDate(version.createdAt),fontSize=12.sp)
                        Text(version.body.permissions.joinToString("、") {pluginPermissions.getValue(it)}.ifEmpty {"只计算传入的数据"},fontSize=13.sp)
                        TextButton(onClick={showVersions=false;review=entry.id to version.number},enabled=!busy) {Text(if(version.number==entry.activeVersion) "查看或调整权限" else "查看并使用此版本")}
                    }
                    Text("回退只切换代码版本，已经执行的外部操作不会撤销。",fontSize=12.sp)
                }
            },confirmButton={TextButton(onClick={showVersions=false}) {Text("完成")}})
            review?.let { (id,number) ->
                val target=data?.entries?.firstOrNull {it.id==id}
                val version=target?.versions?.firstOrNull {it.number==number}
                if(target!=null && version!=null) PluginReview(version,busy,onDismiss={review=null}) { permissions ->
                    perform { host.approve(id,target.revision,number,permissions);review=null }
                }
            }
        }
    }
}

@Composable private fun PluginStationery(palette: String,opacity: Float,content: @Composable ColumnScope.()->Unit) {
    val tint=pluginTint(palette)
    Surface(shape=RoundedCornerShape(22.dp),color=Color.Transparent,border=BorderStroke(1.dp,tint)) {
        Column(Modifier.background(Brush.verticalGradient(listOf(tint.copy(alpha=opacity),Color(0xFFFFFAF7).copy(alpha=opacity))))
            .padding(7.dp).border(1.dp,Color.White.copy(alpha=.75f),RoundedCornerShape(16.dp)).padding(13.dp),
            verticalArrangement=Arrangement.spacedBy(6.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            CompositionLocalProvider(LocalContentColor provides if(opacity>=.72f) Color(0xFF4E455F) else OrbisTheme.colors.ink) { content() }
        }
    }
}
@Composable private fun PluginCard(entry: PluginEntry,approved: Boolean,opacity: Float,modifier: Modifier,onClick: ()->Unit) {
    val body=entry.latest().body
    Surface(onClick=onClick,modifier=modifier.testTag("plugin-card-${entry.id}"),shape=RoundedCornerShape(22.dp),color=Color.Transparent) {
        PluginStationery(body.palette,opacity) {
            PluginArtwork(body.icon,pluginTint(body.palette))
            Text(body.title,fontSize=18.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(body.summary,fontSize=12.sp,lineHeight=18.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
            val status=when { entry.activeVersion!=entry.latest().number -> "待确认"; !approved -> "待授权"; entry.enabled -> "已启用"; else -> "已停用" }
            Surface(color=if(status=="已启用") Color(0xFFCAE5D9) else Color(0xFFE7DDEE),shape=RoundedCornerShape(12.dp)) {
                Text(status,Modifier.padding(horizontal=10.dp,vertical=3.dp),fontSize=10.sp,color=Color(0xFF4D625D))
            }
            HorizontalDivider(color=Color(0xFF9C91AC).copy(alpha=.4f))
            Text("近30天 ${entry.recentUses(System.currentTimeMillis())}次",fontSize=14.sp)
            Text("累计 ${entry.totalUses}次",fontSize=11.sp)
            Text(pluginDate(entry.updatedAt),fontSize=10.sp)
        }
    }
}
@Composable private fun PluginStat(label: String,count: Long) {
    Column(horizontalAlignment=Alignment.CenterHorizontally) { Text(label,color=OrbisTheme.colors.mutedInk,fontSize=12.sp);Text("$count 次",color=OrbisTheme.colors.ink,fontSize=28.sp) }
}
@Composable private fun PluginRow(title: String,onClick: ()->Unit) {
    Surface(onClick=onClick,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(15.dp),color=OrbisTheme.colors.panel) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {Text(title,Modifier.weight(1f),color=OrbisTheme.colors.ink);Text("›",fontSize=22.sp,color=OrbisTheme.colors.mutedInk)}
    }
}
internal fun pluginDate(time: Long)=SimpleDateFormat("MM月dd日 HH:mm",Locale.getDefault()).format(Date(time))

@Composable private fun PluginReview(version: PluginVersion,busy: Boolean,onDismiss: ()->Unit,onApprove: (Set<String>)->Unit) {
    var permissions by remember(version.hash) {mutableStateOf(emptySet<String>())}
    AlertDialog(onDismissRequest=onDismiss,title={Text("启用 ${version.body.title} · v${version.number}")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(version.body.introduction,fontSize=14.sp)
            Text("允许这个版本使用哪些能力？",fontWeight=FontWeight.SemiBold)
            if(version.body.permissions.isEmpty()) Text("只处理调用时传入的数据，不读取文件、不联网。")
            version.body.permissions.forEach { p -> Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(p in permissions,{yes -> permissions=if(yes) permissions+p else permissions-p},enabled=!busy)
                Text(pluginPermissions.getValue(p),fontSize=13.sp)
            } }
            if(version.body.networkHosts.isNotEmpty()) Text("网站：${version.body.networkHosts.joinToString("、")}",fontSize=12.sp)
            if(version.body.beforeResponse) Text("启用交稿检查后，这位助手的回复会先完成检查再显示。检查期间仅能读取插件自己的文件；检查不通过会保留草稿并提示，不会自动多次请求模型。",fontSize=12.sp)
            if("shell" in version.body.permissions) Text("终端可访问工作区文件和网络，也需要原工作区许可。仅在信任这个版本时勾选。",fontSize=12.sp)
            Text("未勾选的能力会被拒绝。你可随时停用，更新版本需重新确认。",fontSize=12.sp)
        }
    },confirmButton={TextButton(onClick={onApprove(permissions)},enabled=!busy,modifier=Modifier.testTag("plugin-approve")) {Text("启用这个版本")}},
        dismissButton={TextButton(onClick=onDismiss,enabled=!busy) {Text("暂不启用")}})
}
@Composable private fun PluginRecords(entry: PluginEntry,owner: String,close: ()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var jobs by remember(entry.id) {mutableStateOf(emptyList<PluginJob>())}
    var jobError by remember(entry.id) {mutableStateOf(false)}
    LaunchedEffect(entry.id,entry.runs) {
        try { jobs=withContext(Dispatchers.IO) {pluginJobs(context,owner).list().filter {it.pluginId==entry.id}} }
        catch(cancelled: CancellationException) {throw cancelled}
        catch(_: Exception) {jobError=true}
    }
    AlertDialog(onDismissRequest=close,title={Text("使用记录")},text={
        LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(jobError) item {Text("后台任务记录暂时无法读取，原记录保留。")}
            if(jobs.isNotEmpty()) item {Text("后台任务",fontWeight=FontWeight.SemiBold)}
            items(jobs,key={"job-${it.id}"}) {job -> Column {
                Text(job.intervalMinutes?.let {"每 $it 分钟检查一次"} ?: "一次性任务",fontWeight=FontWeight.SemiBold)
                Text(when(job.status) {"running"->"执行已开始，结果尚未确认";"completed"->"上次执行完成";"failed"->"上次执行失败";"unknown"->"上次结果未知，未自动重试";"paused"->"已暂停";else->"等待系统调度"},fontSize=12.sp)
                if(job.enabled && job.nextAt!=Long.MAX_VALUE) Text("下次最早 ${pluginDate(job.nextAt)}；系统可能推迟",fontSize=12.sp)
                TextButton(onClick={scope.launch {
                    try {withContext(Dispatchers.IO) {
                        val store=pluginJobs(context,owner);store.pause(job.id)
                        runCatching {PluginJobScheduler.cancel(context,owner,job.id)};store.delete(job.id)
                        jobs=store.list().filter {it.pluginId==entry.id}
                    }} catch(cancelled: CancellationException) {throw cancelled} catch(_: Exception) {jobError=true}
                }}) {Text("取消这项任务")}
            } }
            if(entry.runs.isEmpty()) item {Text("还没有执行记录。")}
            items(entry.runs.reversed(),key={it.id}) {run -> Column {
                val label=when(run.status) {"succeeded"->"已完成";"running"->"运行中";"timed_out"->"已超时停止";"interrupted","cancelled"->"已中断";else->"未完成"}
                Text("$label · v${run.version}",fontWeight=FontWeight.SemiBold)
                Text("${pluginDate(run.startedAt)} · ${run.elapsedMs} 毫秒",fontSize=12.sp)
                run.error?.let {Text(pluginErrorText(it),fontSize=12.sp)}
                run.calls.forEach { call ->
                    val result=when(call.outcome) {"returned"->"已返回结果";"not_started"->"未执行";"not_completed"->"未完成";else->"结果未知，请核对，勿自动重试"}
                    Text("${pluginDate(call.startedAt)} · ${pluginCallLabel(call.method)}：$result",fontSize=12.sp)
                }
            } }
            item {Text("展示最近 100 次执行；累计次数保留。失败不会自动重试。",fontSize=11.sp)}
        }
    },confirmButton={TextButton(onClick=close) {Text("完成")}})
}
