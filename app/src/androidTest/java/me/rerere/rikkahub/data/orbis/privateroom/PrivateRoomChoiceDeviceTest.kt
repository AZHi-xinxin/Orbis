package me.rerere.rikkahub.data.orbis.privateroom

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class PrivateRoomChoiceDeviceTest {
    @Test fun explicitConsentPersistsOnAndroidAndChangesRequireFreshConfirmation() {
        val runner = InstrumentationRegistry.getInstrumentation()
        check(runner is IsolatedGenerationLoopRunner)
        check(runner.targetContext.applicationContext.javaClass == Application::class.java)
        val parent = runner.targetContext.noBackupFilesDir.canonicalFile
        val fixture = Files.createTempDirectory(parent.toPath(), "private-choice-fixture-").toFile()
        try {
            val owner = UUID.randomUUID().toString()
            val directory = fixture.resolve("routes")
            val store = PrivateRoomModelChoiceStore(directory, owner, parent)
            val model = Model(modelId = "synthetic-room")
            val provider = ProviderSetting.OpenAI(baseUrl = "https://synthetic.invalid/v1", apiKey = "not-a-real-key")
            val choice = PrivateRoomModelChoice(modelId = model.id.toString(), binding = privateRoomModelBinding(provider, model), directApi = true)
            assertNull(store.read()); assertFalse(directory.exists())
            store.save(choice)
            val reopened = PrivateRoomModelChoiceStore(directory, owner, parent).read()!!
            assertEquals(choice, reopened)
            assertTrue(reopened.matches(provider, model))
            assertFalse(reopened.matches(provider.copy(baseUrl = "https://changed.invalid/v1"), model))
            assertFalse(directory.resolve("$owner.json").readText().contains("not-a-real-key"))
            store.clear(); assertNull(store.read())
            val link = fixture.resolve("linked-routes")
            android.system.Os.symlink(directory.absolutePath, link.absolutePath)
            try {
                try { PrivateRoomModelChoiceStore(link, owner, parent).save(choice); fail("app-owned symlink rejected") }
                catch (_: IllegalStateException) { }
                assertNull(store.read())
            } finally { Files.delete(link.toPath()) }
        } finally {
            check(fixture.canonicalFile.parentFile == parent && fixture.name.startsWith("private-choice-fixture-"))
            fixture.deleteRecursively()
        }
    }
}
