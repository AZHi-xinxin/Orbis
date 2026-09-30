package me.rerere.rikkahub.ui.pages.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisMiniGamePolicyTest {
    @Test fun documentMustBeTheExactOwnedMainFrame() {
        val policy = OrbisMiniGamePolicy("fixture")
        assertTrue(policy.isDocument(policy.pageUrl, true, "GET"))
        assertFalse(policy.isDocument(policy.pageUrl, false, "GET"))
        assertFalse(policy.isDocument(policy.pageUrl, true, "POST"))
        listOf(policy.pageUrl + "?file=private", policy.pageUrl + "/..", "file:///data/private",
            "content://private", "https://example.org/", "http://127.0.0.1:8081/").forEach {
            assertFalse(policy.isDocument(it, true, "GET"))
        }
        assertNotEquals(OrbisMiniGamePolicy().pageUrl, OrbisMiniGamePolicy().pageUrl)
    }

    @Test fun resultPayloadCannotChooseSessionsOrInvokeNativeCapabilities() {
        val seen = mutableListOf<OrbisMiniGameResult>()
        val gate = OrbisMiniGameResultGate { seen += it }
        assertTrue(gate.finish("""{"result":"win","move_count":5}""").contains("\"ok\":true"))
        assertEquals(listOf(OrbisMiniGameResult("win", 5)), seen)
        listOf("""{"result":"win","move_count":5,"session_id":"other"}""",
            """{"result":"win","move_count":"5"}""", """{"result":"win","move_count":-1}""",
            """{"result":"win","move_count":1.5}""", """{"result":"win","move_count":1000001}""",
            """{"result":"paid","move_count":2}""", "null", "x".repeat(4097)).forEach {
            assertTrue(gate.finish(it).contains("\"ok\":false"))
        }
        assertEquals(1, seen.size)
    }

    @Test fun closedFrameCannotWriteAndWriterFailureDoesNotLeakDetails() {
        val gate = OrbisMiniGameResultGate { error("private-token-and-path") }
        val failure = gate.finish("""{"result":"completed","move_count":0}""")
        assertTrue(failure.contains("game_result_not_saved"))
        assertFalse(failure.contains("private-token"))
        gate.close()
        assertTrue(gate.finish("""{"result":"completed","move_count":0}""").contains("game_frame_closed"))
    }

    @Test fun contentPolicyOnlyAllowsSelfContainedArtworkAndScripts() {
        assertTrue(OrbisMiniGamePolicy.CSP.contains("connect-src 'none'"))
        assertTrue(OrbisMiniGamePolicy.CSP.contains("frame-src 'none'"))
        assertTrue(OrbisMiniGamePolicy.CSP.contains("form-action 'none'"))
        assertTrue(OrbisMiniGamePolicy.CSP.contains("base-uri 'none'"))
        assertTrue(OrbisMiniGamePolicy.CSP.contains("webrtc 'block'"))
        assertTrue(OrbisMiniGamePolicy.CSP.contains("sandbox allow-scripts;"))
        assertFalse(OrbisMiniGamePolicy.CSP.contains("allow-same-origin"))
        assertFalse(OrbisMiniGamePolicy.CSP.contains("https:"))
        assertFalse(OrbisMiniGamePolicy.CSP.contains("file:"))
    }

    @Test fun hostBootstrapPrecedesImportedScriptsWithoutChangingTheirSource() {
        val source = "<!doctype html><script>window.fixture=42;</script>"
        val hosted = OrbisMiniGamePolicy.hostedHtml(source)
        assertTrue(hosted.endsWith(source))
        assertTrue(hosted.indexOf("RTCPeerConnection") < hosted.indexOf("window.fixture"))
        assertTrue(hosted.contains("configurable:false"))
        assertTrue(hosted.contains("x-dns-prefetch-control"))
    }

    @Test fun gameModelErrorsAreActionableWithoutLeakingProviderDetails() {
        assertTrue(gameModelErrorLabel("game_model_auxiliary_alias_missing").contains("辅助任务连接"))
        assertTrue(gameModelErrorLabel("game_model_call_limit_reached").contains("切回本地"))
        assertFalse(gameModelErrorLabel("https://private-endpoint?token=private-value").contains("private"))
    }
}
