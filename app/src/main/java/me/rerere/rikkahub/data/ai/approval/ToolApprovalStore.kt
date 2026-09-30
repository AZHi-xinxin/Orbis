package me.rerere.rikkahub.data.ai.approval

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.HostToolApproval
import java.io.File
import java.util.UUID

@Serializable
data class RememberedToolGrant(val assistantId: String, val approval: HostToolApproval)

@Serializable
private data class ToolApprovalFile(
    val version: Int = 2,
    val installationScope: String,
    val grants: List<RememberedToolGrant>,
    val alwaysAllowAssistantIds: Set<String> = emptySet(),
)

/** Device-local, excluded from Android backup and Orbis exports; no credential values. */
class ToolApprovalStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val state = MutableStateFlow<List<RememberedToolGrant>>(emptyList())
    val grants = state.asStateFlow()
    private val alwaysAllowed = MutableStateFlow<Set<String>>(emptySet())
    val allowAllAssistants = alwaysAllowed.asStateFlow()
    private var healthy = true
    var installationScope: String = UUID.randomUUID().toString()
        private set

    init {
        try {
            val stored = read()?.let { json.decodeFromString<ToolApprovalFile>(it) }
            if (stored == null) commit(emptyList())
            else {
                require(stored.version in 1..2 && stored.installationScope.isNotBlank())
                installationScope = stored.installationScope
                state.value = stored.grants
                // v1 did not support the switch: an update never turns it on implicitly.
                alwaysAllowed.value = if (stored.version >= 2) stored.alwaysAllowAssistantIds.filter { it.isNotBlank() }.toSet() else emptySet()
            }
        }
        catch (_: Exception) { healthy = false }
    }

    /** Execution approval only: callers must still enforce enabled tools, target identity and system permissions. */
    @Synchronized
    fun allowsAll(assistantId: String): Boolean = healthy && assistantId in alwaysAllowed.value

    @Synchronized
    fun setAllowAll(assistantId: String, enabled: Boolean) {
        require(assistantId.isNotBlank()) { "当前 AI 标识为空，未修改授权。" }
        if (enabled) {
            check(healthy) { "工具授权文件无法读取，请先撤销全部授权后重试。" }
            commit(state.value, alwaysAllowed.value + assistantId)
        } else {
            val remaining = alwaysAllowed.value - assistantId
            alwaysAllowed.value = remaining
            try { commit(state.value, remaining); healthy = true }
            catch (error: Exception) { healthy = false; throw error }
        }
    }

    @Synchronized
    fun permits(assistantId: String, approval: HostToolApproval): Boolean = healthy &&
        approval.rememberable && state.value.any {
            it.assistantId == assistantId && it.approval.stableId == approval.stableId &&
                it.approval.revision == approval.revision
        }

    @Synchronized
    fun allow(assistantId: String, approval: HostToolApproval) {
        check(healthy) { "工具授权文件无法读取，请先撤销全部授权后重试。" }
        require(approval.rememberable) { "请用当前工具定义保存授权。" }
        commit(state.value.filterNot {
            it.assistantId == assistantId && it.approval.stableId == approval.stableId
        } + RememberedToolGrant(assistantId, approval))
    }

    @Synchronized
    fun revoke(assistantId: String, stableId: String? = null) {
        // Revoke in memory first: a failed disk write must not leave a live authorization.
        val remaining = state.value.filterNot {
            it.assistantId == assistantId && (stableId == null || it.approval.stableId == stableId)
        }
        state.value = remaining
        val allRemaining = if (stableId == null) alwaysAllowed.value - assistantId else alwaysAllowed.value
        alwaysAllowed.value = allRemaining
        try { commit(remaining, allRemaining); healthy = true }
        catch (error: Exception) { healthy = false; throw error }
    }

    private fun commit(next: List<RememberedToolGrant>, allNext: Set<String> = alwaysAllowed.value) {
        write(json.encodeToString(ToolApprovalFile(installationScope = installationScope, grants = next,
            alwaysAllowAssistantIds = allNext)))
        state.value = next
        alwaysAllowed.value = allNext
    }

    companion object {
        @Volatile private var instance: ToolApprovalStore? = null
        fun get(context: Context): ToolApprovalStore = instance ?: synchronized(this) {
            instance ?: run {
                val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "orbis-tool-approvals-v1.json"))
                ToolApprovalStore(
                    read = { if (file.baseFile.exists()) file.openRead().bufferedReader().use { it.readText() } else null },
                    write = { content ->
                        val stream = file.startWrite()
                        try { stream.write(content.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
                        catch (error: Exception) { file.failWrite(stream); throw error }
                    },
                ).also { instance = it }
            }
        }
    }
}
