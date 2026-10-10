package me.rerere.rikkahub.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.orbis.screenshare.*
import me.rerere.rikkahub.ui.activity.OrbisScreenShareActivity
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

class OrbisScreenShareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime by lazy { OrbisScreenShareRuntime.get(this) }
    private val worker = HandlerThread("OrbisScreenFrame")
    private lateinit var captureHandler: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: LinearLayout? = null
    private var layout: WindowManager.LayoutParams? = null
    private var overlayUi: OrbisScreenShareOverlay? = null
    private var previousOverlayArea: ScreenShareOverlayArea? = null
    private var collapsed = false
    private var started = false
    private var destroying = false
    private var owner = ""
    private var conversation = ""
    private var stateObserver: Job? = null
    private var disconnectedTimeout: Job? = null
    private var lastSpeechNotice: String? = null
    @Volatile private var overlayMask: Rect? = null
    @Volatile private var displayGeometry: Rect = Rect()
    @Volatile private var captureMask: Rect? = null
    private val pendingCapture = AtomicReference<CompletableDeferred<Pair<ByteArray, IntArray>>?>(null)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { runtime.stop("screen_locked"); stopSelf() }
    }
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { scope.launch {
            if (destroying || projection == null) return@launch
            runtime.authorizationLost()
            runtime.stop("projection_stopped", keepDisconnectedOverlay = true)
            releaseProjection()
            started = false
            overlayUi?.renderDisconnected("授权已结束 · 点此重连")
            val open = PendingIntent.getActivity(this@OrbisScreenShareService, 2090,
                OrbisScreenShareActivity.intent(this@OrbisScreenShareService, owner, conversation).putExtra("reconnect", true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(this@OrbisScreenShareService, "orbis_screen_share")
                .setSmallIcon(R.drawable.ic_stat_rikkahub).setContentTitle("Orbis 屏幕共享已断开")
                .setContentText("画面、麦克风和朗读已停止；点击重新授权，不会自动续用旧授权。")
                .setContentIntent(open).setAutoCancel(true).build()
            // The stopped grant no longer owns a foreground capture service.
            ServiceCompat.stopForeground(this@OrbisScreenShareService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).notify(2091, notification)
            disconnectedTimeout?.cancel()
            disconnectedTimeout = scope.launch { delay(10 * 60_000L); stopSelf() }
        } }
        override fun onCapturedContentResize(width: Int, height: Int) { resize(width, height) }
    }

    override fun onCreate() {
        super.onCreate()
        worker.start(); captureHandler = Handler(worker.looper)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("orbis_screen_share", "屏幕共享", NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { runtime.stop(); stopSelf(); return START_NOT_STICKY }
        if (started || intent == null) return START_NOT_STICKY
        started = true
        disconnectedTimeout?.cancel(); disconnectedTimeout = null
        getSystemService(NotificationManager::class.java).cancel(2091)
        try {
            owner = checkNotNull(intent.getStringExtra("assistant")); conversation = checkNotNull(intent.getStringExtra("conversation"))
            check(Settings.canDrawOverlays(this)) { "请先允许显示悬浮窗。" }
            val action = PendingIntent.getService(this, 2089, Intent(this, javaClass).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open = PendingIntent.getActivity(this, 2090, OrbisScreenShareActivity.intent(this, owner, conversation), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(this, "orbis_screen_share").setSmallIcon(R.drawable.ic_stat_rikkahub)
                .setContentTitle("Orbis 正在共享屏幕").setContentText("仅按设置频率抽帧；可随时关闭画面或停止。")
                .setContentIntent(open).setOngoing(true).addAction(0, "停止共享", action).build()
            ServiceCompat.startForeground(this, 2089, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            @Suppress("DEPRECATION")
            val permission = checkNotNull(intent.getParcelableExtra<Intent>("projection"))
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, permission)
            checkNotNull(projection).registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            val bounds = screenBounds()
            resize(bounds.width(), bounds.height())
            display = checkNotNull(projection).createVirtualDisplay("OrbisSharedScreen", checkNotNull(reader).width,
                checkNotNull(reader).height, resources.configuration.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                checkNotNull(reader).surface, null, captureHandler)
            showOverlay()
            stateObserver?.cancel()
            stateObserver = scope.launch {
                try {
                    runtime.begin(owner, conversation, intent.getStringExtra("initiator") ?: "human") { captureFrame() }
                    var captureEpoch = runtime.state.value.screenEpoch
                    val voice = OrbisVoiceCallRuntime.get(this@OrbisScreenShareService)
                    combine(runtime.state, voice.callState, voice.voiceSession.state,
                        OrbisScreenShareSpeechOutput.get(this@OrbisScreenShareService).state) { state, _, _, speech ->
                        if (state.sessionId != null && speech.sessionId == state.sessionId && speech.notice != null && speech.notice != lastSpeechNotice) {
                            Toast.makeText(this@OrbisScreenShareService, speech.notice, Toast.LENGTH_SHORT).show()
                        }
                        lastSpeechNotice = speech.notice
                        state
                    }.collectLatest { state ->
                        if (state.sessionId == null && state.health != ScreenShareHealth.AUTHORIZATION_LOST) return@collectLatest
                        if (state.screenEpoch != captureEpoch) { captureEpoch = state.screenEpoch; pendingCapture.getAndSet(null)?.cancel() }
                        renderOverlay(state)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { runtime.setNotice("共享未能开始，请回到聊天重试授权。"); runtime.stop("startup_failed"); stopSelf() }
            }
        } catch (_: Exception) { runtime.setNotice("屏幕授权未能启用，请重新打开共享。"); stopSelf() }
        return START_NOT_STICKY
    }

    private fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || destroying) return
        displayGeometry = screenBounds()
        val ratio = minOf(1.0, 1280.0 / maxOf(width, height))
        val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
        if (reader?.width == targetWidth && reader?.height == targetHeight) return
        val old = reader
        val replacement = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2)
        reader = replacement
        replacement.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            image.use {
                val request = pendingCapture.getAndSet(null) ?: return@use
                if (!request.isActive) return@use
                if (!runtime.state.value.screenEnabled || destroying) { request.cancel(); return@use }
                try {
                    val plane = image.planes[0]
                    val paddedWidth = plane.rowStride / plane.pixelStride
                    val bitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.copyPixelsFromBuffer(plane.buffer)
                        val cropped = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).apply {
                            Canvas(this).drawBitmap(bitmap, 0f, 0f, null)
                        }
                        try {
                            // Do not mark the user's window secure: system screenshots remain available.
                            // Only the AI-bound full-display frame is masked; a single-app projection
                            // excludes other apps' overlays at the OS boundary already.
                            val bounds = displayGeometry
                            val mask = captureMask
                            if (mask != null && bounds.width() > 0 && bounds.height() > 0 &&
                                kotlin.math.abs(width.toDouble() / height - bounds.width().toDouble() / bounds.height()) < 0.02) {
                                val sx = cropped.width.toFloat() / bounds.width()
                                val sy = cropped.height.toFloat() / bounds.height()
                                Canvas(cropped).drawRect(RectF((mask.left - bounds.left) * sx, (mask.top - bounds.top) * sy,
                                    (mask.right - bounds.left) * sx, (mask.bottom - bounds.top) * sy), Paint().apply { color = Color.BLACK })
                            }
                            val small = Bitmap.createScaledBitmap(cropped, 32, 32, true)
                            val pixels = IntArray(1024)
                            small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
                            if (small !== cropped) small.recycle()
                            val luminance = pixels.map { (Color.red(it) * 30 + Color.green(it) * 59 + Color.blue(it) * 11) / 100 }.toIntArray()
                            val bytes = ByteArrayOutputStream().use { output -> cropped.compress(Bitmap.CompressFormat.JPEG, 75, output); output.toByteArray() }
                            if (!runtime.state.value.screenEnabled || destroying || !request.complete(bytes to luminance)) bytes.fill(0)
                        } finally { if (cropped !== bitmap) cropped.recycle() }
                    } finally { bitmap.recycle() }
                } catch (error: Exception) { request.completeExceptionally(IllegalStateException("本次画面不可用。")) }
            }
        }, captureHandler)
        display?.apply { resize(targetWidth, targetHeight, resources.configuration.densityDpi); surface = replacement.surface }
        captureHandler.post { old?.close() }
    }

    private suspend fun captureFrame(): Pair<ByteArray, IntArray> {
        captureMask = overlayUi?.currentBoundsOnScreen()?.let(::Rect) ?: overlayMask?.let(::Rect)
        val request = CompletableDeferred<Pair<ByteArray, IntArray>>()
        check(pendingCapture.compareAndSet(null, request)) { "画面仍在读取。" }
        return try { withTimeout(4500) { request.await() } }
        finally { pendingCapture.compareAndSet(request, null); request.cancel(); captureMask = null }
    }

    private fun showOverlay() {
        if (overlay != null) { updateOverlayGeometry(); return }
        val window = getSystemService(WindowManager::class.java)
        val area = overlayArea()
        val size = screenShareOverlaySize(resources.displayMetrics.density, area, collapsed = false)
        val params = WindowManager.LayoutParams(size.width, size.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = area.right - size.width; y = area.top + (24 * resources.displayMetrics.density).toInt()
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
        layout = params
        val ui = OrbisScreenShareOverlay(this, ScreenShareOverlayActions(
            onCollapse = { setOverlayCollapsed(true) }, onExpand = { setOverlayCollapsed(false) },
            onRetry = { runtime.retryConnection() }, onReconnect = ::reopenScreenShareAuthorization,
            onScreen = { runtime.setScreenEnabled(!runtime.state.value.screenEnabled) },
            onMicrophone = {
                val current = runtime.state.value
                val voice = OrbisVoiceCallRuntime.get(this)
                if (current.sessionId != null && current.voiceCallId != null &&
                    voice.callState.value.callId == current.voiceCallId && voice.callState.value.isActive) {
                    voice.setMicrophoneEnabled(!voice.voiceSession.state.value.microphoneEnabled)
                } else if (current.sessionId != null) {
                    startActivity(OrbisScreenShareActivity.intent(this, owner, conversation)
                        .putExtra("microphone", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            onStop = { runtime.stop(); stopSelf() },
            onInterval = {
                val choices = listOf(5, 15, 30, 60, 120)
                runtime.setInterval(choices[(choices.indexOf(runtime.state.value.intervalSeconds) + 1) % choices.size])
            },
            onSpeechOutput = { runtime.setSpeechOutputEnabled(!runtime.state.value.speechOutputEnabled) },
            onInputTouched = {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                overlay?.let { window.updateViewLayout(it, params) }
            },
            onSend = { draft ->
                scope.launch {
                    var accepted = false
                    try {
                        accepted = runtime.send(draft)
                        if (!accepted) runtime.setNotice("消息暂未发出，草稿仍保留；请检查原聊天状态。")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { runtime.setNotice("消息尚未发出，草稿仍保留。") }
                    finally { overlayUi?.finishSending(draft, accepted) }
                }
                releaseOverlayInput()
            },
        ))
        overlayUi = ui; overlay = ui.view
        ui.setGeometry(size, collapsed)
        installOverlayDrag(ui.statusDragHandle)
        installOverlayDrag(ui.collapsedDragHandle)
        ui.view.setOnApplyWindowInsetsListener { _, insets ->
            ui.view.post { if (!destroying) updateOverlayGeometry() }
            insets
        }
        window.addView(ui.view, params)
        updateOverlayGeometry()
    }

    private fun renderOverlay(state: ScreenShareState) {
        val voice = OrbisVoiceCallRuntime.get(this)
        val ownedVoice = state.voiceCallId != null && voice.callState.value.callId == state.voiceCallId && voice.callState.value.isActive
        overlayUi?.render(ScreenShareOverlayUiState(state.health, state.healthText, state.canRetry,
            sessionActive = state.sessionId != null, latestReply = state.latestReply, screenEnabled = state.screenEnabled,
            microphoneEnabled = ownedVoice && voice.voiceSession.state.value.microphoneEnabled,
            speechOutputEnabled = state.speechOutputEnabled, intervalSeconds = state.intervalSeconds))
    }

    private fun reopenScreenShareAuthorization() {
        releaseOverlayInput()
        startActivity(OrbisScreenShareActivity.intent(this, owner, conversation)
            .putExtra("reconnect", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun releaseOverlayInput() {
        val root = overlay ?: return
        overlayUi?.input?.clearFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        layout?.let { params ->
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching { getSystemService(WindowManager::class.java).updateViewLayout(root, params) }
        }
    }

    private fun setOverlayCollapsed(value: Boolean) {
        if (collapsed == value) return
        val params = layout ?: return
        val area = overlayArea()
        val dockLeft = params.x + params.width / 2 <= area.left + area.width / 2
        collapsed = value
        releaseOverlayInput()
        val size = screenShareOverlaySize(resources.displayMetrics.density, area, collapsed)
        params.x = if (dockLeft) area.left else area.right - size.width
        updateOverlayGeometry(snap = collapsed)
    }

    private fun installOverlayDrag(handle: View) {
        var initialX = 0; var initialY = 0; var downX = 0f; var downY = 0f; var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnTouchListener { view, event ->
            val params = layout ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y; downX = event.rawX; downY = event.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > slop) moved = true
                    if (moved) { params.x = initialX + dx.toInt(); params.y = initialY + dy.toInt(); updateOverlayGeometry() }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) updateOverlayGeometry(snap = collapsed) else view.performClick()
                }
                MotionEvent.ACTION_CANCEL -> if (moved) updateOverlayGeometry(snap = collapsed)
            }
            true
        }
    }

    private fun overlayArea(): ScreenShareOverlayArea {
        val bounds = screenBounds()
        val margin = (8 * resources.displayMetrics.density).toInt()
        val insets = if (Build.VERSION.SDK_INT >= 30) getSystemService(WindowManager::class.java).maximumWindowMetrics
            .windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()) else null
        val keyboard = if (Build.VERSION.SDK_INT >= 30) overlay?.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0 else 0
        return ScreenShareOverlayArea(bounds.left + (insets?.left ?: 0) + margin,
            bounds.top + (insets?.top ?: margin * 3) + margin,
            bounds.right - (insets?.right ?: 0) - margin,
            bounds.bottom - maxOf(insets?.bottom ?: margin * 3, keyboard) - margin)
    }

    private fun updateOverlayGeometry(snap: Boolean = false) {
        val root = overlay ?: return
        val params = layout ?: return
        val area = overlayArea()
        val wasRightDocked = previousOverlayArea?.let { old -> kotlin.math.abs(params.x + params.width - old.right) <= 2 } == true
        val size = screenShareOverlaySize(resources.displayMetrics.density, area, collapsed)
        var position = ScreenShareOverlayPosition(if (wasRightDocked) area.right - size.width else params.x, params.y)
        position = if (snap) snapScreenShareOverlayToEdge(position, size, area) else clampScreenShareOverlay(position, size, area)
        val changed = params.x != position.x || params.y != position.y || params.width != size.width || params.height != size.height
        params.x = position.x; params.y = position.y; params.width = size.width; params.height = size.height
        overlayMask = Rect(params.x, params.y, params.x + size.width, params.y + size.height)
        if (pendingCapture.get() != null) captureMask = Rect(checkNotNull(captureMask ?: overlayMask)).apply { union(checkNotNull(overlayMask)) }
        previousOverlayArea = area
        overlayUi?.setGeometry(size, collapsed)
        if (changed && root.isAttachedToWindow) runCatching { getSystemService(WindowManager::class.java).updateViewLayout(root, params) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val bounds = screenBounds()
        if (Build.VERSION.SDK_INT < 34) resize(bounds.width(), bounds.height())
        updateOverlayGeometry(snap = collapsed)
    }

    override fun onDestroy() {
        destroying = true
        runtime.stop("service_stopped")
        runCatching { unregisterReceiver(screenReceiver) }
        releaseProjection()
        captureHandler.post { worker.quitSafely() }
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }; overlay = null
        scope.cancel(); super.onDestroy()
    }

    private fun releaseProjection() {
        val oldProjection = projection; projection = null
        runCatching { oldProjection?.unregisterCallback(projectionCallback) }
        runCatching { display?.release() }; display = null
        runCatching { oldProjection?.stop() }
        pendingCapture.getAndSet(null)?.cancel()
        val oldReader = reader; reader = null
        captureHandler.post { oldReader?.close() }
    }

    private fun screenBounds(): Rect = if (Build.VERSION.SDK_INT >= 30)
        getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
    else Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
}
