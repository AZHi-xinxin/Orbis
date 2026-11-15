package me.rerere.rikkahub.data.orbis.agentwork

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PhonePageProjectionTest {
    private fun node(index: Int, text: String = "") = PhoneElement(index, listOf(0, index), text, "",
        "view_$index", "0 0 50 50", false, false, false, true, false, false)
    private fun page(elements: List<PhoneElement>, truncated: Boolean = false) =
        PhonePage("ticket", "com.example.chat", 1000, elements, truncated)

    @Test fun wrappersCannotDisplaceLateComposerAndMessageBodies() {
        val nodes = (0 until 500).map { node(it) } + node(500, "消息正文") +
            node(501).copy(editable = true) + node(502, "发送").copy(clickable = true)
        val projected = PhonePageProjection.project(page(nodes))
        assertEquals(listOf("消息正文", "", "发送"), projected.elements.map { it.text })
        assertEquals(listOf(0, 1, 2), projected.elements.map { it.id })
        assertEquals(listOf(0, 501), projected.elements.single { it.editable }.path)
        assertFalse(projected.truncated)
    }

    @Test fun richHistoryKeepsLateInputSendAndScrollWithinUtf8Budget() {
        val nodes = (0 until 200).map { node(it, "带引号\"与换行\n的消息🌙".repeat(20)) } +
            node(200).copy(scrollable = true) + node(201).copy(editable = true) +
            node(202, "发送").copy(clickable = true)
        val projected = PhonePageProjection.project(page(nodes))
        assertTrue(projected.truncated)
        assertTrue(projected.elements.any { it.editable })
        assertTrue(projected.elements.any { it.clickable && it.text == "发送" })
        assertTrue(projected.elements.any { it.scrollable })
        assertTrue(projected.elements.any { it.text.startsWith("带引号") })
        assertTrue(Json.encodeToString(projected).toByteArray().size <= PhonePageProjection.MAX_BYTES)
        assertEquals(projected.elements.indices.toList(), projected.elements.map { it.id })
    }

    @Test fun omittedContentStillInvalidatesLease() {
        val nodes = (0 until 200).map { node(it, "长消息".repeat(160)) } + node(200).copy(editable = true)
        val before = PhonePageProjection.project(page(nodes))
        val hidden = nodes.first { n -> before.elements.none { it.path == n.path } }
        val after = PhonePageProjection.project(page(nodes.map { if(it == hidden) it.copy(text = "已修改".repeat(160)) else it }))
        assertEquals(before.elements, after.elements)
        assertNotEquals(before.snapshotHash, after.snapshotHash)
        assertThrows(IllegalStateException::class.java) {
            PhoneControlPolicy.validate(before, before.token, after, setOf(before.packageName), 1100)
        }
    }

    @Test fun descriptionsCheckedStateAndBlankActionControlsSurvive() {
        val nodes = listOf(node(0).copy(description = "群名称"), node(1).copy(checked = true),
            node(2).copy(selected = true), node(3).copy(clickable = true, enabled = false), node(4))
        val projected = PhonePageProjection.project(page(nodes, truncated = true))
        assertEquals(4, projected.elements.size)
        assertTrue(projected.truncated)
        assertFalse(projected.elements.last().enabled)
    }

    @Test fun overBudgetControlsRemainBoundedAndExplicitlyPartial() {
        val nodes = (0 until 300).map { node(it, "按钮".repeat(150)).copy(clickable = true) } +
            node(300).copy(editable = true)
        val projected = PhonePageProjection.project(page(nodes))
        assertTrue(projected.truncated)
        assertTrue(projected.elements.any { it.editable })
        assertTrue(Json.encodeToString(projected).toByteArray().size <= PhonePageProjection.MAX_BYTES)
        assertEquals(projected, PhonePageProjection.project(page(nodes)))
    }
}
