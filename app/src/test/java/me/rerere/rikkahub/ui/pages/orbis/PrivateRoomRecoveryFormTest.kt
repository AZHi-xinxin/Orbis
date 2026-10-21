package me.rerere.rikkahub.ui.pages.orbis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultException
import org.junit.Assert.*
import org.junit.Test

class PrivateRoomRecoveryFormTest {
    @Test fun passwordLengthIncludesBothBoundariesWithoutComplexityRules() {
        listOf("a".repeat(8), "中".repeat(128), "12345678").forEach {
            assertNull(privateRoomPasswordValidation(it, it))
        }
    }

    @Test fun invalidLengthReturnsFixedMessageWithoutEchoingInput() {
        listOf("", "a".repeat(7), "SYNTHETIC_PRIVATE_".repeat(10)).forEach {
            val notice = checkNotNull(privateRoomPasswordValidation(it, it))
            assertTrue(notice.contains("8–128"))
            assertFalse(notice.contains("SYNTHETIC_PRIVATE"))
        }
    }

    @Test fun chineseAndWhitespaceAreAcceptedWithoutTrimming() {
        val value = " 这是合成中文密码 "
        assertNull(privateRoomPasswordValidation(value, value))
        assertEquals("两次输入的恢复密码不一致，请核对后再保存。",
            privateRoomPasswordValidation(value, value.trim()))
    }

    @Test fun confirmationKeepsCaseAndUnicodeNormalizationSignificant() {
        assertNotNull(privateRoomPasswordValidation("SyntheticAbc", "syntheticabc"))
        assertNotNull(privateRoomPasswordValidation("abcdefghé", "abcdefghe\u0301"))
    }

    @Test fun humanPasswordMayUseLegacyPrefixWithoutChoosingLegacyMode() {
        val value = "ORPV1-synthetic-password"
        assertNull(privateRoomPasswordValidation(value, value))
        assertNotEquals(PrivateRoomRecoveryMethod.PASSWORD, PrivateRoomRecoveryMethod.RANDOM_CODE)
    }

    @Test fun temporaryPasswordCopyIsExactAndErasedAfterSuccess() = runBlocking {
        val source = " 合成 Password ".toCharArray()
        val result = privateRoomUsePassword(source) {
            assertArrayEquals(" 合成 Password ".toCharArray(), it)
            17
        }
        assertEquals(17, result)
        assertTrue(source.all { it == '\u0000' })
    }

    @Test fun temporaryPasswordCopyIsErasedAfterFailure() = runBlocking {
        val source = "synthetic password".toCharArray()
        try {
            privateRoomUsePassword<Unit>(source) { throw PrivateVaultException("authentication_failed") }
            fail("Failure must propagate")
        } catch (error: PrivateVaultException) {
            assertEquals("authentication_failed", error.reasonCode)
        }
        assertTrue(source.all { it == '\u0000' })
    }

    @Test fun temporaryPasswordCopyIsErasedAfterCancellation() = runBlocking {
        val source = "synthetic password".toCharArray()
        try {
            privateRoomUsePassword<Unit>(source) { throw CancellationException("synthetic cancellation") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(source.all { it == '\u0000' })
    }

    @Test fun failureIsVisibleBeforeSlowReadbackFinishes() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val notices = mutableListOf<String>()
        val job = launch {
            privateRoomReportFailure(PrivateVaultException("authentication_failed"), { true }, notices::add) {
                assertEquals(1, notices.size)
                started.complete(Unit)
                release.await()
                true
            }
        }
        started.await()
        assertTrue(notices.single().contains("解密校验未通过"))
        assertTrue(notices.single().contains("正在只读核对"))
        release.complete(Unit); job.join()
        assertTrue(notices.last().contains("已只读核对"))
    }

    @Test fun backgroundDuringReadbackCannotPublishFinalFailureIntoAnotherLifetime() = runBlocking {
        var foreground = true
        val notices = mutableListOf<String>()
        privateRoomReportFailure(PrivateVaultException("authentication_failed"), { foreground }, notices::add) {
            foreground = false
            false
        }
        assertEquals(1, notices.size)
        var readCalls = 0
        privateRoomReportFailure(PrivateVaultException("authentication_failed"), { false }, notices::add) {
            readCalls++; true
        }
        assertEquals(0, readCalls)
        assertEquals(1, notices.size)
    }

    @Test fun failedReadbackKeepsOriginalSafeReasonAndNeverEchoesSecrets() = runBlocking {
        val notices = mutableListOf<String>()
        privateRoomReportFailure(IllegalStateException("SYNTHETIC_PASSWORD", RuntimeException("PRIVATE_CAUSE")),
            { true }, notices::add) { false }
        assertEquals(2, notices.size)
        assertTrue(notices.last().contains("operation_unconfirmed"))
        assertTrue(notices.last().contains("暂时无法核对"))
        assertTrue(notices.none { it.contains("SYNTHETIC_PASSWORD") || it.contains("PRIVATE_CAUSE") })
    }

    @Test fun cancellingReadbackDoesNotEraseAlreadyPublishedFailure() = runBlocking {
        val notices = mutableListOf<String>()
        try {
            privateRoomReportFailure(PrivateVaultException("authentication_failed"), { true }, notices::add) {
                throw CancellationException("synthetic cancelled read")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, notices.size)
        assertTrue(notices.single().contains("解密校验未通过"))
    }
}
