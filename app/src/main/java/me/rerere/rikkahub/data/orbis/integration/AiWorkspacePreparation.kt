package me.rerere.rikkahub.data.orbis.integration

import java.util.UUID
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity

/** A stable default permits retries after a failed settings write without making more directories. */
internal object AiWorkspacePreparation {
    private val gate = Mutex()

    fun defaultId(owner: String): String {
        Uuid.parse(owner)
        return UUID.nameUUIDFromBytes("orbis-ai-workspace:$owner".toByteArray(Charsets.UTF_8)).toString()
    }

    suspend fun prepare(
        owner: String,
        snapshot: () -> Settings,
        find: suspend (String) -> WorkspaceEntity?,
        create: suspend (String, String) -> WorkspaceEntity,
        ensureNative: suspend (String) -> Unit,
        update: suspend ((Settings) -> Settings) -> Boolean,
    ): JsonObject = gate.withLock {
        val initial = snapshot()
        val assistant = AiConfigurationRules.requireOwner(initial, owner)
        val previous = assistant.workspaceId
        val id = previous?.toString() ?: defaultId(owner)
        if (previous == null) check(initial.assistants.none { it.id.toString() != owner && it.workspaceId?.toString() == id }) {
            "workspace_in_use"
        }
        val existing = find(id)
        check(previous == null || existing != null) { "workspace_missing" }
        val workspace = existing ?: create(id, "Orbis 工作区 · ${id.take(8)}")
        check(workspace.id == id && (previous != null || workspace.root == id)) { "workspace_binding_changed" }
        ensureNative(id)
        check(update { latest ->
            val live = AiConfigurationRules.requireOwner(latest, owner)
            check(live.workspaceId == previous) { "workspace_binding_changed" }
            if (previous == null) check(latest.assistants.none {
                it.id.toString() != owner && it.workspaceId?.toString() == id
            }) { "workspace_in_use" }
            latest.copy(assistants = latest.assistants.map {
                if (it.id == live.id) it.copy(workspaceId = Uuid.parse(id)) else it
            })
        }) { "save_failed" }
        check(AiConfigurationRules.requireOwner(snapshot(), owner).workspaceId?.toString() == id) { "workspace_binding_changed" }
        buildJsonObject {
            put("ok", true); put("status", "workspace_ready"); put("workspace_id", id)
            put("name", workspace.name); put("path", "/workspace/")
            put("linux_install_requested", false); put("previous_files_restored", false)
        }
    }
}
