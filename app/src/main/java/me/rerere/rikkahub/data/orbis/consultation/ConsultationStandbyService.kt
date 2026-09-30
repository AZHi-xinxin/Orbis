package me.rerere.rikkahub.data.orbis.consultation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.koin.core.context.GlobalContext
import me.rerere.rikkahub.service.ChatService

/** Explicit human-started outbound HTTPS standby. Never started by boot/permission restore. */
class ConsultationStandbyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var poll: Job? = null
    private var execution: Job? = null
    private var executingSession: String? = null
    private var executingPhase: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!consultationFeature.enabled) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action != START) { stopSelf(); return START_NOT_STICKY }
        if (poll?.isActive == true) return START_NOT_STICKY
        startNotification("咨询室待命 · 最长一小时 · 点击停止仅停止本机待命")
        poll = scope.launch { runStandby() }
        return START_NOT_STICKY
    }
    private suspend fun runStandby() {
        consultationFeature.requireEnabled()
        val store = ConsultationRuntimeStore.open(this)
        val client = ConsultationRuntimeClient()
        val koin = GlobalContext.get()
        val chats = koin.get<ChatService>()
        val executor = NormalConsultationExecutor(store, koin.get<SettingsStore>(), chats, client)
        val began = System.currentTimeMillis()
        var unavailableSince = 0L
        try {
            while (currentCoroutineContext().isActive && System.currentTimeMillis() - began < 3600000) {
                val config = store.config()
                if (!config.enabled) break
                try {
                    client.verify(config)
                    val heartbeat = client.call(config, "heartbeat", buildJsonObject {})
                    for (raw in (heartbeat["events"] as? JsonArray).orEmpty()) {
                        val event = raw.jsonObject
                        if (event["expires_at"]!!.jsonPrimitive.long * 1000 > System.currentTimeMillis() &&
                            event["kind"]?.jsonPrimitive?.content == "consultation_ended") {
                            val sid = event["session_id"]!!.jsonPrimitive.content
                            if (sid == executingSession && executingPhase == "ACTIVE") execution?.cancel()
                            chats.cancelConsultationExecution(sid)
                            showNotice("咨询已结束，双方停止对话；等待各自整理记录。")
                        }
                        client.call(config, "event_ack", buildJsonObject { put("event_id", event["id"]!!.jsonPrimitive.content) })
                    }
                    val items = (client.call(config, "inbox")["items"] as? JsonArray).orEmpty().map { it.jsonObject }
                    chats.cancelConsultationExecutionsOutsideInbox(items.mapNotNull { item ->
                        val sid = item["session_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val phase = item["phase"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        sid to phase
                    }.toSet())
                    if (execution?.isActive == true && items.none {
                        it["session_id"]?.jsonPrimitive?.content == executingSession && it["phase"]?.jsonPrimitive?.content == executingPhase
                    }) execution?.cancel()
                    if (execution?.isActive != true) {
                        val item = items.firstOrNull()
                        if (item != null) {
                            val sid = item["session_id"]!!.jsonPrimitive.content
                            val phase = item["phase"]!!.jsonPrimitive.content
                            if (phase == "WAITING" && config.counselor) {
                                client.call(config, "sessions/$sid/start", buildJsonObject {})
                            } else if (phase in setOf("ACTIVE", "ARCHIVING")) {
                                executingSession = sid; executingPhase = phase
                                execution = scope.launch {
                                    try { executor.execute(config, sid, phase) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: ConsultationGatewayBusy) {
                                        showNotice("当前助手的主窗回复尚未结束，咨询暂缓；未消耗新回复，不自动重试。请等当前回复完成后在咨询室处理。")
                                    }
                                    catch (_: Exception) { showNotice("咨询执行已暂停，检查点已保留。请查看连接或明确终止；不会重新生成未知结果。") }
                                }
                            }
                        }
                    }
                    unavailableSince = 0L
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                    if (unavailableSince == 0L) unavailableSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - unavailableSince >= 180000) {
                        showNotice("咨询室连接异常超过三分钟；请核对网络并由主窗工具或人类入口终止。")
                        break
                    }
                }
                delay(8000)
            }
        } finally { execution?.cancel(); stopSelf() }
    }
    private fun startNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "咨询室待命", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 8701, Intent(this, ConsultationStandbyService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle("Orbis · 咨询室").setContentText(text).setOngoing(true)
            .addAction(0, "停止本机待命", stop).build()
        ServiceCompat.startForeground(this, 8701, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }
    private fun showNotice(text: String) {
        getSystemService(NotificationManager::class.java).notify(8702,
            NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_rikkahub)
                .setContentTitle("Orbis · 咨询室状态").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).build())
    }
    override fun onTimeout(startId: Int, fgsType: Int) { execution?.cancel(); stopSelf() }
    override fun onDestroy() { scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    companion object {
        private const val CHANNEL = "orbis_consultation_standby"
        private const val START = "orbis.consultation.STANDBY"
        private const val STOP = "orbis.consultation.STOP_STANDBY"
        fun start(context: Context) { consultationFeature.requireEnabled(); ContextCompat.startForegroundService(context,
            Intent(context, ConsultationStandbyService::class.java).setAction(START)) }
        fun stop(context: Context) { context.stopService(Intent(context, ConsultationStandbyService::class.java)) }
    }
}
