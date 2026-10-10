package me.rerere.rikkahub.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import me.rerere.rikkahub.data.orbis.screenshare.ScreenShareHealth
import me.rerere.rikkahub.data.orbis.screenshare.ScreenShareOverlaySize
import me.rerere.rikkahub.data.orbis.screenshare.screenShareOverlayFontScale
import me.rerere.rikkahub.data.orbis.screenshare.screenShareOverlayReply

internal data class ScreenShareOverlayUiState(
    val health: ScreenShareHealth = ScreenShareHealth.CONNECTING,
    val healthText: String = "正在连接",
    val canRetry: Boolean = false,
    val sessionActive: Boolean = false,
    val latestReply: String = "",
    val screenEnabled: Boolean = true,
    val microphoneEnabled: Boolean = false,
    val speechOutputEnabled: Boolean = false,
    val intervalSeconds: Int = 30,
)

internal data class ScreenShareOverlayActions(
    val onCollapse: () -> Unit,
    val onExpand: () -> Unit,
    val onRetry: () -> Unit,
    val onReconnect: () -> Unit,
    val onScreen: () -> Unit,
    val onMicrophone: () -> Unit,
    val onStop: () -> Unit,
    val onInterval: () -> Unit,
    val onSpeechOutput: () -> Unit,
    val onInputTouched: () -> Unit,
    val onSend: (String) -> Unit,
)

/** Small native View: no runtime singleton, capture, model, permission, or file access.
 * The owner provides all real state and actions. Only latestReply enters the message area. */
internal class OrbisScreenShareOverlay(private val context: Context, private val actions: ScreenShareOverlayActions) {
    private val density = context.resources.displayMetrics.density
    private val fontScale = screenShareOverlayFontScale(context.resources.configuration.fontScale)
    private fun dp(value: Int) = (value * density).toInt().coerceAtLeast(1)
    private fun text(size: Float) = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * density * fontScale)
        includeFontPadding = false
    }
    private fun background(color: Int, radius: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = background(Color.argb(222, 25, 31, 43))
        elevation = dp(4).toFloat()
        tag = "screen-share-overlay"
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    private val expanded = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    val statusDragHandle = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
        tag = "screen-share-status"
    }
    private val statusDot = View(context).apply { tag = "screen-share-status-dot" }
    private val statusText = text(11f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val retryIcon = ImageView(context).apply { setImageDrawable(OverlayGlyph(OverlayIcon.RETRY)); visibility = View.GONE }
    private val collapse = ImageView(context).apply {
        tag = "screen-share-collapse"; contentDescription = "收起共享小窗，画面仍继续共享"
        setImageDrawable(OverlayGlyph(OverlayIcon.COLLAPSE)); isClickable = true; isFocusable = true
        setPadding(dp(4), dp(4), dp(4), dp(4)); setOnClickListener { actions.onCollapse() }
    }
    val collapsedDragHandle = LinearLayout(context).apply {
        tag = "screen-share-expand"; gravity = Gravity.CENTER_VERTICAL
        isClickable = true; isFocusable = true; visibility = View.GONE
        contentDescription = "展开屏幕共享小窗；长按拖动位置"
        setOnClickListener { actions.onExpand() }
    }
    private val collapsedDot = View(context)
    private val collapsedText = text(10f).apply { text = "共享"; maxLines = 1 }
    private val reply = text(13f).apply {
        tag = "screen-share-reply"; setTextIsSelectable(true)
        setPadding(dp(2), dp(4), dp(2), dp(4))
    }
    private val replyScroll = ScrollView(context).apply {
        tag = "screen-share-reply-scroll"; isFillViewport = false
        background = background(Color.argb(56, 255, 255, 255), 7)
        addView(reply, FrameLayout.LayoutParams(-1, -2))
    }
    private val inputRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    val input = EditText(context).apply {
        tag = "screen-share-input"; hint = "说点什么…"; contentDescription = "发给共享原聊天的消息"
        setHintTextColor(Color.rgb(179, 188, 202)); setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f * density * fontScale)
        setSingleLine(true); maxLines = 1; isHorizontalScrollBarEnabled = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        setPadding(dp(4), 0, dp(2), 0)
        background = background(Color.argb(30, 255, 255, 255), 6)
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) actions.onInputTouched()
            false
        }
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendDraft(); true } else false
        }
    }
    private val send = ImageView(context).apply {
        tag = "screen-share-send"; contentDescription = "发送到共享发起时的原聊天"
        setImageDrawable(OverlayGlyph(OverlayIcon.SEND)); isClickable = true; isFocusable = true
        setPadding(dp(7), dp(7), dp(7), dp(7)); setOnClickListener { sendDraft() }
    }
    private data class Control(val view: LinearLayout, val icon: ImageView, val label: TextView)
    private val controls = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; tag = "screen-share-controls" }
    private fun control(tagName: String, icon: OverlayIcon, label: String, action: () -> Unit): Control {
        val iconView = ImageView(context).apply { setImageDrawable(OverlayGlyph(icon)) }
        val labelView = text(9f).apply { text = label; maxLines = 1; gravity = Gravity.CENTER; ellipsize = TextUtils.TruncateAt.END }
        val button = LinearLayout(context).apply {
            tag = tagName; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            addView(iconView, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(labelView, LinearLayout.LayoutParams(-1, dp(13)))
            setOnClickListener { action() }
        }
        controls.addView(button, LinearLayout.LayoutParams(0, -1, 1f))
        return Control(button, iconView, labelView)
    }
    private val screen = control("screen-share-screen", OverlayIcon.SCREEN, "关画面", actions.onScreen)
    private val microphone = control("screen-share-mic", OverlayIcon.MICROPHONE, "开麦", actions.onMicrophone)
    private val stop = control("screen-share-stop", OverlayIcon.STOP, "停止", actions.onStop)
    private val interval = control("screen-share-interval", OverlayIcon.CLOCK, "30秒", actions.onInterval)
    private val speech = control("screen-share-output", OverlayIcon.SPEAKER, "外放", actions.onSpeechOutput)
    private var current = ScreenShareOverlayUiState()
    private var sending = false

    init {
        statusDragHandle.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(5) })
        statusDragHandle.addView(statusText, LinearLayout.LayoutParams(0, -2, 1f))
        statusDragHandle.addView(retryIcon, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginStart = dp(2) })
        statusDragHandle.setOnClickListener {
            if (current.health == ScreenShareHealth.AUTHORIZATION_LOST) actions.onReconnect()
            else if (current.canRetry) actions.onRetry()
        }
        header.addView(statusDragHandle, LinearLayout.LayoutParams(0, -1, 1f))
        header.addView(collapse, LinearLayout.LayoutParams(dp(28), -1))
        expanded.addView(header, LinearLayout.LayoutParams(-1, dp(28)))
        expanded.addView(replyScroll, LinearLayout.LayoutParams(-1, dp(88)))
        inputRow.addView(input, LinearLayout.LayoutParams(0, dp(30), 1f))
        inputRow.addView(send, LinearLayout.LayoutParams(dp(30), dp(30)))
        expanded.addView(inputRow, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(2) })
        expanded.addView(controls, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(2) })
        view.addView(expanded, LinearLayout.LayoutParams(-1, -2))
        collapsedDragHandle.addView(collapsedDot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(4) })
        collapsedDragHandle.addView(collapsedText, LinearLayout.LayoutParams(0, -2, 1f))
        collapsedDragHandle.addView(ImageView(context).apply { setImageDrawable(OverlayGlyph(OverlayIcon.EXPAND)) },
            LinearLayout.LayoutParams(dp(10), dp(12)))
        view.addView(collapsedDragHandle, LinearLayout.LayoutParams(-1, -1))
        render(current)
    }

    fun setGeometry(size: ScreenShareOverlaySize, collapsed: Boolean) {
        expanded.visibility = if (collapsed) View.GONE else View.VISIBLE
        collapsedDragHandle.visibility = if (collapsed) View.VISIBLE else View.GONE
        if (replyScroll.layoutParams.height != size.replyHeight) {
            replyScroll.layoutParams = (replyScroll.layoutParams as LinearLayout.LayoutParams).apply { height = size.replyHeight }
        }
        view.setPadding(dp(6), dp(if (collapsed) 4 else 6), dp(6), dp(if (collapsed) 4 else 6))
        if (collapsed) input.clearFocus()
    }

    /** Snapshot actual compositor coordinates, including drag/collapse/rotation, for capture redaction. */
    fun currentBoundsOnScreen(): Rect? {
        if (!view.isAttachedToWindow || !view.isShown || view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    fun render(state: ScreenShareOverlayUiState) {
        current = state
        val statusColor = when (state.health) {
            ScreenShareHealth.READY -> Color.rgb(108, 224, 148)
            ScreenShareHealth.ERROR, ScreenShareHealth.STALE, ScreenShareHealth.AUTHORIZATION_LOST -> Color.rgb(255, 113, 113)
            ScreenShareHealth.CONNECTING, ScreenShareHealth.BUSY -> Color.rgb(250, 204, 104)
            ScreenShareHealth.IDLE, ScreenShareHealth.PAUSED -> Color.rgb(167, 179, 195)
        }
        statusDot.background = background(statusColor, 4)
        collapsedDot.background = background(statusColor, 4)
        statusText.text = state.healthText
        retryIcon.visibility = if (state.canRetry || state.health == ScreenShareHealth.AUTHORIZATION_LOST) View.VISIBLE else View.GONE
        statusDragHandle.contentDescription = state.healthText + when {
            state.health == ScreenShareHealth.AUTHORIZATION_LOST -> "，点击重新授权屏幕共享；长按拖动"
            state.canRetry -> "，点击重试连接；长按拖动"
            else -> "；长按拖动共享小窗"
        }
        collapsedText.text = if (state.health == ScreenShareHealth.AUTHORIZATION_LOST) "断开" else "共享"
        collapsedDragHandle.contentDescription = "${state.healthText}，点击展开屏幕共享小窗；长按拖动"
        val body = screenShareOverlayReply(state.latestReply)
        if (reply.text.toString() != body) reply.text = body
        screen.label.text = if (state.screenEnabled) "关画面" else "开画面"
        screen.view.contentDescription = if (state.screenEnabled) "关闭共享画面，仍可打字和使用已开启的麦克风" else "恢复共享画面"
        microphone.label.text = if (state.microphoneEnabled) "关麦" else "开麦"
        microphone.view.contentDescription = if (state.microphoneEnabled) "关闭自己的麦克风，不关闭 AI 朗读" else "开启自己的麦克风，需授权，不影响 AI 朗读开关"
        interval.label.text = "${state.intervalSeconds}秒"
        interval.view.contentDescription = "画面采样频率每 ${state.intervalSeconds} 秒，点击切换"
        speech.label.text = if (state.speechOutputEnabled) "静音" else "外放"
        speech.view.contentDescription = if (state.speechOutputEnabled) "关闭 AI 朗读声音，不关闭自己的麦克风" else "开启 AI 朗读声音，不开启自己的麦克风"
        stop.view.contentDescription = "停止屏幕共享并关闭小窗"
        for (button in listOf(screen, microphone, interval, speech)) {
            button.view.isEnabled = state.sessionActive
            button.view.alpha = if (state.sessionActive) 1f else .45f
        }
        microphone.icon.setImageDrawable(OverlayGlyph(OverlayIcon.MICROPHONE, muted = !state.microphoneEnabled))
        speech.icon.setImageDrawable(OverlayGlyph(OverlayIcon.SPEAKER, muted = !state.speechOutputEnabled))
        screen.icon.setImageDrawable(OverlayGlyph(OverlayIcon.SCREEN, muted = !state.screenEnabled))
        stop.icon.setImageDrawable(OverlayGlyph(OverlayIcon.STOP, tint = Color.rgb(255, 149, 149)))
        input.isEnabled = state.sessionActive
        send.isEnabled = state.sessionActive && !sending
        send.alpha = if (send.isEnabled) 1f else .45f
    }

    /** Root lifecycle can retain a truthful red entry after Android invalidates the projection. */
    fun renderDisconnected(message: String = "共享已断开") = render(current.copy(
        health = ScreenShareHealth.AUTHORIZATION_LOST, healthText = message, canRetry = true,
        sessionActive = false, microphoneEnabled = false,
    ))

    fun finishSending(draft: String, accepted: Boolean) {
        if (accepted && input.text.toString() == draft) input.text.clear()
        sending = false
        render(current)
    }

    private fun sendDraft() {
        val draft = input.text.toString()
        if (draft.isBlank() || sending || !current.sessionActive) return
        sending = true
        render(current)
        actions.onSend(draft)
    }
}

private enum class OverlayIcon { SCREEN, MICROPHONE, STOP, CLOCK, SPEAKER, COLLAPSE, EXPAND, RETRY, SEND }

/** Tiny original line icons, independent of emoji fonts and accessibility font scaling. */
private class OverlayGlyph(private val icon: OverlayIcon, private val muted: Boolean = false,
    private val tint: Int = Color.WHITE) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        paint.color = tint
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = canvas.drawLine(x1, y1, x2, y2, paint)
        when (icon) {
            OverlayIcon.SCREEN -> { canvas.drawRoundRect(3f, 4f, 21f, 17f, 2f, 2f, paint); line(12f, 17f, 12f, 21f); line(8f, 21f, 16f, 21f) }
            OverlayIcon.MICROPHONE -> { canvas.drawRoundRect(9f, 3f, 15f, 14f, 3f, 3f, paint); canvas.drawArc(6f, 6f, 18f, 18f, 0f, 180f, false, paint); line(6f, 10f, 6f, 12f); line(18f, 10f, 18f, 12f); line(12f, 18f, 12f, 22f); line(9f, 22f, 15f, 22f) }
            OverlayIcon.STOP -> canvas.drawRoundRect(5f, 5f, 19f, 19f, 2f, 2f, paint)
            OverlayIcon.CLOCK -> { canvas.drawCircle(12f, 12f, 9f, paint); line(12f, 6f, 12f, 12f); line(12f, 12f, 16f, 14f) }
            OverlayIcon.SPEAKER -> {
                canvas.drawPath(Path().apply { moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close() }, paint)
                if (!muted) { canvas.drawArc(10f, 6f, 20f, 18f, -55f, 110f, false, paint); canvas.drawArc(10f, 3f, 24f, 21f, -55f, 110f, false, paint) }
            }
            OverlayIcon.COLLAPSE -> { line(8f, 5f, 16f, 12f); line(16f, 12f, 8f, 19f) }
            OverlayIcon.EXPAND -> { line(16f, 5f, 8f, 12f); line(8f, 12f, 16f, 19f) }
            OverlayIcon.RETRY -> { canvas.drawArc(4f, 4f, 20f, 20f, 30f, 290f, false, paint); line(20f, 3f, 20f, 9f); line(20f, 9f, 14f, 9f) }
            OverlayIcon.SEND -> { line(12f, 20f, 12f, 4f); line(12f, 4f, 5f, 11f); line(12f, 4f, 19f, 11f) }
        }
        if (muted) line(3f, 3f, 21f, 21f)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
