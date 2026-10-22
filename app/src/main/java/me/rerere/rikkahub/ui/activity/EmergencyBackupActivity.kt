package me.rerere.rikkahub.ui.activity

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.recovery.EmergencyAndroidRuntime
import me.rerere.rikkahub.data.recovery.EmergencyArchiveExport
import me.rerere.rikkahub.data.recovery.EmergencyArchiveMetadata
import me.rerere.rikkahub.data.recovery.EmergencyArchiveRoot
import me.rerere.rikkahub.data.recovery.EmergencyArchiveProgress
import me.rerere.rikkahub.data.recovery.createEmergencyArchiveWithMountAccess
import me.rerere.rikkahub.data.recovery.emergencyMountPointLease
import me.rerere.rikkahub.data.recovery.emergencyRecoveryError
import me.rerere.rikkahub.data.recovery.emergencyRecoveryDiagnostic
import me.rerere.rikkahub.data.recovery.emergencyExportDestinationRejection
import me.rerere.rikkahub.data.recovery.emergencyDestinationDiagnostic
import me.rerere.rikkahub.data.recovery.EmergencyMediaStoreDownload
import me.rerere.rikkahub.data.recovery.saveEmergencyDownload
import me.rerere.rikkahub.utils.EmergencyProcessGate
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** Intentionally native Views in a separate process: no Compose, Koin or live data decoding. */
class EmergencyBackupActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var lease: EmergencyProcessGate.Lease? = null
    private var busy = false
    private var exportFile: File? = null
    private var pendingRequest = 0
    private var pendingExport: File? = null
    private lateinit var statusPanel: EmergencyBackupStatusPanel
    private lateinit var statusDetails: TextView
    private var operationStage = "prepare"
    private var lastSafeDiagnostic: String? = null
    private val controls = mutableListOf<Button>()
    private val root get() = EmergencyAndroidRuntime.directory(this)
    private fun roots() = listOf("databases", "files", "shared_prefs", "no_backup")
        .associateWith { File(applicationInfo.dataDir, it).canonicalFile }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Never read intents for a destination, path, automatic export or automatic restore.
        exportFile = savedInstanceState?.getString("preparedExport")?.let { name ->
            if (name.matches(Regex("Orbis-emergency-[A-Za-z0-9_.-]+\\.zip"))) File(root, "exports/$name") else null
        }?.takeIf { it.isFile }
        pendingRequest = savedInstanceState?.getInt("pendingRequest", 0)?.takeIf { it in setOf(EXPORT) } ?: 0
        pendingExport = savedInstanceState?.getString("pendingExport")?.let { name ->
            if (name.matches(Regex("Orbis-emergency-[A-Za-z0-9_.-]+\\.zip"))) File(root, "exports/$name") else null
        }?.takeIf { it.isFile }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding + (24 * resources.displayMetrics.density).toInt(), padding, padding / 2)
        }
        statusPanel = EmergencyBackupStatusPanel(this) { diagnostic ->
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Orbis 紧急备份安全错误码", diagnostic))
        }
        // The state/progress/error stays above the scroll area, including on short screens.
        page.addView(statusPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, padding / 2, 0, padding)
        }
        page.addView(ScrollView(this).apply { addView(column) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(page)
        fun text(value: String, size: Float = 16f) = TextView(this).apply {
            this.text = value; textSize = size; setPadding(0, padding / 2, 0, padding / 2)
            column.addView(this)
        }
        statusDetails = text("请选择操作。不要先卸载或清除数据。", 14f).apply { setTextIsSelectable(true) }
        text("重要：Orbis 与“Orbis 紧急备份”是同一个应用的两个入口。卸载任意一个图标，都会卸载整个 Orbis，并删除本机聊天、助手与未导出的内容。请勿卸载或清除数据！", 18f).apply {
            setTextColor(Color.rgb(180, 40, 40))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        setStatus("请选择操作。不要先卸载或清除数据。")
        text("先保住内容，再处理故障。这里不打开聊天，也不加载助手外观。即使普通页面打不开，仍可从桌面的“Orbis 紧急备份”图标进入。")
        text("备份保存的是此刻磁盘上仍存在的数据，不是时间倒流：未写入的最后片段、已删除内容或损坏的存储无法凭空恢复。原始包可能保留故障，导出成功不等于故障已修好。")
        fun button(label: String, action: () -> Unit) {
            val button = Button(this).apply { this.text = label; setOnClickListener { action() } }
            controls += button; column.addView(button)
        }
        button("查看系统退出诊断（不打开聊天）") {
            runWork("正在读取系统保留的退出原因……", stage = "exit_diagnostics") {
                // No business-process pause, database access, restoration, or network request.
                val report = me.rerere.rikkahub.data.recovery.collectEmergencyExitDiagnostics(this)
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    setStatus("系统退出诊断报告已生成，是否取到记录以报告状态为准。聊天与备份没有被修改。没有记录不代表未发生闪退。")
                    val body = TextView(this).apply {
                        text = report; textSize = 13f; setTextIsSelectable(true)
                        val inset = (16 * resources.displayMetrics.density).toInt()
                        setPadding(inset, inset, inset, inset)
                    }
                    val dialog = AlertDialog.Builder(this)
                        .setTitle("系统退出诊断")
                        .setView(ScrollView(this).apply { addView(body) })
                        .setPositiveButton("复制诊断", null)
                        .setNegativeButton("关闭", null).create()
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            try {
                                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("Orbis 系统退出诊断", report))
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "已复制"
                            } catch (_: Exception) {
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "无法复制，可长按文本"
                            }
                        }
                    }
                    dialog.show()
                }
            }
        }
        button("导出崩溃前内容备份到本机") {
            confirmPause("备份可能包含聊天、咨询记录、文件、服务地址和密钥，未加密，请只保存在自己控制的本机目录，不要发到群或 GitHub。\n\n继续会暂停 Orbis 的通话、生成和后台任务；只备份已经落盘的内容。已发到外部的工具操作不会撤销，也不会自动重放。") {
                runWork("正在暂停后台并保存原始文件……") {
                    ensurePaused()
                    val directory = File(root, "exports").apply { check(isDirectory || mkdirs()) }
                    val name = "Orbis-emergency-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}-${UUID.randomUUID()}.zip"
                    val archive = File(directory, name)
                    val archiveProgress = progressListener(checkPaused = true)
                    val manifest = createEmergencyArchiveWithMountAccess(emergencyMountPointLease(this),
                        roots().map { EmergencyArchiveRoot(it.key, it.value) }, archive,
                        EmergencyArchiveMetadata(packageName, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()),
                        onProgress = archiveProgress)
                    EmergencyAndroidRuntime.checkPaused(this)
                    check(manifest.files.isNotEmpty()) { "没有发现可备份文件，未宣称已保存聊天。" }
                    exportFile = archive
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        setStatus("原始包已在应用内生成并校验，但还不能卸载。请继续选择手机“下载”等应用之外的位置。")
                        chooseExportDestination()
                    }
                }
            }
        }
        button("继续保存已生成的备份") {
            val candidate = exportFile ?: File(root, "exports").listFiles()?.filter { it.isFile && it.name.endsWith(".zip") }?.maxByOrNull { it.lastModified() }
            if (candidate == null) setStatus("尚无已生成的备份，请先导出。") else {
                exportFile = candidate
                chooseExportDestination()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) button("选择器失败？直接保存到本机下载") {
            val candidate = exportFile ?: File(root, "exports").listFiles()?.filter {
                it.isFile && it.name.matches(Regex("Orbis-emergency-[A-Za-z0-9_.-]+\\.zip"))
            }?.maxByOrNull { it.lastModified() }
            if (candidate == null) setStatus("尚无已生成的备份，请先导出。") else {
                confirmPause("会把已经生成的原始包保存到手机本机“下载/Orbis Backups”，不经过文件选择器，不上传云端。\n\n原始包未加密，可能含私人聊天和密钥；请仅自己保管。系统下载目录里的其他文件不会被覆盖。仍需完整写入并读回校验后，才会确认保存成功。") {
                    runWork("正在校验原始包并保存到本机下载……", stage = "save") {
                        ensurePaused()
                        val receipt = saveEmergencyDownload(candidate, EmergencyMediaStoreDownload(contentResolver, candidate.name),
                            onProgress = progressListener(checkPaused = true))
                        progress("已保存到本机“下载/Orbis Backups”，完整读回校验通过。\n文件：${receipt.displayName}\nSHA-256：${receipt.archive.sha256}\n\n应用内原始包仍保留；请在系统文件管理器确认并另存到电脑。导出成功不等于聊天故障已经修复。")
                    }
                }
            }
        }
        button("结束备份并打开 Orbis") {
            confirmPause("将尝试正常打开 Orbis，不修复或替换数据。未知故障不会因导出自动消失。建议先把备份保存到应用外。") {
                leaveBackup()
            }
        }
        text("此包只提供本地备份导出，不检查修复会话、不替换数据库、不恢复旧任务。导出成功不代表故障已修复；原始数据和已导出的备份不会自动删除。外部附件、Android 密钥库中的密钥及尚未落盘内容可能不在包内。请另存一份到电脑。", 14f)
        controls.forEach { it.isEnabled = pendingRequest == 0 }
        if (savedInstanceState != null) {
            val previous = emergencyBackupRebuiltPresentation(
                wasBusy = savedInstanceState.getBoolean("wasBusy", false),
                waitingForPicker = pendingRequest != 0,
                savedDiagnostic = savedInstanceState.getString("safeDiagnostic"),
            )
            setStatus(previous.message, failed = previous.failed, diagnostic = previous.diagnostic)
        } else if (pendingRequest != 0) setStatus(EMERGENCY_PICKER_WAITING_MESSAGE)
    }

    private fun leaveBackup() {
        runWork("正在退出救援保护……", stage = "resume") {
            ensurePaused()
            val current = checkNotNull(lease)
            check(EmergencyAndroidRuntime.gate(this).clearRecoveryRequest(current)) { "无法退出救援保护。" }
            current.close(); lease = null
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                startActivity(Intent(this, RouteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }
    }

    private fun ensurePaused() {
        val nextStage = operationStage
        operationStage = "pause"
        if (lease?.isValid != true) lease = EmergencyAndroidRuntime.pauseBusiness(this)
        EmergencyAndroidRuntime.checkPaused(this)
        // A previous process death may have interrupted a read-only permission lease.
        // Restore it before exporting/restoring data OR clearing the rescue process fence.
        operationStage = "permission_restore"
        emergencyMountPointLease(this).recoverInterrupted()
        operationStage = nextStage
    }

    private fun confirmPause(message: String, action: () -> Unit) {
        if (busy || pendingRequest != 0 || isDestroyed || isFinishing) return
        AlertDialog.Builder(this).setTitle("先保护现有内容").setMessage(message)
            .setNegativeButton("取消", null).setPositiveButton("确认继续") { _, _ -> action() }.show()
    }

    private fun chooseExportDestination() {
        val file = exportFile ?: return
        pendingExport = file
        launchPicker(EXPORT, Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, file.name); putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        })
    }

    private fun launchPicker(code: Int, intent: Intent) {
        if (isFinishing || isDestroyed || pendingRequest != 0) return
        pendingRequest = code
        controls.forEach { it.isEnabled = false }
        setStatus(EMERGENCY_PICKER_WAITING_MESSAGE)
        try { startActivityForResult(intent, code) } catch (_: Exception) {
            pendingRequest = 0; pendingExport = null
            controls.forEach { it.isEnabled = !busy }
            setStatus("无法打开系统文件选择器。原数据和应用内副本仍保留；请确认系统文件管理器可用。",
                failed = true, diagnostic = "ORBIS_RECOVERY_PICKER_UNAVAILABLE · picker")
        }
    }

    @Deprecated("Native isolated rescue deliberately uses Activity results without Compose dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pendingRequest || requestCode !in setOf(EXPORT)) return
        val selectedExport = pendingExport
        pendingRequest = 0; pendingExport = null
        controls.forEach { it.isEnabled = !busy }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            setStatus("已取消文件选择。应用内副本若已生成仍保留，但尚未确认保存到应用外。")
            return
        }
        if (uri.scheme != "content" || uri.authority?.startsWith(packageName) == true) {
            setStatus("请选择系统文件管理器中的本机目录，不能保存到 Orbis 自己的工作区或私有文件。",
                failed = true, diagnostic = emergencyDestinationDiagnostic(if (uri.scheme != "content") "invalid_scheme" else "app_private_directory"))
            return
        }
        val destinationRejection = emergencyExportDestinationRejection(uri.authority,
                runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull(), packageName)
        if (destinationRejection != null) {
            setStatus("请另存到系统文件管理器的手机“下载”或外置存储普通目录。为防卸载时一起删除，不接受应用专属目录；也不向无法确认是本机存储的第三方文件服务写入。" +
                ("应用内原始包仍保留。" +
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "也可返回本页点“选择器失败？直接保存到本机下载”。" else ""),
                failed = true, diagnostic = emergencyDestinationDiagnostic(destinationRejection))
            return
        }
        runWork("正在写入所选位置并读回校验……") {
            val archive = checkNotNull(selectedExport) { "没有匹配的待导出文件，请重新选择备份。" }
            val receipt = EmergencyArchiveExport.saveVerified(archive,
                openDestination = { checkNotNull(contentResolver.openOutputStream(uri, "wt")) { "无法写入所选位置。" } },
                reopenDestination = { checkNotNull(contentResolver.openInputStream(uri)) { "无法读回备份，尚未确认保存成功。" } },
                onProgress = progressListener(checkPaused = false))
            progress("已保存到所选位置并读回校验通过。\n文件：${archive.name}\nSHA-256：${receipt.sha256}\n\n原始文件没有被修复或删除；请在文件管理器确认并另存一份。备份的可读性不保证所有内容都能自动恢复。")
        }
    }

    private fun setStatus(message: String, active: Boolean = false, failed: Boolean = false, diagnostic: String? = null) {
        lastSafeDiagnostic = if (failed) emergencyBackupSavedDiagnostic(diagnostic) else null
        statusPanel.showMessage(message, active, failed, diagnostic)
        statusDetails.text = message + if (diagnostic != null) "\n\n安全错误码：$diagnostic" else ""
    }

    private fun progress(message: String) { runOnUiThread { if (!isDestroyed && !isFinishing) setStatus(message) } }

    private fun progressListener(checkPaused: Boolean): (EmergencyArchiveProgress) -> Unit {
        val throttle = EmergencyBackupProgressThrottle()
        return { event ->
            val stage = emergencyBackupProgressStage(event.phase)
            operationStage = stage
            if (checkPaused && throttle.needsSafetyCheck(stage, SystemClock.elapsedRealtime())) {
                EmergencyAndroidRuntime.checkPaused(this)
                throttle.safetyCheckFinished(stage, SystemClock.elapsedRealtime())
            }
            if (throttle.shouldDisplay(stage, SystemClock.elapsedRealtime())) {
                val presentation = emergencyBackupProgressPresentation(event.phase, event.completedBytes, event.totalBytes,
                    event.completedEntries, event.totalEntries)
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        statusPanel.showProgress(presentation)
                        statusDetails.text = "${presentation.title}\n${presentation.detail}\n\n状态始终显示在上方。可以回桌面，但请不要卸载或清除数据；备份不会自动重试。"
                    }
                }
            }
        }
    }

    private fun runWork(message: String, stage: String = "prepare", operation: () -> Unit) {
        if (busy || isDestroyed || isFinishing) return
        busy = true
        operationStage = stage
        controls.forEach { it.isEnabled = false }; setStatus(message, active = true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        worker.execute {
            try { operation() } catch (error: Exception) {
                // Do not display raw provider paths or serialized content; no private data in logs.
                val message = "${emergencyRecoveryError(error)}\n\n操作未完成，不能据此卸载或清数据。原数据和已生成的备份不会自动删除。"
                val diagnostic = emergencyRecoveryDiagnostic(error, operationStage)
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        setStatus(message, failed = true, diagnostic = diagnostic)
                        statusPanel.announceForAccessibility("操作未完成。错误提示固定显示在页面上方，可复制安全错误码。")
                    }
                }
            } finally {
                runOnUiThread {
                    busy = false
                    if (!isDestroyed) {
                        controls.forEach { it.isEnabled = pendingRequest == 0 }
                        statusPanel.stopProgress()
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else { lease?.close(); lease = null }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // The status snapshot stores only a fixed diagnostic + busy bit, never displayed text.
        outState.putBoolean("wasBusy", busy)
        lastSafeDiagnostic?.let { outState.putString("safeDiagnostic", it) }
        exportFile?.let { outState.putString("preparedExport", it.name) }
        outState.putInt("pendingRequest", pendingRequest)
        pendingExport?.let { outState.putString("pendingExport", it.name) }
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Native rescue back handling")
    override fun onBackPressed() {
        if (busy) {
            statusDetails.text = "正在保护数据，请等待完成；可回桌面，但不要卸载或清数据。当前阶段与进度仍显示在上方。"
        } else super.onBackPressed()
    }

    override fun onDestroy() {
        if (!busy) { lease?.close(); lease = null }
        worker.shutdown()
        super.onDestroy()
    }

    companion object { private const val EXPORT = 7401 }
}

/** Native presentation only; constructing this panel cannot start recovery or any app service. */
internal class EmergencyBackupStatusPanel(context: Context, private val copyDiagnostic: (String) -> Unit) : LinearLayout(context) {
    internal val heading = TextView(context)
    internal val summary = TextView(context)
    internal val indicator = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    internal val errorCode = TextView(context)
    internal val copyButton = Button(context)
    internal val scrollHint = TextView(context)
    private var diagnostic: String? = null

    init {
        orientation = VERTICAL
        val inset = (12 * resources.displayMetrics.density).toInt()
        setPadding(inset, inset, inset, inset)
        heading.apply { textSize = 20f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setTextColor(Color.rgb(30, 45, 62)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        summary.apply { textSize = 14f; maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setTextColor(Color.rgb(40, 48, 58)) }
        errorCode.apply { textSize = 12f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setTextColor(Color.rgb(140, 24, 24)); setTextIsSelectable(true) }
        scrollHint.apply { text = "完整说明与操作在下方，可滚动查看 ↓"; textSize = 12f; maxLines = 2; setTextColor(Color.rgb(68, 79, 90)) }
        indicator.max = 100
        copyButton.text = "复制安全错误码"
        copyButton.setOnClickListener {
            diagnostic?.let {
                try {
                    copyDiagnostic(it)
                    copyButton.text = "已复制安全错误码"
                } catch (_: Exception) { copyButton.text = "无法复制，可长按上方错误码" }
            }
        }
        listOf(heading, summary, indicator, errorCode, copyButton, scrollHint).forEach {
            addView(it, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        showMessage("请选择操作。不要先卸载或清除数据。")
    }

    fun showMessage(message: String, active: Boolean = false, failed: Boolean = false, diagnostic: String? = null) {
        this.diagnostic = diagnostic
        setBackgroundColor(if (failed) Color.rgb(255, 235, 232) else Color.rgb(241, 246, 251))
        heading.text = if (failed) "操作未完成 · 请保留原数据" else if (active) "Orbis 紧急备份 · 正在处理" else "Orbis 紧急备份 · 当前状态"
        heading.setTextColor(if (failed) Color.rgb(155, 25, 25) else Color.rgb(30, 45, 62))
        summary.text = message
        summary.contentDescription = message + "。完整说明在下方可滚动区域。"
        indicator.isIndeterminate = true
        indicator.visibility = if (active) View.VISIBLE else View.GONE
        errorCode.text = diagnostic.orEmpty()
        errorCode.visibility = if (diagnostic != null) View.VISIBLE else View.GONE
        copyButton.visibility = if (diagnostic != null) View.VISIBLE else View.GONE
        copyButton.text = "复制安全错误码"
    }

    fun showProgress(value: EmergencyBackupProgressPresentation) {
        showMessage(value.detail, active = true)
        heading.text = value.title
        indicator.isIndeterminate = value.percent == null
        if (value.percent != null) indicator.progress = value.percent
    }

    fun stopProgress() { indicator.visibility = View.GONE }
}
