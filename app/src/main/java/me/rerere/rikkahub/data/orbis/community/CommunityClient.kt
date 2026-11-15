package me.rerere.rikkahub.data.orbis.community

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*

internal interface CommunityApi {
    val owner: String
    suspend fun get(path: String, actor: String = "", quick: Boolean = false): JsonObject
    suspend fun post(path: String, actor: String = "", body: JsonObject = buildJsonObject {}): JsonObject
}

internal interface CommunityTransport {
    val owner: String
    suspend fun request(method: String,path: String,body: JsonObject?,id: String?,quick: Boolean): JsonObject
}

internal fun communityBody(vararg values: Pair<String, String>) = marketObject(*values)
internal fun JsonObject.cl(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
internal fun JsonObject.cs(key: String) = (get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
internal fun communityUuid(value: String): String = UUID.fromString(value).toString().also { require(it == value) }

/** Reuses the encrypted account session. Public outbox and downloaded attachments are separate. */
internal class CommunityClient(private val context: Context,private val transport: CommunityTransport? = null) : CommunityApi {
    private val market = MarketClient(context)
    override val owner = communityUuid(transport?.owner ?: market.communityOwner() ?: error("请先登录社区账号"))
    private val root = File(context.noBackupFilesDir, "orbis-community/$owner").apply { mkdirs() }
    private val images = File(context.filesDir, "orbis-community/$owner").apply { mkdirs() }

    override suspend fun get(path: String, actor: String, quick: Boolean): JsonObject {
        val suffix = if (actor.isNotEmpty()) (if ('?' in path) "&" else "?") + "actor=" + communityUuid(actor) else ""
        return request("GET", "/community$path$suffix", quick = quick)
    }

    private suspend fun request(method: String,path: String,body: JsonObject?=null,id: String?=null,quick: Boolean=false): JsonObject =
        transport?.request(method,path,body,id,quick) ?: market.communityRequest(owner,method,path,body,id,quick)

    override suspend fun post(path: String, actor: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val escape = when { path=="/rooms" && body.ms("action")=="leave" -> "leave-${communityUuid(body.ms("room"))}"; path=="/friends" && body.ms("action")=="block" -> "block-${communityUuid(body.ms("target"))}"; else -> "" }
            val pending = if(escape.isEmpty()) pendingFile(actor) else File(root,"pending-${communityUuid(actor)}-$escape.json")
            check(!pending.exists()) { "上一笔操作结果待核对，请到“我的”查询回执后继续。" }
            val operation = buildJsonObject {
                put("id", UUID.randomUUID().toString()); put("path", path); put("actor", actor)
                put("body", JsonObject(body + (if (actor.isNotEmpty()) mapOf("actor" to JsonPrimitive(communityUuid(actor))) else emptyMap())))
            }
            marketAtomic(pending, operation.toString().toByteArray())
            submit(pending, operation)
        }
    }

    private fun pendingFile(actor: String) = File(root,"pending-${actor.takeIf { it.isNotEmpty() }?.let(::communityUuid) ?: "account"}.json")
    private fun pendingFiles(actor: String): List<File> {
        val exact=pendingFile(actor)
        return listOf(exact).filter { it.exists() } + root.listFiles().orEmpty().filter { actor.isNotEmpty() && it.name.startsWith("pending-${communityUuid(actor)}-") && it.name.endsWith(".json") }.sortedBy { it.name }
    }
    fun pending(actor: String) = pendingFiles(actor).isNotEmpty()
    private suspend fun submit(file: File, operation: JsonObject): JsonObject {
        try {
            val result = request("POST","/community"+operation.ms("path"), operation["body"]!!.jsonObject, operation.ms("id"))
            AtomicFile(file).delete()
            if(operation.ms("path")=="/rooms" && operation["body"]?.jsonObject?.ms("action")=="leave")
                CommunityRuntime.cancel(operation.ms("actor"))
            return result
        } catch (e: MarketProblem) {
            if (e.status in 400..499 && e.status !in setOf(401,408)) AtomicFile(file).delete()
            throw e
        }
    }
    suspend fun reconcile(actor: String): JsonObject = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val file = pendingFiles(actor).firstOrNull()
            if (file==null) return@withLock buildJsonObject { put("message","没有待核对操作") }
            val op = Json.parseToJsonElement(AtomicFile(file).readFully().decodeToString()).jsonObject
            val receipt = request("GET","/receipts/${op.ms("id")}")
            if (receipt.mb("found")) { AtomicFile(file).delete(); if(op.ms("path")=="/rooms" && op["body"]?.jsonObject?.ms("action")=="leave") CommunityRuntime.cancel(actor); receipt["result"]!!.jsonObject }
            else submit(file,op) // Same persisted key and body; never creates another publication.
        }
    }
    suspend fun identities(): List<JsonObject> = get("/identities").ma("items").also {
        withContext(Dispatchers.IO) { marketAtomic(File(root,"identities.json"), JsonArray(it).toString().toByteArray()) }
    }
    fun boundAssistant(id: String): JsonObject? = runCatching {
        Json.parseToJsonElement(AtomicFile(File(root,"identities.json")).readFully().decodeToString()).jsonArray
            .mapNotNull { it as? JsonObject }.singleOrNull { it.ms("local_key") == id }
    }.getOrNull()
    fun hintPreferences(assistant: String): CommunityHintPreferences = runCatching {
        val file=File(root,"hint-${communityUuid(assistant)}.json")
        if(!file.exists()) CommunityHintPreferences() else CommunityHintPreferences.parse(Json.parseToJsonElement(AtomicFile(file).readFully().decodeToString()).jsonObject)
    }.getOrDefault(CommunityHintPreferences(false,false))
    fun hintsEnabled(assistant:String, channel:String="all")=hintPreferences(assistant).enabled(channel)
    fun setHintsEnabled(assistant: String, enabled: Boolean, channel:String="all") {
        marketAtomic(File(root,"hint-${communityUuid(assistant)}.json"),hintPreferences(assistant).update(channel,enabled).json().toString().toByteArray())
    }
    suspend fun upload(actor: String, uri: Uri, room: String? = null): String = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytesBounded(16*1024*1024) } ?: error("无法读取图片")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw,0,raw.size,bounds)
        require(bounds.outWidth in 1..30000 && bounds.outHeight in 1..30000) { "图片尺寸无效" }
        var sample = 1
        while (maxOf(bounds.outWidth,bounds.outHeight)/sample > 1600) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(raw,0,raw.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("无法解析图片")
        val bytes = try {
            var quality = 85
            var encoded: ByteArray
            do { encoded = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,quality,it); it.toByteArray() }; quality -= 15 }
            while (encoded.size > 256*1024 && quality >= 25)
            require(encoded.size <= 256*1024) { "图片压缩后仍过大，请裁剪后再选" }; encoded
        } finally { bitmap.recycle() }
        val id=post("/media",actor,buildJsonObject { put("purpose", if(room==null) "feed" else "group"); room?.let { put("room",it) }; put("data",Base64.encodeToString(bytes,Base64.NO_WRAP)) }).ms("id")
        // The author already supplied these bytes; preview them without a second download.
        marketAtomic(File(images,"${communityUuid(actor)}/${communityUuid(id)}.jpg"),bytes)
        id
    }
    /** Explicit download, no 500-member automatic image fanout. Revalidates server ACL first. */
    suspend fun image(actor: String, id: String): File = withContext(Dispatchers.IO) {
        val result = get("/media/${communityUuid(id)}",actor)
        val bytes = Base64.decode(result.ms("data"),Base64.NO_WRAP)
        require(bytes.size <= 256*1024)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(sha == result.ms("sha")) { "图片校验失败" }
        val target = File(images,"${communityUuid(actor)}/$id.jpg")
        marketAtomic(target,bytes); target
    }
    fun savedImage(actor: String,id: String): File? = File(images,"${communityUuid(actor)}/${communityUuid(id)}.jpg").takeIf { it.isFile }
    fun savedBytes(): Long = images.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun clearSavedImages() { images.deleteRecursively(); images.mkdirs() }
    companion object { private val writeLock = Mutex() }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val size=read(buffer); if(size<0) break; check(out.size()+size<=limit) { "原图最多16MiB" }; out.write(buffer,0,size) }
    return out.toByteArray()
}
