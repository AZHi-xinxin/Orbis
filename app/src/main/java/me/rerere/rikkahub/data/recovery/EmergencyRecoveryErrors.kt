package me.rerere.rikkahub.data.recovery

/** Never surface exception payloads (they can contain chat text, keys, paths or malformed settings). */
internal fun emergencyRecoveryError(error: Throwable): String {
    return when (emergencyRecoveryCode(error)) {
        "E_MOUNT_RESTORE" -> "工作区临时读取权限尚未全部恢复，未确认本次导出完成。请保留原数据并留在救援保护中，重新执行导出会先尝试收回权限；仍失败请复制诊断信息联系维护者，不要卸载或清数据。"
        "E_MOUNT_ACCESS" -> "发现受限工作区目录，但暂时无法安全读取或确认其身份；没有跳过原文件，也没有宣称备份完整。请保留数据并联系维护者。"
        "E_INTERRUPTED" -> "操作已取消或中断，尚未确认导出或恢复完成。原数据与已有副本保留；若恢复中断，请先撤回。"
        "E_NEWER_VERSION" -> "这个包来自较新的 Orbis，请先安装相同或更新版本；不要清数据。"
        "E_PACKAGE_MISMATCH" -> "这不是当前 Orbis 安装版本所属应用的救援包（正式版和 Dev 分开）。"
        "E_STORAGE_MISMATCH" -> "备份来自不同的应用存储路径，当前自动恢复不能安全迁移附件；原始包已保留。"
        "E_CORE_CONTENT_MISSING" -> "原始包没有完整的主聊天库和助手设置，不能宣称已恢复；请保留原包供进一步救援。"
        "E_PATH_POLICY" -> "备份文件名或恢复位置未通过安全检查，操作已停止。没有跳过或删除这些文件，请复制诊断信息联系维护者。"
        "E_STORAGE_FULL" -> "本机或目标位置空间不足。请保留 Orbis 数据，腾出其他空间后重试。"
        "E_SOURCE_CHANGED_OR_BUSY" -> "尚不能确认原始文件保持不变或后台已完全停止，操作已停止。请保留原数据，不要卸载或清数据。"
        "E_INTEGRITY" -> "文件校验或数据库检查未通过，已停止自动处理；请保留原始包，不要卸载或清数据。"
        "E_FORMAT" -> "设置或备份格式暂时无法解析，已停止自动恢复；原始文件仍可保留供后续修复。"
        "E_ACCESS_DENIED" -> "无法读取原文件或写入所选位置，操作已停止。没有跳过这些文件，请保留原数据并复制诊断信息。"
        "E_SOURCE_UNAVAILABLE" -> "所需原文件或所选保存位置暂时无法访问，操作已停止。请保留已有副本并复制诊断信息。"
        "E_FILESYSTEM_UNSUPPORTED" -> "当前文件系统暂不支持安全备份所需的操作，已停止处理。没有降低安全检查，请保留原数据并复制诊断信息。"
        else -> "操作已停止，尚未确认备份或恢复完成。请保留原始数据，复制诊断信息联系维护者；不要卸载、清数据或反复重试。"
    }
}

/** Fixed categories only: no exception text, file names, provider address, class name or stack. */
internal fun emergencyRecoveryDiagnostic(error: Throwable, stage: String): String {
    val allowedStages = setOf(
        "prepare", "pause", "scan", "copy", "rescan", "rehash", "verify", "complete",
        "source_verify", "source_hash", "source_recheck", "save", "readback", "destination", "restore",
        "manifest", "final_scan", "sync", "extract",
        "report", "resume", "permission_restore", "unknown",
    )
    val safeStage = stage.takeIf { it in allowedStages } ?: "unknown"
    return "ORBIS-RESCUE/${emergencyRecoveryCode(error)} stage=$safeStage"
}

private fun emergencyRecoveryCode(error: Throwable): String {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val errors = mutableListOf<Throwable>()
    var next: Throwable? = error
    while (next != null && errors.size < 8 && seen.add(next)) {
        errors += next
        next = next.cause
    }
    val markers = errors.map { it.message.orEmpty().take(512).lowercase() }
    fun has(vararg known: String) = markers.any { text -> known.any { it in text } }
    return when {
        has("emergency_mount_restore_required") -> "E_MOUNT_RESTORE"
        errors.any { it is java.util.concurrent.CancellationException || it is java.io.InterruptedIOException } ||
            has("operation was interrupted") -> "E_INTERRUPTED"
        has("emergency_mount_") -> "E_MOUNT_ACCESS"
        has("unsafe archive path", "emergency_archive_path", "emergency_archive_target_platform") -> "E_PATH_POLICY"
        has("emergency_restore_newer_version") -> "E_NEWER_VERSION"
        has("emergency_restore_package_mismatch") -> "E_PACKAGE_MISMATCH"
        has("emergency_restore_storage_path_mismatch") -> "E_STORAGE_MISMATCH"
        has("required_content_missing") -> "E_CORE_CONTENT_MISSING"
        has("enospc", "no space left", "insufficient_space", "空间不足") -> "E_STORAGE_FULL"
        has("checksum", "mismatch", "corrupt", "integrity") || errors.any { it is java.util.zip.ZipException } -> "E_INTEGRITY"
        has("changed", "后台", "进程") -> "E_SOURCE_CHANGED_OR_BUSY"
        errors.any { it is java.nio.file.AccessDeniedException || it is SecurityException } -> "E_ACCESS_DENIED"
        errors.any { it is java.nio.file.NoSuchFileException || it is java.io.FileNotFoundException } -> "E_SOURCE_UNAVAILABLE"
        errors.any { it is UnsupportedOperationException } -> "E_FILESYSTEM_UNSUPPORTED"
        errors.any { it.javaClass.simpleName in setOf("SerializationException", "CorruptionException", "InvalidProtocolBufferException") } -> "E_FORMAT"
        else -> "E_UNCLASSIFIED"
    }
}
