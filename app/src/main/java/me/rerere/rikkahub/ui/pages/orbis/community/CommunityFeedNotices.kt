package me.rerere.rikkahub.ui.pages.orbis.community

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.community.*
import me.rerere.rikkahub.data.orbis.market.*

/** Remains mounted while its selected post is open, preserving the list and scroll position. */
@Composable
internal fun CommunityFeedNotices(api:CommunityClient, actor:String, visible:Boolean, readReceipt:JsonObject?,
    close:()->Unit, open:(JsonObject)->Unit) {
    var rows by remember(actor) {mutableStateOf(emptyList<JsonObject>())}
    var next by remember(actor) {mutableStateOf<Long?>(null)}
    var busy by remember(actor) {mutableStateOf(false)}
    var error by remember(actor) {mutableStateOf("")}
    val scope=rememberCoroutineScope();val list=rememberLazyListState()
    suspend fun load(before:Long?=null) {
        if(busy) return
        busy=true;error=""
        try {
            val data=api.get("/feed/notices?limit=20"+(before?.let {"&before=$it"} ?: ""),actor)
            rows=(if(before==null) data.ma("items") else rows+data.ma("items")).distinctBy {it.ms("id")}
            next=(data["next_before"] as? JsonPrimitive)?.longOrNull
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {error=e.message ?: "提醒加载失败，请重试"}
        finally {busy=false}
    }
    LaunchedEffect(actor) {load()}
    LaunchedEffect(readReceipt) {
        val receipt=readReceipt ?: return@LaunchedEffect
        val comments=receipt.cs("read_comments").toSet()
        rows=rows.map {row ->
            if(row.ms("post")==receipt.ms("post") &&
                ((row.ms("comment").let {it.isEmpty() || it=="null"} && receipt.mb("post_read")) || row.ms("comment") in comments))
                JsonObject(row+mapOf("unread" to JsonPrimitive(false))) else row
        }
    }
    if(visible) CommunityFullPage("提醒我的",close,footer={
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(enabled=!busy,onClick={scope.launch {load()}}) {Text("刷新提醒")}
            TextButton(onClick=close) {Text("关闭")}
        }
    }) {
        Text("朋友圈中的提及与回复 · 点一条查看原动态",color=CommunityMuted,fontSize=12.sp)
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(error.isNotEmpty()) {
            Text(error,color=MaterialTheme.colorScheme.error)
            TextButton(enabled=!busy,onClick={scope.launch {load()}}) {Text("重试加载提醒")}
        }
        LazyColumn(Modifier.fillMaxSize().testTag("community-feed-notices"),state=list,
            contentPadding=PaddingValues(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            items(rows,key={it.ms("id")}) {row ->
                Column(Modifier.fillMaxWidth().clickable {open(row)}.padding(vertical=8.dp).testTag("community-feed-notice-${row.ms("id")}")) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(row["author"]?.jsonObject?.ms("name").orEmpty(),modifier=Modifier.weight(1f))
                        if(row.mb("unread")) Text("未读",color=CommunityInk,fontSize=12.sp)
                    }
                    Text(if(row.ms("kind")=="comment") "评论提醒" else "动态提醒",color=CommunityMuted,fontSize=12.sp)
                    Text(row.ms("text").ifBlank {"[图片动态]"},Modifier.padding(vertical=5.dp))
                    if(row.ms("kind")=="comment" && row.ms("post_text").isNotEmpty())
                        Text("来自：${row.ms("post_text")}",color=CommunityMuted,fontSize=12.sp,maxLines=2)
                    HorizontalDivider(color=CommunityLine)
                }
            }
            if(rows.isEmpty() && !busy && error.isEmpty()) item {Text("还没有收到朋友圈提醒",color=CommunityMuted)}
            if(next!=null) item {TextButton(enabled=!busy,onClick={scope.launch {load(next)}}) {Text("更多提醒")}}
        }
    }
}
