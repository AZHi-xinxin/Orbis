package me.rerere.rikkahub.data.orbis.contact

import com.lover.connect.CompanionToolDescriptor
import com.lover.connect.CompanionToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.companiontools.buildCompanionTools
import org.junit.Assert.*
import org.junit.Test

/** Synthetic ports only: no Android audio, notification, remote TTS, microphone, or model calls. */
class OrbisNotificationSpeechTest {
    private val assistant = "11111111-1111-4111-8111-111111111111"
    private val conversation = "22222222-2222-4222-8222-222222222222"

    private class Lease : OrbisNotificationSpeechLease {
        override var playbackStarted = false
        val spoken = mutableListOf<String>()
        var closes = 0
        var playAction: suspend () -> Unit = {}
        var closeAction: suspend () -> Unit = {}
        override suspend fun play(text: String) {
            spoken += text
            playbackStarted = true
            playAction()
        }
        override suspend fun close() { closes++; closeAction() }
    }

    private class Port : OrbisNotificationSpeechPort {
        val lease = Lease()
        var block: String? = null
        var acquisitions = 0
        var denied = false
        var acquiredScope: OrbisNotificationSpeechScope? = null
        var onAcquire: () -> Unit = {}
        override fun skipReason(scope: OrbisNotificationSpeechScope) = block
        override suspend fun acquire(scope: OrbisNotificationSpeechScope): OrbisNotificationSpeechLease? {
            acquisitions++
            acquiredScope = scope
            onAcquire()
            return if (denied) null else lease
        }
        fun reader(timeout: Long = 45_000, lock: Mutex = Mutex()) = OrbisNotificationSpeech(this, lock, timeout)
    }

    private fun descriptor(name: String = "send_notification") = CompanionToolDescriptor(
        name, "Synthetic notification", """{"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}""",
        effect = "write", requiresService = true,
    )

    private fun args(text: String = "synthetic notification") = buildJsonObject { put("message", text) }

    @Test fun privacyDefaultsAndSerializationNeverEnableAutomaticReading() {
        val preferences = Json.decodeFromString<OrbisContactPreferences>("{}")
        assertFalse(preferences.notificationAutoRead)
        assertFalse(preferences.notificationReadWhenLocked)
        assertTrue(preferences.allowIncomingCalls)
        assertEquals(15, preferences.rejectCooldownMinutes)
        val changed = preferences.copy(notificationAutoRead = true, notificationReadWhenLocked = true)
        assertEquals(changed, Json.decodeFromString<OrbisContactPreferences>(Json.encodeToString(changed)))
    }

    @Test fun cooldownIsBoundedWithoutChangingPrivacyChoices() {
        val normalized = OrbisContactPreferences(rejectCooldownMinutes = Int.MAX_VALUE).normalized()
        assertEquals(120, normalized.rejectCooldownMinutes)
        assertEquals(1, OrbisContactPreferences(rejectCooldownMinutes = -1).normalized().rejectCooldownMinutes)
        assertFalse(normalized.notificationAutoRead)
    }

    @Test fun disabledAndLockScreenPoliciesAreIndependent() {
        val baseline = OrbisNotificationSpeechConditions()
        assertEquals("disabled", notificationSpeechSkipReason(baseline))
        assertEquals("disabled", notificationSpeechSkipReason(baseline.copy(allowLocked = true)))
        assertEquals("device_locked", notificationSpeechSkipReason(baseline.copy(enabled = true, deviceLocked = true)))
        assertNull(notificationSpeechSkipReason(baseline.copy(enabled = true, deviceLocked = true, allowLocked = true)))
    }

    @Test fun callsAlarmsSilentDndAndCompetingPlaybackEachSuppressSpeech() {
        val clear = OrbisNotificationSpeechConditions(enabled = true)
        val cases = listOf(
            clear.copy(assistantAvailable = false) to "assistant_unavailable",
            clear.copy(providerAvailable = false) to "provider_unavailable",
            clear.copy(callActive = true) to "call_active",
            clear.copy(alarmActive = true) to "alarm_active",
            clear.copy(silent = true) to "silent_mode",
            clear.copy(doNotDisturb = true) to "do_not_disturb",
            clear.copy(priorityAudioActive = true) to "priority_audio_active",
            clear.copy(otherAudioActive = true) to "other_audio_active",
        )
        cases.forEach { (state, reason) -> assertEquals(reason, notificationSpeechSkipReason(state)) }
        assertNull(notificationSpeechSkipReason(clear))
    }

    @Test fun successfulPlaybackUsesExactCurrentTextTrustedScopeAndReleasesLease() = runTest {
        val port = Port()
        val receipt = port.reader().readNotification("  only this posted text\n正文  ", assistant, conversation)
        assertEquals("played", receipt.status)
        assertEquals("playback_completed", receipt.reasonCode)
        assertTrue(receipt.playbackStarted)
        assertFalse(receipt.userHeardConfirmed)
        assertEquals(listOf("  only this posted text\n正文  "), port.lease.spoken)
        assertEquals(OrbisNotificationSpeechScope(assistant, conversation), port.acquiredScope)
        assertEquals(1, port.lease.closes)
    }

    @Test fun blockedReadNeverAcquiresFocusOrSynthesizes() = runTest {
        val port = Port().also { it.block = "disabled" }
        val receipt = port.reader().readNotification("private notice", assistant, conversation)
        assertEquals("skipped", receipt.status)
        assertEquals("disabled", receipt.reasonCode)
        assertEquals(0, port.acquisitions)
        assertTrue(port.lease.spoken.isEmpty())
    }

    @Test fun invalidHostScopeAndBlankTextNeverReachBackend() = runTest {
        val port = Port()
        assertEquals("invalid_host_scope", port.reader().readNotification("x", "model-picked-owner", conversation).reasonCode)
        assertEquals("empty_text", port.reader().readNotification(" \n ", assistant, conversation).reasonCode)
        assertEquals(0, port.acquisitions)
    }

    @Test fun longNotificationIsSkippedNotTruncatedAndMisreported() = runTest {
        val port = Port()
        val result = port.reader().readNotification("x".repeat(OrbisNotificationSpeech.MAX_TEXT_CHARS + 1), assistant, conversation)
        assertEquals("text_too_long", result.reasonCode)
        assertEquals(0, port.acquisitions)
    }

    @Test fun focusDenialIsSkippedNotPlayedOrQueued() = runTest {
        val port = Port().also { it.denied = true }
        val receipt = port.reader().readNotification("notice", assistant, conversation)
        assertEquals("audio_focus_denied", receipt.reasonCode)
        assertFalse(receipt.playbackStarted)
        assertTrue(port.lease.spoken.isEmpty())
        assertEquals(0, port.lease.closes)
    }

    @Test fun settingRevokedDuringFocusAcquisitionClosesWithoutPlayback() = runTest {
        val port = Port()
        port.onAcquire = { port.block = "disabled" }
        val receipt = port.reader().readNotification("notice", assistant, conversation)
        assertEquals("disabled", receipt.reasonCode)
        assertTrue(port.lease.spoken.isEmpty())
        assertEquals(1, port.lease.closes)
    }

    @Test fun playbackFailureNeverExposesProviderTextCredentialsOrEndpoint() = runTest {
        val port = Port()
        port.lease.playAction = { error("SECRET_NOTIFICATION api_key=secret https://private.invalid") }
        val receipt = port.reader().readNotification("notice", assistant, conversation)
        assertEquals("failed", receipt.status)
        assertEquals("speech_failed", receipt.reasonCode)
        assertFalse(receipt.toString().contains("SECRET"))
        assertFalse(receipt.userHeardConfirmed)
        assertEquals(1, port.lease.closes)
    }

    @Test fun timeoutStopsPlaybackAndAllowsASeparateLaterRequest() = runTest {
        val port = Port()
        val reader = port.reader(timeout = 100)
        port.lease.playAction = { delay(1_000) }
        val timeout = reader.readNotification("one", assistant, conversation)
        assertEquals("speech_timeout", timeout.reasonCode)
        assertEquals(1, port.lease.closes)
        port.lease.playAction = {}
        assertEquals("played", reader.readNotification("two", assistant, conversation).status)
        assertEquals(2, port.lease.closes)
    }

    @Test fun callerCancellationReleasesLeaseAndDoesNotTurnIntoFakeCompletion() = runTest {
        val port = Port()
        val entered = CompletableDeferred<Unit>()
        port.lease.playAction = { entered.complete(Unit); awaitCancellation() }
        val reader = port.reader()
        val request = launch { reader.readNotification("notice", assistant, conversation) }
        entered.await()
        request.cancelAndJoin()
        assertEquals(1, port.lease.closes)
        port.lease.playAction = {}
        assertEquals("played", reader.readNotification("next", assistant, conversation).status)
    }

    @Test fun cleanupFailureDoesNotLeakPrivateErrorOrKeepTheSpeechLock() = runTest {
        val port = Port()
        port.lease.closeAction = { error("SECRET platform path") }
        val reader = port.reader()
        assertEquals("played", reader.readNotification("one", assistant, conversation).status)
        assertEquals("played", reader.readNotification("two", assistant, conversation).status)
        assertEquals(2, port.lease.closes)
    }

    @Test fun cancellationDuringFocusHandoffStillClosesTheAcquiredLease() = runTest {
        val port = Port()
        lateinit var request: Job
        port.onAcquire = { request.cancel() }
        request = launch(start = CoroutineStart.LAZY) { port.reader().readNotification("notice", assistant, conversation) }
        request.start()
        request.join()
        assertEquals(1, port.acquisitions)
        assertEquals(1, port.lease.closes)
        assertTrue(port.lease.spoken.isEmpty())
    }

    @Test fun outerTimeoutIsNotMistakenForTheReadersOwnTimeout() = runTest {
        val port = Port()
        port.lease.playAction = { delay(100_000) }
        try {
            withTimeout(10) { port.reader(timeout = 1_000).readNotification("notice", assistant, conversation) }
            fail("outer cancellation must propagate")
        } catch (_: TimeoutCancellationException) { }
        assertEquals(1, port.lease.closes)
    }

    @Test fun concurrentReadersSkipInsteadOfInterruptingOrQueuing() = runTest {
        val lock = Mutex()
        val first = Port()
        val second = Port()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        first.lease.playAction = { started.complete(Unit); finish.await() }
        val ongoing = async { first.reader(lock = lock).readNotification("first", assistant, conversation) }
        started.await()
        val receipt = second.reader(lock = lock).readNotification("second", assistant, conversation)
        assertEquals("speech_busy", receipt.reasonCode)
        assertEquals(0, second.acquisitions)
        finish.complete(Unit)
        assertEquals("played", ongoing.await().status)
        assertEquals(0, second.acquisitions)
    }

    @Test fun onlySuccessfulCurrentNotificationExecutionTriggersSpeech() = runTest {
        var spoken: String? = null
        val tool = buildCompanionTools(listOf(descriptor()), null, { "authorized" },
            readNotification = { text -> spoken = text; OrbisNotificationSpeechReceipt("played", "playback_completed", true) },
        ) { _, _, _ -> CompanionToolResult(true, "posted") }.single()
        assertNull(spoken)
        val output = tool.execute(args())
        assertEquals("synthetic notification", spoken)
        assertEquals(2, output.size)
        val posted = Json.parseToJsonElement((output.first() as UIMessagePart.Text).text).jsonObject
        assertTrue(posted.getValue("ok").jsonPrimitive.boolean)
        val read = Json.parseToJsonElement((output.last() as UIMessagePart.Text).text).jsonObject
        assertTrue(read.getValue("already_posted").jsonPrimitive.boolean)
        assertEquals("played", read.getValue("speech").jsonObject.getValue("status").jsonPrimitive.content)
        assertFalse(read.getValue("speech").jsonObject.getValue("userHeardConfirmed").jsonPrimitive.boolean)
        assertTrue(tool.needsApproval(args()))
        assertNotNull(tool.hostApproval)
    }

    @Test fun failedOrUnknownPostingNeverTriggersSpeech() = runTest {
        var calls = 0
        for (result in listOf(CompanionToolResult(false, errorCode = "permission_required", outcome = "not_started"),
            CompanionToolResult(false, errorCode = "unconfirmed", outcome = "unknown"),
            CompanionToolResult(true, outcome = "unknown"))) {
            val tool = buildCompanionTools(listOf(descriptor()), null, { "authorized" },
                readNotification = { calls++; error("must not speak") },
            ) { _, _, _ -> result }.single()
            assertEquals(1, tool.execute(args()).size)
        }
        assertEquals(0, calls)
    }

    @Test fun alarmsOtherToolsAndOldResultConversionNeverTriggerSpeech() = runTest {
        var calls = 0
        val tool = buildCompanionTools(listOf(descriptor("set_alarm")), null, { "authorized" },
            readNotification = { calls++; error("must not speak") },
        ) { _, _, _ -> CompanionToolResult(true, "alarm receipt") }.single()
        assertEquals(1, tool.execute(args()).size)
        assertEquals(0, calls)
    }

    @Test fun missingHostScopeDoesNotInventCurrentTabIdentity() = runTest {
        val tool = buildCompanionTools(listOf(descriptor()), null, { "authorized" }) { _, _, _ -> CompanionToolResult(true, "posted") }.single()
        val output = tool.execute(args())
        val receipt = Json.parseToJsonElement((output.last() as UIMessagePart.Text).text).jsonObject.getValue("speech").jsonObject
        assertEquals("missing_host_scope", receipt.getValue("reasonCode").jsonPrimitive.content)
        assertEquals(setOf("message"), (tool.parameters() as me.rerere.ai.core.InputSchema.Obj).properties.keys)
    }

    @Test fun speechFailureCannotUndoSuccessfulNotificationOrLeakErrorBody() = runTest {
        val tool = buildCompanionTools(listOf(descriptor()), null, { "authorized" },
            readNotification = { error("SECRET provider request") },
        ) { _, _, _ -> CompanionToolResult(true, "posted") }.single()
        val output = tool.execute(args()).map { (it as UIMessagePart.Text).text }
        assertTrue(Json.parseToJsonElement(output.first()).jsonObject.getValue("ok").jsonPrimitive.boolean)
        assertTrue(output.last().contains("speech_failed"))
        assertFalse(output.any { it.contains("SECRET") })
    }

    @Test fun toolCancellationIsNotSwallowedAsAFalsePlayedReceipt() = runTest {
        val tool = buildCompanionTools(listOf(descriptor()), null, { "authorized" },
            readNotification = { throw CancellationException("cancel") },
        ) { _, _, _ -> CompanionToolResult(true, "posted") }.single()
        try { tool.execute(args()); fail("cancellation must propagate") } catch (_: CancellationException) { }
    }
}
