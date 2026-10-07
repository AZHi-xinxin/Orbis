package me.rerere.rikkahub.ui.pages.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisCallQueueRecoveryPresentationTest {
    @Test fun recoveryCardSuppressesOnlyItsDuplicateQueueAndDroppedFrameNotices() {
        for (notice in listOf("聊天队列已暂停，通话原文仍保留；恢复队列后可重试。",
            "当前消息队列卡顿", "本次画面已跳过；语音通话继续。",
            "本次画面未获得回复，已跳过；语音通话继续。")) {
            assertNull(videoNoticeAlongsideQueueRecovery(notice, true))
            assertEquals(notice, videoNoticeAlongsideQueueRecovery(notice, false))
        }
    }

    @Test fun actualCameraAndStorageFailuresRemainVisibleAlongsideRecovery() {
        for (notice in listOf("未获得相机权限", "临时画面存储空间不足", "上一张画面仍在处理，请稍后再看。")) {
            assertEquals(notice, videoNoticeAlongsideQueueRecovery(notice, true))
        }
        assertNull(videoNoticeAlongsideQueueRecovery(null, true))
    }
}
