package me.rerere.rikkahub.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class WebRequestPolicyTest {
    @Test fun sensitiveRequestsRequireNonemptyHeaderBearerEvenIfQueryAuthenticationSucceeded() {
        for (header in listOf(null, "", "Bearer", "Bearer ", "Bearer     ", "Bearer \t", "Basic synthetic")) {
            assertNull(sensitiveWebBearer(header))
        }
        assertEquals("synthetic", sensitiveWebBearer("bEaReR synthetic "))
    }
    @Test fun sameOriginBrowserRequestIsAllowed() {
        assertTrue(isSameOriginWebRequest("phone.test:8080", "http://phone.test:8080", "http://phone.test:8080", "same-origin"))
    }
    @Test fun sameOriginGetUsesExplicitOriginProof() {
        assertTrue(isSameOriginWebRequest("phone.test:8080", "http://phone.test:8080", null, "same-origin"))
    }
    @Test fun missingOrForeignOriginIsRejected() {
        assertFalse(isSameOriginWebRequest("phone.test", null, null, "same-origin"))
        assertFalse(isSameOriginWebRequest("phone.test", "https://foreign.test", null, "same-origin"))
        assertFalse(isSameOriginWebRequest("phone.test", "https://phone.test", "https://foreign.test", "same-origin"))
    }
    @Test fun CrossSiteEvenWithClaimedOriginIsRejected() {
        for (site in listOf("cross-site", "same-site", "none")) {
            assertFalse(isSameOriginWebRequest("phone.test", "https://phone.test", null, site))
        }
    }
    @Test fun originMustBeAnOriginNotAResourceOrCredentialUrl() {
        for (origin in listOf("null", "file://phone.test", "https://user@phone.test", "https://phone.test/path", "https://phone.test?x", "https://phone.test#x")) {
            assertFalse(isSameOriginWebRequest("phone.test", origin, null, "same-origin"))
        }
    }
}
