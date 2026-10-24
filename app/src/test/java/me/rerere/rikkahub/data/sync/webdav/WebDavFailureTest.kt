package me.rerere.rikkahub.data.sync.webdav

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class WebDavFailureTest {
    @Test fun transportFailuresAreDistinguishedWithoutShowingPrivateExceptionText() {
        for ((error, expected) in listOf(
            UnknownHostException("private-host") to "DNS",
            ConnectException("private-host") to "无法连接",
            SSLHandshakeException("private-certificate") to "TLS",
            SocketTimeoutException("private-url") to "远端是否已写入尚未确认",
        )) {
            val result = webDavFailure("上传备份", IOException("private-wrapper", error))
            assertTrue(result.message!!.contains(expected))
            assertFalse(result.toString().contains("private-"))
            assertNull(result.cause)
        }
    }
    @Test fun cancellationEscapesAndNestedStagesPreserveOriginalFailure() = runBlocking {
        val cancellation = CancellationException("cancel")
        try { davResult("读取目录") { throw cancellation }; fail("cancellation swallowed") }
        catch (actual: CancellationException) { assertSame(cancellation, actual) }
        val original = webDavFailure("读取目录", WebDavException("secret", 401, "secret"))
        assertSame(original, webDavFailure("检查备份目录", original))
    }
}
