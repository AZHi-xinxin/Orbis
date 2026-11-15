package me.rerere.rikkahub.data.orbis.tabletop

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*
import me.rerere.rikkahub.data.orbis.OrbisInboxEvent

/** One listener per companion, independent of the game page's foreground state. */
internal class TabletopCompanionLoop(
    private val api:TabletopGameApi,
    private val enabled:()->Boolean,
    private val visible:()->Boolean,
    private val busy:()->Boolean,
    private val eventState:(String)->String?,
    private val deliver:suspend (String,JsonObject,Int)->String,
    private val report:(String)->Unit,
    private val finished:()->Unit={},
    private val now:()->Long=System::currentTimeMillis,
    private val pollMs:Long=2500,
    private val retryMs:Long=15000,
    private val loadEnding:()->JsonObject?={null},
    private val saveEnding:(JsonObject)->Unit={},
    private val restoreWake:(String)->String?={null},
    private val quoteReceipt:(Int)->OrbisInboxEvent?={null},
    private val closed:()->Unit=finished,
) {
    suspend fun run() {
        var phase="";var cursor=0;var record:String?=null;var heartbeatAt=Long.MIN_VALUE
        var retryAt=0L;var failures=0;var retry=false;var attempt=0
        val cycle=UUID.randomUUID().toString()
        var ending=loadEnding();var recordIsEnding=false;var endingAttempt=0
        var endingAcknowledged=false
        var pendingQuote:JsonObject?=null
        var recordIsQuote=false
        val deliveredQuotes=mutableSetOf<Int>()
        while(currentCoroutineContext().isActive && enabled()) {
            try {
                if(api.room.isBlank()) { delay(pollMs);continue }
                // A failed request is reconciled with its original id before any new write.
                val current=ending ?: run {
                    api.reconcilePending()
                    api.game("script_status",asAi=true).also { status ->
                        if(api.gameKind in setOf("werewolf","drawguess","heartprison") && status.ms("stage")=="ended") {
                            val snapshot=if(api.gameKind=="heartprison")heartPrisonEndingSnapshot(status) else if(api.gameKind=="drawguess")drawGuessEndingSnapshot(status) else werewolfEndingSnapshot(status)
                            saveEnding(snapshot);ending=snapshot
                        }
                    }
                }
                if(!enabled())break
                if(ending!=null && !endingAcknowledged) {
                    try { api.acknowledgeEnding();endingAcknowledged=true }
                    catch(cancel:CancellationException){throw cancel}
                    catch(_:Exception){ /* A saved ending must still be delivered while offline. */ }
                }
                if(current.ms("stage")!="ended" && (heartbeatAt==Long.MIN_VALUE || now()-heartbeatAt>=30000)) {
                    api.game("script_pass",buildJsonObject {
                        put("action","heartbeat");put("foreground",visible())
                        put("phase_version",current.mi("phase_version"));put("request_id",UUID.randomUUID().toString())
                    },asAi=true)
                    heartbeatAt=now()
                }
                // Reuse the durable receipt, including receipts written by older app versions.
                // A listener's random cycle is not a new game event after process recreation.
                if(api.gameKind=="heartprison" && current.ms("stage")!="ended" &&
                    record==null && !retry && current.ms("wake_key")!=phase) {
                    restoreWake(current.ms("wake_key"))?.let {
                        phase=current.ms("wake_key");record=it;recordIsEnding=false
                    }
                }
                val prior=record?.let(eventState)
                if(prior in setOf("accepted","queued","generating","pending_tool")) {
                    report(if(prior=="pending_tool")"原聊天中有工具确认，点聊聊查看。" else "游戏提醒已到原聊天，AI 正在阅读或思考。")
                    delay(pollMs);continue
                }
                if(record!=null && prior in setOf("failed","unknown","suppressed","target_invalid","skipped")) {
                    if(recordIsEnding)endingAttempt++
                    record=null;retry=true;failures++;retryAt=now()+retryMs*minOf(failures,4)
                    report("上次回复未完成，本局监听保持开启；稍后核对当前进度并重新提醒原助手。")
                } else if(record!=null) {
                    if(recordIsQuote && prior=="replied") {
                        pendingQuote?.let { deliveredQuotes.add(it.mi("seq")) }
                        pendingQuote=null;recordIsQuote=false
                    }
                    if(recordIsEnding && prior=="replied") {
                        report("本局已结束，自动监听已收起。");finished();return
                    }
                    record=null;failures=0
                }
                if(busy()) { report("原聊天正在回复，圆桌提醒等待空闲。");delay(pollMs);continue }
                if(now()<retryAt) { delay(pollMs);continue }
                if(current.ms("stage") !in setOf("playing","ended") && !(api.gameKind=="heartprison" && current.ms("stage")=="lobby")) {
                    report("等待开局，工具已接到原助手，不自动唤醒。");delay(pollMs);continue
                }
                if(current.ms("paused").let { it.isNotBlank() && it!="null" }) {
                    report("本局暂停中，连接保持在线。");delay(pollMs);continue
                }
                if(api.gameKind in setOf("werewolf","drawguess","heartprison")) {
                    if(current.ms("stage")=="ended") {
                        record=deliver("${api.room}:ended:${current.mi("phase_version")}:$endingAttempt",current,0)
                        recordIsEnding=true;retry=false
                        report(if(api.gameKind=="drawguess")"本局结果已送到原聊天。" else "本局结局和全员身份已送到原聊天。")
                        delay(pollMs);continue
                    }
                    val wake=when(api.gameKind) {"heartprison"->heartPrisonCanWake(current);"drawguess"->drawGuessCanWake(current);else->werewolfCanWake(current)}
                    val key=current.ms("wake_key")
                    if(wake && (key!=phase || retry)) {
                        if(!enabled())break
                        record=deliver("${api.room}:$cycle:$key:${attempt++}",current,0)
                        recordIsEnding=false
                        phase=key;retry=false;report("本回合提醒已送到原助手。")
                    }
                    // Status polls never consume unread speeches. Each game selects its own actionable or notification phases.
                    delay(pollMs);continue
                }
                val nextPhase=current.ms("phase_version")+":"+current.ms("stage")
                val events=api.game("script_read",params=mapOf("kind" to "events","cursor" to cursor.toString()),asAi=true)
                val nextCursor=events.mi("next_cursor")
                // A completed seat still needs to hear a reply addressed to it. Keep the public
                // speech limits unchanged and retain the same reply across a failed generation.
                val reply=if(current.ms("stage")=="playing") pendingQuote ?:
                    tabletopQuotedReplies(events,current).firstOrNull {
                        it.mi("seq") !in deliveredQuotes && quoteReceipt(it.mi("seq"))?.state!="replied"
                    } else null
                if(reply!=null) {
                    val seq=reply.mi("seq")
                    val saved=if(pendingQuote==null)quoteReceipt(seq) else null
                    pendingQuote=reply;recordIsQuote=true;recordIsEnding=false
                    if(saved!=null) {
                        record=saved.id;delay(pollMs);continue
                    }
                    if(!enabled())break
                    val notice=JsonObject(current+mapOf("quoted_reply" to reply))
                    record=deliver("${api.room}:quote:$seq:$cycle:${attempt++}",notice,(seq-1).coerceAtLeast(0))
                    cursor=maxOf(cursor,seq);phase=nextPhase;retry=false
                    report("有人引用并回复了 AI 的发言，回应已送到原聊天。")
                    delay(pollMs);continue
                }
                val changed=nextPhase!=phase || tabletopHasNewCompanionEvent(events,current.ms("self")) || retry
                val done=(current["progress"] as? JsonArray).orEmpty().any {
                    it.jsonObject.ms("actor")==current.ms("self") && it.jsonObject["done"]?.jsonPrimitive?.booleanOrNull==true
                }
                if(!changed || (done && current.ms("stage")!="ended")) {
                    cursor=nextCursor;phase=nextPhase;retry=false;delay(pollMs);continue
                }
                if(!enabled())break
                record=deliver("${api.room}:$cycle:$nextPhase:$nextCursor:${attempt++}",current,cursor)
                recordIsEnding=current.ms("stage")=="ended"
                cursor=nextCursor;phase=nextPhase;retry=false
                report("本局提醒已送到你们的原聊天，点聊聊可查看。")
            }catch(cancel:CancellationException){throw cancel}
            catch(error:Exception) {
                if(error is MarketProblem && error.status in setOf(404,410)) {
                    report("房间已关闭，本局自动监听已收起。");closed();return
                }
                report("圆桌连接中断，正在自动重联；本局 AI 保持开启。")
                if(error is MarketProblem && error.status in setOf(401,403)) {
                    try { api.restoreConnection() }
                    catch(cancel:CancellationException){throw cancel}
                    catch(restore:MarketProblem) {
                        if(restore.status in setOf(404,410)) {
                            report("原席位已释放，本局监听已停止；请由人类重新入席。");closed();return
                        }
                    }
                    catch(_:Exception){ /* Next poll retries the same room. */ }
                }
                delay(retryMs)
            }
            delay(pollMs)
        }
    }
}
