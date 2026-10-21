package me.rerere.rikkahub.data.orbis.integration

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AiWorkspacePreparationTest {
    private class Fixture {
        val owner = Assistant()
        val other = Assistant()
        var settings = Settings(assistants = listOf(owner, other))
        val rows = mutableMapOf<String, WorkspaceEntity>()
        var creates = 0
        var saves = true
        var beforeBind: () -> Unit = {}
        suspend fun prepare() = AiWorkspacePreparation.prepare(owner.id.toString(), { settings }, { rows[it] },
            { id, name -> creates++; WorkspaceEntity(id, name, id, createdAt = 1, updatedAt = 1).also { rows[id] = it } },
            { id -> check(rows.containsKey(id)); beforeBind() },
            { update -> if (saves) { settings = update(settings); true } else false })
    }

    @Test fun firstPreparationBindsOnlyOwnerAndRepeatedCallsReuseTheSameWorkspace() = runBlocking {
        val f = Fixture()
        val first = f.prepare()
        assertEquals("workspace_ready", first["status"]!!.jsonPrimitive.content)
        assertEquals(AiWorkspacePreparation.defaultId(f.owner.id.toString()), f.settings.assistants.first().workspaceId.toString())
        assertNull(f.settings.assistants[1].workspaceId)
        assertEquals(first, f.prepare())
        assertEquals(1, f.creates)
    }

    @Test fun failedSettingsWriteIsRetryableWithoutCreatingAnotherDirectory() = runBlocking {
        val f = Fixture()
        f.saves = false
        try { f.prepare(); fail() } catch (e: IllegalStateException) { assertEquals("save_failed", e.message) }
        assertNull(f.settings.assistants.first().workspaceId)
        f.saves = true
        f.prepare()
        assertEquals(1, f.creates)
    }

    @Test fun existingBindingIsReusedAndHumanRebindingDuringPreparationWins() = runBlocking {
        val f = Fixture()
        val id = Uuid.random()
        f.settings = f.settings.copy(assistants = listOf(f.owner.copy(workspaceId = id), f.other))
        f.rows[id.toString()] = WorkspaceEntity(id.toString(), "human workspace", id.toString(), createdAt = 1, updatedAt = 1)
        f.prepare()
        assertEquals(0, f.creates)
        val changed = Uuid.random()
        f.beforeBind = { f.settings = f.settings.copy(assistants = listOf(f.owner.copy(workspaceId = changed), f.other)) }
        try { f.prepare(); fail() } catch (e: IllegalStateException) { assertEquals("workspace_binding_changed", e.message) }
        assertEquals(changed, f.settings.assistants.first().workspaceId)
    }

    @Test fun missingOwnerMissingExistingWorkspaceAndAnotherOwnersDefaultAreNotOverwritten() = runBlocking {
        val f = Fixture()
        f.settings = f.settings.copy(assistants = emptyList())
        try { f.prepare(); fail() } catch (e: IllegalStateException) { assertEquals("assistant_unavailable", e.message) }
        assertEquals(0, f.creates)
        f.settings = Settings(assistants = listOf(f.owner.copy(workspaceId = Uuid.random())))
        try { f.prepare(); fail() } catch (e: IllegalStateException) { assertEquals("workspace_missing", e.message) }
        assertEquals(0, f.creates)
        f.settings = Settings(assistants = listOf(f.owner, f.other.copy(
            workspaceId = Uuid.parse(AiWorkspacePreparation.defaultId(f.owner.id.toString())))))
        try { f.prepare(); fail() } catch (e: IllegalStateException) { assertEquals("workspace_in_use", e.message) }
        assertEquals(0, f.creates)
    }
}
