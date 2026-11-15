package me.rerere.rikkahub.ui.pages.orbis.community

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.community.*
import me.rerere.rikkahub.data.orbis.market.*
import me.rerere.rikkahub.ui.pages.orbis.OrbisTabletopPage

private fun JsonObject.seconds(k:String)=(get(k) as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0L
private fun gatheringTime(t:Long)=DateTimeFormatter.ofPattern("MM 月 dd 日 HH:mm").format(Instant.ofEpochSecond(t).atZone(ZoneId.systemDefault()))

@Composable internal fun CommunityGatherings(community:CommunityClient,initialIdentities:List<JsonObject>,selected:String,assistantId:String,gatherings:GatheringClient?=null,close:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val clipboard=LocalClipboardManager.current
    val api=remember {gatherings ?: GatheringClient(context)}
    var identities by remember {mutableStateOf(initialIdentities)}
    val human=identities.firstOrNull {it.ms("kind")=="human"}
    val actor=human?.ms("id").orEmpty()
    val choices=identities.filter {it.ms("kind")=="ai" && it.ms("local_key").isNotBlank()}
    var mine by rememberSaveable {mutableStateOf(false)}
    var rows by remember {mutableStateOf(emptyList<JsonObject>())}
    var next by remember {mutableStateOf<Int?>(null)}
    var detail by remember {mutableStateOf<JsonObject?>(null)}
    var options by remember {mutableStateOf(emptyList<JsonObject>())}
    var form by rememberSaveable {mutableStateOf("")}
    var option by remember {mutableStateOf<JsonObject?>(null)}
    var mode by rememberSaveable {mutableStateOf("ai_only")}
    var start by remember {mutableLongStateOf(Instant.now().plusSeconds(3600).epochSecond/60*60)}
    var ai by rememberSaveable {mutableStateOf("")}
    var chooseAction by remember {mutableStateOf("")}
    var confirmation by remember {mutableStateOf("")}
    var lookup by rememberSaveable {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    var error by remember {mutableStateOf(false)}
    var openedGame by remember {mutableStateOf<Pair<String,String>?>(null)}
    fun run(block:suspend ()->Unit) {
        if(busy)return
        busy=true;message="";error=false
        scope.launch {try {block()}catch(e:CancellationException){throw e}catch(e:Exception){error=true;message=e.message ?: "暂时无法获取拼桌信息"}finally{busy=false}}
    }
    suspend fun listing(append:Boolean=false) {
        val page=api.get("list",mapOf("mine" to mine.toString(),"offset" to if(append) (next ?: 0).toString() else "0"))
        rows=if(append)(rows+page.ma("items")).distinctBy {it.ms("code")} else page.ma("items")
        next=page["next_offset"]?.jsonPrimitive?.intOrNull
    }
    suspend fun refresh() {val d=detail;if(d==null)listing() else detail=api.get("detail",mapOf("table" to d.ms("code")))}
    fun back() {when {form.isNotEmpty()->form="";detail!=null->{detail=null;run {listing()}};else->close()}}
    fun pickTime() {
        val at=Instant.ofEpochSecond(start).atZone(ZoneId.systemDefault())
        DatePickerDialog(context,{_,year,month,day ->
            TimePickerDialog(context,{_,hour,minute ->start=LocalDateTime.of(year,month+1,day,hour,minute).atZone(ZoneId.systemDefault()).toEpochSecond()},at.hour,at.minute,true).show()
        },at.year,at.monthValue-1,at.dayOfMonth).show()
    }
    fun selectAi(action:String) {
        if(choices.isEmpty()){message="请先在社区“我的”为 AI 建立名片。";return}
        ai=choices.firstOrNull {it.ms("id") in detail?.cs("mine").orEmpty()}?.ms("id")
            ?: choices.firstOrNull {it.ms("local_key")==assistantId}?.ms("id") ?: choices.first().ms("id")
        chooseAction=action
    }
    suspend fun mutate(op:String,chosen:String=ai) {
        val target=choices.singleOrNull {it.ms("id")==chosen}
        val local=target?.ms("local_key").orEmpty()
        val conversation=if(op in setOf("signup","create","resolve")) {
            check(local.isNotBlank() && CommunityRuntime.enabled(local)) {"请先在这位 AI 的本地能力中开启“社区”工具，再报名。"}
            GatheringDelivery.target(context,local)
        } else null
        val result=api.post(op,actor,buildJsonObject {
            if(chosen.isNotBlank())put("ai",chosen)
            detail?.let {put("table",it.ms("code"));put("revision",it.mi("revision"))}
            if(op=="create") {val o=option ?: error("请选择游戏");put("game",o.ms("game"));put("mode",mode);if(o.ms("script").isNotBlank())put("script",o.ms("script"))}
            if(op in setOf("create","reschedule")){put("starts_at",start);put("timezone",ZoneId.systemDefault().id)}
        })
        detail=result;form="";chooseAction="";confirmation=""
        if(op=="resolve") {
            GatheringDelivery.join(context,local,conversation!!,result)
            openedGame=local to result.ms("game")
        } else {
            message=when(op) {
                "signup","create"->"报名已保存，已为对应 AI 生成提醒回执。哨兵是否设置成功，以 AI 的工具回执为准。"
                "cancel"->"本次拼桌已解散，全部名额已释放，已通知相关 AI 取消对应哨兵。"
                "withdraw"->"已取消自己的报名，其他成员的接龙不受影响。对应 AI 会收到取消提醒。"
                else->"拼桌信息已更新，相关 AI 会收到变更通知。"
            }
            try {GatheringDelivery.receive(context,preferredAssistant=local,preferredConversation=conversation)}
            catch(e:CancellationException){throw e}catch(_:Exception){message+=" 当前通知尚待接收，连接恢复后会继续投递。"}
        }
    }
    LaunchedEffect(mine) {run {identities=community.identities();listing();if(options.isEmpty())options=api.get("options").ma("items")}}
    val d=detail
    LaunchedEffect(d?.ms("code"),d?.seconds("starts_at"),d?.ms("state"),d?.mb("can_join"),busy,form) {
        if(d!=null && d.ms("state")=="reserved" && !busy && form.isEmpty()) {
            val nextTime=if(!d.mb("can_join"))d.seconds("starts_at") else d.seconds("open_at")
            delay(((nextTime-d.seconds("server_time")).coerceAtLeast(1)+1)*1000)
            run {refresh()}
        }
    }
    CommunityFullPage(if(form=="create") "发起拼桌" else if(form=="reschedule") "修改开局时间" else if(d!=null) "拼桌详情" else "拼桌大厅",::back,
        actions={
            if(form.isEmpty() && d==null)TextButton(enabled=!busy,onClick={option=options.firstOrNull();mode=option?.ms("mode")?.ifBlank {"ai_only"} ?: "ai_only";form="create"}) {Text("＋ 发起拼桌",fontSize=12.sp)}
            else if(form.isEmpty())TextButton(enabled=!busy,onClick={run {refresh()}}) {Text("刷新")}
        },footer={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                when {
                    form=="create"->Button(enabled=!busy && option!=null && actor.isNotBlank(),onClick={selectAi("create")},modifier=Modifier.fillMaxWidth()) {Text("选择搭档并发起拼桌")}
                    form=="reschedule"->Button(enabled=!busy,onClick={run {mutate("reschedule")}},modifier=Modifier.fillMaxWidth()) {Text("保存时间并通知已报名 AI")}
                    d!=null && d.ms("state") in setOf("reserved","started","open")-> {
                        val signed=d.cs("mine").isNotEmpty()
                        val open=d.ms("state")=="open"
                        Button(enabled=!busy && (open || if(signed) d.mb("can_join") else d.ms("state")=="reserved" && gatheringHasSpace(d)),onClick={selectAi(if(signed || open) "resolve" else "signup")},modifier=Modifier.fillMaxWidth()) {
                            Text(if(open && !signed) "进入普通房" else if(signed && d.mb("can_join")) "进入这张桌" else if(signed) "已报名 · 等待开局" else if(!gatheringHasSpace(d)) "接龙已满" else "报名接龙")
                        }
                        Text(if(open) "已转普通房，原留位已解除；所有人可从游戏大厅直接进入。" else "${gatheringTime(d.seconds("open_at"))} 仍未开局，将解除留位，原房转为普通房。",fontSize=11.sp,color=CommunityMuted)
                        if(d.ms("state") in setOf("reserved","open")) {
                            if(d.mb("can_manage"))OutlinedButton(enabled=!busy,onClick={confirmation="cancel"},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error)) {Text("取消拼桌 · 解散整桌")}
                            else if(!open && signed)OutlinedButton(enabled=!busy,onClick={selectAi("withdraw")},modifier=Modifier.fillMaxWidth()) {Text("取消我的报名")}
                        }
                    }
                    else->Text("约好时间，在这里找到彼此。",fontSize=12.sp,color=CommunityMuted)
                }
                if(message.isNotBlank())Text(message,color=if(error)MaterialTheme.colorScheme.error else CommunityMuted,fontSize=12.sp)
                if(error)TextButton(enabled=!busy && actor.isNotBlank(),onClick={run {val r=api.reconcile(actor);message=r.ms("message").ifBlank {"上次操作已核对，请查看当前接龙。"};refresh()}}) {Text("核对上次操作")}
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }) {
        when {
            form.isNotEmpty()->Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(form=="create") {
                    Text("选择游戏",fontSize=18.sp,modifier=Modifier.padding(top=14.dp))
                    options.forEach {o->CommunityChoice(o.ms("title"),"${o.mi("capacity")} 席",option==o) {option=o;mode=o.ms("mode").ifBlank {o.cs("modes").firstOrNull() ?: "mixed"}}}
                    if(option?.cs("modes").orEmpty().size>1) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        option!!.cs("modes").forEach {v->FilterChip(selected=mode==v,onClick={mode=v},label={Text(if(v=="mixed")"人类 + AI" else "AI 专场")})}
                    }
                    Text(if(mode=="mixed") "接龙以一对为单位：你和自己的 AI 一起占两个席位。" else "接龙以 AI 为单位；人类可以替自己的 AI 报名。",color=CommunityMuted,fontSize=12.sp)
                }
                Text("开局时间",fontSize=18.sp)
                OutlinedButton(onClick=::pickTime,modifier=Modifier.fillMaxWidth()) {Text(gatheringTime(start))}
                Text("时区：${ZoneId.systemDefault().id}。可预约未来七天；桌号会在发起成功后分配。",fontSize=12.sp,color=CommunityMuted)
                Spacer(Modifier.height(16.dp))
            }
            d!=null->Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Surface(color=CommunitySage,shape=RoundedCornerShape(14.dp),modifier=Modifier.padding(top=14.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(d.ms("title"),fontSize=21.sp,fontWeight=FontWeight.Medium)
                        Text(gatheringTime(d.seconds("starts_at"))+" · "+if(d.ms("mode")=="mixed") "人类 + AI" else "AI 专场")
                        Text("${d["creator"]?.jsonObject?.ms("name").orEmpty()} 发起 · ${gatheringOccupancy(d)}",fontSize=12.sp,color=CommunityMuted)
                        Text("实际入座 ${d.mi("occupied")} / ${d.mi("capacity")} 席"+(if(d.mb("public_open")) " · 预约留位已解除" else ""),fontSize=12.sp,color=CommunityMuted)
                        HorizontalDivider(color=CommunityLine)
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(d.ms("code"),fontSize=19.sp,modifier=Modifier.weight(1f))
                            TextButton(onClick={clipboard.setText(AnnotatedString(d.ms("code")));message="桌号已复制"}) {Text("复制桌号")}
                        }
                    }
                }
                Text(when(d.ms("state")){"open"->"已转普通房 · 原接龙名单";"expired"->"原房间已关闭";"cancelled"->if(d.mb("disbanded")) "房主已解散本次拼桌" else "发起人已取消预约";"ended"->"本桌已结束";"started"->"本桌已开局";else->"报名接龙 · ${gatheringOccupancy(d)}"},fontSize=16.sp)
                d.ma("members").forEachIndexed {index,m->
                    Row(Modifier.fillMaxWidth().padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("${index+1}",color=CommunityMuted)
                        CommunityAvatar(buildJsonObject {put("id",m.ms("ai"));put("name",m.ms("name"));put("kind","ai")},32)
                        Text((if(d.ms("mode")=="mixed")m.ms("human_name")+" ＋ " else "")+m.ms("name")+"（AI）",modifier=Modifier.weight(1f))
                    }
                    HorizontalDivider(color=CommunityLine)
                }
                if(d.ms("state")=="reserved") {
                    OutlinedButton(enabled=!busy && gatheringHasSpace(d),onClick={selectAi("signup")},modifier=Modifier.fillMaxWidth()) {Text(if(gatheringHasSpace(d)) "＋ 在这里接龙报名" else "接龙已满")}
                    Text("报名会给对应 AI 发送游戏、桌号与完整开局时间，请它自行设置哨兵。AI 关闭社区工具或暂停哨兵时，请先检查设置。",color=CommunityMuted,fontSize=12.sp)
                    if(d.mb("can_manage"))Row {
                        TextButton(enabled=!busy,onClick={start=d.seconds("starts_at");form="reschedule"}) {Text("修改时间")}
                    }
                }
            }
            else->{
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    FilterChip(!mine,onClick={mine=false},label={Text("全部拼桌")})
                    FilterChip(mine,onClick={mine=true},label={Text("我报名的")})
                    TextButton(enabled=!busy,onClick={run {listing()}}){Text("刷新")}
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(lookup,{lookup=it.uppercase().take(24)},modifier=Modifier.weight(1f),placeholder={Text("输入桌号 PT-…")},singleLine=true)
                    TextButton(enabled=!busy && lookup.isNotBlank(),onClick={run {detail=api.get("detail",mapOf("table" to lookup.trim()))}}){Text("找桌")}
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(rows,key={it.ms("code")}) {r->
                        Column(Modifier.fillMaxWidth().clickable(enabled=!busy) {run {detail=api.get("detail",mapOf("table" to r.ms("code")))}}.padding(vertical=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(r.ms("title")+if(r.ms("mode")=="mixed") " · 人类 + AI" else " · AI 专场",fontSize=18.sp)
                            Text(gatheringTime(r.seconds("starts_at")),fontSize=14.sp)
                            Text("${r["creator"]?.jsonObject?.ms("name").orEmpty()} 发起 · ${r.ms("code")}",fontSize=12.sp,color=CommunityMuted)
                            Row {Text(gatheringOccupancy(r),modifier=Modifier.weight(1f),color=MaterialTheme.colorScheme.secondary);Text("查看接龙 ›",color=CommunityMuted)}
                            if(r.mb("can_manage") && r.ms("state") in setOf("reserved","open"))TextButton(enabled=!busy,onClick={run {detail=api.get("detail",mapOf("table" to r.ms("code")));confirmation="cancel"}},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) {Text("取消拼桌")}
                            else if(r.ms("state")=="reserved" && r.cs("mine").isNotEmpty())TextButton(enabled=!busy,onClick={run {detail=api.get("detail",mapOf("table" to r.ms("code")));selectAi("withdraw")}}) {Text("取消我的报名")}
                        }
                        HorizontalDivider(color=CommunityLine)
                    }
                    if(rows.isEmpty() && !busy)item {Text("这里还没有拼桌。可以从右上角发起。",Modifier.padding(vertical=30.dp),color=CommunityMuted)}
                    if(next!=null)item {TextButton(enabled=!busy,onClick={run {listing(true)}},modifier=Modifier.fillMaxWidth()) {Text("加载更多")}}
                }
            }
        }
    }
    if(chooseAction.isNotEmpty())AlertDialog(onDismissRequest={if(!busy)chooseAction=""},title={Text(if(chooseAction=="withdraw")"取消哪位 AI 的报名" else "选择参加的 AI")},text={
        Column(Modifier.heightIn(max=350.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            choices.filter {chooseAction !in setOf("withdraw","resolve") || (chooseAction=="resolve" && detail?.mb("public_open")==true) || it.ms("id") in detail?.cs("mine").orEmpty()}.forEach {who->
                CommunityChoice(who.ms("name"),if(chooseAction=="withdraw") "只取消自己的接龙，其他成员保留" else if((detail?.ms("mode") ?: mode)=="mixed") "你和这位 AI 一起报名" else "为这位 AI 报名",ai==who.ms("id")) {ai=who.ms("id")}
            }
        }
    },confirmButton={TextButton(enabled=!busy && ai.isNotBlank(),onClick={val action=chooseAction;chooseAction="";run {mutate(action)}}) {Text(if(chooseAction=="resolve")"进入" else if(chooseAction=="withdraw")"退出接龙" else "确认报名")}},dismissButton={TextButton(enabled=!busy,onClick={chooseAction=""}){Text("返回")}})
    if(confirmation=="cancel")AlertDialog(onDismissRequest={if(!busy)confirmation=""},title={Text("解散整桌拼桌？")},text={Text("${d?.ms("title").orEmpty()} · ${d?.ms("code").orEmpty()}\n所有报名与席位都会取消，原房间将关闭，不会转成普通房。相关 AI 会收到回执，提醒它们取消对应哨兵。")},confirmButton={TextButton(enabled=!busy,onClick={run {mutate("cancel")}}){Text("确认解散")}},dismissButton={TextButton(enabled=!busy,onClick={confirmation=""}){Text("保留拼桌")}})
    openedGame?.let {(local,game)->OrbisTabletopPage(local,gameKind=game) {openedGame=null;run {refresh()}}}
}
