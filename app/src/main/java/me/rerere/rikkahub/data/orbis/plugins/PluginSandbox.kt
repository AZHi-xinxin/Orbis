package me.rerere.rikkahub.data.orbis.plugins

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean

internal object PluginSandbox {
    private val execution=Mutex()
    suspend fun run(context: Context, code: String, arguments: JsonElement, host: (String)->String): JsonObject {
        check(execution.tryLock()) { "plugin_busy" }
        val ready=CompletableDeferred<IBinder>()
        val result=CompletableDeferred<JsonObject>()
        val died=CompletableDeferred<Unit>()
        val active=AtomicBoolean(true)
        var worker: IBinder?=null
        var workerUid: Int?=null
        val connection=object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                worker=service
                try { service.linkToDeath({ died.complete(Unit); if(!result.isCompleted) result.completeExceptionally(IllegalStateException("plugin_worker_stopped")) },0) }
                catch (e: RemoteException) { ready.completeExceptionally(e) }
                ready.complete(service)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                died.complete(Unit)
                if(!result.isCompleted) result.completeExceptionally(IllegalStateException("plugin_worker_stopped"))
            }
            override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(IllegalStateException("plugin_worker_unavailable")) }
            override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
        }
        val callback=object : Binder() {
            override fun onTransact(op: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if(op !in setOf(PluginWire.HOST,PluginWire.DONE,PluginWire.HELLO)) return super.onTransact(op,data,reply,flags)
                data.enforceInterface(PluginWire.DESCRIPTOR)
                val uid=getCallingUid()
                check(uid!=Process.myUid() && active.get()) { "plugin_worker_identity" }
                synchronized(this) {
                    if(op==PluginWire.HELLO && workerUid==null) workerUid=uid
                    check(uid==workerUid) { "plugin_worker_identity" }
                }
                val payload=data.readString().orEmpty()
                check(payload.toByteArray().size<=96*1024) { "plugin_request_size" }
                val identity=clearCallingIdentity()
                val answer=try {
                    when(op) {
                        PluginWire.HELLO -> "ok"
                        PluginWire.HOST -> host(payload)
                        else -> {
                            check(payload.toByteArray().size<=PLUGIN_OUTPUT_BYTES+8192) { "plugin_output_limit" }
                            result.complete(pluginJson.parseToJsonElement(payload).jsonObject); "ok"
                        }
                    }
                } finally { restoreCallingIdentity(identity) }
                reply?.writeNoException(); reply?.writeString(answer); return true
            }
        }
        var bound=false
        try {
            val started=SystemClock.elapsedRealtime()
            return withTimeout(8_000) {
                bound=context.bindService(Intent(context,PluginSandboxService::class.java),connection,Context.BIND_AUTO_CREATE)
                check(bound) { "plugin_worker_unavailable" }
                val binder=ready.await()
                withContext(Dispatchers.IO) { PluginWire.call(binder,PluginWire.RUN,buildJsonObject {
                    put("code",code); put("arguments",arguments)
                }.toString(),callback) }
                try { result.await() } catch (e: IllegalStateException) {
                    if(SystemClock.elapsedRealtime()-started>=PLUGIN_TIMEOUT_MS) error("plugin_timeout") else throw e
                }
            }
        } catch (_: TimeoutCancellationException) { error("plugin_timeout") }
        finally {
            active.set(false)
            withContext(NonCancellable+Dispatchers.IO) {
                worker?.let { runCatching { PluginWire.call(it,PluginWire.STOP,"") } }
                if(bound) runCatching { context.unbindService(connection) }
                if(worker!=null && !died.isCompleted) withTimeoutOrNull(1_000) { died.await() }
            }
            execution.unlock()
        }
    }
}
