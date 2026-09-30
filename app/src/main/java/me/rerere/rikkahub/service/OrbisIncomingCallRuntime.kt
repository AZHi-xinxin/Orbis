package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lover.connect.OrbisRingtonePlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.orbis.contact.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.activity.OrbisIncomingCallActivity
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

data class OrbisIncomingCallState(val attempt: IncomingCallAttempt, val title: String)

/** Exactly one pending invitation; no microphone or ASR exists until an explicit answer action. */
class OrbisIncomingCallRuntime private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: OrbisIncomingCallRuntime? = null
        fun get(context: Context): OrbisIncomingCallRuntime = instance ?: synchronized(this) {
            instance ?: OrbisIncomingCallRuntime(context.applicationContext).also { instance = it }
        }
        private const val CHANNEL = "orbis_incoming_calls_v1"
        private const val NOTIFICATION = 2011
        private const val FALLBACK_CHANNEL = "orbis_missed_calls_v1"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ ->
        // A durable-log error must not crash the UI or leave ringing detached from its tool.
        failClosed()
    })
    val ledger = IncomingCallLedger(AndroidIncomingCallStorage(context))
    private val recovery = scope.async { ledger.recoverInterrupted(System.currentTimeMillis()) }
    private val gate = Mutex()
    private val finishMutex = Mutex()
    private val mutableState = MutableStateFlow<OrbisIncomingCallState?>(null)
    val state = mutableState.asStateFlow()
    private val ringtone = OrbisRingtonePlayer(context)
    private var pending: CompletableDeferred<IncomingCallAttempt>? = null
    private var connectingJob: Job? = null
    private var settingsJob: Job? = null
    private var preparedCall: Pair<String, String>? = null
    private var acceptedCall: Pair<String, String>? = null

    suspend fun request(assistantId: String, conversationId: String, reason: String, ringSeconds: Int): IncomingCallAttempt =
        withContext(Dispatchers.Main.immediate) {
            require(reason.isNotBlank() && reason.length <= 2000) { "reason 必须为 1–2000 字的来电原因" }
            require(ringSeconds in 5..60) { "max_ring_seconds 必须为 5–60 秒" }
            recovery.await()
            val attempt = ledger.create(IncomingCallAttempt(Uuid.random().toString(), assistantId,
                conversationId, reason, System.currentTimeMillis(), ringSeconds))
            suspend fun failed(code: String) = ledger.update(attempt.id) { it.copy(
                outcome = IncomingCallOutcome.FAILED, finishedAtMs = System.currentTimeMillis().coerceAtLeast(it.startedAtMs), failureCode = code) }
            if (!gate.tryLock()) return@withContext failed("another_invitation_active")
            try {
                val settingsStore = GlobalContext.get().get<SettingsStore>()
                val settings = settingsStore.settingsFlow.value
                val assistant = settings.getAssistantById(Uuid.parse(assistantId)) ?: return@withContext failed("assistant_missing")
                val conversation = GlobalContext.get().get<ConversationRepository>().getConversationSummaryOfAssistant(
                    Uuid.parse(conversationId), Uuid.parse(assistantId))
                if (conversation == null) return@withContext failed("conversation_owner_changed")
                if (!settings.orbisContact.allowIncomingCalls) return@withContext failed("incoming_calls_disabled")
                if (OrbisVoiceCallRuntime.get(context).callState.value.let { it.isActive || it.ending }) return@withContext failed("call_busy")
                val audio = context.getSystemService(android.media.AudioManager::class.java)
                if (audio.mode != android.media.AudioManager.MODE_NORMAL || audio.activeRecordingConfigurations.isNotEmpty() ||
                    com.lover.connect.AlarmRingService.activeAlarmId != null) return@withContext failed("device_audio_busy")
                if (ledger.cooldownRemaining(assistantId, System.currentTimeMillis(),
                        settings.orbisContact.rejectCooldownMinutes.coerceIn(1, 120) * 60_000L) > 0) return@withContext failed("cooldown")
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(NotificationChannel(CHANNEL, "AI 主动来电", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "接听前不开启麦克风；来电铃声由本地音乐设置控制"; setSound(null, null); enableVibration(true) })
                if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
                    notificationManager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) {
                    return@withContext failed("notification_permission_or_channel_blocked")
                }
                val result = CompletableDeferred<IncomingCallAttempt>()
                pending = result
                mutableState.value = OrbisIncomingCallState(attempt, assistant.name.ifBlank { "AI" })
                showNotification(checkNotNull(mutableState.value))
                ringtone.startIncoming(onFailure = { scope.launch { finish(attempt.id, IncomingCallOutcome.FAILED, "ringtone_unavailable") } })
                settingsJob = scope.launch {
                    settingsStore.settingsFlow.collectLatest { live ->
                        if (!live.orbisContact.allowIncomingCalls) finish(attempt.id, IncomingCallOutcome.FAILED, "incoming_calls_disabled")
                    }
                }
                val resolved = withTimeoutOrNull(ringSeconds * 1000L) { result.await() }
                if (resolved != null) resolved else {
                    // Answering freezes the ringing deadline; ASR startup has its own bounded deadline.
                    if (mutableState.value?.attempt?.outcome == IncomingCallOutcome.CONNECTING) {
                        withTimeoutOrNull(25_000) { result.await() }
                            ?: finish(attempt.id, IncomingCallOutcome.FAILED, "connection_timeout")!!
                    } else finish(attempt.id, IncomingCallOutcome.NO_RESPONSE)!!
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { finish(attempt.id, IncomingCallOutcome.FAILED, "request_cancelled") }
                throw cancelled
            } catch (_: Exception) {
                finish(attempt.id, IncomingCallOutcome.FAILED, "incoming_call_failed") ?: failed("incoming_call_failed")
            } finally {
                try {
                    ringtone.stop()
                    runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION) }
                    // Disk failure/cancellation must not strand a recorder started by the
                    // independent voice runtime. Only a durably accepted exact call survives.
                    preparedCall?.takeIf { it != acceptedCall }?.let {
                        runCatching { OrbisVoiceCallRuntime.get(context).hangUpCall(it.second) }
                    }
                    settingsJob?.cancel(); settingsJob = null
                    connectingJob?.cancel(); connectingJob = null
                    preparedCall = null
                    acceptedCall = null
                    mutableState.value = null
                    pending = null
                } finally { gate.unlock() }
            }
        }

    /** The activity invokes this after a human tap, on the main thread. */
    fun answer(id: String, muted: Boolean, start: suspend (IncomingCallAttempt) -> Unit) {
        val current = mutableState.value ?: return
        if (current.attempt.id != id || current.attempt.outcome != IncomingCallOutcome.RINGING || connectingJob != null) return
        // Claim the human answer synchronously before the disk suspension/deadline can race it.
        mutableState.value = current.copy(attempt = current.attempt.copy(outcome = IncomingCallOutcome.CONNECTING, mutedAnswer = muted))
        ringtone.stop()
        connectingJob = scope.launch {
            try {
                val next = ledger.update(id) { it.copy(outcome = IncomingCallOutcome.CONNECTING, mutedAnswer = muted) }
                if (mutableState.value?.attempt?.let { it.id == id && it.outcome == IncomingCallOutcome.CONNECTING } != true || pending?.isCompleted != false) return@launch
                mutableState.value = current.copy(attempt = next)
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
                withTimeout(25_000) { start(next) }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { finish(id, IncomingCallOutcome.FAILED, "answer_interrupted") }
                throw cancelled
            } catch (_: Exception) { finish(id, IncomingCallOutcome.FAILED, "connection_failed") }
        }
    }

    suspend fun connected(id: String, callId: String): Boolean =
        finish(id, IncomingCallOutcome.CONNECTED, callId = callId)?.let {
            it.outcome == IncomingCallOutcome.CONNECTED && it.connectedCallId == callId
        } == true
    suspend fun connectionFailed(id: String) { finish(id, IncomingCallOutcome.FAILED, "connection_failed") }
    fun registerConnectingCall(attemptId: String, callId: String) {
        check(mutableState.value?.attempt?.let { it.id == attemptId && it.outcome == IncomingCallOutcome.CONNECTING } == true) { "incoming_expired" }
        preparedCall = attemptId to callId
    }
    fun reject(id: String) { scope.launch { finish(id, IncomingCallOutcome.REJECTED) } }

    private fun failClosed() {
        ringtone.stop()
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION) }
        preparedCall?.let { runCatching { OrbisVoiceCallRuntime.get(context).hangUpCall(it.second) } }
        // Preserve the existing file, even if the failed write may already have committed.
        mutableState.value = null
        pending?.completeExceptionally(IllegalStateException("来电日志未能确认保存，已停止来电；不会自动重试"))
    }

    private suspend fun finish(id: String, outcome: IncomingCallOutcome, error: String? = null, callId: String? = null): IncomingCallAttempt? = finishMutex.withLock {
        val visible = mutableState.value?.takeIf { it.attempt.id == id } ?: return@withLock null
        val deferred = pending ?: return@withLock null
        if (deferred.isCompleted) return@withLock ledger.get(id)
        ringtone.stop()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        var record = ledger.update(id) { before ->
            if (before.outcome !in setOf(IncomingCallOutcome.RINGING, IncomingCallOutcome.CONNECTING)) before else before.copy(
                outcome = outcome, failureCode = error, connectedCallId = callId,
                finishedAtMs = System.currentTimeMillis().coerceAtLeast(before.startedAtMs))
        }
        mutableState.value = visible.copy(attempt = record)
        if (record.outcome == IncomingCallOutcome.CONNECTED) {
            acceptedCall = id to checkNotNull(record.connectedCallId)
        }
        if (record.outcome != IncomingCallOutcome.CONNECTED) preparedCall?.takeIf { it.first == id }?.let {
            OrbisVoiceCallRuntime.get(context).hangUpCall(it.second)
        }
        if (record.outcome == IncomingCallOutcome.NO_RESPONSE && !record.fallbackAttempted) {
            // Save intent first; process death after notify must not resend it.
            record = ledger.update(id) { it.copy(fallbackAttempted = true) }
            val posted = runCatching { postFallback(visible.title, record) }.getOrDefault(false)
            if (posted) {
                record = ledger.update(id) { it.copy(fallbackPosted = true) }
                val speech = runCatching {
                    OrbisNotificationSpeech.get(context).readNotification(record.reason, record.assistantId, record.conversationId).status
                }.getOrDefault("failed")
                record = ledger.update(id) { it.copy(fallbackSpeech = speech) }
            }
        }
        deferred.complete(record)
        record
    }

    private fun showNotification(value: OrbisIncomingCallState) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val view = activityIntent(value.attempt, "view")
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rikkahub).setContentTitle("${value.title}的语音来电")
            .setContentText(value.attempt.reason).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true).setContentIntent(view)
            .setTimeoutAfter(value.attempt.ringSeconds * 1000L)
            .addAction(0, "接听", activityIntent(value.attempt, "answer"))
            .addAction(0, "静音接听", activityIntent(value.attempt, "muted"))
            .addAction(0, "拒接", activityIntent(value.attempt, "reject"))
        if (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()) notification.setFullScreenIntent(view, true)
        manager.notify(NOTIFICATION, notification.build())
    }

    private fun activityIntent(attempt: IncomingCallAttempt, action: String): PendingIntent = PendingIntent.getActivity(
        context, (attempt.id + action).hashCode(), Intent(context, OrbisIncomingCallActivity::class.java).apply {
            this.action = "orbis.incoming.$action.${attempt.id}"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("attemptId", attempt.id); putExtra("callAction", action)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun postFallback(title: String, attempt: IncomingCallAttempt): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(FALLBACK_CHANNEL, "未接来电提醒", NotificationManager.IMPORTANCE_HIGH))
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            manager.getNotificationChannel(FALLBACK_CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return false
        manager.notify(attempt.id.hashCode(), NotificationCompat.Builder(context, FALLBACK_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rikkahub).setContentTitle("$title 的未接来电")
            .setContentText(attempt.reason).setStyle(NotificationCompat.BigTextStyle().bigText(attempt.reason))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).build())
        return true
    }
}
