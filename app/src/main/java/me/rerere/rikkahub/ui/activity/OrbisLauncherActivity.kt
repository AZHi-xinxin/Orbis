package me.rerere.rikkahub.ui.activity

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.recovery.EmergencyAndroidRuntime
import me.rerere.rikkahub.data.recovery.EmergencyLauncherState
import me.rerere.rikkahub.data.recovery.openOrResumeEmergencyTask
import me.rerere.rikkahub.data.recovery.inspectNoRescueLauncher
import me.rerere.rikkahub.data.recovery.requireSafeNoRescueStartup
import java.io.File
import java.util.concurrent.Executors

/**
 * Native, database-free launcher in :launcher. Application must remain lightweight here.
 * Incoming action/data/extras/flags never select a destination or cause export, restoration,
 * configuration changes, or acknowledgement. All outgoing intents are freshly constructed.
 */
class OrbisLauncherActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var retry: Button
    private lateinit var rescue: Button
    private var checking = false
    private var resumed = false
    private var pending: Pair<EmergencyLauncherState, Boolean>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Healthy launch never constructs the recovery form. Recreating this router
        // is not an error and must not strand the user on a manual retry screen.
        inspect(allowNormalNavigation = true)
    }

    private fun ensureRecoveryForm() {
        if (::status.isInitialized) return
        val padding = (24 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding * 2, padding, padding)
        }
        fun text(value: String, size: Float) = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, padding / 2, 0, padding / 2)
            column.addView(this)
        }
        text("Orbis 启动检查", 24f)
        status = text("正在检查启动状态……", 17f)
        text(if (BuildConfig.ORBIS_EMERGENCY_RESCUE_ENABLED)
            "不要卸载或清除数据。这里不会打开聊天数据库、自动修复、导出或上传内容，也不会自动退出救援保护。"
        else "当前为无紧急救援版，没有救援入口，也不会因旧崩溃记录拦截启动。这里仅检查旧版尚未完成的文件恢复，不能直接打开半恢复的数据。请勿卸载或清除数据。", 15f)
        retry = Button(this).apply {
            text = "重新检查并打开 Orbis"
            setOnClickListener { inspect(allowNormalNavigation = true) }
            column.addView(this)
        }
        rescue = Button(this).apply {
            text = "打开紧急备份"
            setOnClickListener { openRecovery() }
            column.addView(this)
            visibility = if (BuildConfig.ORBIS_EMERGENCY_RESCUE_ENABLED) android.view.View.VISIBLE else android.view.View.GONE
        }
        Button(this).apply {
            text = "暂时关闭"
            setOnClickListener { closeRouterTask() }
            column.addView(this)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(android.graphics.Color.WHITE); addView(column) },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Repeated launcher taps never reopen recovery or replay navigation/side effects.
        // In particular do not forward this Intent or use its extras.
        inspect(allowNormalNavigation = true)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        pending?.let { (state, navigate) -> pending = null; present(state, navigate) }
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    private fun inspect(allowNormalNavigation: Boolean) {
        if (checking || isFinishing || isDestroyed) return
        checking = true
        if (::status.isInitialized) {
            retry.isEnabled = false
            rescue.isEnabled = false
            status.text = "正在检查启动状态……"
        }
        worker.execute {
            val state = try {
                inspectNoRescueLauncher(EmergencyAndroidRuntime.gate(this)) {
                    requireSafeNoRescueStartup(File(applicationInfo.dataDir, "orbis-emergency"))
                }
            } catch (_: Exception) {
                if (BuildConfig.ORBIS_EMERGENCY_RESCUE_ENABLED) EmergencyLauncherState.CHECK_FAILED
                else EmergencyLauncherState.LEGACY_RESTORE_BLOCKED
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                checking = false
                if (resumed) present(state, allowNormalNavigation)
                else pending = state to allowNormalNavigation
            }
        }
    }

    private fun present(state: EmergencyLauncherState, navigate: Boolean) {
        if (state == EmergencyLauncherState.READY && navigate) {
            try {
                startActivity(Intent(this, RouteActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                })
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
                // This router has its own .launcher task affinity. Remove that task,
                // not merely its Activity, so compatibility hosts cannot retain a blank card.
                closeRouterTask()
                return
            } catch (_: Exception) {
                ensureRecoveryForm()
                retry.isEnabled = true
                rescue.isEnabled = true
                status.text = if (BuildConfig.ORBIS_EMERGENCY_RESCUE_ENABLED)
                    "未能打开 Orbis，原数据未清除。可重试或打开紧急备份。\nORBIS-STARTUP/E_OPEN_APP"
                else "未能打开 Orbis，原数据未清除。可重试；若仍失败，请保留数据并联系维护者。\nORBIS-STARTUP/E_OPEN_APP"
                return
            }
        }
        ensureRecoveryForm()
        retry.isEnabled = true
        rescue.isEnabled = true
        status.text = when (state) {
            EmergencyLauncherState.READY -> "启动检查通过，可打开 Orbis。"
            EmergencyLauncherState.RECOVERY_REQUESTED -> "Orbis 仍处于救援保护，聊天和后台任务暂不启动。请进入紧急备份，先保住内容；准备好后再在那里选择“退出救援并打开 Orbis”。"
            EmergencyLauncherState.PREVIOUS_CRASH -> "检测到上次启动异常，未继续加载聊天。请先进入紧急备份，保存内容和错误报告后再尝试恢复。"
            EmergencyLauncherState.BUSINESS_BUSY -> "启动保护正在被其他操作占用，尚未打开聊天。若正在备份或选择保存位置，请先完成该操作；不要清数据。"
            EmergencyLauncherState.CHECK_FAILED -> "无法安全检查启动状态，已保留原数据并停止普通启动。可尝试紧急备份；仍失败请联系维护者。\nORBIS-STARTUP/E_GATE_CHECK"
            EmergencyLauncherState.LEGACY_RESTORE_BLOCKED -> "旧版文件恢复尚未完成，或无法读取其状态。原数据与旧恢复副本均保留；请联系维护者处理旧恢复。当前主版和备份包都不会自动修复或替换数据库，不要卸载、清数据或删除恢复文件。\nORBIS-STARTUP/E_LEGACY_RESTORE"
        }
    }

    private fun openRecovery() {
        if (!BuildConfig.ORBIS_EMERGENCY_RESCUE_ENABLED) return
        if (checking || isFinishing || isDestroyed) return
        val opened = openOrResumeEmergencyTask(findExistingTask = {
            val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val task = manager.appTasks.firstOrNull {
                // Missing task metadata is not proof that no rescue task exists. Fail
                // visibly rather than risk restarting a rescue picker already in flight.
                val info = requireNotNull(it.taskInfo) { "Task metadata unavailable" }
                val component = info.baseActivity ?: info.baseIntent.component
                component?.packageName == packageName &&
                    component.className == EmergencyBackupActivity::class.java.name
            }
            task?.let { existing -> { existing.moveToFront() } }
        }, openNew = {
            startActivity(Intent(this, EmergencyBackupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        if (opened) closeRouterTask() else status.text =
            "无法安全打开或切回紧急备份。若已在选择保存位置，请从最近任务返回原救援窗口，不要重复启动或清除数据。\nORBIS-STARTUP/E_OPEN_RECOVERY"
    }

    private fun closeRouterTask() {
        if (isTaskRoot) finishAndRemoveTask() else finish()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
