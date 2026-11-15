package me.rerere.rikkahub.data.orbis.market

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun JsonObject.ms(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.mi(key: String) = (get(key) as? JsonPrimitive)?.intOrNull ?: 0
internal fun JsonObject.mb(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.ma(key: String) = (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
internal fun marketObject(vararg values: Pair<String, String>) = buildJsonObject { values.forEach { put(it.first, it.second) } }
internal fun marketAtomic(file: File, bytes: ByteArray) {
    file.parentFile!!.mkdirs()
    val target = AtomicFile(file); val stream = target.startWrite()
    try { stream.write(bytes); target.finishWrite(stream) }
    catch (failure: Throwable) { target.failWrite(stream); throw failure }
}

/** Credentials are Keystore-encrypted and excluded from all ordinary app backups. */
internal class MarketClient(context: Context) {
    private val root = File(context.noBackupFilesDir, "orbis-market").apply { mkdirs() }
    private val session = File(root, "session.enc")
    private val pending = File(root, "pending.json")
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey("orbis-market-session-v1", null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("orbis-market-session-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); generateKey()
        }
    }
    private fun credentials(): JsonObject? = synchronized(lock) {
        if (!session.exists()) return@synchronized null
        val bytes = AtomicFile(session).readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        Json.parseToJsonElement(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()).jsonObject
    }
    fun hasSession(): Boolean = runCatching { credentials()?.ms("token")?.isNotBlank() == true }.getOrDefault(false)
    internal fun communityOwner(): String? = credentials()?.takeIf { it.ms("token").isNotBlank() }?.ms("owner")
    /** Fixed first-party destination. Account credentials never enter the game WebView. */
    internal suspend fun tabletopRequest(owner: String, method: String, path: String, body: JsonObject? = null,
        ticket: String? = null, upload: File? = null, actor: String? = null, requestId: String? = null): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/") && !path.contains("..") && path.none { it == '\r' || it == '\n' || it == '#' || it == '\\' })
        val identity = credentials()
        check(identity?.ms("owner") == owner && identity.ms("token").isNotBlank()) { "社区账号已切换，请重新打开圆桌。" }
        val builder = Request.Builder().url("https://consult.azhi-xinxin.cn/v1/tabletop$path")
            .header("Authorization", "Bearer " + (ticket ?: identity!!.ms("token")))
        if (upload != null) {
            require(path == "/api/submissions/upload" && method == "POST" && upload.length() in 1..134217728L)
            builder.header("X-Actor-Id", actor ?: error("缺少投稿身份"))
                .header("X-Request-Id", requestId ?: error("缺少请求编号"))
            builder.post(object : okhttp3.RequestBody() {
                override fun contentType() = "application/zip".toMediaType()
                override fun contentLength() = upload.length()
                override fun writeTo(sink: okio.BufferedSink) { upload.inputStream().use { input ->
                    val buffer=ByteArray(65536);var count=input.read(buffer)
                    while(count>=0) { if(count>0)sink.write(buffer,0,count);count=input.read(buffer) }
                } }
            })
        } else if (method == "POST") builder.post((body ?: buildJsonObject {}).toString().toRequestBody("application/json".toMediaType()))
        else require(method == "GET")
        // Ten drawing seats may queue behind the isolated single-browser renderer.
        val drawingRequest=path=="/api/drawguess/script_pass" && body?.ms("action")=="canvas"
        val requestHttp=if(drawingRequest)
            http.newBuilder().readTimeout(48,TimeUnit.SECONDS).build() else http
        val call=requestHttp.newCall(builder.build());call.timeout().timeout(if(upload!=null) 120 else if(drawingRequest) 50 else 40,TimeUnit.SECONDS)
        call.execute().use { response ->
            val source=response.body?.source() ?: error("服务未返回内容")
            val limit=if(path.startsWith("/api/drawguess/") || path.startsWith("/api/asset?") || path.startsWith("/api/source?") || path.startsWith("/api/werewolf/script_read?") || path.startsWith("/api/werewolf/observe?")) 24*1024*1024L else 1024*1024L
            source.request(limit+1);val bytes=source.readByteArray(minOf(source.buffer.size,limit+1))
            check(bytes.size<=limit) { "资料过大，请在主持端拆分后重试。" }
            if(!response.isSuccessful) {
                val result=runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()
                throw MarketProblem(response.code,result ?: buildJsonObject { put("message","圆桌暂不可用，请保留待核对操作。") })
            }
            (response.header("Content-Type")?.substringBefore(';') ?: "application/octet-stream") to bytes
        }
    }
    internal suspend fun communityRequest(owner: String, method: String, path: String, body: JsonObject? = null,
        id: String? = null, quick: Boolean = false): JsonObject {
        require(path.startsWith("/community/") || path.startsWith("/receipts/"))
        val identity = credentials()
        check(identity?.ms("owner") == owner && identity.ms("token").isNotBlank()) { "社区账号已切换，请重新打开。" }
        return request(method, path, body, id, tokenOverride = identity.ms("token"), quick = quick)
    }
    fun recoveryCode(): String = credentials()?.ms("recovery_code").orEmpty()
    fun acknowledgeRecovery() = synchronized(lock) {
        credentials()?.let { seal(JsonObject(it - "recovery_code")) }
    }
    fun saveEditor(value: JsonObject) = synchronized(lock) {
        val owner = credentials()?.ms("owner") ?: return@synchronized
        marketAtomic(File(root, "editor-$owner.json"), value.toString().toByteArray())
    }
    fun readEditor(): JsonObject? = synchronized(lock) {
        val owner = credentials()?.ms("owner") ?: return@synchronized null
        val file = File(root, "editor-$owner.json")
        if (file.exists()) Json.parseToJsonElement(AtomicFile(file).readFully().decodeToString()).jsonObject else null
    }
    fun pendingRequest(): JsonObject? = synchronized(lock) {
        if (pending.exists()) Json.parseToJsonElement(AtomicFile(pending).readFully().decodeToString()).jsonObject else null
    }
    private fun storeSession(value: JsonObject) = synchronized(lock) {
        val saved = buildJsonObject { put("token", value.ms("token")); put("owner", value["user"]!!.jsonObject.ms("id"));
            value["recovery_code"]?.let { put("recovery_code", it) } }
        seal(saved)
    }
    private fun seal(saved: JsonObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        marketAtomic(session, cipher.iv + cipher.doFinal(saved.toString().toByteArray()))
    }
    suspend fun get(path: String): JsonObject = request("GET", path)
    suspend fun auth(action: String, body: JsonObject): JsonObject {
        check(action in setOf("login", "register", "recover"))
        val result = request("POST", "/auth/$action", body, authenticated = false)
        withContext(Dispatchers.IO) { storeSession(result) }; return result
    }
    /** The durable request survives navigation/process loss. Never invent another key on a timeout. */
    suspend fun post(path: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val operation = synchronized(lock) {
            check(!pending.exists()) { "有一笔操作结果待核对，请先在消息页查询回执。" }
            buildJsonObject { put("id", UUID.randomUUID().toString()); put("owner", credentials()?.ms("owner") ?: error("请先登录")); put("path", path); put("body", body) }
                .also { marketAtomic(pending, it.toString().toByteArray()) }
        }
        submit(operation)
    }
    private suspend fun submit(operation: JsonObject): JsonObject {
        val identity = credentials()
        check(identity?.ms("owner") == operation.ms("owner")) { "待核对操作属于另一个账号，请登录原账号。" }
        return try {
            val result = request("POST", operation.ms("path"), operation["body"]!!.jsonObject, operation.ms("id"), tokenOverride = identity!!.ms("token"))
            finish(operation, result); result
        } catch (failure: MarketProblem) {
            if (failure.status in 400..499 && failure.status !in setOf(401, 408)) synchronized(lock) {
                if(pendingRequest()?.ms("id") == operation.ms("id")) AtomicFile(pending).delete()
            }
            throw failure
        }
    }
    private fun finish(operation: JsonObject, result: JsonObject) = synchronized(lock) {
        if(pendingRequest()?.ms("id") != operation.ms("id")) return@synchronized
        if(operation.ms("path") == "/items" && result.ms("id").isNotBlank() && result["prompt"] != null)
            marketAtomic(File(root, "editor-${operation.ms("owner")}.json"), result.toString().toByteArray())
        AtomicFile(pending).delete()
    }
    suspend fun reconcile(): JsonObject? {
        val operation = pendingRequest() ?: return null
        val identity = credentials()
        check(identity?.ms("owner") == operation.ms("owner")) { "请登录原账号后核对。" }
        val receipt = request("GET", "/receipts/${operation.ms("id")}", tokenOverride = identity!!.ms("token"))
        if (receipt.mb("found")) { val result = receipt["result"]!!.jsonObject; finish(operation, result); return result }
        return null
    }
    suspend fun retryPending(): JsonObject = submit(pendingRequest() ?: error("没有待核对操作"))
    suspend fun logout() {
        val before = credentials(); post("/auth/logout", buildJsonObject {})
        synchronized(lock) { if(credentials() == before) AtomicFile(session).delete() }
    }
    private suspend fun request(method: String, path: String, body: JsonObject? = null, id: String? = null, authenticated: Boolean = true, tokenOverride: String? = null, quick: Boolean = false): JsonObject = withContext(Dispatchers.IO) {
        require(path.startsWith("/") && !path.contains("..") && path.none { it == '\r' || it == '\n' })
        val builder = Request.Builder().url("https://consult.azhi-xinxin.cn/v1/market$path")
            .header("Accept", "application/json")
        val sentToken = if(authenticated) tokenOverride ?: credentials()?.ms("token") else null
        sentToken?.let { builder.header("Authorization", "Bearer $it") }
        id?.let { builder.header("Idempotency-Key", it) }
        if (method == "POST") builder.post((body ?: buildJsonObject {}).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val call = http.newCall(builder.build())
        if (quick) call.timeout().timeout(1200, TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            val raw = response.body?.source()?.let { source -> source.request(512 * 1024L + 1); source.readByteArray(minOf(source.buffer.size, 512 * 1024L + 1)) } ?: error("服务未返回内容")
            check(raw.size <= 512 * 1024) { "服务返回内容过大" }
            val result = runCatching { Json.parseToJsonElement(raw.decodeToString()).jsonObject }.getOrElse { error("服务返回格式异常，请保留待核对请求。") }
            if (response.code == 401 && authenticated) synchronized(lock) {
                credentials()?.takeIf { it.ms("token") == sentToken }?.let { seal(JsonObject(it - "token")) }
            }
            if (!response.isSuccessful) throw MarketProblem(response.code, result)
            result
        }
    }
    companion object { private val lock = Any() }
}
internal class MarketProblem(val status: Int, val details: JsonObject) : Exception(details.ms("message"))
