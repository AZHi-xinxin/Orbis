package me.rerere.rikkahub.data.favorite

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisFavoriteSnapshotTest {
    private fun target(): NodeFavoriteTarget {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning("full reasoning\nline 2", metadata = JsonObject(mapOf("signature" to JsonPrimitive("not retained")))),
            UIMessagePart.Text("full body\nline 2"), UIMessagePart.Reasoning("second reasoning"),
            UIMessagePart.Image("file:///not-copied.jpg"), UIMessagePart.Tool("tool", "sample", "{\"private\":1}"),
        ))
        val node = message.toMessageNode()
        return NodeFavoriteTarget(Uuid.random(), "synthetic", node.id, node)
    }

    @Test fun `complete selected body and reasoning ordered snapshot excludes opaque metadata and assets`() {
        val t = target()
        val entity = NodeFavoriteAdapter.buildFavoriteEntity(t, null, 123L)
        val snapshot = NodeFavoriteAdapter.decodeSnapshot(entity)!!
        assertEquals(listOf("full reasoning\nline 2", "full body\nline 2", "second reasoning"), snapshot.parts.map { it.text })
        assertEquals(listOf(OrbisFavoritePartKind.REASONING, OrbisFavoritePartKind.BODY, OrbisFavoritePartKind.REASONING), snapshot.parts.map { it.kind })
        assertEquals(2, snapshot.unarchivedPartCount)
        assertFalse(entity.snapshotJson.contains("not-copied"))
        assertFalse(entity.snapshotJson.contains("not retained"))
        assertFalse(entity.snapshotJson.contains("private"))
    }

    @Test fun `snapshot remains independent from selected branch or missing source`() {
        val t = target()
        val entity = NodeFavoriteAdapter.buildFavoriteEntity(t, null, 1L)
        val changed = t.copy(node = t.node.copy(messages = listOf(UIMessage.assistant("new selected answer"))))
        assertNotEquals(changed.node.currentMessage.id, NodeFavoriteAdapter.decodeSnapshot(entity)!!.messageId)
        assertEquals(t.node.currentMessage.id, NodeFavoriteAdapter.decodeSnapshot(entity)!!.messageId)
    }

    @Test fun `existing key id creation time retained and persisted snapshot roundtrip supports DB backup`() {
        val t = target()
        val old = NodeFavoriteAdapter.buildFavoriteEntity(t, null, 1L)
        val updated = NodeFavoriteAdapter.buildFavoriteEntity(t, old, 2L)
        assertEquals(old.refKey, updated.refKey)
        assertEquals(old.id, updated.id)
        assertEquals(1L, updated.createdAt)
        assertEquals(2L, updated.updatedAt)
        val snapshot = NodeFavoriteAdapter.decodeSnapshot(updated)!!
        assertEquals(snapshot, JsonInstant.decodeFromString<OrbisFavoriteSnapshot>(JsonInstant.encodeToString(snapshot)))
    }

    @Test fun `legacy malformed mismatched and future snapshot never fabricate full contents`() {
        val entity = NodeFavoriteAdapter.buildFavoriteEntity(target(), null, 1L)
        assertNull(NodeFavoriteAdapter.decodeSnapshot(entity.copy(snapshotJson = "")))
        assertNull(NodeFavoriteAdapter.decodeSnapshot(entity.copy(snapshotJson = "{bad")))
        val snapshot = NodeFavoriteAdapter.decodeSnapshot(entity)!!
        assertNull(NodeFavoriteAdapter.decodeSnapshot(entity.copy(snapshotJson = JsonInstant.encodeToString(snapshot.copy(nodeId = Uuid.random())))))
        assertNull(NodeFavoriteAdapter.decodeSnapshot(entity.copy(snapshotJson = JsonInstant.encodeToString(snapshot.copy(version = 9)))))
    }
}
