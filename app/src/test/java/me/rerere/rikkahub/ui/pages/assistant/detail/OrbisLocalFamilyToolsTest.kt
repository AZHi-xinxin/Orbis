package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.withNativeToolSelection
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolFamily
import org.junit.Assert.*
import org.junit.Test

class OrbisLocalFamilyToolsTest {
    @Test fun threeExistingEntrypointsMapToActualLocalFactories() {
        assertEquals(listOf(LocalToolOption.LocalGarden), localToolsForFamily(CloudToolFamily.ORBIS))
        assertEquals(listOf(LocalToolOption.LocalReading), localToolsForFamily(CloudToolFamily.READING))
        assertEquals(listOf(LocalToolOption.LocalSoup), localToolsForFamily(CloudToolFamily.TURTLESOUP))
    }

    @Test fun emptyLocalSelectionCanBeEnabledWithoutAnyCloudCredentialOrProvider() {
        val target = Assistant(localTools = emptyList())
        val other = Assistant(localTools = emptyList())
        var settings = Settings(providers = emptyList(), assistants = listOf(target, other), assistantId = other.id)
        CloudToolFamily.entries.forEach { family ->
            settings = settings.withNativeToolSelection(target.id, localToolsForFamily(family).single(), true)
        }
        assertEquals(setOf(LocalToolOption.LocalGarden, LocalToolOption.LocalReading, LocalToolOption.LocalSoup), settings.assistants[0].localTools.toSet())
        assertEquals(other, settings.assistants[1])
        assertEquals(target.cloudTools, settings.assistants[0].cloudTools)
        assertTrue(settings.providers.isEmpty())
    }
}
