package me.rerere.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class OAuthLoopbackCallbackServerTest {
    private val httpClient = OkHttpClient()

    @Test fun `Orbis callback is only a receipt and never claims a working model`() = runBlocking {
        val server = OAuthLoopbackCallbackServer(callbackPath = "/auth/callback", appName = "Orbis", callbackReceiptOnly = true)
        val session = server.openSession("receipt-state")
        try {
            withContext(Dispatchers.IO) {
                execute("${session.redirectUri}?code=synthetic&state=receipt-state&client_id=oaiapp_test").use {
                    val page = it.body.string()
                    assertEquals(200, it.code)
                    assertTrue(page.contains("Orbis"))
                    assertTrue(page.contains("已收到授权结果"))
                    assertTrue(page.contains("不代表模型连接测试已通过"))
                    assertFalse(page.contains("RikkaHub"))
                    assertFalse(page.contains("connected successfully"))
                }
            }
        } finally { session.close() }
    }

    @Test
    fun `issued dynamic client id is preserved without changing legacy callbacks`() = runBlocking {
        val server = OAuthLoopbackCallbackServer(callbackPath = "/auth/callback")
        val session = server.openSession("siwc-state")
        try {
            withContext(Dispatchers.IO) {
                execute("${session.redirectUri}?code=test-code&state=siwc-state&client_id=oaiapp_test").use { assertEquals(200, it.code) }
            }
            assertEquals("oaiapp_test", session.awaitCallback(2.seconds)?.clientId)
        } finally { session.close() }
    }

    @Test
    fun `callback is delivered only to matching state`() = runBlocking {
        val server = OAuthLoopbackCallbackServer()
        val session = server.openSession("expected-state")
        try {
            execute("${session.redirectUri}?code=wrong-code&state=wrong-state").use { response ->
                assertEquals(400, response.code)
            }

            val callbackAndClose = async(Dispatchers.IO) {
                session.awaitCallback(2.seconds).also { session.close() }
            }
            withContext(Dispatchers.IO) {
                execute("${session.redirectUri}?code=auth-code&state=expected-state").use { response ->
                    assertEquals(200, response.code)
                    assertTrue(response.body.string().contains("Authorization complete"))
                }
            }

            val callback = callbackAndClose.await()
            assertEquals("auth-code", callback?.code)
            assertEquals("expected-state", callback?.state)
            assertFalse(callback?.error != null)
        } finally {
            session.close()
        }
    }

    private fun execute(url: String) = httpClient.newCall(
        Request.Builder()
            .url(url)
            .build()
    ).execute()
}
