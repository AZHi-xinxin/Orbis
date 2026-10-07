package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.spaces.OrbisCompanionSpacesStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CompanionSpaceToolsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store() = OrbisCompanionSpacesStore(File(temp.root, "orbis-companion-spaces/1c66025d-e84f-4c0e-9f08-b2ca044be81a"))
    private fun call(module: String, raw: String) = executeCompanionSpaceTool(store(), module, Json.parseToJsonElement(raw))

    @Test fun secretToolCannotModifyHumanPromptOrImpersonateHuman() {
        val item = store().saveHumanStory("番外", "人类原文", "letter")
        assertThrows(IllegalArgumentException::class.java) { call("secret_base", """{"action":"write","id":"${item.id}","expected_revision":1,"text":"正文","prompt":"覆盖"}""") }
        assertThrows(IllegalStateException::class.java) { call("secret_base", """{"action":"create","prompt":"伪造人类"}""") }
        call("secret_base", """{"action":"write","id":"${item.id}","expected_revision":1,"text":"正文"}""")
        assertEquals("人类原文", store().snapshot().stories.single().prompt)
        assertEquals("正文", store().snapshot().stories.single().body)
    }
    @Test fun publicPostActorIsAlwaysAiAndCannotDeleteHumanPost() {
        call("shared_space", """{"action":"publish","text":"AI动态"}""")
        assertEquals("ai", store().snapshot().posts.single().actor)
        assertThrows(IllegalArgumentException::class.java) { call("shared_space", """{"action":"publish","text":"x","actor":"human"}""") }
        val human = store().publishPost("human", "人类动态")
        assertThrows(IllegalArgumentException::class.java) { call("shared_space", """{"action":"delete","id":"${human.id}"}""") }
    }
    @Test fun readPhotoReturnsActualBoundedImageNotAnUnusablePrivatePath() {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3)
        val media = store().addMedia(bytes, "image/jpeg"); val photo = store().addPhoto(media.id)
        val parts = call("photo_wall", """{"action":"read","id":"${photo.id}"}""")
        assertTrue((parts.last() as UIMessagePart.Image).url.startsWith("data:image/jpeg;base64,"))
        assertThrows(IllegalArgumentException::class.java) { call("photo_wall", """{"action":"read","path":"/private/file"}""") }
    }
    @Test fun unknownFieldsAndTypedNumbersDoNotSilentlyCoerce() {
        assertThrows(IllegalArgumentException::class.java) { call("photo_wall", """{"action":"list","assistant_id":"other"}""") }
        assertThrows(IllegalStateException::class.java) { call("photo_wall", """{"action":"list","offset":"0"}""") }
        assertThrows(IllegalStateException::class.java) { call("photo_wall", """{"action":"list","offset":1.5}""") }
    }
    @Test fun longStoryAndLongSocialPostArePagedInsteadOfFloodingContext() {
        val story = store().saveHumanStory("长番外", "🌙".repeat(8000), "letter")
        val part = call("secret_base", """{"action":"read","id":"${story.id}"}""").single() as UIMessagePart.Text
        val first = Json.parseToJsonElement(part.text).jsonObject
        assertTrue(first.getValue("content").jsonPrimitive.content.length <= 12000)
        assertNotEquals(JsonNull, first["next_offset"])
        store().publishPost("human", "x".repeat(19000))
        val listing = call("shared_space", """{"action":"list"}""").single() as UIMessagePart.Text
        assertTrue(listing.text.length < 1500)
    }
    @Test fun factoryOnlyExposesThreeExplicitlyScopedTools() {
        assertEquals(setOf("orbis_secret_base", "orbis_shared_space", "orbis_photo_wall"), createOrbisCompanionSpaceTools { store() }.map { it.name }.toSet())
    }
}
