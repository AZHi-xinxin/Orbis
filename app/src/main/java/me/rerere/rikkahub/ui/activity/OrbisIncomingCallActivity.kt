package me.rerere.rikkahub.ui.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.orbis.contact.IncomingCallOutcome
import me.rerere.rikkahub.service.OrbisIncomingCallRuntime
import me.rerere.rikkahub.service.OrbisCallFailure
import me.rerere.rikkahub.service.startIncomingVoice
import me.rerere.rikkahub.ui.theme.RikkahubTheme

/** Private notification target. A stale/expired intent can neither ring nor reopen the mic. */
class OrbisIncomingCallActivity : ComponentActivity() {
    private val runtime by lazy { OrbisIncomingCallRuntime.get(this) }
    private var attemptId by mutableStateOf("")
    private var requestedAction by mutableStateOf("view")
    private var permissionMessage by mutableStateOf<String?>(null)
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionMessage = if (granted) "麦克风权限已开启，请再次点击接听。" else "未开启麦克风；仍可选择静音接听。"
    }
    private val camera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionMessage = if (granted) "相机权限已开启，请再次点击接听视频。" else "视频来电未接听；相机保持关闭。"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        readIntent(intent)
        setContent {
            RikkahubTheme {
                val call by runtime.state.collectAsStateWithLifecycle()
                val visible = call?.takeIf { it.attempt.id == attemptId }
                // The runtime clears its active invitation immediately after finishing. Retain a
                // presentation-only failure here; it can never ring, retry, or reopen the mic.
                var failed by remember(attemptId) { mutableStateOf<OrbisCallFailure?>(null) }
                var title by remember(attemptId) { mutableStateOf("语音来电") }
                LaunchedEffect(attemptId, visible?.attempt?.outcome) {
                    visible?.title?.let { title = it }
                    val attempt = visible?.attempt ?: runCatching { runtime.ledger.get(attemptId) }.getOrNull()
                    if (attempt == null) { failed = OrbisCallFailure.UNKNOWN; return@LaunchedEffect }
                    when (attempt.outcome) {
                        IncomingCallOutcome.CONNECTED -> {
                            startActivity(Intent(this@OrbisIncomingCallActivity, RouteActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                putExtra("conversationId", attempt.conversationId)
                            }); finish()
                        }
                        IncomingCallOutcome.FAILED -> failed = OrbisCallFailure.fromCode(attempt.failureCode)
                        IncomingCallOutcome.REJECTED, IncomingCallOutcome.NO_RESPONSE -> finish()
                        else -> failed = null
                    }
                }
                LaunchedEffect(requestedAction, attemptId) {
                    val action = requestedAction; requestedAction = "view"
                    when (action) { "answer" -> answer(false); "muted" -> answer(true); "reject" -> runtime.reject(attemptId) }
                }
                Surface(Modifier.fillMaxSize(), color = Color(0xFF1D263D)) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                        Text(if (visible?.attempt?.video == true) "ORBIS · 视频来电" else "ORBIS · 来电", color = Color.White.copy(alpha = .6f))
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Surface(Modifier.size(116.dp), shape = CircleShape, color = Color(0xFF405E93)) {
                                Box(contentAlignment = Alignment.Center) { Text("★", fontSize = 54.sp, color = Color.White) }
                            }
                            Text(title, color = Color.White, fontSize = 28.sp)
                            val failure = failed
                            if (failure != null) {
                                Text("本次来电未接通", color = Color.White, fontSize = 20.sp)
                                Text(failure.explanation, color = Color.White.copy(alpha = .85f))
                                Text("诊断代码：${failure.code}", color = Color.White.copy(alpha = .6f))
                                Text("请在设置中检查语音服务；也可在通知设置查看全部主动来电日志。不会自动重拨。",
                                    color = Color.White.copy(alpha = .6f))
                            } else {
                                Text(visible?.attempt?.reason.orEmpty(), color = Color.White.copy(alpha = .8f),
                                    modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                                Text(if (visible?.attempt?.outcome == IncomingCallOutcome.CONNECTING) "正在接通…"
                                    else if (visible?.attempt?.video == true) "接听后开启视频与语音 · 画面定期发给当前模型"
                                    else "等待你接听 · 接听前不开麦",
                                    color = Color.White.copy(alpha = .6f))
                                permissionMessage?.let { Text(it, color = Color.White) }
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val enabled = visible?.attempt?.outcome == IncomingCallOutcome.RINGING
                            if (failed != null) {
                                Button(onClick = { finish() }) { Text("知道了 · 关闭") }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { runtime.reject(attemptId) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBA4D59))) { Text("拒接") }
                                    Button(enabled = enabled, onClick = { answer(false) }) { Text("接听") }
                                }
                                TextButton(enabled = enabled, onClick = { answer(true) }) { Text("静音接听 · 关麦，仍听对方", color = Color.White) }
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readIntent(intent) }
    private fun readIntent(intent: Intent) {
        attemptId = intent.getStringExtra("attemptId").orEmpty()
        requestedAction = intent.getStringExtra("callAction") ?: "view"
        permissionMessage = null
    }
    private fun answer(muted: Boolean) {
        if (runtime.state.value?.attempt?.video == true) {
            if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
                permissionMessage = "请先解锁手机，再接听视频；锁屏时不会打开相机。"
                return
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                camera.launch(Manifest.permission.CAMERA); return
            }
        }
        if (!muted && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphone.launch(Manifest.permission.RECORD_AUDIO); return
        }
        runtime.answer(attemptId, muted) { startIncomingVoice(this, lifecycle, it) }
    }
}
