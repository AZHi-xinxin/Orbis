package me.rerere.rikkahub.ui.pages.orbis

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.voice.VIDEO_FRAME_MAX_BYTES
import me.rerere.rikkahub.service.OrbisVideoCallRuntime
import me.rerere.rikkahub.service.OrbisVoiceCallRuntime
import me.rerere.rikkahub.ui.components.ai.BindVoiceCallDialogVolumeStream
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

@Composable
fun OrbisVideoCallOverlay(voice: OrbisVoiceCallRuntime, video: OrbisVideoCallRuntime) {
    val call by voice.callState.collectAsStateWithLifecycle()
    val state by video.state.collectAsStateWithLifecycle()
    val audio by voice.voiceSession.state.collectAsStateWithLifecycle()
    var minimized by rememberSaveable(call.callId) { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val id = state.callId ?: return
    if (minimized) {
        Popup(alignment = Alignment.TopEnd) {
            Surface(Modifier.padding(top = 70.dp, end = 12.dp).width(170.dp).height(270.dp),
                color = Color.Black, shape = RoundedCornerShape(22.dp), shadowElevation = 8.dp) {
                Box {
                    VideoCameraPreview(video, id, state.frontCamera, state.cameraEnabled, Modifier.fillMaxSize())
                    Column(Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = .65f)).fillMaxWidth()) {
                        Text(audio.lastReplyText.takeLast(70), color = Color.White, maxLines = 2, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp))
                        Row { TextButton(onClick = { minimized = false }) { Text("展开", color = Color.White) }
                            TextButton(onClick = voice::hangUp) { Text("挂断", color = Color(0xFFFF9DAD)) } }
                    }
                }
            }
        }
        return
    }
    Dialog(onDismissRequest = { minimized = true }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        BindVoiceCallDialogVolumeStream()
        Box(Modifier.fillMaxSize().background(Color(0xFF111522))) {
            VideoCameraPreview(video, id, state.frontCamera, state.cameraEnabled, Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("ORBIS · 视频通话", color = Color.White, fontSize = 15.sp)
                        Text(call.title, color = Color.White.copy(alpha = .85f), fontSize = 22.sp) }
                    TextButton(onClick = { minimized = true }) { Text("收起", color = Color.White) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.cameraEnabled) Text("相机已关闭 · 语音仍在继续", color = Color.White)
                    videoNoticeAlongsideQueueRecovery(state.notice, audio.replyBlocked)?.let {
                        Text(it, color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = .6f)).padding(8.dp))
                    }
                    call.audioInterruption?.let { Text(it, color = Color.White) }
                    if (call.canResumeAudio) TextButton(onClick = { voice.resumeAudio(id) }) { Text("恢复音频", color = Color.White) }
                    OrbisCallQueueRecoveryControls(audio, Color.White,
                        onRecover = { voice.resumeReplies(id) }, onReview = { minimized = true })
                    audio.recoveryNotice?.let { Text(it, color = Color.White.copy(alpha = .8f), fontSize = 12.sp) }
                    Surface(color = Color.Black.copy(alpha = .58f), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(audio.lastReplyText.ifBlank { "已连接后，你可以自然说话，或让对方看看眼前的画面。" },
                                color = Color.White, maxLines = 6, fontSize = 17.sp)
                            if (audio.transcript.isNotBlank()) Text("你：${audio.transcript}", color = Color.White.copy(alpha = .65f), maxLines = 2)
                        }
                    }
                    Text(if (state.intervalSeconds == 0) "仅按需看图 · 已暂存 ${state.capturedCount} 张"
                        else "每 ${state.intervalSeconds} 秒看一帧 · 已暂存 ${state.capturedCount} 张",
                        color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = video::flipCamera) { Text("翻转", color = Color.White) }
                        TextButton(onClick = { video.setCameraEnabled(!state.cameraEnabled) }) {
                            Text(if (state.cameraEnabled) "关相机" else "开相机", color = Color.White) }
                        TextButton(onClick = { voice.setMicrophoneEnabled(!audio.microphoneEnabled) }) {
                            Text(if (audio.microphoneEnabled) "关麦" else "开麦", color = Color.White) }
                        TextButton(onClick = { settings = !settings }) { Text("频率", color = Color.White) }
                    }
                    TextButton(onClick = { voice.setSpeakerEnabled(!audio.speakerEnabled) }) {
                        Text(if (audio.speakerEnabled) "关闭对方声音" else "打开对方声音", color = Color.White)
                    }
                    if (settings) Surface(color = Color.Black.copy(alpha = .75f), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("间隔越长越省用量；默认 30 秒，忙碌时不堆积请求。图像发送给当前助手的模型服务商。", color = Color.White, fontSize = 12.sp)
                            Row { listOf(0, 15, 30, 60).forEach { seconds -> TextButton(onClick = { video.setInterval(seconds) }) {
                                Text(if (seconds == 0) "按需" else "${seconds}秒", color = if (seconds == state.intervalSeconds) Color(0xFFFFD693) else Color.White) } } }
                            Text("结束后临时画面保留 10 分钟；每通可选 10 张存照片墙。离开应用或锁屏即暂停相机。", color = Color.White.copy(alpha = .7f), fontSize = 11.sp)
                        }
                    }
                    Button(onClick = voice::hangUp, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBD455C))) { Text("结束通话") }
                }
            }
        }
    }
}

/** Reference: Android's Apache-2.0 CameraX samples. Lifecycle ownership is stricter: RESUMED only. */
@Composable
private fun VideoCameraPreview(runtime: OrbisVideoCallRuntime, callId: String, front: Boolean,
    enabled: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = remember(context) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    } }
    AndroidView(factory = { view }, modifier = modifier)
    DisposableEffect(view, lifecycleOwner, callId, front, enabled) {
        val token = Any()
        val executor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var image: ImageCapture? = null
        fun release() {
            runtime.detachCamera(token)
            val owned = listOfNotNull(preview, image).toTypedArray()
            if (owned.isNotEmpty()) provider?.unbind(*owned)
            preview = null; image = null
        }
        fun bind() {
            if (disposed || !enabled || preview != null || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
            val camera = provider ?: return
            try {
                val usePreview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                @Suppress("DEPRECATION")
                val useImage = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetResolution(Size(768, 1024)).build()
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                camera.bindToLifecycle(lifecycleOwner, selector, usePreview, useImage)
                preview = usePreview; image = useImage
                runtime.attachCamera(callId, token) { captureCompressed(useImage, executor, front) }
            } catch (_: Exception) { release(); runtime.cameraFailure("相机暂不可用，请检查权限或切换摄像头。语音仍可继续。") }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) bind()
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) release()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val screenReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) release()
            }
        }
        ContextCompat.registerReceiver(context, screenReceiver,
            android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        future.addListener({ if (!disposed) {
            runCatching { provider = future.get(); bind() }.onFailure { runtime.cameraFailure("相机初始化失败，语音仍可继续。") }
        } }, executor)
        onDispose {
            disposed = true; release(); lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(screenReceiver) }
        }
    }
}

private suspend fun captureCompressed(capture: ImageCapture, executor: java.util.concurrent.Executor, front: Boolean): ByteArray {
    val bitmap = suspendCancellableCoroutine<Bitmap> { continuation ->
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val raw = image.toBitmap()
                    val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()); if (front) postScale(-1f, 1f) }
                    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                    if (rotated !== raw) raw.recycle()
                    if (continuation.isActive) continuation.resume(rotated) { _, value, _ -> value.recycle() } else rotated.recycle()
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                finally { image.close() }
            }
            override fun onError(exception: ImageCaptureException) { if (continuation.isActive) continuation.resumeWithException(exception) }
        })
    }
    try {
        return withContext(Dispatchers.Default) {
            val ratio = minOf(1f, 768f / maxOf(bitmap.width, bitmap.height))
            val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                (bitmap.height * ratio).roundToInt().coerceAtLeast(1), true)
            try {
                var bytes = ByteArray(0)
                for (quality in listOf(72, 58, 42, 28)) {
                    bytes = ByteArrayOutputStream().use { stream ->
                        check(small.compress(Bitmap.CompressFormat.JPEG, quality, stream)); stream.toByteArray() }
                    if (bytes.size <= VIDEO_FRAME_MAX_BYTES) break
                }
                check(bytes.size <= VIDEO_FRAME_MAX_BYTES) { "画面压缩后仍过大，未发送。" }
                bytes
            } finally { if (small !== bitmap) small.recycle() }
        }
    } finally { bitmap.recycle() }
}
