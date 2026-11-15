package me.rerere.rikkahub.ui.pages.orbis.community

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.community.*
import me.rerere.rikkahub.data.orbis.market.*

@Composable internal fun PixelAppearance(outfit:JsonObject,modifier:Modifier=Modifier,portrait:Boolean=false,item:String?=null) {
    val context=LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null,outfit,portrait,item) {
        try {value=withContext(Dispatchers.Default) {if(item!=null) CommunityAvatarRenderer.thumbnail(context,item) else CommunityAvatarRenderer.render(context,outfit,portrait)}}
        catch(e:CancellationException){throw e}catch(_:Exception){value=null}
    }
    if(bitmap!=null) Image(bitmap!!.asImageBitmap(),if(item==null) "像素人物装扮" else "部件预览",modifier,contentScale=ContentScale.Fit)
    else Box(modifier,contentAlignment=Alignment.Center){Text("☆",color=CommunityMuted)}
}

@Composable internal fun CommunityWardrobeDialog(api:CommunityClient,person:JsonObject,close:()->Unit,saved:()->Unit) {
    PixelWardrobeDialog(person,"community","社区装扮",
        load={api.get("/wardrobe",person.ms("id"))},
        save={body->communityWardrobe(api,person.ms("id"),JsonObject(body+mapOf("action" to JsonPrimitive("wardrobe"),"operation" to JsonPrimitive("save"))))},
        close=close,saved=saved)
}

@Composable internal fun PixelWardrobeDialog(person:JsonObject,wardrobeScope:String,label:String,
    load:suspend ()->JsonObject,save:suspend (JsonObject)->JsonObject,close:()->Unit,saved:()->Unit) {
    val actor=person.ms("id");val context=LocalContext.current;val scope=rememberCoroutineScope()
    val catalog=remember {CommunityAvatarRenderer.catalog(context)}
    var current by remember(actor) {mutableStateOf<JsonObject?>(null)}
    var draft by remember(actor) {mutableStateOf(buildJsonObject {})}
    var category by remember {mutableStateOf("hair")}
    var faceEmotion by remember(actor) {mutableStateOf("平静")}
    var busy by remember {mutableStateOf(false)}
    var note by remember {mutableStateOf("")}
    fun stored()=current?.get(wardrobeScope)?.jsonObject ?: buildJsonObject {}
    LaunchedEffect(actor,wardrobeScope) {
        try {
            current=load()["wardrobe"]!!.jsonObject;draft=stored()
            faceEmotion=catalog.ma("items").firstOrNull {it.ms("id")==draft.ms("faces")}?.ms("emotion")?.ifBlank {"平静"} ?: "平静"
        }
        catch(e:CancellationException){throw e}catch(e:Exception){note=e.message ?: "暂时无法读取更衣室"}
    }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        CommunityTheme {Surface(Modifier.fillMaxSize(),color=CommunityPaper) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal=14.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("${person.ms("name")}的更衣室",Modifier.weight(1f),fontSize=21.sp)
                    TextButton(onClick=close){Text("关闭")}
                }
                Text(label+" · 独立保存",fontSize=12.sp,color=CommunityMuted)
                Box(Modifier.fillMaxWidth().height(192.dp).background(CommunitySage),contentAlignment=Alignment.Center) {
                    if(current!=null) PixelAppearance(draft,Modifier.fillMaxHeight().width(150.dp)) else if(note.isBlank()) CircularProgressIndicator()
                }
                if(note.isNotBlank()) Text(note,fontSize=12.sp,color=CommunityMuted,modifier=Modifier.padding(vertical=5.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    for((id,label) in catalog["categories"]!!.jsonObject) FilterChip(selected=category==id,onClick={category=id},label={Text(label.jsonPrimitive.content)})
                }
                val parts=catalog.ma("items").filter {it.ms("category")==category}
                if(category=="faces") Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    for(emotion in parts.map {it.ms("emotion").ifBlank {it.ms("name")}}.distinct()) {
                        FilterChip(selected=faceEmotion==emotion,onClick={faceEmotion=emotion},label={Text(emotion)})
                    }
                }
                val rows=if(category=="faces")parts.filter {it.ms("emotion").ifBlank {it.ms("name")}==faceEmotion} else parts
                LazyVerticalGrid(GridCells.Adaptive(100.dp),modifier=Modifier.weight(1f).testTag("community-wardrobe-items"),horizontalArrangement=Arrangement.spacedBy(7.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    items(rows,key={it.ms("id")}) {item->
                        val id=item.ms("id");val selected=if(category=="accessories") id in draft.cs("accessories") else draft.ms(category)==id
                        OutlinedButton(modifier=Modifier.testTag("wardrobe-part-$id"),enabled=current!=null && !busy,onClick={
                            if(category=="accessories") {
                                val previous=draft.cs("accessories")
                                val occupied=catalog.ma("items").filter {it.ms("slot")==item.ms("slot")}.map {it.ms("id")}.toSet()
                                val next=if(id in previous) previous-id else previous.filterNot {it in occupied}+id
                                if(next.size>4) note="配饰最多四件，请先取下一件" else {draft=JsonObject(draft+mapOf("accessories" to JsonArray(next.map(::JsonPrimitive))));note=""}
                            }else {
                                draft=JsonObject((draft-(if(category=="pants") "skirts" else if(category=="skirts") "pants" else ""))+mapOf(category to JsonPrimitive(id)));note=""
                            }
                        },contentPadding=PaddingValues(5.dp),colors=ButtonDefaults.outlinedButtonColors(containerColor=if(selected) CommunitySage else CommunityPaper)) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally) {PixelAppearance(buildJsonObject {},Modifier.size(72.dp),item=id);Text((if(selected) "✓ " else "")+item.ms("name"),fontSize=11.sp,maxLines=2)}
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(category=="accessories") TextButton(enabled=!busy,onClick={draft=JsonObject(draft+mapOf("accessories" to JsonArray(emptyList())))}) {Text("取下配饰")}
                    Button(enabled=current!=null && !busy,modifier=Modifier.weight(1f).testTag("community-wardrobe-save"),onClick={scope.launch {
                        busy=true
                        try {
                            val result=save(buildJsonObject {put("target",wardrobeScope);put("appearance",draft);put("expected_revision",current!!.cl("revision"))})
                            current=result["wardrobe"]!!.jsonObject;draft=stored();note=label+"已保存";saved()
                        }catch(e:CancellationException){throw e}catch(e:Exception){note=e.message ?: "保存未完成，请核对回执"}finally{busy=false}
                    }}) {Text(if(busy) "保存中…" else "保存当前装扮")}
                }
            }
        }}
    }
}
