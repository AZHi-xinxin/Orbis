package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.orbis.tabletop.*
import me.rerere.rikkahub.service.ChatService
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun TabletopPreparationDialog(assistant:String,room:String,close:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val repo=koinInject<ConversationRepository>();val chat=koinInject<ChatService>()
    val prefs=remember { context.getSharedPreferences("tabletop-private-preparation",0) }
    val key="$assistant:$room";var restore by remember { mutableStateOf(false) };var offset by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<Conversation>>(emptyList()) };var next by remember { mutableStateOf<Int?>(null) }
    var chosen by remember { mutableStateOf<Conversation?>(null) };var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("由你点击后，向选定的原窗口发送一次准备请求。大厅本身不会唤醒 AI。") }
    LaunchedEffect(offset) { try { val page=repo.getConversationsOfAssistantPage(Uuid.parse(assistant),offset,10);items=page.items;next=page.nextOffset } catch(e:Exception){status=e.message ?: "窗口列表未读取"} }
    AlertDialog(onDismissRequest=close,title={Text("开局准备 · 散场恢复")},text={
        Column(Modifier.fillMaxWidth()) {
            Text("私人哨兵操作和回执只留在手机原窗口，不上传圆桌。是否保留任务由你和 AI 决定。")
            Row { FilterChip(selected=!restore,onClick={restore=false},label={Text("开局准备")});Spacer(Modifier.width(8.dp));FilterChip(selected=restore,onClick={restore=true},label={Text("散场恢复")}) }
            Text("选择已有窗口：${chosen?.title ?: "未选择"}")
            LazyColumn(Modifier.heightIn(max=180.dp)) { items(items,key={it.id.toString()}) { c->TextButton(enabled=!busy,onClick={chosen=c}){Text(c.title.ifBlank { "未命名聊天" })} } }
            Row { TextButton(enabled=offset>0&&!busy,onClick={offset=(offset-10).coerceAtLeast(0)}){Text("上一页")};TextButton(enabled=next!=null&&!busy,onClick={offset=next!!}){Text("下一页")} }
            Text(status,style=MaterialTheme.typography.bodySmall)
            TextButton(enabled=!busy,onClick={scope.launch {
                busy=true
                try {
                    val saved=prefs.getString(key+if(restore)":restore" else ":prepare",null)
                    check(saved!=null){"还没有本次准备记录"}
                    val receipt=Json.parseToJsonElement(saved).jsonObject;val id=Uuid.parse(receipt["conversation"]!!.jsonPrimitive.content)
                    chat.initializeDisplayedConversation(id)
                    status=tabletopPreparationReceipt(chat.getDisplayedConversationFlow(id).value.currentMessages,receipt["request"]!!.jsonPrimitive.content,restore)
                }catch(e:CancellationException){throw e}catch(e:Exception){status=e.message ?: "核对未完成"}finally{busy=false}
            }}){Text("核对原窗口工具回执")}
        }
    },confirmButton={TextButton(enabled=chosen!=null&&!busy,onClick={scope.launch {
        busy=true
        try {
            check(!chat.hasActiveGeneration()){ "AI 正在生成，请空闲后再发送准备请求。" }
            val target=chosen!!;val suffix=if(restore)":restore" else ":prepare"
            check(prefs.getString(key+suffix,null)==null){"本局这项请求已有记录，请核对原窗口；不重复发送。"}
            chat.initializeDisplayedConversation(target.id);chat.requireGardenQuickChatTarget(target.id,Uuid.parse(assistant))
            val request=java.util.UUID.randomUUID().toString()
            val prior=prefs.getString(key+":prepare",null)?.let { Json.parseToJsonElement(it).jsonObject["request"]?.jsonPrimitive?.content }
            val record=buildJsonObject { put("request",request);put("conversation",target.id.toString());put("restore",restore) }
            check(prefs.edit().putString(key+suffix,record.toString()).commit()){ "无法保存请求编号，尚未发送。" }
            val committed=chat.trySendGardenQuickChat(target.id,Uuid.parse(assistant),tabletopPreparationPrompt(request,room,restore,prior))
            status=if(committed)"请求已存入原窗口。请在那里查看回复和审批，再回来核对工具回执。" else "输入是否存入尚未确认，请核对原窗口，不自动重发。"
        }catch(e:CancellationException){throw e}catch(e:Exception){status=e.message ?: "尚未完成，请核对原窗口。"}finally{busy=false}
    }}){Text("发送一次请求")}},dismissButton={TextButton(onClick=close){Text("返回圆桌")}})
}
