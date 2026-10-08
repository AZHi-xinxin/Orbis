package me.rerere.rikkahub.service

import android.content.Context
import android.util.Base64
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import me.rerere.rikkahub.data.orbis.voice.OrbisVideoFrame
import me.rerere.rikkahub.data.orbis.voice.OrbisVideoFrameStore
import me.rerere.rikkahub.data.orbis.voice.VIDEO_FRAME_TTL_MS
import me.rerere.rikkahub.ui.pages.chat.VoicePhase
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

data class OrbisVideoCallState(val callId: String? = null, val assistantId: String? = null,
    val conversationId: String? = null, val cameraEnabled: Boolean = true, val foreground: Boolean = false,
    val frontCamera: Boolean = true, val intervalSeconds: Int = 30, val capturedCount: Int = 0,
    val lastFrameId: String? = null, val notice: String? = null, val samplingPaused: Boolean = false,
    val lastFrameAtMs: Long? = null)

/** Camera only exists while a visible, RESUMED call surface owns a capture callback. */
class OrbisVideoCallRuntime private constructor(private val context: Context) {
    private val frames = OrbisVideoFrameStore(File(context.noBackupFilesDir, "orbis-video-frames"))
    private val mutableState = MutableStateFlow(OrbisVideoCallState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ ->
        mutableState.value = mutableState.value.copy(notice = "临时画面整理失败，已停止新画面；语音与原文件保留。", cameraEnabled = false)
        capture = null; captureToken = null
    })
    val state = mutableState.asStateFlow()
    private val captureLock = Mutex()
    private var capture: (suspend () -> ByteArray)? = null
    private var captureToken: Any? = null
    private var periodic: Job? = null
    private var visualReply: Deferred<String?>? = null
    private var lastCaptureAt = 0L
    private val recovery = scope.async(Dispatchers.IO) { frames.cleanup(System.currentTimeMillis(), recoverInterrupted = true) }
    private val readableFrames = me.rerere.rikkahub.data.orbis.voice.RecoveredVideoFrameAccess(frames, recovery)

    init {
        scope.launch {
            recovery.await()
            while (isActive) { delay(30_000); runCatching { frames.cleanup(System.currentTimeMillis()) } }
        }
        scope.launch {
            OrbisVoiceCallRuntime.get(context).callState.collectLatest { call ->
                val video = mutableState.value
                if (video.callId != null && (call.callId != video.callId || !call.isActive)) end(video.callId)
            }
        }
    }

    suspend fun begin(assistantId: String, conversationId: String, callId: String) {
        recovery.await()
        val live = OrbisVoiceCallRuntime.get(context).callState.value
        check(live.callId == callId && live.isActive) { "视频通话已结束，不能重新打开相机。" }
        frames.begin(assistantId, conversationId, callId, System.currentTimeMillis())
        check(OrbisVoiceCallRuntime.get(context).callState.value.let { it.callId == callId && it.isActive }) {
            frames.end(assistantId, callId, System.currentTimeMillis())
            "视频通话已结束。"
        }
        mutableState.value = OrbisVideoCallState(callId, assistantId, conversationId)
        lastCaptureAt = 0L
        periodic?.cancel()
        periodic = scope.launch {
            while (isActive && mutableState.value.callId == callId) {
                delay(1_000)
                val current = mutableState.value
                val voice = OrbisVoiceCallRuntime.get(context)
                val audio = voice.voiceSession.state.value
                if (current.intervalSeconds == 0 || current.samplingPaused || !current.foreground || !current.cameraEnabled ||
                    audio.pendingReplies > 0 || audio.replyBlocked || audio.audioFocusSuspended ||
                    audio.humanTurnInProgress ||
                    audio.phase != VoicePhase.Listening || voice.callState.value.connectedAtMillis == null ||
                    System.currentTimeMillis() - lastCaptureAt < current.intervalSeconds * 1000L) continue
                val samplingOwnerToken = captureToken
                try {
                    val chat = GlobalContext.get().get<ChatService>()
                    val chatId = Uuid.parse(conversationId)
                    // Fresh-human-only recovery does not renew automatic camera work.
                    // Check before capture and again after its suspension, before admission.
                    val frame = capturePeriodicFrameIfAllowed(
                        allowed = { chat.mayAcceptPeriodicVideoFrame(chatId, callId) },
                        capture = { captureNow(assistantId, conversationId, callId) },
                    ) ?: continue
                    val reply = voice.voiceSession.enqueueSupplementaryReply(
                        stillCurrent = {
                            val latest = mutableState.value
                            latest.callId == callId && captureToken === samplingOwnerToken &&
                                latest.intervalSeconds != 0 && !latest.samplingPaused &&
                                latest.foreground && latest.cameraEnabled &&
                                voice.callState.value.callId == callId && voice.callState.value.isActive &&
                                chat.mayAcceptPeriodicVideoFrame(chatId, callId)
                        },
                        createReply = { chat.enqueueVideoCallFrame(chatId, callId, frame.id) },
                    ) ?: continue
                    visualReply = reply
                    // No periodic backlog even if the provider takes several minutes.
                    try { awaitVideoFrameReply(reply) }
                    finally { if (visualReply === reply) visualReply = null }
                } catch (cancelled: CancellationException) {
                    // Capture timeout or a withdrawn frame is not a hung-up call. Only the
                    // sampler's own cancelled Job (hang-up/replacement) ends this loop.
                    currentCoroutineContext().ensureActive()
                    mutableState.value = scopedVideoFailureState(mutableState.value, captureToken,
                        callId, samplingOwnerToken, "本次画面已跳过，下一周期继续；语音通话仍在进行。")
                }
                catch (error: Exception) { mutableState.value = scopedVideoFailureState(
                    mutableState.value, captureToken, callId, samplingOwnerToken,
                    error.message?.take(160) ?: "画面暂未发送，语音通话继续。") }
            }
        }
    }

    fun end(callId: String) {
        val old = mutableState.value.takeIf { it.callId == callId } ?: return
        visualReply?.let { reply ->
            runCatching { GlobalContext.get().get<ChatService>().cancelVoiceCallReply(
                Uuid.parse(checkNotNull(old.conversationId)), callId, reply) }
        }
        visualReply = null
        capture = null; captureToken = null; periodic?.cancel(); periodic = null
        mutableState.value = OrbisVideoCallState()
        val endedAt = System.currentTimeMillis()
        scope.launch {
            frames.end(checkNotNull(old.assistantId), callId, endedAt)
            delay(VIDEO_FRAME_TTL_MS)
            frames.cleanup(System.currentTimeMillis())
        }
    }

    fun setCameraEnabled(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(cameraEnabled = enabled, notice = null)
        if (!enabled) { capture = null; captureToken = null }
    }
    fun flipCamera() {
        mutableState.value = mutableState.value.copy(frontCamera = !mutableState.value.frontCamera, lastFrameId = null, lastFrameAtMs = null)
        lastCaptureAt = 0L
    }
    fun setInterval(seconds: Int) {
        require(seconds in setOf(0, 15, 30, 60))
        mutableState.value = mutableState.value.copy(intervalSeconds = seconds)
    }
    fun attachCamera(callId: String, token: Any, captureJpeg: suspend () -> ByteArray) {
        if (mutableState.value.callId != callId || !mutableState.value.cameraEnabled) return
        captureToken = token; capture = captureJpeg
        mutableState.value = mutableState.value.copy(foreground = true, notice = null)
    }
    fun detachCamera(token: Any) {
        if (captureToken !== token) return
        capture = null; captureToken = null
        mutableState.value = mutableState.value.copy(foreground = false)
    }
    fun cameraFailure(message: String) { mutableState.value = mutableState.value.copy(notice = message.take(160)) }

    /** Used by queue dispatch AND every model request; never restore camera access from history. */
    fun permitsLiveRequest(owner: String, conversationId: String, callId: String): Boolean {
        val video = mutableState.value
        val voice = OrbisVoiceCallRuntime.get(context).callState.value
        return me.rerere.rikkahub.data.orbis.voice.OrbisVideoRequestPermission(
            owner, conversationId, callId, video.assistantId, video.conversationId, video.callId,
            voice.conversationId?.toString(), voice.callId,
            voice.isActive, voice.ending, voice.connectedAtMillis != null,
            video.foreground, video.cameraEnabled,
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
            !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked,
        ).permitsRequest()
    }

    suspend fun captureNow(owner: String, conversationId: String, callId: String): OrbisVideoFrame = withContext(Dispatchers.Main.immediate) {
        check(captureLock.tryLock()) { "上一张画面仍在处理，请稍后再看。" }
        val failureOwnerToken = captureToken
        try {
            val before = mutableState.value
            check(!before.samplingPaused) { before.notice ?: "本次通话已停止新抽帧。" }
            require(before.assistantId == owner && before.conversationId == conversationId && before.callId == callId)
            check(permitsLiveRequest(owner, conversationId, callId)) {
                "当前没有已授权且在前台的视频画面。"
            }
            check(System.currentTimeMillis() - lastCaptureAt >= 3_000) { "抽帧过于频繁，请至少间隔 3 秒。" }
            val ownerToken = captureToken
            lastCaptureAt = System.currentTimeMillis()
            val jpeg = withTimeout(8_000) { checkNotNull(capture) { "摄像头尚未准备好。" }.invoke() }
            check(ownerToken != null && ownerToken === captureToken && mutableState.value.callId == callId &&
                mutableState.value.cameraEnabled && mutableState.value.foreground) { "相机已暂停，本次画面未保存或发送。" }
            val frame = frames.add(owner, callId, jpeg, System.currentTimeMillis())
            check(ownerToken === captureToken && mutableState.value.callId == callId &&
                mutableState.value.foreground && mutableState.value.cameraEnabled) { "相机已暂停，本次画面未发送。" }
            lastCaptureAt = System.currentTimeMillis()
            mutableState.value = mutableState.value.copy(lastFrameId = frame.id,
                capturedCount = mutableState.value.capturedCount + 1, notice = null, lastFrameAtMs = frame.capturedAtMs)
            frame
        } catch (limit: me.rerere.rikkahub.data.orbis.voice.VideoFrameStorageLimitException) {
            mutableState.value = scopedVideoFailureState(mutableState.value, captureToken,
                callId, failureOwnerToken, limit.message, pauseSampling = true)
            throw limit
        } finally { captureLock.unlock() }
    }

    suspend fun listFrames(owner: String, callId: String? = null) = readableFrames.list(owner, callId)

    suspend fun readFrame(owner: String, callId: String, frameId: String) = readableFrames.read(owner, callId, frameId)

    suspend fun retainFrame(owner: String, callId: String, frameId: String, import: suspend (File) -> String) =
        readableFrames.retain(owner, callId, frameId, import)

    suspend fun imageDataUrl(owner: String, callId: String, frameId: String): String =
        "data:image/jpeg;base64," + Base64.encodeToString(readFrame(owner, callId, frameId), Base64.NO_WRAP)

    companion object {
        @Volatile private var instance: OrbisVideoCallRuntime? = null
        /** Automatic chat projection must never start camera storage or recovery by itself. */
        internal fun getIfInitialized(): OrbisVideoCallRuntime? = instance
        fun get(context: Context): OrbisVideoCallRuntime = instance ?: synchronized(this) {
            instance ?: OrbisVideoCallRuntime(context.applicationContext).also { instance = it }
        }
    }
}

/** A periodic tick cannot capture or hand off a frame across a new recovery hold. */
internal suspend fun <T : Any> capturePeriodicFrameIfAllowed(
    allowed: () -> Boolean,
    capture: suspend () -> T,
): T? {
    if (!allowed()) return null
    val frame = capture()
    return frame.takeIf { allowed() }
}

/** A late IO/camera failure belongs only to its original call and camera attachment. */
internal suspend fun scopedVideoFailureState(
    current: OrbisVideoCallState,
    currentCaptureToken: Any?,
    failedCallId: String,
    failedCaptureToken: Any?,
    notice: String?,
    pauseSampling: Boolean = false,
): OrbisVideoCallState {
    // Failure reporting must never consume cancellation of the surrounding operation.
    currentCoroutineContext().ensureActive()
    if (current.callId != failedCallId || currentCaptureToken !== failedCaptureToken) return current
    return current.copy(notice = notice, samplingPaused = current.samplingPaused || pauseSampling)
}

/** A human may interrupt one model reply without cancelling the ongoing camera sampler. */
internal suspend fun awaitVideoFrameReply(reply: Deferred<String?>): String? = try {
    reply.await()
} catch (cancelled: CancellationException) {
    currentCoroutineContext().ensureActive()
    null
}
