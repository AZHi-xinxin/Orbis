package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultException
import kotlinx.coroutines.CancellationException

internal enum class PrivateRoomScreen { HOME, OWNERS, NEW_OWNER, CREATE, RECOVERY_CODE, COMPLETE, SETTINGS, REQUEST }

internal fun privateRoomOwnerDestination(source: PrivateRoomScreen): PrivateRoomScreen =
    if (source == PrivateRoomScreen.NEW_OWNER) PrivateRoomScreen.CREATE else PrivateRoomScreen.SETTINGS

internal fun privateRoomBusyLabel(screen: PrivateRoomScreen): String = when (screen) {
    PrivateRoomScreen.CREATE -> "正在处理本机空间步骤，请稍候；没有调用模型。"
    PrivateRoomScreen.RECOVERY_CODE -> "正在处理恢复码步骤，请稍候。"
    else -> "正在处理本次操作，请稍候；请勿重复点击。"
}

/** Main-thread ticket gate: only the newest read in the same foreground lifetime may publish. */
internal class PrivateRoomRefreshGate(initiallyForeground: Boolean) {
    private var foreground = initiallyForeground
    private var revision = 0L

    fun invalidate(isForeground: Boolean) { foreground = isForeground; revision++ }
    fun begin(): Long? = if (foreground) ++revision else null
    fun complete(ticket: Long): Boolean {
        if (!foreground || ticket != revision) return false
        revision++
        return true
    }
}

/** A failed or cancelled read never establishes ABSENT, and an old failure cannot erase a newer result. */
internal suspend fun <T> privateRoomRefreshLatest(
    gate: PrivateRoomRefreshGate,
    read: suspend () -> T,
    publish: (T) -> Unit,
    failed: () -> Unit,
): Boolean {
    val ticket = gate.begin() ?: return false
    val result = try { read() }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) {
        if (gate.complete(ticket)) failed()
        return false
    }
    if (!gate.complete(ticket)) return false
    publish(result)
    return true
}

/** A fixed allowlist, never an exception message, file path, key or server response. */
internal fun privateRoomFailureNotice(error: Throwable): String {
    val code = (error as? PrivateVaultException)?.reasonCode
    val explanation = when (code) {
        "device_key_unavailable" -> "本机安全密钥暂不可用。"
        "storage_failed" -> "本机加密存储操作失败。"
        "unsafe_path" -> "存储路径未通过安全检查。"
        "already_exists" -> "这位主人已有空间，没有重复创建或覆盖。"
        "recovery_required" -> "原空间需要恢复码重新绑定本机。"
        "recovery_not_confirmed" -> "尚未确认离线保管恢复码，不能开启。"
        "authentication_failed" -> "解密校验未通过。"
        "invalid_format" -> "加密文件格式未通过检查。"
        "size_limit" -> "本次内容超过本机空间的大小限制。"
        else -> null
    }
    val safeCode = if (explanation == null) "operation_unconfirmed" else code
    return "操作未完成（$safeCode）。${explanation ?: "尚不能确认结果。"}未自动重试，原件保留。"
}

/** Local navigation only: never write Settings.assistantId or transfer a vault owner. */
internal data class PrivateRoomOwner(val id: String, val name: String)

internal fun privateRoomOwners(assistants: List<Assistant>, initialId: String, initialName: String): List<PrivateRoomOwner> {
    val owners = assistants.map { PrivateRoomOwner(it.id.toString(), it.name.ifBlank { "AI" }) }
    return (owners + PrivateRoomOwner(initialId, initialName.ifBlank { "AI" })).distinctBy { it.id }
}

/** A fixture supplied for one owner must never open another owner's room. */
internal fun privateRoomUsesInitialRepository(selectedId: String, initialId: String): Boolean = selectedId == initialId
