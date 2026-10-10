package me.rerere.rikkahub.ui.activity

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.service.OrbisScreenShareRuntime
import me.rerere.rikkahub.service.OrbisScreenShareService
import me.rerere.rikkahub.service.startScreenShareVoice

class OrbisScreenShareActivity : ComponentActivity() {
    private val runtime by lazy { OrbisScreenShareRuntime.get(this) }
    private lateinit var status: TextView
    private var permissionPending = false
    private var summarySending = false
    private val projectionPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        permissionPending = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, OrbisScreenShareService::class.java)
                    .putExtra("projection", result.data).putExtra("assistant", intent.getStringExtra("assistant"))
                    .putExtra("conversation", intent.getStringExtra("conversation"))
                    .putExtra("initiator", intent.getStringExtra("initiator") ?: "human"))
                finish()
            } catch (_: Exception) { status.text = "系统暂未允许开启共享，请回到页面重试。" }
        } else status.text = "未开启共享。点击开始后可重新授权。"
    }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableMicrophone() else status.text = "麦克风仍关闭，可以继续打字。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }
        status = TextView(this).apply {
            textSize = 18f
            text = "屏幕共享\n\n默认每 30 秒向当前 AI 发送一张有变化的画面，可能产生模型用量。可调 5–120 秒。\n\n右上小窗可拖动；点箭头收进侧边，点侧边标签展开。绿点表示近期采集正常，红点表示故障或授权结束，黄点表示处理或等待；点状态可查看/重试。\n\n麦克风和右下外放独立控制，默认均关闭。正文只显示 AI 对你的回复。共享结束清空画面，保留文字总结。\n\nOrbis 不限制系统截图；系统录屏可能结束当前共享，届时需要重新授权。受保护画面仍不能提供给 AI，共享范围以系统授权为准。"
        }
        root.addView(status)
        fun button(label: String, action: () -> Unit) { root.addView(Button(this).apply { text = label; setOnClickListener { action() } }) }
        if (runtime.state.value.sessionId == null) button("开始共享") { startSharing() }
        else {
            button(if (runtime.state.value.screenEnabled) "关闭共享画面" else "恢复共享画面") {
                runtime.setScreenEnabled(!runtime.state.value.screenEnabled); finish()
            }
            button("开启麦克风对话") {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO) else enableMicrophone()
            }
            button("结束共享") { runtime.stop(); finish() }
        }
        button("查看最近共享总结") { lifecycleScope.launch {
            val text = try { withContext(Dispatchers.IO) { runtime.latestSummary(checkNotNull(intent.getStringExtra("assistant"))) } }
                catch (_: Exception) { "总结暂不能读取，原文件未改动。" }
            val summary = TextView(this@OrbisScreenShareActivity).apply { this.text = text; textSize = 16f; setPadding(32, 24, 32, 24); setTextIsSelectable(true) }
            android.app.AlertDialog.Builder(this@OrbisScreenShareActivity).setTitle("最近共享文字总结")
                .setView(ScrollView(this@OrbisScreenShareActivity).apply { addView(summary) })
                .setPositiveButton("关闭", null).show()
        } }
        button("把总结带回聊天（发送）") { previewSummaryForSending() }
        button("返回") { finish() }
        setContentView(ScrollView(this).apply { addView(root) })
        if (intent.getBooleanExtra("reconnect", false) && runtime.state.value.sessionId == null && savedInstanceState == null)
            startSharing()
        if (intent.getBooleanExtra("microphone", false)) status.text = "屏幕共享仍在进行。点击“开启麦克风对话”后才会收音；建议佩戴耳机，避免把影视声音转写给 AI。"
    }

    private fun startSharing() {
        if (permissionPending || runtime.state.value.sessionId != null) return
        if (!Settings.canDrawOverlays(this)) {
            status.text = "请允许 Orbis 显示悬浮窗，返回后再次点击开始共享。"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        lifecycleScope.launch {
            try {
                runtime.validate(checkNotNull(intent.getStringExtra("assistant")), checkNotNull(intent.getStringExtra("conversation")))
                permissionPending = true
                projectionPermission.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            } catch (_: Exception) { permissionPending = false; status.text = "请确认当前聊天仍存在、模型支持图片，并先结束其他通话后重试。" }
        }
    }
    private fun enableMicrophone() {
        lifecycleScope.launch {
            try {
                startScreenShareVoice(this@OrbisScreenShareActivity)
                finish()
            } catch (_: Exception) { status.text = "麦克风尚未启动。请检查当前 AI、语音识别和朗读配置；屏幕共享及打字仍可使用。" }
        }
    }
    private fun previewSummaryForSending() {
        if (summarySending) return
        lifecycleScope.launch {
            val preview = try { withContext(Dispatchers.IO) {
                runtime.completedSummary(checkNotNull(intent.getStringExtra("assistant")), checkNotNull(intent.getStringExtra("conversation")))
            } } catch (_: Exception) { null }
            if (preview == null) { status.text = "本窗口还没有已保存且已结束的共享总结。请等整理完成；未发送任何消息。"; return@launch }
            val target = try { runtime.summaryDestination(preview) } catch (_: Exception) {
                status.text = "找不到这份总结所属的原助手或原聊天，未发送消息。"; return@launch
            }
            val body = TextView(this@OrbisScreenShareActivity).apply {
                val ended = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(preview.endedAt))
                text = "$target\n共享结束：$ended\n\n确认后会发送一条新消息，可能调用模型和已有获授权的记忆工具；不会重开共享。\n\n${preview.summary}"
                textSize = 16f; setPadding(32, 24, 32, 24); setTextIsSelectable(true)
            }
            android.app.AlertDialog.Builder(this@OrbisScreenShareActivity).setTitle("确认总结与发送对象")
                .setView(ScrollView(this@OrbisScreenShareActivity).apply { addView(body) })
                .setNegativeButton("暂不发送", null).setPositiveButton("确认发送") { _, _ ->
                    if (!summarySending) lifecycleScope.launch {
                        summarySending = true
                        try { status.text = if (runtime.sendSavedSummary(preview)) "已交给原聊天的消息队列。总结文件仍保留，没有重新开启共享。"
                            else "消息暂未发出，已保存总结仍保留；可稍后重试。" }
                        catch (_: Exception) { status.text = "消息尚未发送，已保存总结仍保留；请检查原聊天或重新预览。" }
                        finally { summarySending = false }
                    }
                }.show()
        }
    }
    companion object {
        fun intent(context: Context, owner: String, conversation: String, initiator: String = "human") =
            Intent(context, OrbisScreenShareActivity::class.java).putExtra("assistant", owner)
                .putExtra("conversation", conversation).putExtra("initiator", initiator)
    }
}
