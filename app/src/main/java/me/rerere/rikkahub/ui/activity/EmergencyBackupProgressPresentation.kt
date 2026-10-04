package me.rerere.rikkahub.ui.activity

import java.util.Locale

/** Presentation only: accepts counters, never a source path, exception payload or file content. */
internal data class EmergencyBackupProgressPresentation(
    val stage: String,
    val title: String,
    val detail: String,
    /** This stage's byte percentage, never an overall completion promise. Null means unknown. */
    val percent: Int?,
)

private val emergencyStageTitles = mapOf(
    "prepare" to "正在准备，请稍候",
    "scan" to "正在扫描待备份内容",
    "copy" to "正在复制原始文件",
    "manifest" to "正在写入备份文件清单",
    "rescan" to "正在复查原始目录",
    "rehash" to "正在重新核对原始文件",
    "final_scan" to "正在做最后一次目录检查",
    "verify" to "正在验证应用内备份",
    "sync" to "正在确认应用内备份落盘",
    "source_verify" to "正在验证待导出的备份",
    "source_hash" to "正在计算待导出备份校验值",
    "source_recheck" to "正在复核备份包",
    "save" to "正在保存到你选择的位置",
    "readback" to "正在读回并校验外部备份",
    "complete" to "本阶段完成，正在确认结果",
)

internal fun emergencyBackupProgressStage(phase: String): String =
    phase.takeIf { it in emergencyStageTitles } ?: "prepare"

internal fun emergencyBackupProgressPresentation(
    phase: String,
    completedBytes: Long,
    totalBytes: Long,
    completedEntries: Long = 0,
    totalEntries: Long = 0,
): EmergencyBackupProgressPresentation {
    val stage = emergencyBackupProgressStage(phase)
    val title = emergencyStageTitles.getValue(stage)
    val scanning = stage in setOf("scan", "rescan", "final_scan")
    val known = !scanning && stage !in setOf("prepare", "complete", "manifest", "sync") && totalBytes > 0 &&
        completedBytes in 0..totalBytes
    val percent = if (known) ((completedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100) else null
    fun bytes(value: Long): String = if (value < 1024 * 1024) "${value / 1024} KB"
        else String.format(Locale.ROOT, "%.1f MB", value / (1024.0 * 1024.0))
    val detail = when {
        known -> "本阶段 ${percent}% · ${bytes(completedBytes)} / ${bytes(totalBytes)}"
        scanning -> "已扫描 ${completedEntries.coerceAtLeast(0)} 项；正在统计目录，请勿卸载或清除数据。"
        stage == "complete" -> "还需确认保存位置与读回结果；这不代表外部备份已经完成。"
        totalEntries > 0 && completedEntries in 0..totalEntries -> "本阶段已处理 $completedEntries / $totalEntries 项；字节进度尚未确定。"
        completedBytes > 0 -> "本阶段已处理 ${bytes(completedBytes)}；总量尚未确定。"
        else -> "正在处理；总量尚未确定。"
    }
    return EmergencyBackupProgressPresentation(stage, title, detail, percent)
}

/** Independent clocks: a UI refresh never causes another expensive process inspection. */
internal class EmergencyBackupProgressThrottle(
    private val displayIntervalMs: Long = 400,
    private val safetyIntervalMs: Long = 2_000,
) {
    private var displayPhase: String? = null
    private var displayAt = 0L
    private var checkedPhase: String? = null
    private var checkedAt = 0L

    fun shouldDisplay(phase: String, now: Long): Boolean {
        if (displayPhase == phase && phase != "complete" && now >= displayAt && now - displayAt < displayIntervalMs) return false
        displayPhase = phase
        displayAt = now
        return true
    }

    fun needsSafetyCheck(phase: String, now: Long): Boolean =
        checkedPhase != phase || phase == "complete" || now < checkedAt || now - checkedAt >= safetyIntervalMs

    /** Called after the potentially slow safety check finishes, not when it started. */
    fun safetyCheckFinished(phase: String, finishedAt: Long) {
        checkedPhase = phase
        checkedAt = finishedAt
    }
}

internal const val EMERGENCY_PICKER_WAITING_MESSAGE =
    "正在等待系统文件选择；本次尚未确认保存或恢复完成。取消选择不会删除原数据或已生成的应用内副本。"

/** Saved state holds only a whitelisted diagnostic and a busy bit, never display/exception text. */
internal data class EmergencyBackupRebuiltPresentation(
    val message: String,
    val failed: Boolean,
    val diagnostic: String? = null,
)

private val emergencySavedFailureReasons = mapOf(
    "E_MOUNT_RESTORE" to "工作区临时读取权限尚未全部恢复。请留在救援保护中并联系维护者。",
    "E_MOUNT_ACCESS" to "受限工作区目录暂时无法安全读取或确认身份，没有跳过原文件。",
    "E_INTERRUPTED" to "操作已取消或中断；若恢复中断，请先检查是否需要撤回。",
    "E_NEWER_VERSION" to "备份来自较新版本，请先使用相同或更新的 Orbis。",
    "E_PACKAGE_MISMATCH" to "备份所属应用与当前安装不匹配，正式版与 Dev 分开。",
    "E_STORAGE_MISMATCH" to "原附件存储路径无法安全自动迁移，原始包仍保留。",
    "E_CORE_CONTENT_MISSING" to "没有确认完整的主聊天库与助手设置，不能视为已恢复。",
    "E_PATH_POLICY" to "备份文件名或恢复位置未通过安全检查，没有跳过或删除文件。",
    "E_STORAGE_FULL" to "本机或所选位置空间不足，请保留 Orbis 数据。",
    "E_SOURCE_CHANGED_OR_BUSY" to "无法确认原文件保持不变或后台完全停止。",
    "E_INTEGRITY" to "文件校验或数据库检查未通过，请保留原始包。",
    "E_FORMAT" to "设置或备份格式暂时无法解析，请保留原文件。",
    "E_ACCESS_DENIED" to "无法读取原文件或写入所选位置，没有跳过原文件。",
    "E_SOURCE_UNAVAILABLE" to "原文件或所选保存位置暂时无法访问。",
    "E_FILESYSTEM_UNSUPPORTED" to "文件系统不支持所需的安全操作，没有降低安全检查。",
    "E_UNCLASSIFIED" to "尚未确认备份或恢复结果，请保留数据并联系维护者。",
    "E_PICKER_UNAVAILABLE" to "无法打开系统文件选择器，请确认文件管理器可用。",
    "E_DESTINATION_REJECTED" to "请选择系统文件管理器中的本机普通目录，不能使用应用私有目录。",
)

internal fun emergencyBackupSavedDiagnostic(value: String?): String? {
    // Earlier UI-specific fixed codes normalize to the same saved format.
    val candidate = when (value) {
        "ORBIS_RECOVERY_PICKER_UNAVAILABLE · picker" -> "ORBIS-RESCUE/E_PICKER_UNAVAILABLE stage=destination"
        "ORBIS_RECOVERY_DESTINATION_REJECTED · picker" -> "ORBIS-RESCUE/E_DESTINATION_REJECTED stage=destination"
        else -> value
    } ?: return null
    if (candidate.length > 120) return null
    val parts = Regex("ORBIS-RESCUE/(E_[A-Z_]+) stage=([a-z_]+)").matchEntire(candidate) ?: return null
    val (code, stage) = parts.destructured
    val stages = emergencyStageTitles.keys + setOf("pause", "destination", "restore", "extract", "report", "resume", "permission_restore", "unknown")
    return candidate.takeIf { code in emergencySavedFailureReasons && stage in stages }
}

internal fun emergencyBackupRebuiltPresentation(
    wasBusy: Boolean,
    waitingForPicker: Boolean,
    savedDiagnostic: String?,
): EmergencyBackupRebuiltPresentation {
    if (wasBusy) return EmergencyBackupRebuiltPresentation(
        "页面已重建，无法在此确认上一操作结果；原数据保留，备份不能据此视为完成。不会自动重新开始或重试。请先检查已有副本；如系统仍在选择文件，请完成或取消选择。",
        failed = true,
    )
    if (waitingForPicker) return EmergencyBackupRebuiltPresentation(EMERGENCY_PICKER_WAITING_MESSAGE, failed = false)
    val diagnostic = emergencyBackupSavedDiagnostic(savedDiagnostic)
    if (diagnostic != null) {
        val code = diagnostic.substringAfter("ORBIS-RESCUE/").substringBefore(" stage=")
        return EmergencyBackupRebuiltPresentation(
            "${emergencySavedFailureReasons.getValue(code)}\n\n上次操作未完成，安全错误码已保留。不要卸载或清除数据；不会自动重试。",
            failed = true, diagnostic = diagnostic,
        )
    }
    return EmergencyBackupRebuiltPresentation(
        "页面已重新打开，请选择操作。本页没有恢复上次操作的成功状态；请在文件管理器核对应用外备份，不要先卸载或清除数据。",
        failed = false,
    )
}
