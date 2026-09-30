package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class HistoryTranslationEditTest {
    private fun fixture(): Conversation = Conversation(
        assistantId = Uuid.parse("00000000-0000-4000-8000-000000000046"),
        title = "Synthetic preserved title",
        messageNodes = listOf(
            MessageNode.of(UIMessage.user("synthetic first message")),
            MessageNode(
                messages = listOf(
                    UIMessage.assistant("synthetic alternative"),
                    UIMessage.assistant("synthetic text to translate").copy(translation = "old translation"),
                ),
                selectIndex = 1,
                isFavorite = true,
            ),
            MessageNode.of(UIMessage.user("synthetic later message")),
        ),
    )

    private fun target(conversation: Conversation) = conversation.messageNodes[1].messages[1]

    @Test fun `translation changes only target field and preserves branches selection favorites and other nodes`() {
        val original = fixture()
        val expected = target(original)
        val changed = original.withTranslationIfUnchanged(expected, expected.translation, "new translation")
        assertEquals(original.copy(messageNodes = original.messageNodes.toMutableList().also { nodes ->
            val node = nodes[1]
            nodes[1] = node.copy(messages = node.messages.toMutableList().also {
                it[1] = expected.copy(translation = "new translation")
            })
        }), changed)
        assertSame(original.messageNodes[0], changed.messageNodes[0])
        assertSame(original.messageNodes[2], changed.messageNodes[2])
        assertSame(original.messageNodes[1].messages[0], changed.messageNodes[1].messages[0])
        assertTrue(changed.messageNodes[1].isFavorite)
        assertEquals(1, changed.messageNodes[1].selectIndex)
        assertEquals("old translation", target(original).translation)
    }

    @Test fun `no op translation retains original state identity`() {
        val original = fixture()
        val expected = target(original)
        assertSame(original, original.withTranslationIfUnchanged(expected, expected.translation, expected.translation))
    }

    @Test fun `successive streamed translations compare against their own most recent publication`() {
        val original = fixture()
        val expected = target(original)
        val loading = original.withTranslationIfUnchanged(expected, "old translation", "loading")
        val partial = loading.withTranslationIfUnchanged(expected, "loading", "first part")
        val finished = partial.withTranslationIfUnchanged(expected, "first part", "complete translation")
        assertEquals("complete translation", target(finished).translation)
        assertSame(finished, finished.withTranslationIfUnchanged(expected, "complete translation", "complete translation"))
    }

    @Test fun `delayed clear cannot erase a newer completed translation`() {
        val original = fixture()
        val oldTap = target(original)
        val newer = original.withTranslationIfUnchanged(oldTap, oldTap.translation, "new translation")
        assertThrows(IllegalStateException::class.java) {
            newer.withTranslationIfUnchanged(oldTap, oldTap.translation, null)
        }
        assertEquals("new translation", target(newer).translation)
    }

    @Test fun `failed old stream cleanup cannot erase another stream publication`() {
        val original = fixture()
        val expected = target(original)
        val loading = original.withTranslationIfUnchanged(expected, expected.translation, "loading")
        val partial = loading.withTranslationIfUnchanged(expected, "loading", "my partial")
        val newer = partial.withTranslationIfUnchanged(expected, "my partial", "another completed translation")
        assertThrows(IllegalStateException::class.java) {
            newer.withTranslationIfUnchanged(expected, "my partial", expected.translation)
        }
        assertEquals("another completed translation", target(newer).translation)
    }

    @Test fun `failed stream may restore its own previous translation only while it still owns field`() {
        val original = fixture()
        val expected = target(original)
        val partial = original.withTranslationIfUnchanged(expected, expected.translation, "my partial")
        val restored = partial.withTranslationIfUnchanged(expected, "my partial", expected.translation)
        assertEquals(original, restored)
    }

    @Test fun `changed source text or role rejects late stream even when id and old translation match`() {
        val original = fixture()
        val expected = target(original)
        listOf(
            expected.copy(parts = listOf(UIMessagePart.Text("edited synthetic text"))),
            expected.copy(role = MessageRole.USER),
        ).forEach { changedTarget ->
            val changed = original.copy(messageNodes = original.messageNodes.toMutableList().also { nodes ->
                nodes[1] = nodes[1].copy(messages = nodes[1].messages.toMutableList().also { it[1] = changedTarget })
            })
            assertThrows(IllegalStateException::class.java) {
                changed.withTranslationIfUnchanged(expected, expected.translation, "late result")
            }
            assertSame(changedTarget, target(changed))
        }
    }

    @Test fun `missing or duplicate message identity fails without inventing a replacement node`() {
        val original = fixture()
        val expected = target(original)
        listOf(
            original.copy(messageNodes = original.messageNodes.filterIndexed { index, _ -> index != 1 }),
            original.copy(messageNodes = original.messageNodes + MessageNode.of(expected)),
        ).forEach { invalid ->
            assertThrows(IllegalStateException::class.java) {
                invalid.withTranslationIfUnchanged(expected, expected.translation, "late result")
            }
        }
    }

    @Test fun `unrelated newer message metadata is preserved not replaced from translation snapshot`() {
        val original = fixture()
        val expected = target(original)
        val newer = expected.copy(
            annotations = listOf(UIMessageAnnotation.UrlCitation("synthetic", "https://example.invalid")),
            orbisEvent = OrbisEventMetadata("synthetic", "source", "event", 123),
            orbisVoiceCallId = "synthetic-call",
        )
        val current = original.copy(title = "newer title", messageNodes = original.messageNodes.toMutableList().also { nodes ->
            nodes[1] = nodes[1].copy(messages = nodes[1].messages.toMutableList().also { it[1] = newer })
        })
        val changed = current.withTranslationIfUnchanged(expected, expected.translation, "translated")
        assertEquals(newer.copy(translation = "translated"), target(changed))
        assertEquals("newer title", changed.title)
    }

    @Test fun `empty and null translation are distinct expected values`() {
        val original = fixture()
        val expected = target(original)
        val cleared = original.withTranslationIfUnchanged(expected, expected.translation, null)
        assertThrows(IllegalStateException::class.java) {
            cleared.withTranslationIfUnchanged(expected, "", "late result")
        }
        assertEquals("accepted", target(cleared.withTranslationIfUnchanged(expected, null, "accepted")).translation)
    }
}
