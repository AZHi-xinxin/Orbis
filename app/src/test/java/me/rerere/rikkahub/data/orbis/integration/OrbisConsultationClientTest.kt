package me.rerere.rikkahub.data.orbis.integration

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import me.rerere.rikkahub.data.orbis.consultation.ConsultationFeaturePolicy

/** Every request is intercepted in memory; no socket, real credential, relay or model is used. */
class OrbisConsultationClientTest {
    private val humanToken = "synthetic-human-" + "h".repeat(32)
    private val operatorToken = "synthetic-operator-" + "o".repeat(32)
    private val sid = "a".repeat(32)
    private val otherSid = "b".repeat(32)
    private val secret = "synthetic-private-body-not-for-human"
    private val credential get() = OrbisConnectionCredential("https://consultation.invalid", humanToken, 1)
    private fun capabilities(role: String = "human", enabled: Boolean = true) =
        """{"protocol":"orbis.consultation/1","role":"$role","enabled":$enabled,"human_public_segments_only":true}"""
    private fun session(id: String = sid, emergency: Boolean = false): JsonObject = buildJsonObject {
        put("session_id", id); put("state", "ACTIVE"); put("created_at", 123); put("updated_at", 124)
        put("remaining_rounds", 19); put("emergency_visible", emergency)
    }
    private fun row(body: String = "public", seq: Int = 1) = buildJsonObject {
        put("seq", seq); put("part", 0); put("speaker", "synthetic-ai"); put("body", body)
    }
    private fun reply(request: Request, body: String, status: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
        .body(body.toResponseBody("application/json".toMediaType())).build()
    private fun client(answer: (Request) -> Response) = OrbisConsultationClient(
        OkHttpClient.Builder().addInterceptor { answer(it.request()) }.build(),
        feature = ConsultationFeaturePolicy(true))
    private suspend fun reason(action: suspend () -> Unit): String {
        try { action(); fail("expected rejection") } catch (e: OrbisConsultationException) {
            assertFalse(e.message.orEmpty().contains(secret)); assertNull(e.cause); return e.reason
        }
        error("unreachable")
    }

    @Test fun closedBuildRejectsSavedAuthorizationReadsAndWritesWithoutNetwork() = runBlocking {
        var calls = 0
        val client = OrbisConsultationClient(OkHttpClient.Builder().addInterceptor {
            calls++; error("closed consultation must not contact any endpoint")
        }.build(), feature = ConsultationFeaturePolicy(false))
        val oldAuthorization = ConsultationAuthorization(credential, ConsultationRole.HUMAN, true)
        assertEquals("not_open", reason { client.human(credential) })
        assertEquals("not_open", reason { client.sessions(oldAuthorization) })
        assertEquals("not_open", reason { client.listening(oldAuthorization, sid) })
        assertEquals("not_open", reason { client.stop(oldAuthorization, sid) })
        assertEquals("not_open", reason { client.retryOnce(oldAuthorization,
            ConsultationRetryStatus(sid, "b".repeat(32), "synthetic-ai", true, 0), "c".repeat(32)) })
        assertEquals("正在开发，暂未开放", consultationFailureText(OrbisConsultationException("not_open")))
        assertEquals(0, calls)
    }

    @Test fun constructionAndUnconfiguredStoreMakeNoNetworkRequest() = runBlocking {
        var calls = 0
        client { calls++; error("unexpected") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val memory = object : OrbisConnectionPersistence {
                override suspend fun read(): String? = null
                override suspend fun write(value: String?) = Unit
            }
            val store = OrbisConnectionStore(memory, scope)
            assertFalse(store.state.value.available); assertNull(store.readCredential())
            assertEquals("consultation-human", OrbisIntegration.CONSULTATION.key)
            assertNotEquals(OrbisIntegration.TECH_HUB.key, OrbisIntegration.CONSULTATION.key)
            assertEquals(0, calls)
        } finally { scope.cancel() }
    }
    @Test fun humanEntryRejectsAiAndOperatorRolesBeforeAnyBodyEndpoint() = runBlocking {
        for (role in listOf("ai", "operator")) {
            var calls = 0
            val client = client { request -> calls++; assertTrue(request.url.encodedPath.endsWith("/capabilities")); reply(request, capabilities(role)) }
            assertEquals("human_token_required", reason { client.human(credential) })
            assertEquals(1, calls)
        }
    }
    @Test fun operatorRequiresSeparateTokenAndServerOperatorRole() = runBlocking {
        var calls = 0
        val client = client { request -> calls++; reply(request, capabilities("human")) }
        assertEquals("operator_token_required", reason { client.operator(credential, humanToken) })
        assertEquals(0, calls)
        assertEquals("operator_token_required", reason { client.operator(credential, operatorToken) })
        assertEquals(1, calls)
    }
    @Test fun capabilitiesAreStrictProtocolAndRoleChecksNotClientSideButtons() = runBlocking {
        for (body in listOf(capabilities().replace("orbis.consultation/1", "other/1"),
            capabilities().replace("segments_only\":true", "segments_only\":false"))) {
            val client = client { reply(it, body) }
            assertEquals("protocol_mismatch", reason { client.human(credential) })
        }
    }
    @Test fun publicProjectionNeverKeepsHiddenServerFieldsOrPrivateSetup() = runBlocking {
        val body = JsonObject(session() + mapOf("segments" to JsonArray(listOf(row())),
            "private_context" to JsonPrimitive(secret), "question" to JsonPrimitive(secret),
            "messages" to JsonArray(listOf(row(secret))))).toString()
        val client = client { reply(it, if (it.url.encodedPath.endsWith("capabilities")) capabilities() else body) }
        val result = client.listening(client.human(credential), sid)
        assertEquals(listOf("public"), result.segments.map { it.body }); assertFalse(result.toString().contains(secret))
    }
    @Test fun ordinaryHumanCannotCallInspectionWithoutEmergencyMetadata() = runBlocking {
        var calls = 0
        val client = client { calls++; reply(it, capabilities()) }
        val auth = client.human(credential)
        assertEquals("inspection_not_authorized", reason {
            client.inspection(auth, ConsultationSession(sid, "ACTIVE", 0, 0, 20, false))
        })
        assertEquals(1, calls)
    }
    @Test fun emergencyInspectionStillGoesThroughServerAndRequiresEmergencyResult() = runBlocking {
        val visited = mutableListOf<String>()
        val body = JsonObject(session(emergency = true) + ("messages" to JsonArray(listOf(row(secret))))).toString()
        val client = client { visited += it.url.encodedPath; reply(it, if (it.url.encodedPath.endsWith("capabilities")) capabilities() else body) }
        val result = client.inspection(client.human(credential), ConsultationSession(sid, "CLOSED", 0, 0, 19, true))
        assertEquals(secret, result.messages.single().body)
        assertEquals(listOf("/v1/consultation/capabilities", "/v1/consultation/sessions/$sid/inspection"), visited)
    }
    @Test fun staleEmergencyAndMismatchedSessionNeverDisplayInspection() = runBlocking {
        for (metadata in listOf(session(emergency = false), session(otherSid, true))) {
            val body = JsonObject(metadata + ("messages" to JsonArray(listOf(row(secret))))).toString()
            val client = client { reply(it, if (it.url.encodedPath.endsWith("capabilities")) capabilities() else body) }
            assertTrue(reason { client.inspection(client.human(credential), ConsultationSession(sid, "CLOSED", 0, 0, 19, true)) }
                in setOf("inspection_not_authorized", "invalid_response"))
        }
    }
    @Test fun operatorInspectionUsesOnlySeparateTokenOnOriginalOrigin() = runBlocking {
        val client = client { request ->
            assertEquals("consultation.invalid", request.url.host)
            assertEquals("Bearer $operatorToken", request.header("Authorization"))
            val body = JsonObject(session() + ("messages" to JsonArray(listOf(row(secret))))).toString()
            reply(request, if (request.url.encodedPath.endsWith("capabilities")) capabilities("operator") else body)
        }
        val auth = client.operator(credential, operatorToken)
        assertEquals(secret, client.inspection(auth, ConsultationSession(sid, "ACTIVE", 0, 0, 19, false)).messages.single().body)
        assertEquals("human_token_required", reason { client.listening(auth, sid) })
        assertEquals("human_token_required", reason { client.ownSummary(auth, sid) })
        assertFalse(auth.toString().contains(operatorToken))
    }
    @Test fun redirectNeverFollowsOrLeaksBearerToTarget() = runBlocking {
        var calls = 0
        val client = client { request -> calls++; reply(request, secret, 307).newBuilder().header("Location", "https://other.invalid/private").build() }
        assertEquals("redirect_refused", reason { client.human(credential) }); assertEquals(1, calls)
    }
    @Test fun untrustedHttpErrorBodyIsNotEchoedAndNoRetry() = runBlocking {
        for (status in listOf(401, 403, 404, 409, 429, 503)) {
            var calls = 0
            val client = client { calls++; reply(it, secret, status) }
            reason { client.human(credential) }; assertEquals(1, calls)
        }
    }
    @Test fun traversalCredentialUrlsAndPublicCleartextFailBeforeNetwork() = runBlocking {
        var calls = 0
        val client = client { calls++; error("not requested") }
        for (url in listOf("https://u:p@consultation.invalid", "http://public.invalid", "https://consultation.invalid?token=x",
            "https://consultation.invalid/a/../", "https://consultation.invalid/%2e%2e/", "https://consultation.invalid\\other")) {
            assertEquals("invalid_connection", reason { client.human(OrbisConnectionCredential(url, humanToken, 1)) })
        }
        assertEquals(0, calls)
    }
    @Test fun listContainsBoundedMetadataOnlyAndNeverQuestionOrContext() = runBlocking {
        val metadata = JsonObject(session() + mapOf("question" to JsonPrimitive(secret), "private_context" to JsonPrimitive(secret)))
        val body = buildJsonObject { put("sessions", JsonArray(listOf(metadata))) }.toString()
        val client = client { reply(it, if (it.url.encodedPath.endsWith("capabilities")) capabilities() else body) }
        val sessions = client.sessions(client.human(credential)); assertEquals(1, sessions.size)
        assertFalse(sessions.toString().contains(secret)); assertEquals(19, sessions.single().remainingRounds)
    }
    @Test fun bodyAndProjectionLimitsRefuseOversizedResponses() = runBlocking {
        val variants = listOf("x".repeat(786433),
            JsonObject(session() + ("segments" to JsonArray(List(513) { row() }))).toString(),
            JsonObject(session() + ("segments" to JsonArray(listOf(row("x".repeat(16385)))))).toString())
        for (body in variants) {
            val client = client { reply(it, if (it.url.encodedPath.endsWith("capabilities")) capabilities() else body) }
            assertEquals("invalid_response", reason { client.listening(client.human(credential), sid) })
        }
    }
    @Test fun duplicateRowsAndWrongSessionAreRejected() = runBlocking {
        for (body in listOf(JsonObject(session() + ("segments" to JsonArray(listOf(row(), row())))).toString(),
            JsonObject(session(otherSid) + ("segments" to JsonArray(emptyList()))).toString())) {
            val client = client { reply(it, if (it.url.encodedPath.endsWith("capabilities")) capabilities() else body) }
            assertEquals("invalid_response", reason { client.listening(client.human(credential), sid) })
        }
    }
    @Test fun ownSummaryIsExplicitAndNullIsNotInventedText() = runBlocking {
        val visited = mutableListOf<String>()
        val client = client { visited += it.url.encodedPath; reply(it, if (it.url.encodedPath.endsWith("capabilities")) capabilities() else """{"summary":null}""") }
        val auth = client.human(credential); assertEquals(1, visited.size)
        assertNull(client.ownSummary(auth, sid)); assertEquals("/v1/consultation/sessions/$sid/summary", visited.last())
    }
    @Test fun stopUsesOneShotPostAndNeverStartsOrSubmitsAGeneration() = runBlocking {
        var stopCalls = 0
        val client = client { request ->
            if (request.url.encodedPath.endsWith("capabilities")) reply(request, capabilities()) else {
                stopCalls++; assertEquals("/v1/consultation/sessions/$sid/stop", request.url.encodedPath)
                assertEquals("POST", request.method); assertTrue(request.body!!.isOneShot())
                val buffer = Buffer(); request.body!!.writeTo(buffer); assertEquals("{}", buffer.readUtf8())
                reply(request, """{"state":"CLOSED"}""")
            }
        }
        val auth = client.human(credential); assertEquals(0, stopCalls)
        client.stop(auth, sid); assertEquals(1, stopCalls)
    }
    @Test fun unknownStopResultIsNotReportedAsConfirmedOrRetried() = runBlocking {
        for ((status, body) in listOf(503 to "invalid", 200 to "invalid", 200 to "{}", 200 to "x".repeat(786433))) {
            var stops = 0
            val client = client { if (it.url.encodedPath.endsWith("capabilities")) reply(it, capabilities()) else {
                stops++; reply(it, body, status)
            } }
            assertEquals("stop_unknown", reason { client.stop(client.human(credential), sid) }); assertEquals(1, stops)
        }
    }

    @Test fun stoppingAnAlreadyTerminatedSessionAcceptsArchivingWithoutRepeatingPost() = runBlocking {
        for (state in listOf("CLOSED", "ARCHIVING", "ARCHIVED")) {
            var stops = 0
            val client = client { request ->
                if (request.url.encodedPath.endsWith("capabilities")) reply(request, capabilities()) else {
                    stops++; reply(request, """{"state":"$state"}""")
                }
            }
            assertEquals(state, client.stop(client.human(credential), sid))
            assertEquals(1, stops)
        }
    }

    @Test fun activePausedAndMerelyTerminatingAreNotAConfirmedStop() = runBlocking {
        for (state in listOf("WAITING", "ACTIVE", "PAUSED", "TERMINATING", "UNKNOWN")) {
            var stops = 0
            val client = client { request ->
                if (request.url.encodedPath.endsWith("capabilities")) reply(request, capabilities()) else {
                    stops++; reply(request, """{"state":"$state"}""")
                }
            }
            assertEquals("stop_unknown", reason { client.stop(client.human(credential), sid) })
            assertEquals(1, stops)
        }
    }
}
