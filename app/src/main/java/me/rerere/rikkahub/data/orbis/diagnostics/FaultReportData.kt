package me.rerere.rikkahub.data.orbis.diagnostics

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.serialization.Serializable

internal const val FAULT_CAPTURE_MILLIS=30L*60*1000
internal const val FAULT_EVENT_LIMIT=48

internal enum class FaultStage {
    CAPTURE_STARTED, CAPTURE_STOPPED, PREPARE_CLICKED, MODEL_SNAPSHOT_CHECK,
    WORKSPACE_LOOKUP, PATH_RESOLVE, OPEN_PARENT, ATOMIC_WRITE, WRITE_VERIFIED,
    PREPARE_SUCCEEDED, PREPARE_CANCELLED, PREPARE_FAILED, UNCAUGHT_EXCEPTION, OPERATION_FAILURE,
}

@Serializable internal data class FaultFrame(val type:String,val frames:List<String>)
@Serializable internal data class FaultCode(val source:String,val code:String,val phase:String?=null,val httpStatus:Int?=null)
@Serializable internal data class FaultEvent(val at:Long,val stage:String,val version:String,val code:String,
    val exceptions:List<FaultFrame> = emptyList(),val operation:FaultCode?=null)

/** No exception messages, file paths, thread names, local variables or suppressed exceptions. */
internal fun faultFrames(error:Throwable):List<FaultFrame> {
    val seen=Collections.newSetFromMap(IdentityHashMap<Throwable,Boolean>())
    val out=mutableListOf<FaultFrame>();var current:Throwable?=error
    while(current!=null&&out.size<5&&seen.add(current)) {
        out+=FaultFrame(faultSymbol(current.javaClass.name),current.stackTrace.take(24).map {
            "${faultSymbol(it.className)}.${faultSymbol(it.methodName)}:${it.lineNumber.coerceAtLeast(-2)}"
        })
        current=current.cause
    }
    return out
}

private fun faultSymbol(value:String)=value.takeIf {
    it.length in 1..180&&it.matches(Regex("[A-Za-z0-9_.$<>-]+"))
} ?: "redacted"

internal fun faultCaptureActive(until:Long,now:Long)=until>now&&until-now<=FAULT_CAPTURE_MILLIS

internal fun appendFaultEvent(events:List<FaultEvent>,event:FaultEvent)=
    (events+event).takeLast(FAULT_EVENT_LIMIT)
