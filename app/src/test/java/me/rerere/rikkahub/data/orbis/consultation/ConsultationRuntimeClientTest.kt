package me.rerere.rikkahub.data.orbis.consultation

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ConsultationRuntimeClientTest {
    private val config = ConsultationRuntimeConfig("https://relay.invalid", "synthetic-ai-" + "a".repeat(32),
        "synthetic-human-" + "h".repeat(32), subject = "synthetic-ai-subject", enabled = true)
    private fun reply(request: Request, body: String, code: Int = 200) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(body.toResponseBody("application/json".toMediaType())).build()
    @Test fun verifyRequiresActorRoleMatchingSubjectAndEnabledRelay() = runBlocking<Unit> {
        for (bad in listOf("role", "subject", "enabled", "runtime_protocol")) {
            val body = buildJsonObject {
                put("protocol", "orbis.consultation/1"); put("runtime_protocol", if (bad == "runtime_protocol") "unknown" else "orbis.consultation-runtime/1")
                put("role", if (bad == "role") "human" else "ai"); put("subject", if (bad == "subject") "other-subject" else config.subject)
                put("enabled", bad != "enabled")
            }
            val client = ConsultationRuntimeClient(OkHttpClient.Builder().addInterceptor { reply(it.request(), body.toString()) }.build(),
                feature = ConsultationFeaturePolicy(true))
            assertTrue(runCatching { client.verify(config) }.isFailure)
        }
    }
    @Test fun redirectIsRefusedWithoutSecondRequest() = runBlocking<Unit> {
        var count = 0
        val client = ConsultationRuntimeClient(OkHttpClient.Builder().addInterceptor {
            count++; reply(it.request(), "{}", 302).newBuilder().header("Location", "https://other.invalid/").build()
        }.build(), feature = ConsultationFeaturePolicy(true))
        assertEquals("redirect_refused", (runCatching { client.call(config, "inbox") }.exceptionOrNull() as ConsultationRuntimeFailure).code)
        assertEquals(1, count)
    }
    @Test fun capabilityDeniedIsAnExceptionNotAnExecutionPermission() = runBlocking<Unit> {
        val client = ConsultationRuntimeClient(OkHttpClient.Builder().addInterceptor {
            reply(it.request(), """{"error":{"code":"capability_denied"}}""", 409)
        }.build(), feature = ConsultationFeaturePolicy(true))
        assertEquals("capability_denied", (runCatching { client.call(config, "sessions/${"a".repeat(32)}/capability", buildJsonObject {
            put("capability", "archive_own_work_memory")
        }) }.exceptionOrNull() as ConsultationRuntimeFailure).code)
    }
    @Test fun deviceEnrollmentSendsHashesWithoutActorAuthorization() = runBlocking<Unit> {
        val client = ConsultationRuntimeClient(OkHttpClient.Builder().addInterceptor {
            assertNull(it.request().header("Authorization"))
            val buffer = okio.Buffer(); it.request().body!!.writeTo(buffer)
            assertFalse(buffer.readUtf8().contains(config.aiToken))
            reply(it.request(), "{}")
        }.build(), feature = ConsultationFeaturePolicy(true))
        client.call(config, "enroll", buildJsonObject { put("ai_sha256", consultationDigest(config.aiToken)) }, token = null)
    }
    @Test fun invalidPathAndPublicCleartextNeverMakeRequest() = runBlocking<Unit> {
        var count = 0
        val client = ConsultationRuntimeClient(OkHttpClient.Builder().addInterceptor { count++; error("no request expected") }.build(),
            feature = ConsultationFeaturePolicy(true))
        assertTrue(runCatching { client.call(config, "../outside") }.isFailure)
        assertTrue(runCatching { client.call(ConsultationRuntimeConfig("http://example.invalid", config.aiToken), "inbox") }.isFailure)
        assertEquals(0, count)
    }
    @Test fun modelDiscoveryPrivateOriginIsStrictTailnetNotAll100Addresses() {
        assertEquals("100.64.0.1", consultationModelRoot("http://100.64.0.1:8180/v1").host)
        assertEquals("100.127.255.254", consultationModelRoot("http://100.127.255.254/v1").host)
        for (url in listOf("http://100.0.0.1/v1", "http://100.128.0.1/v1", "http://100.example/v1",
            "https://user:pass@models.invalid/v1", "https://models.invalid/v1?token=x", "https://models.invalid/a/../v1",
            "https://models.invalid/%2e%2e/v1", "https://models.invalid/v1#extra"))
            assertTrue(url, runCatching { consultationModelRoot(url) }.isFailure)
    }
    @Test fun runtimeConfigToStringNeverDisclosesAnySecret() {
        assertFalse(config.toString().contains(config.aiToken))
        assertFalse(config.toString().contains(config.humanToken))
    }

    @Test fun closedBuildBlocksReadsEnrollmentAndWritesDespiteSavedEnabledConfig() = runBlocking<Unit> {
        var count = 0
        val client = ConsultationRuntimeClient(OkHttpClient.Builder().addInterceptor {
            count++; error("closed consultation must not contact any endpoint")
        }.build(), feature = ConsultationFeaturePolicy(false))
        val operations: List<suspend () -> Unit> = listOf(
            { client.verify(config); Unit },
            { client.call(config, "inbox"); Unit },
            { client.call(config, "enroll", buildJsonObject {}, token = null); Unit },
            { client.call(config, "sessions/${"a".repeat(32)}/submit", buildJsonObject {}); Unit },
        )
        for (operation in operations) {
            assertEquals("not_open", (runCatching { operation() }.exceptionOrNull() as ConsultationRuntimeFailure).code)
        }
        assertTrue(config.enabled)
        assertEquals(0, count)
    }

    @Test fun internalPolicyStillPerformsStrictSuccessfulVerification() = runBlocking<Unit> {
        var count = 0
        val client = ConsultationRuntimeClient(OkHttpClient.Builder().addInterceptor {
            count++
            reply(it.request(), buildJsonObject {
                put("protocol", "orbis.consultation/1"); put("runtime_protocol", "orbis.consultation-runtime/1")
                put("role", "ai"); put("subject", config.subject); put("enabled", true)
            }.toString())
        }.build(), feature = ConsultationFeaturePolicy(true))
        assertEquals(config.subject, client.verify(config).getValue("subject").jsonPrimitive.content)
        assertEquals(1, count)
    }
}
