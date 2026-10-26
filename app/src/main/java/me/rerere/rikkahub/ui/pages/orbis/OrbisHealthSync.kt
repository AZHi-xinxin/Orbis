package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.health.*
import org.koin.compose.koinInject

@Composable internal fun HealthSyncEntry(){
    var open by remember{mutableStateOf(false)}
    TextButton(onClick={open=true}){Text("设备自动同步")}
    if(open){
        val context=LocalContext.current
        val store:SettingsStore=koinInject()
        val settings by store.settingsFlow.collectAsState()
        val signal by healthChanges.collectAsState()
        var state by remember{mutableStateOf(HealthSyncSettings())}
        var busy by remember{mutableStateOf(false)}
        var error by remember{mutableStateOf<String?>(null)}
        val scope=rememberCoroutineScope()
        LaunchedEffect(signal){withContext(Dispatchers.IO){runCatching{AndroidHealthSync.read(context)}}.onSuccess{state=it}.onFailure{error="同步配置暂时无法读取"}}
        fun task(action:suspend ()->Unit){if(busy)return;busy=true;scope.launch{
            try{withContext(Dispatchers.IO){action()};error=null}
            catch(e:CancellationException){throw e}catch(_:Exception){error="未完成，请检查连接后重试"}finally{busy=false}
        }}
        HealthSyncContent(state,settings.mcpServers.filter(::supportsHealthSync),busy,error,
            onClose={open=false},onConfigure={server,enabled->task{AndroidHealthSync.configure(context,server,enabled)}},
            onSync={task{AndroidHealthSync.sync(context,true)}})
    }
}

@Composable internal fun HealthSyncContent(state:HealthSyncSettings,servers:List<McpServerConfig>,busy:Boolean,error:String?,
    onClose:()->Unit,onConfigure:(McpServerConfig?,Boolean)->Unit,onSync:()->Unit){
    AlertDialog(onDismissRequest=onClose,title={Text("设备自动同步")},text={
        Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("从已连接的 hcMCP 读取步数、睡眠和当次心率，自动记入人类记录。无需让 AI 转填。")
            Text("每天按手机时间在 23:50 后读取一次，跨日后补齐；省电或离线可能延后，恢复后补拉近 7 天。",style=MaterialTheme.typography.bodySmall)
            if(servers.isEmpty())Text("还没有可用来源。在设置 → MCP 连接 hcMCP，并启用 read_health_data 后返回。")
            servers.forEach{server->
                val selected=server.id.toString()==state.serverId&&state.enabled
                OutlinedCard(Modifier.fillMaxWidth()){
                    Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){
                        Column(Modifier.weight(1f)){Text(server.commonOptions.name);Text(if(selected)"自动同步已开启"else"现有 MCP 连接",style=MaterialTheme.typography.bodySmall)}
                        Switch(selected,{onConfigure(server,it)},enabled=!busy)
                    }
                }
            }
            if(state.enabled&&servers.none{it.id.toString()==state.serverId})TextButton(enabled=!busy,onClick={onConfigure(null,false)}){Text("关闭已失效来源")}
            Text(state.status,style=MaterialTheme.typography.bodyMedium)
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            Text("只读取设备记录，不上传本机填写内容，不调用聊天模型。你手动校正的值会保留；手填和 AI 填写继续可用。",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(enabled=state.enabled&&!busy,onClick=onSync){Text(if(busy)"同步中…"else"立即同步")}},
        dismissButton={TextButton(onClick=onClose){Text("完成")}})
}
