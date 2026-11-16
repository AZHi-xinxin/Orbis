package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.orbis.webshortcuts.*
import org.koin.compose.koinInject

@Composable
internal fun OrbisWebShortcutsPage(assistant: String, conversation: String? = null,
    initialUrl: String? = null, onOpenPage: ((WebShortcut) -> Unit)? = null, store: SettingsStore = koinInject(), onClose: () -> Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val owner=settings.assistants.firstOrNull { it.id.toString()==assistant }
    val repo=remember(assistant) { openWebShortcuts(context,assistant) }
    val revision by repo.changes.collectAsStateWithLifecycle()
    var shelf by remember(assistant) { mutableStateOf(WebShelf()) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember(assistant) { mutableStateOf<WebShortcut?>(null) }
    var readOnly by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(initialUrl!=null) }
    var url by remember { mutableStateOf(initialUrl.orEmpty()) }
    var title by remember { mutableStateOf("") }
    var grantOpen by remember { mutableStateOf(false) }
    val mcps=settings.mcpServers.filter { it.commonOptions.enable && it.id in owner?.mcpServers.orEmpty() }
    LaunchedEffect(assistant,revision) { try { shelf=withContext(Dispatchers.IO) { repo.snapshot() } }
        catch (e: Exception) { error="收藏读取失败，原文件保留，请稍后重试。" } }
    fun change(block: ()->Unit) { scope.launch {
        try { withContext(Dispatchers.IO) { block() };error=null }
        catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled }
        catch(e:Exception) { error="未能保存，请检查网址格式和本机存储。" }
    } }
    OrbisInterfaceTheme {
    ModalBottomSheet(onDismissRequest=onClose,containerColor=OrbisTheme.colors.panel,
        contentColor=OrbisTheme.colors.ink, tonalElevation=0.dp) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).testTag("web-shortcuts-sheet").padding(horizontal=14.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={if(page!=null) {page=null;readOnly=false} else onClose()}) { Text(if(page!=null) "‹ 收藏" else "返回") }
                Text(page?.title ?: "快捷网页",Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                TextButton(onClick=onClose) { Text("收起") }
            }
            if(owner==null) { Text("原助手不可用，请从原聊天重新打开。");return@Column }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            val current=page
            if(current==null) {
                Text("普通网页只读浏览；自建 MCP 游戏可按站点开启交互。浏览和操作记录不会发给 AI。",style=MaterialTheme.typography.bodySmall)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("允许 AI 管理并展示网页",Modifier.weight(1f))
                    Switch(LocalToolOption.WebShortcuts in owner.localTools,onCheckedChange={ enabled ->scope.launch {
                        store.update { value ->value.copy(assistants=value.assistants.map { a -> if(a.id!=owner.id) a else a.copy(
                            localTools=if(enabled) a.localTools+LocalToolOption.WebShortcuts else a.localTools-LocalToolOption.WebShortcuts) }) }
                    } })
                }
                Text("开启后 AI 可管理收藏，并直接在所属聊天弹出网页小窗。不会获得网页登录状态或浏览记录。",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={addOpen=true},modifier=Modifier.fillMaxWidth()) { Text("＋ 保存网页链接") }
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(shelf.entries.isEmpty()) item { Text("还没有收藏。可以粘贴链接，或从浏览器分享到 Orbis 后保存。") }
                    items(shelf.entries.asReversed(),key={it.id}) { entry ->
                        OutlinedCard(onClick={if(onOpenPage != null) onOpenPage(entry) else {page=entry;readOnly=false}},modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(entry.title,style=MaterialTheme.typography.titleSmall)
                                Text(shortcutOrigin(entry.url),style=MaterialTheme.typography.bodySmall)
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Text((if(entry.source=="human") "你保存的" else "AI 保存的")+" · "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(entry.createdAt)),Modifier.weight(1f),style=MaterialTheme.typography.labelSmall)
                                    TextButton(onClick={change {repo.remove(entry.id,true)}}) {Text("移除")}
                                }
                            }
                        }
                    }
                }
            } else {
                val origin=shortcutOrigin(current.url)
                val grant=shelf.interactiveOrigins[origin]
                val interactive=!readOnly && mcps.any { it.id.toString()==grant }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(origin,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                    TextButton(onClick={ if(interactive) change {repo.allowInteraction(current.url,null)} else grantOpen=true }) {
                        Text(if(interactive) "交互已开 · 关闭" else "只读 · MCP 游戏")
                    }
                }
                Text(if(interactive) "使用网页自己的游戏账号或房间。AI 需通过对应 MCP 查询状态；Orbis 不回传操作。"
                    else "只读模式不登录、不填表。需要脚本的网页可能无法完整显示。",style=MaterialTheme.typography.bodySmall)
                if(shelf.entries.none {it.url==current.url}) TextButton(onClick={ change {repo.add(current.url,current.title,"human",conversation)} }) {Text("保存此页")}
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    OrbisShortcutWebView(assistant,current.url,interactive) { link ->
                        page=WebShortcut("reading",link,shortcutOrigin(link),"human");readOnly=true
                    }
                }
            }
        }
    }
    if(addOpen) AlertDialog(modifier=Modifier.testTag("web-shortcuts-add-dialog"),onDismissRequest={addOpen=false},title={Text("保存网页")},text={Column {
        OutlinedTextField(url,{url=it},label={Text("http / https 链接")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(title,{title=it},label={Text("名称（选填）")},modifier=Modifier.fillMaxWidth())
        error?.let {Text(it)}
    }},confirmButton={TextButton(onClick={scope.launch {
        try { withContext(Dispatchers.IO) {repo.add(url,title,"human",conversation)};addOpen=false;url="";title="";error=null }
        catch(e:Exception) {error="未保存，请填写有效的 http/https 链接。"}
    }}) {Text("保存")}},dismissButton={TextButton(onClick={addOpen=false}) {Text("取消")}})
    if(grantOpen && page!=null) AlertDialog(onDismissRequest={grantOpen=false},title={Text("开启这个 MCP 游戏的交互")},text={
        Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .6f).dp)
            .verticalScroll(rememberScrollState())) {
            Text("仅为 ${shortcutOrigin(page!!.url)} 开启脚本和该网页自己的登录存储。请选它对应的已启用 MCP；网页与 MCP 须使用同一账号或房间。这里不会复制 MCP 密钥，也不会替你登录。")
            if(mcps.isEmpty()) Text("当前助手还没有启用的 MCP。先在连接设置中配置对应游戏，再回来关联。")
            mcps.forEach { mcp -> TextButton(onClick={val target=page!!;change {repo.allowInteraction(target.url,mcp.id.toString())};readOnly=false;grantOpen=false}) {Text("关联："+mcp.commonOptions.displayTitle)} }
        }
    },confirmButton={TextButton(onClick={grantOpen=false}) {Text("保持只读")}})
    }
}
