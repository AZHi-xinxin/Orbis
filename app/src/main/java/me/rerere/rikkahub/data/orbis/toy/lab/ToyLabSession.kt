package me.rerere.rikkahub.data.orbis.toy.lab

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal interface LabTransport {
    val report: LabDeviceReport
    suspend fun subscribe(channel: LabNotify)
    suspend fun write(packet: LabPacket)
    fun close()
}
internal data class LabRunState(val busy: Boolean = false, val trial: LabTrial? = null, val message: String = "尚未试调")

/** Every path (deadline, cancel, revocation, transport failure) converges on the same cleanup. */
internal class ToyLabSession(private val scope: CoroutineScope, private val now: () -> Long = System::currentTimeMillis) {
    private val mutable = MutableStateFlow(LabRunState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var job: Job? = null
    suspend fun start(link: LabTransport, revision: LabProfileRevision, actionId: String, seconds: Int,
        valid: () -> Boolean, onClosed: () -> Unit) = mutex.withLock {
        check(job?.isActive != true) { "上次试调尚未结束，请先停止。" }
        val profile = revision.profile
        profile.validate(link.report)
        require(seconds in 1..profile.maxRunSeconds)
        val action = profile.actions.singleOrNull { it.id == actionId } ?: error("没有这个档位或动作。")
        check(valid()) { "设备或授权已经改变。" }
        val trial = LabTrial(UUID.randomUUID().toString(), revision.revision, actionId, seconds, now())
        mutable.value = LabRunState(true, trial, "正在发送；实际反应请本人观察")
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var wrote = false
            var writeInFlight = false
            var writeUncertain = false
            var failure: String? = null
            var stopOk = false
            suspend fun send(packet: LabPacket, actionWrite: Boolean = true) {
                check(valid()) { "授权已收回" }
                writeInFlight = true
                try {
                    withTimeout(3000) { link.write(packet) }
                    if (actionWrite) wrote = true
                } catch (e: Exception) {
                    writeUncertain = true
                    throw e
                } finally { writeInFlight = false }
            }
            suspend fun sequence(steps: List<LabStep>) {
                for (step in steps) {
                    send(step.packet)
                    delay(step.delayAfterMs)
                }
            }
            try {
                // Whole trial deadline includes notifications/handshake. Never replay a failed write.
                withTimeoutOrNull(seconds * 1000L) {
                    for (channel in profile.notifications) {
                        check(valid()) { "授权已收回" }; withTimeout(3000) { link.subscribe(channel) }
                    }
                    for (packet in profile.handshake) {
                        send(packet, actionWrite = false)
                    }
                    sequence(action.startup)
                    do {
                        if (action.cycle.isEmpty()) send(action.packet) else sequence(action.cycle)
                        val repeat = action.repeatMs ?: profile.repeatMs
                        if (repeat == 0L && action.cycle.isEmpty()) awaitCancellation() else delay(repeat)
                    } while (isActive)
                }
                if (writeUncertain) failure = "本次写入超时，未确认发送结果；未自动重试。"
                else if (!wrote) failure = "时限内未确认控制指令发送成功。"
            } catch (_: TimeoutCancellationException) {
                // Deadline is normal only if an action was submitted. A timed-out write is uncertain.
                failure = "本次写入超时，未确认发送结果；未自动重试。"
            } catch (e: CancellationException) {
                failure = "已取消本次试调"
            } catch (e: Exception) {
                failure = e.message?.take(200) ?: "发送失败"
            } finally {
                withContext(NonCancellable) {
                    try {
                        // A failed/timed-out transaction must not receive another characteristic write.
                        val stops = action.stopPackets.ifEmpty { listOfNotNull(profile.stop) }
                        if (writeUncertain || writeInFlight) {
                            failure = listOfNotNull(failure, "写入结果不确定，未追加指令；已尝试断开，请检查实体开关").joinToString("；")
                        } else {
                            if (wrote) withTimeout(2500) {
                                for ((index, packet) in stops.withIndex()) {
                                    if (index > 0) delay(40)
                                    link.write(packet)
                                }
                            }
                            stopOk = true
                        }
                    } catch (e: Exception) {
                        failure = listOfNotNull(failure, "停止指令未确认发送成功；已尝试断开，请检查实体开关").joinToString("；")
                    } finally {
                        try { link.close() } catch (_: Exception) { stopOk = false; failure = "断开请求失败，请检查实体开关。" }
                        onClosed()
                    }
                }
                val result = failure ?: "本次发送结束，已请求停止并断开；实际动作和停止由本人确认"
                mutable.value = LabRunState(false, trial.copy(writeSucceeded = wrote,
                    transportResult = result, stopRequestSucceeded = stopOk), result)
            }
        }
        trial
    }
    fun clearResult() { check(!state.value.busy); mutable.value = LabRunState() }
    suspend fun stop() = mutex.withLock { job?.cancelAndJoin(); job = null }
}
