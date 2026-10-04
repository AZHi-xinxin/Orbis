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
import android.os.SystemClock
import android.provider.Settings
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
import me.rerere.rikkahub.data.recovery.EmergencyCrashReportExport
import me.rerere.rikkahub.data.recovery.EmergencyRestore
import me.rerere.rikkahub.data.recovery.validateEmergencyRestore
import me.rerere.rikkahub.data.recovery.emergencyRecoveryError
import me.rerere.rikkahub.data.recovery.emergencyRecoveryDiagnostic
import me.rerere.rikkahub.data.recovery.emergencyExportDestinationRejection
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.EmergencyProcessGate
import java.io.File
import java.io.FileOutputStream
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
    private var pendingReport: EmergencyCrashReportExport? = null
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
        pendingRequest = savedInstanceState?.getInt("pendingRequest", 0)?.takeIf { it in setOf(EXPORT, IMPORT, EXPORT_REPORT) } ?: 0
        pendingExport = savedInstanceState?.getString("pendingExport")?.let { name ->
            if (name.matches(Regex("Orbis-emergency-[A-Za-z0-9_.-]+\\.zip"))) File(root, "exports/$name") else null
        }?.takeIf { it.isFile }
        pendingReport = if (pendingRequest == EXPORT_REPORT) runCatching {
            EmergencyCrashReportExport.fromSaved(savedInstanceState?.getString("pendingReport"))
        }.getOrNull() else null
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
        setStatus("请选择操作。不要先卸载或清除数据。")
        text("先保住内容，再处理故障。这里不打开聊天，也不加载助手外观。即使普通页面打不开，仍可从桌面的“Orbis 紧急备份”图标进入。")
        text("备份保存的是此刻磁盘上仍存在的数据，不是时间倒流：未写入的最后片段、已删除内容或损坏的存储无法凭空恢复。原始包可能保留故障，导出成功不等于故障已修好。")
        fun button(label: String, action: () -> Unit) {
            val button = Button(this).apply { this.text = label; setOnClickListener { action() } }
            controls += button; column.addView(button)
        }
        button("导出错误报告（仅文字）") {
            confirmPause("只导出上次已保存的错误文字、版本与设备信息，不导出聊天数据库或整个备份，不会清除崩溃记录。错误文字本身可能包含私人内容、地址或密钥，请检查并遮去后再自行分享；不会自动发送或上传。\n\n读取前会暂停 Orbis 的通话、生成与后台任务，完成后仍处于救援保护。") {
                runWork("正在读取已保存的错误报告……", stage = "report") {
                    // SharedPreferences can restore a .bak file on read: take the same cold lease first.
                    ensurePaused()
                    val report = EmergencyCrashReportExport.fromSaved(CrashHandler.getStackTrace(this))
                    if (report == null) {
                        progress("目前没有已保存的错误文字。不会为取报告重新触发崩溃；仍可导出内容备份。")
                    } else runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        pendingReport = report
                        launchPicker(EXPORT_REPORT, Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = EmergencyCrashReportExport.MIME_TYPE
                            putExtra(Intent.EXTRA_TITLE, "Orbis-crash-report-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
                            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                        })
                    }
                }
            }
        }
        button("导出崩溃前内容备份到本机") {
            confirmPause("备份可能包含聊天、咨询记录、文件、服务地址和密钥，未加密，请只保存在自己控制的本机目录，不要发到群或 GitHub。\n\n继续会暂停 Orbis 的通话、生成和后台任务；只备份已经落盘的内容。已发到外部的工具操作不会撤销，也不会自动重放。") {
                runWork("正在暂停后台并保存原始文件……") {
                    ensurePaused()
                    checkNoInterruptedRestore()
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
        button("从紧急备份恢复聊天与助手") {
            AlertDialog.Builder(this).setTitle("选择 Orbis 紧急备份")
                .setMessage("这是原始救援包的专用恢复入口，不是普通备份导入。会先校验文件，再让你确认；仅恢复可验证的聊天、助手设置、附件、作品和通话记录。旧工具等待、后台任务、权限批准、加密服务连接不会自动恢复。")
                .setNegativeButton("取消", null).setPositiveButton("选择文件") { _, _ ->
                    launchPicker(IMPORT, Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    })
                }.show()
        }
        button("撤回尚未完成的恢复") {
            confirmPause("仅撤回意外中断、尚未完成的恢复，回到本次恢复前保存的原数据。不撤销已完成的恢复，也不删除外部备份。") {
                runWork("正在检查恢复日志……", stage = "restore") {
                    ensurePaused()
                    val pending = unfinishedTransactions()
                    check(pending.isNotEmpty()) { "没有需要撤回的未完成恢复。" }
                    pending.forEach { EmergencyRestore.rollback(it, roots(), { EmergencyAndroidRuntime.checkPaused(this) }) }
                    progress("已撤回未完成恢复；原始包和恢复前副本仍保留。可以重新导出。")
                }
            }
        }
        button("系统设置：强行停止 Orbis") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("已备份，尝试会话检查与恢复") {
            confirmPause("建议先完成应用外备份。此操作会退出救援保护并启动原有的会话检查页面；它仍依赖正常的数据库和界面组件，未知故障可能使它打不开。它不会自动修复或打开聊天，但正常后台服务可能恢复运行。") {
                leaveRecovery(ConversationRescueActivity::class.java)
            }
        }
        button("退出救援并打开 Orbis") {
            confirmPause("退出保护后将尝试正常打开 Orbis。若仍崩溃，请再次进入紧急备份；未知故障不会因导出自动消失。建议先把备份保存到应用外。") {
                leaveRecovery(RouteActivity::class.java)
            }
        }
        text("只有看到“已保存到所选位置并读回校验通过”，才算完成外部备份。建议在文件管理器确认文件确实存在，再另存一份到电脑。外部附件原文件、Android 密钥库中的密钥可能无法随包恢复；运行环境链接和特殊节点仅留清单。隐私室只保留密文，恢复后必须输入另行保管的恢复码，不会自动解锁。MCP 服务恢复后保持关闭、工具需重新审批；连接服务可能需要再授权。", 14f)
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

    private fun leaveRecovery(destination: Class<out Activity>) {
        runWork("正在退出救援保护……", stage = "resume") {
            ensurePaused(); checkNoInterruptedRestore()
            check(CrashHandler.acknowledgeCrashed(this)) { "无法确认启动状态，请检查空间。" }
            val current = checkNotNull(lease)
            check(EmergencyAndroidRuntime.gate(this).clearRecoveryRequest(current)) { "无法退出救援保护。" }
            current.close(); lease = null
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                startActivity(Intent(this, destination).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

    private fun unfinishedTransactions(): List<File> {
        val parent = File(root, "transactions")
        if (!parent.exists()) return emptyList()
        return checkNotNull(parent.listFiles()) { "无法读取恢复日志，暂不允许正常启动。" }.filter { directory ->
            val journal = File(directory, EmergencyRestore.JOURNAL)
            if (!journal.exists()) return@filter false // Preparation failed before any live move.
            val state = EmergencyRestore.readJournal(directory).status
            check(state in setOf("PREPARED", "INSTALLING", "ROLLING_BACK", "COMMITTED", "ROLLED_BACK")) {
                "恢复日志无法确认，暂不允许正常启动。"
            }
            state in setOf("PREPARED", "INSTALLING", "ROLLING_BACK")
        }
    }

    private fun checkNoInterruptedRestore() {
        check(unfinishedTransactions().isEmpty()) { "有未完成的恢复，请先点“撤回尚未完成的恢复”。原始包和恢复前副本仍在。" }
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
            pendingRequest = 0; pendingExport = null; pendingReport = null
            controls.forEach { it.isEnabled = !busy }
            setStatus("无法打开系统文件选择器。原数据和应用内副本仍保留；请确认系统文件管理器可用。",
                failed = true, diagnostic = "ORBIS_RECOVERY_PICKER_UNAVAILABLE · picker")
        }
    }

    @Deprecated("Native isolated rescue deliberately uses Activity results without Compose dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pendingRequest || requestCode !in setOf(EXPORT, IMPORT, EXPORT_REPORT)) return
        val selectedExport = pendingExport
        val selectedReport = pendingReport
        pendingRequest = 0; pendingExport = null; pendingReport = null
        controls.forEach { it.isEnabled = !busy }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            setStatus(if (requestCode == EXPORT_REPORT) "已取消错误报告导出，原报告仍保留，未清除崩溃记录。"
                else "已取消文件选择。应用内副本若已生成仍保留，但尚未确认保存到应用外。")
            return
        }
        if (uri.scheme != "content" || uri.authority?.startsWith(packageName) == true) {
            setStatus("请选择系统文件管理器中的本机目录，不能保存到 Orbis 自己的工作区或私有文件。",
                failed = true, diagnostic = "ORBIS_RECOVERY_DESTINATION_REJECTED · picker")
            return
        }
        if (requestCode in setOf(EXPORT, EXPORT_REPORT) && emergencyExportDestinationRejection(uri.authority,
                runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull(), packageName) != null) {
            setStatus("请另存到系统文件管理器的手机“下载”或外置存储普通目录。为防卸载时一起删除，不接受应用专属目录；也不向无法确认是本机存储的第三方文件服务写入。" +
                (if (requestCode == EXPORT_REPORT) "原错误报告仍保留。" else "应用内原始包仍保留。"),
                failed = true, diagnostic = "ORBIS_RECOVERY_DESTINATION_REJECTED · picker")
            return
        }
        if (requestCode == EXPORT_REPORT) runWork("正在保存错误文字并读回校验……", stage = "report") {
            val report = checkNotNull(selectedReport) { "crash_report_snapshot_missing" }
            contentResolver.openOutputStream(uri, "wt").use { target ->
                report.writeTo(checkNotNull(target) { "crash_report_output_unavailable" })
            }
            operationStage = "readback"
            contentResolver.openInputStream(uri).use { input ->
                report.verify(checkNotNull(input) { "crash_report_readback_unavailable" })
            }
            progress("错误报告已保存为 TXT，并读回校验通过；原崩溃记录仍保留。请检查并遮去私人内容后，再自行分享。\n\n这只是诊断文字，不是聊天内容备份，不能作为卸载或清除数据的依据。")
        } else if (requestCode == EXPORT) runWork("正在写入所选位置并读回校验……") {
            val archive = checkNotNull(selectedExport) { "没有匹配的待导出文件，请重新选择备份。" }
            val receipt = EmergencyArchiveExport.saveVerified(archive,
                openDestination = { checkNotNull(contentResolver.openOutputStream(uri, "wt")) { "无法写入所选位置。" } },
                reopenDestination = { checkNotNull(contentResolver.openInputStream(uri)) { "无法读回备份，尚未确认保存成功。" } },
                onProgress = progressListener(checkPaused = false))
            progress("已保存到所选位置并读回校验通过。\n文件：${archive.name}\nSHA-256：${receipt.sha256}\n\n原始文件没有被修复或删除；请在文件管理器确认并另存一份。备份的可读性不保证所有内容都能自动恢复。")
        } else if (requestCode == IMPORT) confirmPause("将暂停 Orbis 并检查这个紧急包。恢复会替换当前聊天与设置，当前四类数据目录会完整保存在本机恢复前副本里；不会自动运行旧任务。建议先导出当前数据，不能把本机副本当作卸载后仍保留的备份。") {
            runWork("正在校验紧急包和准备恢复副本……", stage = "restore") {
                ensurePaused(); checkNoInterruptedRestore()
                val imports = File(root, "imports").apply { check(isDirectory || mkdirs()) }
                val archive = File(imports, "${UUID.randomUUID()}.zip")
                contentResolver.openInputStream(uri).use { input ->
                    checkNotNull(input) { "无法读取所选备份。" }
                    FileOutputStream(archive).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(imports.usableSpace > count + 4L * 1024 * 1024) { "本机空间不足；没有替换当前数据。" }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                val transactions = File(root, "transactions").apply { check(isDirectory || mkdirs()) }
                val transaction = File(transactions, UUID.randomUUID().toString())
                val plan = EmergencyRestore.prepare(archive, transaction, packageName, BuildConfig.VERSION_CODE.toLong(), roots(),
                    { EmergencyAndroidRuntime.checkPaused(this) }, { validateEmergencyRestore(this, it) })
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    setStatus("校验通过，等待你的最终恢复确认；还没有替换当前数据。")
                    AlertDialog.Builder(this).setTitle("确认恢复聊天与助手？")
                        .setMessage("将还原 ${plan.selectedFiles.size} 个选中文件，其余原始数据仍保留在救援包中，不会自动启用旧后台任务。当前内容会保存在本机恢复前副本。恢复后不会自动打开聊天。" +
                            if (plan.restoredPrivateVaults + plan.retainedPrivateVaults > 0)
                                "\n\n隐私室：${plan.restoredPrivateVaults} 个密文副本可恢复，但必须输入独立恢复码才可重新启用；${plan.retainedPrivateVaults} 个暂不能自动恢复，原始密文仍完整保留在救援包中。" else "")
                        .setCancelable(false)
                        .setNegativeButton("暂不恢复") { _, _ ->
                            runWork("正在撤回准备……", stage = "restore") {
                                EmergencyRestore.rollback(transaction, roots(), { EmergencyAndroidRuntime.checkPaused(this) })
                                progress("已取消恢复，当前数据未被替换；原始救援包保留。")
                            }
                        }
                        .setPositiveButton("确认恢复") { _, _ ->
                            runWork("正在还原已验证副本……", stage = "restore") {
                                EmergencyRestore.commit(transaction, roots(), { EmergencyAndroidRuntime.checkPaused(this) })
                                progress("聊天与助手的恢复事务已完成，原始包和恢复前副本保留。旧后台任务未恢复。仍处于救援保护，请点“退出救援并打开 Orbis”手动验收；若旧数据仍触发崩溃，不要清除备份。")
                            }
                        }.show()
                }
            }
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
                val message = "${emergencyRecoveryError(error)}\n\n操作未完成，不能据此卸载或清数据。原始备份与恢复前副本不会自动删除。"
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
        if (pendingRequest == EXPORT_REPORT) pendingReport?.let { outState.putString("pendingReport", it.text) }
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

    companion object { private const val EXPORT = 7401; private const val IMPORT = 7402; private const val EXPORT_REPORT = 7403 }
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
