package me.rerere.rikkahub.data.orbis.privateroom

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class PrivateRoomModelChoiceTest {
    private val owner = UUID.randomUUID().toString()
    private val model = Model(modelId = "synthetic-room-model")
    private val provider = ProviderSetting.OpenAI(baseUrl = "https://synthetic.invalid/v1", apiKey = "synthetic-secret")
    private fun choice() = PrivateRoomModelChoice(modelId = model.id.toString(),
        binding = privateRoomModelBinding(provider, model), directApi = true)

    @Test fun exactBindingInvalidatesOriginKeyModelAndModeChanges() {
        val selected = choice()
        assertTrue(selected.matches(provider, model))
        assertFalse(selected.matches(provider.copy(baseUrl = "https://elsewhere.invalid/v1"), model))
        assertFalse(selected.matches(provider.copy(apiKey = "changed"), model))
        assertFalse(selected.matches(provider.copy(useResponseApi = true), model))
        assertFalse(selected.matches(provider, model.copy(modelId = "different")))
    }
    @Test fun readDoesNotCreateAndSavedConsentContainsNoKey() {
        val root = Files.createTempDirectory("private-choice").toFile()
        try {
            val directory = root.resolve("choices")
            val store = PrivateRoomModelChoiceStore(directory, owner, root)
            assertNull(store.read()); assertFalse(directory.exists())
            store.save(choice()); assertEquals(choice(), store.read())
            val text = directory.resolve("$owner.json").readText()
            assertFalse(text.contains("synthetic-secret")); assertFalse(text.contains("synthetic.invalid"))
            store.clear(); assertNull(store.read())
        } finally { root.deleteRecursively() }
    }
    @Test fun badSavedChoiceFailsClosedNotSilentFallback() {
        val root = Files.createTempDirectory("private-choice-bad").toFile()
        try {
            root.resolve("$owner.json").writeText("{}")
            try { PrivateRoomModelChoiceStore(root, owner, root).read(); fail("reject") } catch (_: Exception) { }
            root.resolve("$owner.json").writeText("x".repeat(2049))
            try { PrivateRoomModelChoiceStore(root, owner, root).read(); fail("reject") } catch (_: Exception) { }
        } finally { root.deleteRecursively() }
    }
    @Test fun unrelatedModelListChangeDoesNotInvalidateSameConnection() {
        assertTrue(choice().matches(provider.copy(models = listOf(Model(modelId = "other"))), model))
    }
    @Test fun checkedConsentCannotAuthorizeAnOriginChangedBeforeSave() {
        val checked = choice()
        val edited = provider.copy(baseUrl = "https://changed.invalid/v1")
        val onSave = PrivateRoomModelChoice(modelId = model.id.toString(),
            binding = privateRoomModelBinding(edited, model), directApi = true)
        assertNotEquals(checked, onSave)
        assertNotEquals(checked, checked.copy(directApi = false))
    }
    @Test fun disabledParentProviderCannotBeBypassedByEnabledModelOverride() {
        val overridden = model.copy(providerOverwrite = provider.copy(enabled = true))
        val parent = provider.copy(enabled = false, models = listOf(overridden))
        assertNull(privateRoomEnabledProvider(overridden, listOf(parent)))
        assertNotNull(privateRoomEnabledProvider(overridden, listOf(parent.copy(enabled = true))))
    }
    @Test fun displayedOriginExcludesUserInfoPathAndQuery() {
        assertEquals("https://actual.invalid:8443", privateRoomProviderOrigin(provider.copy(
            baseUrl = "https://username:password@actual.invalid:8443/private-secret?key=secret#secret")))
    }
}
