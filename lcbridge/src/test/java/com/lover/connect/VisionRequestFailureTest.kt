package com.lover.connect

import org.junit.Assert.*
import org.junit.Test

class VisionRequestFailureTest {
    @Test fun localIoIsNotMisdiagnosedAsRemoteRequestFailure() {
        val error = java.io.IOException("private-path-must-not-appear")
        assertEquals("observation_context_read_failed", observationStageFailure(ObservationStage.CONTEXT, error))
        assertEquals("diary_write_failed", observationStageFailure(ObservationStage.DIARY, error))
        assertEquals("request_failed", observationStageFailure(ObservationStage.REQUEST, error))
        assertEquals("screen_capture_failed", observationStageFailure(ObservationStage.CAPTURE, error))
    }

    @Test fun httpFailuresAreDistinguishedWithoutResponseBody() {
        assertNull(visionHttpFailure(200))
        assertEquals("vision_auth_rejected", visionHttpFailure(401))
        assertEquals("vision_auth_rejected", visionHttpFailure(403))
        assertEquals("vision_endpoint_not_found", visionHttpFailure(404))
        assertEquals("vision_rate_limited", visionHttpFailure(429))
        assertEquals("vision_server_error", visionHttpFailure(503))
        assertEquals("vision_server_timeout", visionHttpFailure(504))
        assertEquals("vision_request_rejected", visionHttpFailure(422))
        assertEquals("vision_http_error", visionHttpFailure(302))
    }

    @Test fun networkClassificationDoesNotCopyExceptionMessages() {
        val secret = "https://private.invalid?api_key=do-not-copy"
        assertEquals("vision_dns_failed", visionRequestFailure(java.net.UnknownHostException(secret)))
        assertEquals("vision_tls_failed", visionRequestFailure(javax.net.ssl.SSLException(secret)))
        assertEquals("vision_connection_failed", visionRequestFailure(java.net.ConnectException(secret)))
        assertEquals("request_timeout", visionRequestFailure(java.net.SocketTimeoutException(secret)))
        assertEquals("request_failed", visionRequestFailure(java.io.IOException(secret)))
        assertEquals("analysis_failed", visionRequestFailure(IllegalArgumentException(secret)))
    }
}
