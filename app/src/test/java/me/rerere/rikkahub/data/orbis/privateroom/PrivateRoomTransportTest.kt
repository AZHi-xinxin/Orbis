package me.rerere.rikkahub.data.orbis.privateroom

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessagePart
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackResponse as MockResponse
import me.rerere.rikkahub.data.orbis.privateroom.PrivateRoomLoopbackServer as MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PrivateRoomTransportTest {

    @Test fun inheritedApplicationAndNetworkLoggingNeverSeePrivateBody() {
        var events = 0
        val base = OkHttpClient.Builder()
            .addInterceptor { error("ordinary logging must not run") }
            .addNetworkInterceptor { error("network logging must not run") }
            .eventListener(object : EventListener() { override fun callStart(call: okhttp3.Call) { events++ } })
            .build()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"private\":\"test\"}"))
            val client = privateRoomHttpClient(base)
            client.newCall(Request.Builder().url(server.url("/private")).build()).execute().use {
                assertEquals("{\"private\":\"test\"}", it.body.string())
            }
            assertEquals(0, events)
            assertFalse(client.retryOnConnectionFailure)
            assertFalse(client.followRedirects)
            assertNull(client.cache)
        }
    }

    @Test fun redirectNeverForwardsPrivateRequestToSecondDestination() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "/other").setBody("{}"))
            val client = privateRoomHttpClient(OkHttpClient())
            client.newCall(Request.Builder().url(server.url("/private")).build()).execute().use { assertEquals(307, it.code) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun responseSizeAndNestingAreBoundedBeforeProviderParser() {
        for (response in listOf("[".repeat(100) + "0" + "]".repeat(100), "x".repeat(1_048_577))) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response))
                val client = privateRoomHttpClient(OkHttpClient())
                try {
                    client.newCall(Request.Builder().url(server.url("/private")).build()).execute().close()
                    fail("response must be rejected")
                } catch (e: java.io.IOException) { assertTrue(e.message!!.startsWith("private_response_")) }
                assertEquals(1, server.requestCount)
            }
        }
    }
}
