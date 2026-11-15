package me.rerere.rikkahub.data.orbis.tabletop

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*
import me.rerere.rikkahub.data.orbis.community.CommunityClient

internal interface TabletopGameApi {
    val assistantId:String
    val room:String
    val gameKind:String get()="tabletop"
    val latestStatus:JsonObject get()=buildJsonObject {}
    suspend fun game(path:String,body:JsonObject?=null,params:Map<String,String> = emptyMap(),asAi:Boolean=false):JsonObject
    suspend fun roleImage(name:String):String
    fun pending(asAi:Boolean):Boolean
    suspend fun reconcilePending() {}
    suspend fun restoreConnection() {}
    suspend fun acknowledgeEnding() {}
}

/** The human view never receives the AI lease or either account/game credential. */
internal class TabletopClient(private val context: Context, override val assistantId: String, override val gameKind:String="tabletop"):TabletopGameApi {
    companion object { private val submissionWrites=Mutex() }
    private val account=MarketClient(context)
    val owner=account.communityOwner() ?: error("请先在社区或格子市场登录。")
    private val root=File(context.noBackupFilesDir,"orbis-tabletop/$owner").apply { mkdirs() }
    init {
        // A room expires server-side within 24 hours. These are only its temporary retry envelopes.
        root.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{32}-(ai|human)-pending.json")) &&
            System.currentTimeMillis()-it.lastModified()>26L*3600*1000 }.forEach { AtomicFile(it).delete() }
    }
    override var latestStatus:JsonObject=buildJsonObject {};private set
    private var readTo=0
    private val writes=Mutex()
    override var room="";private set
    var human: JsonObject?=null;private set
    var ai: JsonObject?=null;private set
    private var humanTicket=""
    private var aiTicket=""
    fun forgetConnection() {
        if(room.isNotBlank())for(asAi in listOf(false,true))AtomicFile(pendingFile(asAi)).delete()
        room="";humanTicket="";aiTicket="";readTo=0;latestStatus=buildJsonObject {}
    }
    override suspend fun roleImage(name:String):String=withContext(Dispatchers.IO) {
        check(room.isNotBlank());require(name.length in 1..240)
        val (type,bytes)=account.tabletopRequest(owner,"GET","/api/"+(if(gameKind=="drawguess")"drawguess/image?" else "asset?")+query(mapOf("room" to room,"name" to name)),ticket=aiTicket)
        require(type.substringBefore(';') in setOf("image/png","image/jpeg","image/webp") && bytes.size<=12*1024*1024) { "仅支持当前角色已开放的PNG/JPG/WebP图片，单张最大12MiB。" }
        val suffix=when(type.substringBefore(';')) { "image/png"->".png";"image/webp"->".webp";else->".jpg" }
        val root=File(context.filesDir,"upload").apply { mkdirs() };val name=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val f=File(root,"tabletop-$name$suffix");if(!f.exists())f.writeBytes(bytes)
        Uri.fromFile(f).toString()
    }
    private fun query(values: Map<String,String>)=values.entries.joinToString("&") { Uri.encode(it.key)+"="+Uri.encode(it.value) }
    private suspend fun request(path: String,body: JsonObject?=null,ticket:String?=null):JsonObject {
        val (_,bytes)=account.tabletopRequest(owner,if(body==null)"GET" else "POST","/api/${if(gameKind in setOf("werewolf","drawguess","heartprison"))"$gameKind/" else ""}$path",body,ticket)
        return Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }
    suspend fun identities(): List<JsonObject> = CommunityClient(context).identities().also { rows ->
        human=rows.singleOrNull { it.ms("kind")=="human" } ?: error("请先创建自己的社区名片。")
        ai=rows.singleOrNull { it.ms("kind")=="ai" && it.ms("local_key")==assistantId } ?: error("请在社区绑定当前 AI 的名片。")
    }
    suspend fun resume():JsonObject {
        identities();val mine=request("my_room"+(if(gameKind in setOf("werewolf","drawguess","heartprison"))"?"+query(mapOf("ai" to ai!!.ms("id"))) else ""))
        if(mine.ms("room").isBlank()){forgetConnection();return buildJsonObject {}}
        val awaitingJoin=mine["join_required"]?.jsonPrimitive?.booleanOrNull==true
        check(awaitingJoin || mine.ms("ai")==ai!!.ms("id")) { "你已和另一位 AI 入席，请从它的入口回原桌。" }
        return connect(mine.ms("room"),!awaitingJoin)
    }
    /** Restore a known seat only. Never join a different/new room or replay game actions. */
    suspend fun resumeExisting(expectedRoom:String) {
        identities()
        val mine=request("my_room"+(if(gameKind in setOf("werewolf","drawguess","heartprison"))"?"+query(mapOf("ai" to ai!!.ms("id"))) else ""))
        check(mine.ms("room")==expectedRoom && mine.ms("ai")==ai!!.ms("id") &&
            mine["join_required"]?.jsonPrimitive?.booleanOrNull!=true) { "原席位已不在当前对局，请在大厅确认。" }
        val watching=gameKind in setOf("werewolf","drawguess","heartprison") && mine["watching"]?.jsonPrimitive?.booleanOrNull==true
        val result=request(if(watching) "watch" else "reconnect",buildJsonObject { put("room",expectedRoom);put("ai",ai!!.ms("id")) })
        check(result.ms("human_ticket").isNotBlank() && result.ms("ai_ticket").isNotBlank()) { "房间连接未恢复，请稍后重试。" }
        room=expectedRoom;humanTicket=result.ms("human_ticket");aiTicket=result.ms("ai_ticket")
    }
    suspend fun connect(id:String,reconnect:Boolean=false,watch:Boolean=false,aiOnly:Boolean=false,gathering:JsonObject?=null):JsonObject {
        require(id.matches(Regex("[a-f0-9]{32}")))
        TabletopConversationHost.get(context).requireRoomAvailable(assistantId,gameKind,id)
        identities()
        val mine=request("my_room"+(if(gameKind in setOf("werewolf","drawguess","heartprison"))"?"+query(mapOf("ai" to ai!!.ms("id"))) else ""))
        val awaitingJoin=mine["join_required"]?.jsonPrimitive?.booleanOrNull==true
        if(gameKind=="tabletop" && reconnect && (awaitingJoin || mine.ms("room")!=id)) {
            throw MarketProblem(410,buildJsonObject {put("error","membership_expired");put("message","原席位已释放，请由人类重新入席。")})
        }
        val reconnecting=reconnect || (mine.ms("room")==id && !awaitingJoin)
        if(mine.ms("room").isNotBlank() && !(mine.ms("room")==id && (awaitingJoin || mine.ms("ai")==ai!!.ms("id")))) {
            if(gameKind=="werewolf")throw werewolfPreviousRoomProblem(mine)
            if(gameKind=="drawguess") throw MarketProblem(409,buildJsonObject {
                put("error","previous_room_exists");put("message","你仍在原画桌，请先恢复原桌连接。")
                put("room",mine.ms("room"));put("mode",mine.ms("mode"));put("stage",mine.ms("stage"))
                put("next_step","调用 drawguess_lobby(action=reconnect)，不传 room；不新占席、不自动退出。")
            })
            error("请先返回原桌。")
        }
        // Each deliberate entry gets a fresh intent. An uncertain previous join
        // is resolved through my_room above, then reconnected without a new seat.
        val body=buildJsonObject { put("room",id);put("ai",ai!!.ms("id"));put("phase_version",0);put("request_id","join-"+UUID.randomUUID().toString());gathering?.let {put("gathering",it)} }
        val watching=gameKind in setOf("werewolf","drawguess","heartprison") && (watch || (reconnect && mine["watching"]?.jsonPrimitive?.booleanOrNull==true))
        val result=request(if(aiOnly)"join_ai" else if(watching)"watch" else if(reconnecting)"reconnect" else "join",body)
        if(room!=id){readTo=0;latestStatus=buildJsonObject {}}
        room=id;humanTicket=result.ms("human_ticket");aiTicket=result.ms("ai_ticket")
        check(humanTicket.isNotBlank() && aiTicket.isNotBlank()) { "入席凭证未返回，请从原桌重连。" }
        return buildJsonObject { put("room",room);put("watching",watching);put("players",buildJsonArray { add(buildJsonObject { for(k in listOf("id","kind","name"))put(k,human!!.ms(k)) }) }) }
    }
    private fun ticket(asAi:Boolean)=if(asAi) aiTicket else humanTicket
    suspend fun wardrobe(person:String,body:JsonObject?=null):JsonObject {
        require(person in setOf("human","ai"));identities()
        val actor=(if(person=="human") human else ai)!!.ms("id")
        return if(body==null)request("wardrobe?"+query(mapOf("actor" to actor)))
        else request("wardrobe",JsonObject(body+mapOf("actor" to JsonPrimitive(actor))))
    }
    private fun pendingFile(asAi:Boolean)=File(root,"$room-${if(asAi)"ai" else "human"}-pending.json")
    override suspend fun game(path:String,body:JsonObject?,params:Map<String,String>,asAi:Boolean):JsonObject {
        require(path in setOf("script_status","script_read","script_say","script_pass","script_note","waiting","wardrobe","chat_image"))
        require(!asAi || path.startsWith("script_"))
        check(room.isNotBlank() && ticket(asAi).isNotBlank()) { "请先入席。" }
        val safeParams=params.filterKeys { it in setOf("kind","cursor","actor","target") } + ("room" to room)
        if(body==null)return request(path+"?"+query(safeParams),ticket=ticket(asAi)).also { result ->
            if(asAi && gameKind in setOf("werewolf","drawguess","heartprison")) {
                if(path=="script_status")latestStatus=result
                if(path=="script_read" && params["kind"]=="unread")readTo=maxOf(readTo,result.mi("read_to"))
            }
        }
        return writes.withLock {
            val f=pendingFile(asAi)
            val payload=JsonObject(body + ("room" to JsonPrimitive(room)) + (if(asAi && gameKind in setOf("werewolf","drawguess","heartprison"))mapOf("read_to" to JsonPrimitive(readTo)) else emptyMap()) +
                (if(!asAi && gameKind=="heartprison" && body.ms("action")=="leave")mapOf("ai" to JsonPrimitive(checkNotNull(ai).ms("id"))) else emptyMap()))
            val op=buildJsonObject { put("path",path);put("body",payload) }
            if(f.exists()) {
                val old=withContext(Dispatchers.IO) { Json.parseToJsonElement(AtomicFile(f).readFully().decodeToString()).jsonObject }
                tabletopReconcileBeforeWrite(old,op) { submit(f,it,asAi) }?.let { return@withLock it }
            }
            withContext(Dispatchers.IO) { marketAtomic(f,op.toString().toByteArray()) }
            submit(f,op,asAi)
        }
    }
    private suspend fun submit(file:File,op:JsonObject,asAi:Boolean):JsonObject {
        return try {
            val value=request(op.ms("path"),op["body"]!!.jsonObject,ticket(asAi))
            withContext(Dispatchers.IO) { AtomicFile(file).delete() };value
        }catch(e:MarketProblem) {
            if(tabletopDefinitiveRejection(e))withContext(Dispatchers.IO) { AtomicFile(file).delete() }
            throw e
        }
    }
    suspend fun reconcile(asAi:Boolean=false):JsonObject = writes.withLock {
        val f=pendingFile(asAi);if(!f.exists())return@withLock buildJsonObject { put("message","没有待核对操作") }
        val op=withContext(Dispatchers.IO) { Json.parseToJsonElement(AtomicFile(f).readFully().decodeToString()).jsonObject }
        val result=submit(f,op,asAi) // Same persisted request ID; receipt precedes phase checks.
        JsonObject(result + ("_reconciled_request_id" to (op["body"]!!.jsonObject["request_id"] ?: JsonNull)))
    }
    override fun pending(asAi:Boolean)=room.isNotBlank() && tabletopOperationNeedsReconcile(pendingFile(asAi),writes)
    override suspend fun restoreConnection() {
        if(gameKind in setOf("drawguess","heartprison"))resumeExisting(room) else connect(room,reconnect=true)
    }
    override suspend fun acknowledgeEnding() {
        if(gameKind !in setOf("werewolf","drawguess","heartprison"))return
        // Only acknowledge after the public result is durably saved on this phone.
        // This idempotent receipt neither consumes unread actions nor replays pending writes.
        request("script_pass",buildJsonObject {
            put("room",room);put("action","ack_ending");put("request_id","ending-$room-$assistantId")
        },aiTicket)
    }
    override suspend fun reconcilePending() {
        if(!pending(true))return
        try { reconcile(true) }
        catch(e:MarketProblem) {
            // Definitive rejection (for example an already-ended phase) clears the envelope.
            // Authentication and transport failures retain it for the exact-id retry.
            if(!tabletopDefinitiveRejection(e))throw e
        }
    }
    suspend fun publicApi(path:String,body:JsonObject?=null,params:Map<String,String> = emptyMap()):JsonObject {
        require(path in setOf("catalog","submissions","submissions/guide","my_room","room","observe","history","leaderboard"))
        require(path!="leaderboard" || gameKind in setOf("drawguess","heartprison"))
        require(body==null || path=="room")
        if(path in setOf("my_room","history","leaderboard") && gameKind in setOf("werewolf","drawguess","heartprison")) {
            // Read membership for the bound assistant; do not reconnect, issue tickets or start a listener.
            identities()
            return request("$path?"+query(params.filterKeys { it in setOf("cursor","board") } + mapOf("ai" to ai!!.ms("id"))))
        }
        return request(path+(if(path in setOf("observe","leaderboard")) "?"+query(params.filterKeys { it in setOf("room","kind","cursor","board") }) else ""),body)
    }
    suspend fun asset(name:String,observeRoom:String=""):JsonObject {
        require(observeRoom.isEmpty() || observeRoom.matches(Regex("[a-f0-9]{32}")))
        check(room.isNotBlank() || observeRoom.isNotBlank());require(name.length in 1..240)
        val (type,bytes)=account.tabletopRequest(owner,"GET","/api/"+(if(gameKind=="drawguess") {if(observeRoom.isNotEmpty()) "drawguess/observe_image" else "drawguess/image"} else if(observeRoom.isNotEmpty()) "observe_asset" else "asset")+"?"+query(mapOf("room" to observeRoom.ifEmpty { room },"name" to name)),ticket=if(observeRoom.isEmpty())humanTicket else null)
        return buildJsonObject { put("type",type);put("data",Base64.encodeToString(bytes,Base64.NO_WRAP)) }
    }
    suspend fun chatImage(uri:Uri,phase:Int):JsonObject {
        val encoded=withContext(Dispatchers.IO) {
            val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it,null,bounds) }
            require(bounds.outWidth>0 && bounds.outHeight>0) { "这张图片无法读取" }
            val options=android.graphics.BitmapFactory.Options().apply {
                while(maxOf(bounds.outWidth/inSampleSize,bounds.outHeight/inSampleSize)>1600)inSampleSize*=2
            }
            val bitmap=context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it,null,options) } ?: error("无法读取图片")
            try {
                val ratio=minOf(1f,1200f/maxOf(bitmap.width,bitmap.height))
                val scaled=android.graphics.Bitmap.createScaledBitmap(bitmap,(bitmap.width*ratio).toInt().coerceAtLeast(1),(bitmap.height*ratio).toInt().coerceAtLeast(1),true)
                try {
                    val out=java.io.ByteArrayOutputStream();var quality=85
                    do { out.reset();scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG,quality,out);quality-=15 } while(out.size()>512*1024 && quality>=25)
                    require(out.size()<=512*1024) { "图片压缩后仍过大，请选择较小图片" }
                    Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)
                } finally { if(scaled!==bitmap)scaled.recycle() }
            } finally { bitmap.recycle() }
        }
        return game("chat_image",buildJsonObject { put("phase_version",phase);put("request_id",UUID.randomUUID().toString());put("data",encoded) })
    }
    suspend fun template():ByteArray=account.tabletopRequest(owner,"GET","/api/submissions/template").second
    suspend fun upload(uri:Uri):JsonObject=withContext(Dispatchers.IO) {
        uploadFrom(false) { out ->
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer=ByteArray(65536);var size=0L;var count=input.read(buffer)
                while(count>=0){size+=count;check(size<=134217728) { "投稿包最大128MiB" };if(count>0)out.write(buffer,0,count);count=input.read(buffer)}
            } ?: error("无法读取投稿文件")
        }
    }
    suspend fun uploadFrom(asAi:Boolean,copy:suspend (java.io.OutputStream)->Unit):JsonObject=submissionWrites.withLock {
        identities();val f=File(root,"submission-pending.zip");val opFile=File(root,"submission-pending.json")
        // No request can be sent before its durable operation exists. A ZIP alone
        // is an interrupted local copy, safe to discard before a new attempt.
        if(f.exists() && !opFile.exists())withContext(Dispatchers.IO) { f.delete() }
        check(!f.exists() && !opFile.exists()) { "有一份投稿待核对，请先核对上一份。" }
        try {
            withContext(Dispatchers.IO) { f.outputStream().use { copy(it) } }
            check(f.length() in 1..134217728L) { "投稿包最大128MiB且不能为空" }
        }catch(e:Exception) { f.delete();throw e }
        val id=UUID.randomUUID().toString();marketAtomic(opFile,buildJsonObject { put("id",id);put("actor",(if(asAi)ai else human)!!.ms("id")) }.toString().toByteArray())
        submitUploadLocked()
    }
    suspend fun submitUpload():JsonObject=submissionWrites.withLock { submitUploadLocked() }
    private suspend fun submitUploadLocked():JsonObject=withContext(Dispatchers.IO) {
        val f=File(root,"submission-pending.zip");val opFile=File(root,"submission-pending.json")
        check(f.isFile && opFile.isFile) { "没有待核对投稿。" }
        val op=Json.parseToJsonElement(AtomicFile(opFile).readFully().decodeToString()).jsonObject
        try {
            val (_,bytes)=account.tabletopRequest(owner,"POST","/api/submissions/upload",upload=f,actor=op.ms("actor"),requestId=op.ms("id"))
            f.delete();AtomicFile(opFile).delete();Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        }catch(e:MarketProblem) {
            if(e.status in 400..499 && e.status !in setOf(401,408)){f.delete();AtomicFile(opFile).delete()};throw e
        }
    }
}

internal fun tabletopSameOperation(a:JsonObject,b:JsonObject):Boolean =
    a.ms("path")==b.ms("path") &&
        (a["body"] as? JsonObject)?.filterKeys { it!="request_id" } ==
        (b["body"] as? JsonObject)?.filterKeys { it!="request_id" }

/** Return the original receipt when the caller is retrying the same action. */
internal suspend fun tabletopReconcileBeforeWrite(old:JsonObject,next:JsonObject,submit:suspend (JsonObject)->JsonObject):JsonObject? {
    try {
        val receipt=submit(old)
        if(tabletopSameOperation(old,next))return receipt
    } catch(error:MarketProblem) {
        if(!tabletopDefinitiveRejection(error))throw error
    }
    return null
}

/** A game rule rejection is final even if the server uses 403. Authentication,
 * unknown 403s and uncertain transport outcomes must still reconcile the original id. */
internal fun tabletopDefinitiveRejection(error:MarketProblem):Boolean =
    error.status in 400..499 && error.status !in setOf(401,408) &&
        (error.status!=403 || error.details.ms("error") in setOf(
            "clue_locked","not_owner","private_missing","human_game_control","image_owner"
        ))

// A durable envelope is also present during a healthy in-flight request.
// Check the lock after the file so a heartbeat starting between checks cannot
// be mistaken for an abandoned write by the reminder loop.
internal fun tabletopOperationNeedsReconcile(file:File,writes:Mutex)=file.exists() && !writes.isLocked
