package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class OrbisNativeToolSelectionTest {
    private val catalog = listOf("read_memory", "save_memory", "get_device_info", "send_notification", "get_alarms")

    @Test fun defaultOptionsDoNotEnableDeviceControlOrMemory() {
        assertTrue(selectedCompanionToolNames(listOf(LocalToolOption.TimeInfo), catalog).isEmpty())
        assertFalse(LocalToolOption.BluetoothToy in Assistant().localTools)
    }

    @Test fun offlineMemoryCanBeSelectedWithoutCompanionRuntime() {
        assertEquals(setOf("read_memory", "save_memory"),
            selectedCompanionToolNames(listOf(LocalToolOption.CompanionMemory), catalog))
    }

    @Test fun companionDoesNotSilentlyGrantMemoryAccess() {
        assertEquals(setOf("get_device_info", "send_notification", "get_alarms"),
            selectedCompanionToolNames(listOf(LocalToolOption.CompanionDevice), catalog))
        assertEquals(catalog.toSet(), selectedCompanionToolNames(
            listOf(LocalToolOption.CompanionDevice, LocalToolOption.CompanionMemory), catalog))
    }

    @Test fun readOnlyAlarmsBelongToExplicitDeviceSelectionNotOfflineMemory() {
        assertFalse("get_alarms" in selectedCompanionToolNames(listOf(LocalToolOption.CompanionMemory), catalog))
        assertTrue("get_alarms" in selectedCompanionToolNames(listOf(LocalToolOption.CompanionDevice), catalog))
        assertTrue(selectedCompanionToolNames(emptyList(), catalog).isEmpty())
    }

    @Test fun toggleOnlyChangesItsCapturedAssistantAndPreservesOtherSelections() {
        val first = Assistant(name = "A", localTools = listOf(LocalToolOption.TimeInfo))
        val second = Assistant(name = "B", localTools = listOf(LocalToolOption.Calendar))
        val before = Settings(assistants = listOf(first, second), assistantId = second.id)
        val after = before.withNativeToolSelection(first.id, LocalToolOption.CompanionMemory, true)
        assertEquals(second, after.assistants[1])
        assertEquals(second.id, after.assistantId)
        assertEquals(listOf(LocalToolOption.TimeInfo, LocalToolOption.CompanionMemory), after.assistants[0].localTools)
        assertEquals(after, after.withNativeToolSelection(first.id, LocalToolOption.CompanionMemory, true))
        assertEquals(before, after.withNativeToolSelection(first.id, LocalToolOption.CompanionMemory, false))
    }

    @Test fun missingOrLoadingAssistantCannotBeAccidentallyCreatedOrGranted() {
        val missing = Assistant().id
        assertThrows(IllegalArgumentException::class.java) {
            Settings(assistants = emptyList()).withNativeToolSelection(missing, LocalToolOption.BluetoothToy, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Settings.dummy().withNativeToolSelection(missing, LocalToolOption.CompanionDevice, true)
        }
    }

    @Test fun newSelectionsHaveStableSerializationAndUnknownFieldsRemainUnrelated() {
        listOf(LocalToolOption.CompanionDevice, LocalToolOption.CompanionMemory, LocalToolOption.BluetoothToy).forEach { option ->
            val encoded = Json.encodeToString<LocalToolOption>(option)
            assertEquals(option, Json.decodeFromString<LocalToolOption>(encoded))
        }
    }
}
