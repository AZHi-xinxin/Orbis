package me.rerere.asr

import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class ASRFailureTest {
    @Test fun eofRetainsNetworkClassificationThroughStateWithoutPrivateText() {
        val raw = EOFException("unexpected end of stream; private-url-and-key")
        val state = ASRState(errorMessage = raw.message, errorKind = classifyASRTransportFailure(raw))
        val error = assertThrows(ASRFailureException::class.java) { state.throwIfFailure() }
        assertEquals(ASRFailureKind.NETWORK, error.kind)
        assertNull(error.cause)
        assertFalse(error.toString().contains("private"))
    }
    @Test fun rejectedHandshakeNeverBecomesNetworkRetryEvenWithIoCause() {
        for (code in listOf(400, 401, 403, 429, 500, 503)) {
            assertNotEquals(ASRFailureKind.NETWORK, classifyASRTransportFailure(IOException("timeout"), code))
        }
        assertEquals(ASRFailureKind.AUTHORIZATION, classifyASRTransportFailure(IOException(), 401))
    }
    @Test fun invalidTlsAndProtocolNeedHumanAttention() {
        assertEquals(ASRFailureKind.PROTOCOL, classifyASRTransportFailure(SSLHandshakeException("certificate")))
        assertEquals(ASRFailureKind.PROTOCOL, classifyASRTransportFailure(ProtocolException("invalid")))
        assertEquals(ASRFailureKind.UNKNOWN, classifyASRTransportFailure(IllegalArgumentException("bad config")))
    }
    @Test fun successfulStateIgnoresStaleClassificationAndOldStateRemainsCompatible() {
        ASRState(errorKind = ASRFailureKind.NETWORK).throwIfFailure()
        assertThrows(IllegalStateException::class.java) { ASRState(errorMessage = "Connection reset").throwIfFailure() }
    }
}
