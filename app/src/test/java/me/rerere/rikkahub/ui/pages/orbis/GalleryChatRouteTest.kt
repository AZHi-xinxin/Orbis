package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.orbis.gallery.GalleryAnswerRef
import me.rerere.rikkahub.ui.pages.chat.decodeChatRouteDraft
import me.rerere.rikkahub.utils.base64Encode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GalleryChatRouteTest {
    private val chatId = "71b83d76-9448-434d-b7d8-0f1fe155a0e0"

    @Test fun savedQuestionnaireReferenceSurvivesChatNavigationAndRouteRestore() {
        val ref = GalleryAnswerRef("answer-123", "form-456", 2, "human", 1792832400000L)
        val draft = galleryAnswerChatText("晚安问卷🌙\n第二阶段 / + =", ref)
        val route = galleryChatRoute(chatId, draft)
        val restored = Json.decodeFromString<Screen.Chat>(Json.encodeToString(route))
        assertEquals(chatId, restored.id)
        assertEquals(draft, decodeChatRouteDraft(restored.text))
        assertTrue(restored.files.isEmpty())
        assertTrue(decodeChatRouteDraft(restored.text)!!.contains("answer_id=answer-123"))
    }

    @Test fun artworkReturnKeepsUnicodeNewlinesAndLiteralBase64LookingText() {
        listOf("格子作品：测试", "第一行\n第二行\r\n🙂", "YWJj", "a/+b=c?", "").forEach { draft ->
            assertEquals(draft, decodeChatRouteDraft(galleryChatRoute(chatId, draft).text))
        }
    }

    @Test fun existingSystemShareEncodingStillWorksWithoutDoubleDecoding() {
        val draft = "系统分享\nYWJj / https://example.invalid/?q=你好"
        assertEquals(draft, decodeChatRouteDraft(draft.base64Encode()))
    }

    @Test fun brokenAndOldUnencodedRoutesAreRejectedWithoutThrowing() {
        listOf("格子问卷《测试》已有提交。", "?not-base64", "Y===", "a").forEach {
            assertNull(decodeChatRouteDraft(it))
        }
        assertNull(decodeChatRouteDraft(null))
    }
}
