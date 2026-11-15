package me.rerere.rikkahub.data.orbis.plugins

import android.app.Service
import android.content.Intent
import android.os.*
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.serialization.json.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object PluginWire {
    const val DESCRIPTOR="org.orbis.personal_plugin.v1"
    const val RUN=IBinder.FIRST_CALL_TRANSACTION
    const val STOP=RUN+1
    const val HOST=RUN+2
    const val DONE=RUN+3
    const val HELLO=RUN+4
    fun call(binder: IBinder, code: Int, payload: String, callback: IBinder?=null): String {
        val data=Parcel.obtain(); val reply=Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeString(payload); if(code==RUN) data.writeStrongBinder(callback)
            check(binder.transact(code,data,reply,0)) { "plugin_worker_unavailable" }
            reply.readException(); return reply.readString().orEmpty()
        } finally { data.recycle(); reply.recycle() }
    }
}

/** Isolated UID, no app permissions or data access. Never run this service in the main app process. */
class PluginSandboxService : Service() {
    private val started=AtomicBoolean(false)
    private val timer=Executors.newSingleThreadScheduledExecutor()
    private fun exitWorker() {
        check(Process.myUid()!=applicationInfo.uid) { "plugin_worker_not_isolated" }
        Process.killProcess(Process.myPid())
    }
    override fun onCreate() {
        super.onCreate()
        check(Process.myUid()!=applicationInfo.uid) { "plugin_worker_not_isolated" }
    }
    override fun onBind(intent: Intent?): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if(code !in setOf(PluginWire.RUN,PluginWire.STOP)) return super.onTransact(code,data,reply,flags)
            data.enforceInterface(PluginWire.DESCRIPTOR)
            val request=data.readString().orEmpty()
            if(code==PluginWire.STOP) { exitWorker(); return true }
            val callback=requireNotNull(data.readStrongBinder())
            check(started.compareAndSet(false,true)) { "plugin_busy" }
            check(request.toByteArray().size<=240*1024) { "plugin_request_size" }
            reply?.writeNoException(); reply?.writeString("accepted")
            Thread({ execute(request,callback) },"orbis-personal-plugin").start()
            return true
        }
    }
    private fun execute(request: String, callback: IBinder) {
        // This independent watchdog can kill native infinite loops; coroutine cancellation alone cannot.
        timer.schedule({ exitWorker() },PLUGIN_TIMEOUT_MS,TimeUnit.MILLISECONDS)
        try {
            PluginWire.call(callback,PluginWire.HELLO,"ready")
            QuickJSLoader.init()
            val payload=pluginJson.parseToJsonElement(request).jsonObject
            val code=payload.getValue("code").jsonPrimitive.content
            val arguments=payload.getValue("arguments").toString()
            val logs=StringBuilder()
            var logsOverflow=false
            QuickJSContext.create().use { js ->
                js.setMemoryLimit(24*1024*1024); js.setMaxStackSize(256*1024)
                fun appendLog(value: String?) {
                    if(logs.length+(value?.length ?: 0)>4096) logsOverflow=true
                    else logs.append(value.orEmpty()).append('\n')
                }
                js.setConsole(object : QuickJSContext.Console {
                    override fun log(info: String?)=appendLog(info)
                    override fun info(info: String?)=appendLog(info)
                    override fun warn(info: String?)=appendLog(info)
                    override fun error(info: String?)=appendLog(info)
                })
                js.globalObject.setProperty("__orbisHost",JSCallFunction { args ->
                    val text=args.firstOrNull() as? String ?: error("plugin_host_request_invalid")
                    require(text.toByteArray().size<=96*1024) { "plugin_request_size" }
                    PluginWire.call(callback,PluginWire.HOST,text)
                })
                val wrapper="""
                    (function(nativeCall, input) {
                      'use strict';
                      const parse = JSON.parse, stringify = JSON.stringify;
                      const call = (method, params={}) => {
                        const result=parse(nativeCall(stringify({method,params})));
                        if(!result.ok) throw new Error(result.error || 'plugin_host_failed');
                        return result.value;
                      };
                      delete globalThis.__orbisHost;
                      const orbis=Object.freeze({
                        files:Object.freeze({read:name=>call('files.read',{name}),write:(name,text)=>call('files.write',{name,text}),list:()=>call('files.list')}),
                        network:Object.freeze({request:options=>call('network.request',options),github:options=>call('network.github',options)}),
                        clipboard:Object.freeze({read:()=>call('clipboard.read'),write:text=>call('clipboard.write',{text})}),
                        shell:Object.freeze({run:command=>call('shell.run',{command})}),
                        schedule:Object.freeze({list:(p={})=>call('schedule.list',p),read:id=>call('schedule.read',{id}),add:p=>call('schedule.add',p),update:p=>call('schedule.update',p),delete:p=>call('schedule.delete',p)}),
                        alarm:Object.freeze({set:p=>call('alarm.set',p),cancel:p=>call('alarm.cancel',p),list:(p={})=>call('alarm.list',p)}),
                        notify:Object.freeze({send:message=>call('notify.send',{message})}),
                        wake:(reason,due_at_ms)=>call('wake',due_at_ms===undefined?{reason}:{reason,due_at_ms}),
                        memory:Object.freeze({save:p=>call('memory.save',typeof p==='string'?{body:p}:p),search:p=>call('memory.search',typeof p==='string'?{query:p}:p)}),
                        sentinel:Object.freeze({create:p=>call('sentinel.create',p),read:rule_id=>call('sentinel.read',{rule_id}),pause:rule_id=>call('sentinel.pause',{rule_id}),resume:rule_id=>call('sentinel.resume',{rule_id}),delete:rule_id=>call('sentinel.delete',{rule_id})}),
                        system:Object.freeze({status:()=>call('system.status'),steps:()=>call('system.steps'),weather:()=>call('system.weather'),foreground:()=>call('system.foreground')}),
                        jobs:Object.freeze({add:p=>call('jobs.add',p),list:()=>call('jobs.list'),cancel:job_id=>call('jobs.cancel',{job_id})})
                      });
                      const result=(function(args,orbis) {
                        $code
                      })(parse(input),orbis);
                      if(result && typeof result.then==='function') throw new Error('plugin_async_not_supported');
                      const output=stringify(result===undefined?null:result);
                      if(output.length>32768) throw new Error('plugin_output_limit');
                      return output;
                    })(__orbisHost,${JsonPrimitive(arguments)});
                """.trimIndent()
                val result=js.evaluate(wrapper) as? String ?: "null"
                check(!logsOverflow && result.toByteArray().size+logs.toString().toByteArray().size<=PLUGIN_OUTPUT_BYTES) { "plugin_output_limit" }
                PluginWire.call(callback,PluginWire.DONE,buildJsonObject {
                    put("ok",true); put("result",pluginJson.parseToJsonElement(result)); put("logs",logs.toString())
                }.toString())
            }
        } catch (failure: Throwable) {
            val known=Regex("plugin_[a-z0-9_]{1,80}").find(failure.message.orEmpty())?.value ?: "plugin_script_error"
            runCatching { PluginWire.call(callback,PluginWire.DONE,buildJsonObject {
                put("ok",false); put("error",known)
                put("detail",failure.message.orEmpty().take(600))
            }.toString()) }
        } finally { exitWorker() }
    }
    override fun onDestroy() { timer.shutdownNow(); super.onDestroy(); exitWorker() }
}
