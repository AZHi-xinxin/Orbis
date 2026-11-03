package me.rerere.rikkahub.data.orbis.boundary

import org.junit.Assert.*
import org.junit.Test

class BoundaryRequestNoticeTest {
    @Test fun unsubmittedHistoryFailureExplainsWhyWithoutPretendingTheAiReplied() {
        val notice = boundaryRequestFailureNotice(IllegalStateException("续聊仍有生成、工具或保存任务，请结束后再整理历史。"))
        assertTrue(notice.contains("申请未发送"))
        assertTrue(notice.contains("未消耗申请次数"))
        assertTrue(notice.contains("BR03"))
    }

    @Test fun unknownFailureDoesNotLeakProviderCredentialsOrAssertNotSubmitted() {
        val notice = boundaryRequestFailureNotice(IllegalStateException("https://private.invalid?key=secret upstream response"))
        assertFalse(notice.contains("secret"))
        assertFalse(notice.contains("private.invalid"))
        assertFalse(notice.contains("未消耗"))
        assertTrue(notice.contains("BR00"))
    }
}
