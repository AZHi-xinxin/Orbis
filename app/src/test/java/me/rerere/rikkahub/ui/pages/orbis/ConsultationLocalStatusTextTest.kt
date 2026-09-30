package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.data.orbis.consultation.ConsultationLocalState
import org.junit.Assert.*
import org.junit.Test

class ConsultationLocalStatusTextTest {
    @Test fun everyKnownStateHasAnExplicitDescription() {
        for (state in ConsultationLocalState.entries) assertTrue(consultationLocalStatusDescription(state).isNotBlank())
        assertTrue(consultationLocalStatusDescription(ConsultationLocalState.RUNNING).contains("不等于"))
        assertTrue(consultationLocalStatusDescription(ConsultationLocalState.SUBMISSION_EXHAUSTED).contains("不能当作生成失败"))
    }

    @Test fun unknownFailureTextNeverEchoesArbitraryStoredContent() {
        val secret = "synthetic-private-body-token-tool-arguments"
        assertFalse(consultationLocalFailureDescription(secret).contains(secret))
        assertEquals(consultationLocalFailureDescription(secret), consultationLocalFailureDescription("future_reason"))
    }

    @Test fun emptyTextAndOversizedTextAreDifferentNonRetryReasons() {
        val empty = consultationLocalFailureDescription("empty_final_text_no_automatic_retry")
        val oversized = consultationLocalFailureDescription("oversized_final_text_no_automatic_retry")
        assertTrue(empty.contains("正文为空"))
        assertTrue(oversized.contains("16 KiB"))
        assertNotEquals(empty, oversized)
    }

    @Test fun existingFailureCodesKeepSpecificSafeDescriptions() {
        val expected = mapOf(
            "process_interrupted_evidence_recovered_no_automatic_retry" to "已恢复保存",
            "generation_cancelled_no_automatic_retry" to "已取消",
            "provider_request_failed_no_automatic_retry" to "模型服务",
            "network_interrupted_no_automatic_retry" to "连接中断",
            "generation_deadline_expired_no_automatic_retry" to "超过时限",
            "empty_or_oversized_final_no_automatic_retry" to "无法从该记录区分",
            "binding_changed_no_automatic_retry" to "绑定已变化",
            "gateway_busy_no_generation_no_automatic_retry" to "本次未开始生成",
            "execution_incomplete_no_automatic_retry" to "没有保存更具体",
            "session_ended_archive_without_active_replay" to "不重放",
            "terminal_response_unverified_no_automatic_retry" to "完成凭据",
        )
        expected.forEach { (code, text) -> assertTrue(code, consultationLocalFailureDescription(code).contains(text)) }
    }
}
