package me.rerere.rikkahub.data.orbis.privateroom

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultAvailability
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultStatus
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class PrivateRoomPreflightTest {
    private val model = Model(modelId = "synthetic-private-model")
    private val owner = Assistant(chatModelId = model.id)
    private val provider = ProviderSetting.OpenAI(apiKey = "SYNTHETIC-SECRET", models = listOf(model))
    private val ready = PrivateVaultStatus(PrivateVaultAvailability.READY, enabled = true, recoveryConfirmed = true)
    private fun settings(connection: ProviderSetting = provider) = Settings(assistants = listOf(owner),
        providers = listOf(connection), chatModelId = model.id)
    private fun check(vault: PrivateVaultStatus = ready, choice: PrivateRoomModelChoice? = null,
                      snapshot: Settings = settings()) = privateRoomPreflight(snapshot, owner.id.toString(), choice, vault)

    @Test fun distinguishesUncreatedPausedUnconfirmedRecoveryAndUnreadable() {
        assertEquals(PrivateRoomReason.ROOM_NOT_CREATED, check(PrivateVaultStatus(PrivateVaultAvailability.ABSENT)).reason)
        assertEquals(PrivateRoomReason.ROOM_PAUSED, check(ready.copy(enabled = false)).reason)
        assertEquals(PrivateRoomReason.RECOVERY_UNCONFIRMED, check(ready.copy(enabled = false, recoveryConfirmed = false)).reason)
        assertEquals(PrivateRoomReason.RECOVERY_REQUIRED, check(PrivateVaultStatus(PrivateVaultAvailability.RECOVERY_REQUIRED)).reason)
        assertEquals(PrivateRoomReason.STORAGE_UNAVAILABLE, check(PrivateVaultStatus(PrivateVaultAvailability.UNREADABLE)).reason)
    }

    @Test fun distinguishesLoadingMissingOwnerAndUnconfiguredModel() {
        assertEquals(PrivateRoomReason.SETTINGS_LOADING, check(snapshot = settings().copy(init = true)).reason)
        assertEquals(PrivateRoomReason.ASSISTANT_UNAVAILABLE,
            privateRoomPreflight(settings(), "invalid-owner", null, ready).reason)
        assertEquals(PrivateRoomReason.ASSISTANT_UNAVAILABLE,
            privateRoomPreflight(settings(), Uuid.random().toString(), null, ready).reason)
        assertEquals(PrivateRoomReason.MODEL_NOT_CONFIGURED, check(snapshot = settings().copy(providers = emptyList())).reason)
    }

    @Test fun removedSavedModelNeverFallsBackToWorkingChatModel() {
        val choice = PrivateRoomModelChoice(modelId = Uuid.random().toString(), binding = "0".repeat(64))
        assertEquals(PrivateRoomReason.MODEL_NOT_CONFIGURED, check(choice = choice).reason)
        assertEquals(PrivateRoomReason.CHOICE_UNREADABLE, check(choice = choice.copy(modelId = "not-an-id")).reason)
    }

    @Test fun disabledParentCannotBeBypassedByEnabledOverwrite() {
        val overridden = model.copy(providerOverwrite = provider.copy(models = emptyList()))
        assertEquals(PrivateRoomReason.PROVIDER_DISABLED, check(snapshot = settings(provider.copy(
            enabled = false, models = listOf(overridden)))).reason)
        assertEquals(PrivateRoomReason.PROVIDER_DISABLED, check(snapshot = settings(provider.copy(
            models = listOf(model.copy(providerOverwrite = provider.copy(enabled = false)))))).reason)
    }

    @Test fun changedCredentialOriginModelOrChoiceRequiresFreshConsent() {
        val choice = PrivateRoomModelChoice(modelId = model.id.toString(), binding = privateRoomModelBinding(provider, model), directApi = true)
        assertTrue(check(choice = choice).canVisit)
        listOf(provider.copy(apiKey = "changed"), provider.copy(baseUrl = "https://other.invalid/v1"),
            provider.copy(models = listOf(model.copy(modelId = "changed")))).forEach {
            assertEquals(PrivateRoomReason.CONFIGURATION_CHANGED, check(choice = choice, snapshot = settings(it)).reason)
        }
    }

    @Test fun unsafeAddressesDoNotBecomeEligibleEvenWithExplicitDirectConsent() {
        listOf("http://remote.invalid/v1", "https://user:secret@remote.invalid/v1",
            "https://remote.invalid/v1?key=secret", "https://remote.invalid/v1#secret", "not a url").forEach { url ->
            assertEquals(PrivateRoomReason.UNSAFE_ADDRESS,
                privateRoomLocalRouteReason(provider.copy(baseUrl = url), confirmedDirectApi = true))
        }
    }

    @Test fun localCheckDoesNotPretendRemoteCapabilityOrConnectivityWasVerified() {
        val result = check()
        assertTrue(result.canVisit); assertFalse(result.networkCheckRequired)
        assertEquals(PrivateRoomReason.LOCAL_READY, result.reason)
        val unknown = check(snapshot = settings(provider.copy(baseUrl = "https://proxy.invalid/v1")))
        assertTrue(unknown.canVisit); assertTrue(unknown.networkCheckRequired)
        assertEquals(PrivateRoomReason.NETWORK_CHECK_REQUIRED, unknown.reason)
    }

    @Test fun unsupportedPathsAndServiceAccountDoNotSilentlyBecomeOrdinaryApi() {
        assertEquals(PrivateRoomReason.ROUTE_UNSUPPORTED,
            privateRoomLocalRouteReason(provider.copy(chatCompletionsPath = "/other"), true))
        assertEquals(PrivateRoomReason.ROUTE_UNSUPPORTED,
            privateRoomLocalRouteReason(provider.copy(baseUrl = "https://proxy.invalid/unrecognised"), false))
        assertEquals(PrivateRoomReason.ROUTE_UNSUPPORTED,
            privateRoomLocalRouteReason(ProviderSetting.Google(vertexAI = true), true))
    }

    @Test fun detailedPublicReceiptContainsOnlyFixedCodesAndNoConfigurationOrContent() = runTest {
        PrivateRoomReason.entries.forEach { reason ->
            val result = PrivateRoomVisitResult(PrivateRoomOutcome.UNAVAILABLE, reason, mayHaveSavedChanges = true)
            val output = privateRoomDetailedVisitTool { result }.execute(JsonObject(emptyMap()))
            val text = (output.single() as UIMessagePart.Text).text
            assertFalse(text.contains(provider.apiKey)); assertFalse(text.contains(provider.baseUrl))
            assertFalse(text.contains(model.modelId)); assertFalse(text.contains(owner.id.toString()))
            val receipt = Json.parseToJsonElement(text).jsonObject
            assertEquals(reason.code, receipt.getValue("reason_code").jsonPrimitive.content)
            assertEquals("false", receipt.getValue("private_content_returned").jsonPrimitive.content)
            assertEquals("true", receipt.getValue("may_have_saved_changes").jsonPrimitive.content)
        }
    }

    @Test fun publicBridgeRefusesBodyArgumentsBeforeEntering() = runTest {
        var calls = 0
        val tool = privateRoomDetailedVisitTool { calls++; PrivateRoomVisitResult(PrivateRoomOutcome.COMPLETED, PrivateRoomReason.COMPLETED) }
        try { tool.execute(Json.parseToJsonElement("{\"body\":\"synthetic\"}")); fail("no content arguments") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, calls)
    }
}
