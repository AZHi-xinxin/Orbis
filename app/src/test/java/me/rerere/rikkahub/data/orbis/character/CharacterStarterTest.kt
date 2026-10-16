package me.rerere.rikkahub.data.orbis.character

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class CharacterStarterTest {
    @get:Rule val folder = TemporaryFolder()
    private val owner = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val png = "synthetic-starter-art-not-a-real-png".toByteArray()
    private fun draft(): CharacterDraft {
        val hash = characterSha(png)
        val asset = CharacterAsset("pose-neutral", CharacterAssetKind.POSE, "neutral", hash, 100, 100,
            colorModel = BuiltinCharacter.COLOR_MODEL)
        return CharacterDraft(CharacterPack(sha256 = hash, width = 100, height = 100),
            CharacterInteractions(), png, CharacterPresentation(assets = listOf(asset)), mapOf(hash to png))
    }
    @Test fun tenRequestedPalettesAreIndependent() {
        assertEquals(listOf("deepseek", "gpt", "claude", "gemini", "grok", "doubao", "kimi", "minimax", "glm", "mimo"),
            characterPalettes.map { it.id })
        characterPalettes.forEach { hair -> characterPalettes.forEach { eyes ->
            val c = CharacterColors(hair.id, eyes.id)
            validateCharacterColors(c)
            assertEquals(eyes, characterEyePalette(c, "calm"))
            assertEquals(hair.id, c.hair)
        } }
        assertNotEquals(characterPalettes.first { it.id == "gemini" }.rgb, characterPalettes.first { it.id == "gemini" }.endRgb)
    }
    @Test fun redMoodNeverOverwritesNormalChoice() {
        val c = CharacterColors("gpt", "mimo")
        listOf("angry", "excited").forEach { assertEquals(0x921C35, characterEyePalette(c, it).rgb) }
        assertEquals("mimo", characterEyePalette(c, "smile").id)
        assertEquals(CharacterColors("gpt", "mimo"), c)
        assertTrue("excited" in characterEmotions)
    }
    @Test fun invalidPaletteRejectedAndOldProfileDefaultsRemainCompatible() {
        assertThrows(IllegalArgumentException::class.java) { validateCharacterColors(CharacterColors("unknown")) }
        assertEquals(CharacterColors(), CharacterStore.json.decodeFromString<CharacterPresentation>("{}").colors)
        assertNull(draft().presentation!!.assets.single().copy(colorModel = null).colorModel)
    }
    @Test fun paletteRoundTripDoesNotChangeSourceArt() {
        val d = draft().copy(presentation = draft().presentation!!.copy(colors = CharacterColors("gemini", "mimo")))
        val out = ByteArrayOutputStream()
        CharacterArchive.write(out, d, false)
        val restored = CharacterArchive.read(out.toByteArray().inputStream())
        assertEquals(d.presentation!!.colors, restored.presentation!!.colors)
        assertEquals(BuiltinCharacter.COLOR_MODEL, restored.presentation.assets.single().colorModel)
        assertArrayEquals(png, restored.png)
    }
    @Test fun freshOwnerGetsStarterOnlyOnce() {
        val store = CharacterStore(folder.root, owner)
        val first = store.installDefaultIfEmpty(::draft)
        assertEquals(1L, first.revision)
        assertEquals(first, store.installDefaultIfEmpty { error("must not load again") })
        assertArrayEquals(png, store.assetBytes(first.presentation.assets.single()))
    }
    @Test fun existingPrivatePortraitIsNeverReplacedEvenWithoutGallery() {
        val store = CharacterStore(folder.root, owner)
        val private = store.install(draft().copy(presentation = CharacterPresentation(), assets = emptyMap()), 0)
        assertEquals(private, store.installDefaultIfEmpty { error("private owner must not load starter") })
    }
    @Test fun existingGalleryWithoutPortraitIsNeverReplaced() {
        val store = CharacterStore(folder.root, owner)
        val p = store.install(draft(), 0)
        val galleryOnly = store.save(p.copy(pack = null), p.revision)
        assertEquals(galleryOnly, store.installDefaultIfEmpty { error("existing gallery must remain") })
    }
    @Test fun blankOwnerPreferencesSurviveStarterInstallation() {
        val store = CharacterStore(folder.root, owner)
        val p = store.save(CharacterProfile(room = RoomMode.NIGHT, presentation =
            CharacterPresentation(card = "peach-cream", scene = "study", greeting = "test", colors = CharacterColors("mimo", "gpt"))), 0)
        val installed = store.installDefaultIfEmpty(::draft)
        assertEquals(p.room, installed.room)
        assertEquals(p.presentation.card, installed.presentation.card)
        assertEquals(p.presentation.scene, installed.presentation.scene)
        assertEquals(p.presentation.greeting, installed.presentation.greeting)
        assertEquals(p.presentation.colors, installed.presentation.colors)
    }
    @Test fun simultaneousWidgetAndSettingsInstallAtMostOnce() {
        val count = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map { pool.submit<CharacterProfile> {
                CharacterStore(folder.root, owner).installDefaultIfEmpty { count.incrementAndGet(); draft() }
            } }
            assertEquals(jobs[0].get(), jobs[1].get())
            assertEquals(1, count.get())
        } finally { pool.shutdownNow() }
    }
    @Test fun failedDefaultLoadLeavesMetadataUntouched() {
        val store = CharacterStore(folder.root, owner)
        assertThrows(IllegalStateException::class.java) { store.installDefaultIfEmpty { error("missing art") } }
        assertEquals(CharacterProfile(), store.read())
    }
    @Test fun colorRegistrationExcludesUniformAndHands() {
        assertEquals(2, characterColorRegion(.411f, .374f))
        assertEquals(2, characterColorRegion(.628f, .362f))
        assertEquals(1, characterColorRegion(.5f, .2f))
        assertEquals(0, characterColorRegion(.5f, .54f))
        assertEquals(0, characterColorRegion(.67f, .66f))
        assertEquals(0, characterColorRegion(.5f, .88f))
        assertEquals(0, characterColorRegion(Float.NaN, .2f))
    }
}

