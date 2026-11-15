package me.rerere.rikkahub.data.orbis.diagnostics

import android.content.Context
import android.os.Build
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.recovery.collectEmergencyExitDiagnostics
import java.time.Instant
import java.time.ZoneId

/** Human-enabled, bounded, local diagnostics. Never reads model settings, chat or key files. */
internal object OrbisFaultDiagnostics {
    private val json=Json {ignoreUnknownKeys=true;prettyPrint=true}
    private fun prefs(context:Context)=context.applicationContext.getSharedPreferences("orbis_fault_capture",Context.MODE_PRIVATE)
    fun active(context:Context)=runCatching {faultCaptureActive(prefs(context).getLong("until",0),System.currentTimeMillis())}.getOrDefault(false)
    @Synchronized fun start(context:Context) {
        val now=System.currentTimeMillis()
        check(prefs(context).edit().putLong("until",now+FAULT_CAPTURE_MILLIS).putString("events","[]").commit()) {"diagnostic_save_failed"}
        record(context,FaultStage.CAPTURE_STARTED)
    }
    @Synchronized fun stop(context:Context) {
        record(context,FaultStage.CAPTURE_STOPPED)
        check(prefs(context).edit().putLong("until",0).commit()) {"diagnostic_save_failed"}
    }
    @Synchronized fun clear(context:Context) {
        check(prefs(context).edit().clear().commit()) {"diagnostic_save_failed"}
    }
    @Synchronized fun record(context:Context,stage:FaultStage,error:Throwable?=null,operation:DiagnosticEvent?=null) {
        // Diagnostics must never replace the original operation or exception.
        runCatching {
            if(!active(context))return
            val store=prefs(context)
            val events=json.decodeFromString<List<FaultEvent>>(store.getString("events","[]")!!)
            val code=operation?.takeIf {it.source in setOf("model","tool","theme","stickers")&&it.code in diagnosticCodes}?.sanitized()?.let {
                FaultCode(it.source,it.code,it.phase,it.httpStatus)
            }
            val event=FaultEvent(System.currentTimeMillis(),stage.name,BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE.toString(),error?.let(::faultFrames).orEmpty(),code)
            store.edit().putString("events",json.encodeToString(appendFaultEvent(events,event))).commit()
        }
    }
    fun workspaceStage(context:Context,stage:String) {
        val mapped=when(stage) {
            "lookup"->FaultStage.WORKSPACE_LOOKUP
            "path"->FaultStage.PATH_RESOLVE
            "parent"->FaultStage.OPEN_PARENT
            "write"->FaultStage.ATOMIC_WRITE
            "verified"->FaultStage.WRITE_VERIFIED
            else->return
        }
        record(context,mapped)
    }
    /** Off the main thread. Android may not retain exit records, especially before API 30. */
    fun report(context:Context):String {
        val events=runCatching {json.decodeFromString<List<FaultEvent>>(prefs(context).getString("events","[]")!!).takeLast(FAULT_EVENT_LIMIT)}.getOrDefault(emptyList())
        return buildString {
            appendLine("Orbis 故障报告 v1")
            appendLine("生成时间（UTC）：${Instant.now()}")
            appendLine("设备时区：${ZoneId.systemDefault().id}")
            appendLine("设备：${deviceLabel(Build.MANUFACTURER)} ${deviceLabel(Build.MODEL)}")
            appendLine("详细记录：${if(active(context))"开启（最多30分钟）" else "已关闭或到期"}")
            appendLine("范围：本应用系统退出摘要、手动开启后的故障类型和代码调用位置、ST准备按钮执行阶段。")
            appendLine("未读取聊天、模型配置、密钥、文件正文；不自动上传。")
            appendLine("Java调用栈仅在本版开启记录后捕获；未捕获、未保留或不受系统支持的记录不能补录。")
            appendLine("执行到某阶段不等于该阶段导致故障；先按时间关联退出原因。")
            appendLine();appendLine(collectEmergencyExitDiagnostics(context))
            appendLine("本地详细记录（时间为Unix毫秒；版本为记录当时的版本）：")
            appendLine(json.encodeToString(events))
        }
    }
    private fun deviceLabel(value:String)=value.filter {it.isLetterOrDigit()||it in " ._-"}.take(80)
}
