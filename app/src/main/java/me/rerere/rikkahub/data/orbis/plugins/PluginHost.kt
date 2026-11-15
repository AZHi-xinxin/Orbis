package me.rerere.rikkahub.data.orbis.plugins

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun openPlugins(context: Context, owner: String): PluginRepository {
    require(owner.matches(Regex("[A-Za-z0-9_-]{1,80}")))
    return PluginRepository(File(context.filesDir.canonicalFile,"orbis-plugins/$owner"))
}
internal fun pluginGrants(context: Context, owner: String): PluginGrantStore {
    require(owner.matches(Regex("[A-Za-z0-9_-]{1,80}")))
    return PluginGrantStore(File(context.noBackupFilesDir.canonicalFile,"personal-plugin-grants/$owner"))
}

/** Capability broker in the app process; no Context, file handles or app secrets cross into JavaScript. */
internal class PluginHost(
    private val context: Context,
    val owner: String,
    private val settings: SettingsStore,
    private val workspaces: WorkspaceRepository,
    private val conversationId: String? = null,
) {
    val repository=openPlugins(context,owner)
    val grants=pluginGrants(context,owner)
    private fun assistant()=settings.settingsFlow.value.assistants.firstOrNull { it.id.toString()==owner }
    fun enabled()=assistant()?.localTools?.contains(LocalToolOption.PersonalPlugins)==true &&
        me.rerere.rikkahub.data.orbis.boundary.BoundaryHost.get(context).permits(owner,"tool","orbis_personal_plugins")
    fun approved(entry: PluginEntry, version: PluginVersion?=entry.active()): Boolean = version?.let {
        runCatching { grants.read(entry.id,it.number)?.hash==it.hash }.getOrDefault(false)
    } ?: false
    fun runnable(id: String, hash: String): Boolean = enabled() && runCatching {
        repository.snapshot().entries.firstOrNull { it.id==id }?.let { it.enabled && it.active()?.hash==hash && approved(it) &&
            me.rerere.rikkahub.data.orbis.boundary.BoundaryHost.get(context).permits(owner,"tool","orbis_plugin_${it.active()!!.body.name}") }==true
    }.getOrDefault(false)
    fun hasPermission(id: String, hash: String, permission: String): Boolean = runCatching {
        val version=repository.snapshot().entries.first { it.id==id }.active() ?: return@runCatching false
        version.hash==hash && permission in version.body.permissions && permission in grants.read(id,version.number)?.permissions.orEmpty()
    }.getOrDefault(false)

    suspend fun approve(id: String, revision: Int, version: Int, permissions: Set<String>) = withContext(Dispatchers.IO) {
        val entry=repository.snapshot().entries.firstOrNull { it.id==id } ?: error("plugin_not_found")
        check(entry.revision==revision) { "plugin_revision_conflict" }
        val target=entry.versions.firstOrNull { it.number==version } ?: error("plugin_version_missing")
        if(target.body.beforeResponse && "response.check" in permissions) check(repository.snapshot().entries.count {
            it.id!=id && it.active()?.body?.beforeResponse==true && runnable(it.id,it.active()!!.hash) &&
                hasPermission(it.id,it.active()!!.hash,"response.check")
        }<4) { "plugin_response_check_limit" }
        val workspace=assistant()?.workspaceId?.toString()
        if("shell" in permissions) checkWorkspace(workspace)
        pausePluginSentinels(context,owner,id)
        repository.locked {
            check(repository.snapshot().entries.firstOrNull { it.id==id }?.revision==revision) { "plugin_revision_conflict" }
            grants.approve(id,target,permissions,workspace)
            repository.activate(id,revision,version)
        }
    }
    suspend fun setEnabled(id: String, revision: Int, value: Boolean) = withContext(Dispatchers.IO) {
        check(repository.snapshot().entries.firstOrNull {it.id==id}?.revision==revision) {"plugin_revision_conflict"}
        if(!value) pausePluginSentinels(context,owner,id)
        repository.locked {
            val entry=repository.snapshot().entries.firstOrNull { it.id==id } ?: error("plugin_not_found")
            if(value) check(approved(entry)) { "plugin_not_approved" }
            repository.setEnabled(id,revision,value)
        }
    }
    suspend fun delete(id: String, revision: Int) = withContext(Dispatchers.IO) {
        check(repository.snapshot().entries.firstOrNull {it.id==id}?.revision==revision) {"plugin_revision_conflict"}
        pausePluginSentinels(context,owner,id)
        repository.locked {
            repository.delete(id,revision); grants.revoke(id)
            PluginFiles(File(repository.root,"data/$id")).deleteAll()
        }
    }
    private suspend fun checkWorkspace(id: String?) {
        check(id!=null && assistant()?.workspaceId?.toString()==id) { "plugin_workspace_changed" }
        val workspace=workspaces.getById(id) ?: error("plugin_workspace_changed")
        check(workspace.shellStatus==WorkspaceShellStatus.READY.name &&
            !resolveWorkspaceToolApproval("workspace_shell",workspace.toolApprovalOverrides())) { "plugin_workspace_approval_required" }
    }

    suspend fun execute(id: String, hash: String, arguments: JsonElement, beforeResponse: Boolean = false,
        jobGuard: () -> Unit = {}): JsonObject = withContext(Dispatchers.IO) {
        jobGuard()
        check(runnable(id,hash)) { "plugin_disabled" }
        val entry=repository.snapshot().entries.first { it.id==id }
        val version=entry.active() ?: error("plugin_not_approved")
        check(version.hash==hash) { "plugin_revision_conflict" }
        if(beforeResponse) check(version.body.beforeResponse && hasPermission(id,hash,"response.check")) { "plugin_permission_denied" }
        else PluginSchema.validateArguments(version.body.parameters,arguments)
        repository.recoverInterrupted()
        val run=repository.begin(id,hash)
        val active=AtomicBoolean(true)
        val start=SystemClock.elapsedRealtime()
        val calls=AtomicInteger(0)
        val files=PluginFiles(File(repository.root,"data/$id"))
        val client=OkHttpClient.Builder().callTimeout(3,TimeUnit.SECONDS).connectTimeout(2,TimeUnit.SECONDS)
            .proxy(java.net.Proxy.NO_PROXY)
            .readTimeout(2,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES).cache(null).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .dns { host -> InetAddress.getAllByName(host).toList().also { addresses ->
                check(addresses.isNotEmpty() && addresses.all(::publicPluginAddress)) { "plugin_network_private_address" }
            } }.build()
        fun current(): PluginGrant {
            jobGuard()
            check(active.get() && SystemClock.elapsedRealtime()-start<PLUGIN_TIMEOUT_MS) { "plugin_timeout" }
            check(runnable(id,hash)) { "plugin_disabled" }
            return grants.read(id,version.number)?.takeIf { it.hash==hash } ?: error("plugin_not_approved")
        }
        fun requirePermission(permission: String): PluginGrant {
            val grant=current()
            check(permission in version.body.permissions && permission in grant.permissions) { "plugin_permission_denied" }
            // Automatic pre-delivery checks are read-only and cannot trigger new wakes or writes.
            if(beforeResponse) check(permission in setOf("filesystem.read","response.check")) { "plugin_hook_read_only" }
            return grant
        }
        fun host(request: String): String {
          var journalIndex: Int?=null
          var journalMethod: String?=null
          return try {
            current(); val index=calls.incrementAndGet(); check(index<=32) { "plugin_host_call_limit" }
            val p=pluginJson.parseToJsonElement(request).jsonObject
            val method=p["method"]?.jsonPrimitive?.content ?: error("plugin_host_arguments_invalid")
            repository.recordCall(id,run.id,index,method)
            journalIndex=index; journalMethod=method
            val params=p["params"] as? JsonObject ?: JsonObject(emptyMap())
            fun text(key: String)=params[key]?.jsonPrimitive?.contentOrNull ?: error("plugin_host_arguments_invalid")
            val value: JsonElement=when(method) {
                "files.read" -> { requirePermission("filesystem.read"); files.read(text("name"))?.let(::JsonPrimitive) ?: JsonNull }
                "files.list" -> { requirePermission("filesystem.read"); JsonArray(files.list().map(::JsonPrimitive)) }
                "files.write" -> { repository.locked {
                    requirePermission("filesystem.write"); files.write(text("name"),text("text")) { requirePermission("filesystem.write") }
                }; JsonPrimitive(true) }
                "network.request", "network.github" -> {
                    requirePermission("network")
                    val metadata=method=="network.github"
                    val url=(if(metadata) pluginGithubUrl(params) else text("url")).toHttpUrlOrNull() ?: error("plugin_network_host_denied")
                    check(url.isHttps && url.port==443 && url.username.isEmpty() && url.password.isEmpty() && url.host in version.body.networkHosts) { "plugin_network_host_denied" }
                    val httpMethod=if(metadata) "GET" else params["method"]?.jsonPrimitive?.content ?: "GET"
                    check(httpMethod in setOf("GET","POST")) { "plugin_network_method_invalid" }
                    val body=params["body"]?.jsonPrimitive?.content.orEmpty()
                    check(body.toByteArray().size<=16*1024) { "plugin_request_size" }
                    val req=Request.Builder().url(url).header("User-Agent","Orbis-personal-plugin").method(httpMethod,if(httpMethod=="POST") body.toRequestBody("application/json; charset=utf-8".toMediaType()) else null).build()
                    client.newCall(req).execute().use { response ->
                        val bytes=response.body.byteStream().use { input ->
                            val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(2048)
                            while(true) { val n=input.read(buffer); if(n<0) break; check(out.size()+n<=if(metadata) 1024*1024 else 24*1024) { "plugin_output_limit" }; out.write(buffer,0,n) }
                            out.toByteArray()
                        }
                        current(); if(metadata) pluginGithubMetadata(response.code,bytes.toString(Charsets.UTF_8),params["kind"]?.jsonPrimitive?.content ?: "release")
                            else buildJsonObject { put("status",response.code); put("body",bytes.toString(Charsets.UTF_8)) }
                    }
                }
                "clipboard.read" -> {
                    requirePermission("clipboard.read")
                    val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    // Plain text only: do not coerce arbitrary URIs (which could read other providers).
                    val value=clipboard.primaryClip?.takeIf { it.itemCount>0 }?.getItemAt(0)?.text?.toString().orEmpty()
                    check(value.toByteArray().size<=24*1024) { "plugin_output_limit" }; JsonPrimitive(value)
                }
                "clipboard.write" -> {
                    requirePermission("clipboard.write"); val text=text("text")
                    check(text.toByteArray().size<=24*1024) { "plugin_output_limit" }
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Orbis 插件",text))
                    JsonPrimitive(true)
                }
                "shell.run" -> {
                    val grant=requirePermission("shell"); val command=text("command")
                    require(command.toByteArray().size<=4096) { "plugin_request_size" }
                    runBlocking {
                        checkWorkspace(grant.workspaceId)
                        val answer=withTimeout((PLUGIN_TIMEOUT_MS-(SystemClock.elapsedRealtime()-start)).coerceAtLeast(1)) {
                            workspaces.executeCommand(grant.workspaceId!!,command,timeoutMillis=3_000)
                        }
                        current(); buildJsonObject { put("result",answer.toString().take(24*1024)) }
                    }
                }
                else -> runBlocking {
                    withTimeout((PLUGIN_TIMEOUT_MS-(SystemClock.elapsedRealtime()-start)).coerceAtLeast(1)) {
                        PluginNativeBridge(context,owner,conversationId,id,hash,"${run.id}:$index") { requirePermission(it) }.execute(method,params)
                    }
                }
            }
            current()
            val outcome=if(value is JsonObject && value["ok"]?.jsonPrimitive?.booleanOrNull==false)
                if(value["outcome"]?.jsonPrimitive?.contentOrNull=="unknown") "unknown" else "not_completed" else "returned"
            repository.recordCall(id,run.id,index,method,outcome)
            journalIndex=null
            buildJsonObject { put("ok",true); put("value",value) }.toString().also {
                check(it.toByteArray().size<=96*1024) { "plugin_output_limit" }
            }
        } catch(failure: Exception) {
            journalIndex?.let { index -> runCatching { repository.recordCall(id,run.id,index,journalMethod!!,
                if(pluginError(failure) in setOf("plugin_permission_denied","plugin_hook_read_only","plugin_capability_unknown","plugin_host_scope_denied")) "not_started" else "unknown") } }
            buildJsonObject { put("ok",false); put("error",pluginError(failure)) }.toString()
        }
        }
        try {
            val result=PluginSandbox.run(context,version.body.code,arguments,::host)
            // Revocation wins even if a worker's successful callback is already in flight.
            current()
            val checked= !beforeResponse || pluginCheckVerdict(result,(arguments as? JsonObject)?.get("text")?.jsonPrimitive?.content.orEmpty()).passed
            val success=result["ok"]?.jsonPrimitive?.booleanOrNull==true && checked
            val code=result["error"]?.jsonPrimitive?.contentOrNull ?: if(!checked) "plugin_response_check_failed" else null
            repository.finish(id,run.id,if(success) "succeeded" else "failed",SystemClock.elapsedRealtime()-start,code)
            JsonObject(result+mapOf("execution_id" to JsonPrimitive(run.id),"version" to JsonPrimitive(version.number)))
        } catch(cancelled: CancellationException) {
            repository.finish(id,run.id,"cancelled",SystemClock.elapsedRealtime()-start,"plugin_interrupted"); throw cancelled
        } catch(failure: Exception) {
            val code=pluginError(failure)
            repository.finish(id,run.id,if(code=="plugin_timeout") "timed_out" else "failed",SystemClock.elapsedRealtime()-start,code)
            buildJsonObject { put("ok",false); put("error",code); put("message",pluginErrorText(code)); put("execution_id",run.id) }
        } finally { active.set(false); client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
    }
}

internal fun publicPluginAddress(address: InetAddress): Boolean {
    if(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
    val b=address.address.map { it.toInt() and 255 }
    if(b.size==4) return b[0] !in setOf(0,10,127) && b[0]<224 && !(b[0]==100 && b[1] in 64..127) &&
        !(b[0]==169 && b[1]==254) && !(b[0]==172 && b[1] in 16..31) && !(b[0]==192 && b[1]==168) &&
        !(b[0]==198 && b[1] in 18..19)
    // Only global-unicast IPv6. ULA, link-local, mapped/private and transition addresses are excluded.
    return b.size==16 && (b[0] and 0xe0)==0x20 && !(b[0]==0x20 && b[1]==0x02)
}
