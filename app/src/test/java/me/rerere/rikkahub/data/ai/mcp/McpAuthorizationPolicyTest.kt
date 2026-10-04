package me.rerere.rikkahub.data.ai.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class McpAuthorizationPolicyTest {
    private val expiredOAuth = McpOAuthState(
        enabled = true, accessToken = "synthetic-old-oauth", refreshToken = "synthetic-refresh",
        expiresAt = 1,
    )
    private fun config(headers: List<Pair<String, String>> = emptyList(), oauth: McpOAuthState? = expiredOAuth) =
        McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "synthetic", headers = headers, oauth = oauth),
            url = "https://synthetic.invalid/mcp",
        )

    @Test fun `manual authorization preserves exact value and prevents expired oauth refresh`() {
        for (name in listOf("Authorization", "authorization", "AUTHORIZATION")) {
            for (value in listOf("Bearer synthetic-manual", "Basic synthetic", "")) {
                val manual = config(listOf(name to value))
                assertTrue(manual.hasManualAuthorization())
                assertFalse(manual.shouldRefreshOAuth(100_000))
                assertEquals(listOf(name to value), manual.resolvedHeaders())
            }
        }
    }

    @Test fun `manual unauthorized never starts discovery or requests oauth`() = runBlocking {
        for (failure in listOf(IOException("401"), IOException("invalid_token Authentication required"), IOException("offline"))) {
            val manual = config(listOf("authorization" to "Bearer synthetic-manual"))
            assertFalse(needsMcpOAuthAuthorization(manual, failure) { error("Must not probe with manual authorization") })
        }
    }

    @Test fun `blank explicit authorization does not silently fall back to oauth`() = runBlocking {
        val manual = config(listOf("Authorization" to ""))
        assertFalse(needsMcpOAuthAuthorization(manual, IOException("401")) { error("Must not probe") })
        assertEquals(listOf("Authorization" to ""), manual.resolvedHeaders())
    }

    @Test fun `oauth without manual header retains refresh and authorization behavior`() = runBlocking {
        val oauthOnly = config()
        assertTrue(oauthOnly.shouldRefreshOAuth(100_000))
        assertEquals(listOf("Authorization" to "Bearer synthetic-old-oauth"), oauthOnly.resolvedHeaders())
        assertTrue(needsMcpOAuthAuthorization(oauthOnly, IOException("401")) { error("Already known OAuth") })
        assertFalse(config(oauth = expiredOAuth.copy(enabled = false)).shouldRefreshOAuth(100_000))
        assertFalse(config(oauth = expiredOAuth.copy(refreshToken = null)).shouldRefreshOAuth(100_000))
        assertFalse(config(oauth = expiredOAuth.copy(expiresAt = 300_000)).shouldRefreshOAuth(100_000))
        assertFalse(config(oauth = expiredOAuth.copy(expiresAt = 0)).shouldRefreshOAuth(100_000))
        assertTrue(config(oauth = expiredOAuth.copy(accessToken = null, expiresAt = 0)).shouldRefreshOAuth(100_000))
    }

    @Test fun `no credential keeps discovery success and failure distinct`() = runBlocking {
        var probes = 0
        val unauthenticated = config(oauth = null)
        assertTrue(needsMcpOAuthAuthorization(unauthenticated, IOException("401")) { probes++ })
        assertFalse(needsMcpOAuthAuthorization(unauthenticated, IOException("401")) {
            probes++
            throw IOException("synthetic discovery unavailable")
        })
        assertEquals(2, probes)
    }

    @Test fun `cancelled discovery remains cancellation`() = runBlocking {
        val cancelled = CancellationException("synthetic cancellation")
        val actual = runCatching {
            needsMcpOAuthAuthorization(config(oauth = null), IOException("401")) { throw cancelled }
        }.exceptionOrNull()
        assertSame(cancelled, actual)
    }

    @Test fun `manual failure summary reveals no credential but original evidence is preserved`() {
        val manual = config(listOf("Authorization" to "Bearer synthetic-sensitive-value"))
        val original = IOException("invalid_token Authentication required")
        val status = mcpConnectionError(manual, original)
        assertTrue(status.message.contains("手动认证未通过"))
        assertFalse(status.message.contains("synthetic-sensitive-value"))
        assertFalse(status.message.contains("synthetic.invalid"))
        assertTrue(checkNotNull(status.detail).contains("invalid_token Authentication required"))
        val network = IOException("synthetic timeout")
        assertEquals(McpStatus.Error.from(network), mcpConnectionError(manual, network))
    }

    @Test fun `duplicate explicit headers are not secretly repaired or replaced`() {
        val headers = listOf("Authorization" to "Bearer synthetic-one", "authorization" to "Bearer synthetic-two")
        assertEquals(headers, config(headers).resolvedHeaders())
        assertFalse(config(headers).shouldRefreshOAuth(100_000))
    }
}
