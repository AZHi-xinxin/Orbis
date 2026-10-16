package me.rerere.rikkahub.data.orbis.character

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class CharacterPresentationTest {
    @get:Rule val folder = TemporaryFolder()
    private val owner = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val bytes = "synthetic-not-pixels".toByteArray()
    private val png = "synthetic-portrait-pixels".toByteArray()
    private fun asset(id: String, emotion: String = "calm", kind: CharacterAssetKind = CharacterAssetKind.EXPRESSION) =
        CharacterAsset(id, kind, emotion, characterSha(bytes), 100, 100, emotion,
            faceAnchor = CharacterFaceAnchor("synthetic", .2f, .3f, .6f, .25f))
    private fun presentation() = CharacterPresentation(assets = listOf(asset("calm"), asset("smile", "smile"), asset("angry", "angry"), asset("pose", kind = CharacterAssetKind.POSE)))
    private fun draft() = CharacterDraft(CharacterPack(sha256 = characterSha(png), width = 100, height = 100), CharacterInteractions(), png,
        presentation(), mapOf(characterSha(bytes) to bytes))

    @Test fun emptyWidgetNeverUsesRoomPortrait() { assertNull(CharacterPresentation().widgetAsset(0)) }
    @Test fun displayFramingRoundTripsButDoesNotChangeImageBytes() {
        val a = asset("fold", kind = CharacterAssetKind.POSE).copy(trimBodyAtCardEdge = true)
        val d = draft().copy(presentation = CharacterPresentation(assets = listOf(a)))
        val out = ByteArrayOutputStream(); CharacterArchive.write(out, d, false)
        val read = CharacterArchive.read(out.toByteArray().inputStream())
        assertTrue(read.presentation!!.assets.single().trimBodyAtCardEdge)
        assertArrayEquals(bytes, read.assets[a.sha256])
        assertFalse(asset("legacy").trimBodyAtCardEdge)
    }
    @Test fun policyFiltersCurrentAndCycling() {
        val p = presentation().copy(widgetAssetId = "angry", expressionPolicy = CharacterExpressionPolicy(listOf("calm", "smile"), 1000))
        assertEquals("angry", p.widgetAsset(100)?.id) // Body stays; disallowed face is replaced.
        assertEquals("calm", p.widgetLayers(100).expression?.id)
        assertEquals("smile", p.cycle(CharacterAssetKind.EXPRESSION, 100).widgetExpressionId)
        assertEquals("angry", p.cycle(CharacterAssetKind.EXPRESSION, 100).widgetAssetId)
        assertFalse(p.allowedAssets(100).any { it.emotion == "angry" })
    }
    @Test fun expiryRestoresAvailableSet() { assertEquals(4, presentation().copy(expressionPolicy = CharacterExpressionPolicy(listOf("calm"), 1000)).allowedAssets(1000).size) }
    @Test fun poseAlsoHonorsPolicy() { assertTrue(presentation().copy(expressionPolicy = CharacterExpressionPolicy(listOf("smile"), 1000)).allowedAssets(10, CharacterAssetKind.POSE).isEmpty()) }
    @Test fun expressionAndPoseAreIndependentAndPolicyUsesVisibleFace() {
        val p = presentation().copy(widgetAssetId = "pose").selectSprite("smile", 10)
        assertEquals("pose", p.widgetAssetId)
        assertEquals("smile", p.widgetLayers(10).expression?.id)
        val calm = p.copy(expressionPolicy = CharacterExpressionPolicy(listOf("calm"), 100))
        assertEquals("pose", calm.widgetAsset(20)?.id)
        assertNotEquals("smile", calm.widgetLayers(20).expression?.id)
        assertEquals("smile", calm.widgetLayers(100).expression?.id)
    }
    @Test fun unknownAlignmentNeverChangesTheBodyToFakeExpressionSwitch() {
        val p = presentation().let { it.copy(assets = it.assets.map { a -> a.copy(faceAnchor = null) }, widgetAssetId = "pose") }
        assertEquals(p, p.cycle(CharacterAssetKind.EXPRESSION, 0))
        assertThrows(IllegalArgumentException::class.java) { p.selectSprite("smile", 0) }
    }
    @Test fun alignmentMustBeValidAndSurvivesArchiveRoundTrip() {
        val d = draft(); val out = ByteArrayOutputStream(); CharacterArchive.write(out, d, false)
        assertEquals(d.presentation!!.assets.first().faceAnchor, CharacterArchive.read(out.toByteArray().inputStream()).presentation!!.assets.first().faceAnchor)
        val a = asset("bad").copy(faceAnchor = CharacterFaceAnchor("group", Float.NaN, .1f, .2f, .2f))
        assertThrows(IllegalArgumentException::class.java) { validatePresentation(CharacterPresentation(assets = listOf(a))) }
    }
    @Test fun emptyAllowedRejects() { assertThrows(IllegalArgumentException::class.java) { validatePresentation(presentation().copy(expressionPolicy = CharacterExpressionPolicy(emptyList(), 1000))) } }
    @Test fun unknownEmotionRejects() { assertThrows(IllegalArgumentException::class.java) { validatePresentation(presentation().copy(expressionPolicy = CharacterExpressionPolicy(listOf("unexpected"), 1000))) } }
    @Test fun traversalIdRejects() { assertThrows(IllegalArgumentException::class.java) { validatePresentation(CharacterPresentation(assets = listOf(asset("../bad")))) } }
    @Test fun duplicatedAssetIdRejects() { assertThrows(IllegalArgumentException::class.java) { validatePresentation(CharacterPresentation(assets = listOf(asset("same"), asset("same")))) } }
    @Test fun unknownSelectedIdRejects() { assertThrows(IllegalArgumentException::class.java) { validatePresentation(presentation().copy(widgetAssetId = "absent")) } }
    @Test fun fractionalDaylightIsContinuous() {
        assertEquals(1f, characterNightFraction(6, 0, RoomMode.AUTO), .0001f)
        assertEquals(.5f, characterNightFraction(7, 0, RoomMode.AUTO), .0001f)
        assertEquals(0f, characterNightFraction(8, 0, RoomMode.AUTO), .0001f)
        assertEquals(.5f, characterNightFraction(18, 0, RoomMode.AUTO), .0001f)
        assertEquals(1f, characterNightFraction(19, 0, RoomMode.AUTO), .0001f)
        assertEquals(0f, characterNightFraction(23, 59, RoomMode.DAY), .0001f)
    }
    @Test fun expandedArchiveRoundTripsAndExcludesPrivateText() {
        val d = draft().copy(presentation = presentation().copy(greeting = "private", expressionPolicy = CharacterExpressionPolicy(listOf("calm"), 1000)))
        val out = ByteArrayOutputStream(); CharacterArchive.write(out, d, false)
        val read = CharacterArchive.read(out.toByteArray().inputStream())
        assertEquals(4, read.presentation!!.assets.size); assertEquals("", read.presentation.greeting); assertNull(read.presentation.expressionPolicy)
        assertArrayEquals(bytes, read.assets[characterSha(bytes)])
    }
    @Test fun missingAssetRejectsBeforeReplacingProfile() {
        val store = CharacterStore(folder.root, owner)
        val before = store.install(draft(), 0)
        val bad = asset("missing").copy(sha256 = "f".repeat(64))
        assertThrows(Exception::class.java) { store.install(draft().copy(presentation = CharacterPresentation(assets = listOf(bad)), assets = emptyMap()), 1) }
        assertEquals(before, store.read())
    }
    @Test fun portraitReplacementRetainsGallery() {
        val store = CharacterStore(folder.root, owner); store.install(draft(), 0)
        val d = draft(); store.install(CharacterDraft(d.pack, d.interactions, d.png), 1)
        assertEquals(4, store.read().presentation.assets.size)
        assertArrayEquals(bytes, store.assetBytes(store.read().presentation.assets.first()))
    }
    @Test fun assetTamperingCannotBeSaved() {
        val store = CharacterStore(folder.root, owner); val p = store.install(draft(), 0)
        java.io.File(folder.root, "orbis-characters/$owner/${characterSha(bytes)}.png").writeText("tampered")
        assertThrows(IllegalArgumentException::class.java) { store.save(p, 1) }
    }
    @Test fun stagePreservesAspectAndMapsCenterToSource() {
        val frame = characterStageFrame(400f, 800f, 1024, 1536)
        assertEquals(1024f / 1536f, frame.width / frame.height, .0001f)
        assertTrue(frame.top >= 0f)
        assertEquals(.5f, frame.point(200f, 400f)!!.x, .0001f)
        assertEquals(.5f, frame.point(200f, 400f)!!.y, .0001f)
        assertEquals(533.3333f, frame.width, .01f)
    }
    @Test fun stageZeroAndLandscapeStayValid() {
        assertNull(characterStageFrame(0f, 0f, 100, 100).point(0f, 0f))
        val frame = characterStageFrame(800f, 400f, 1024, 1536)
        assertEquals(400f, frame.height, .01f)
        assertEquals(0f, frame.top, .01f)
    }
    @Test fun maximumChineseDialogueRoundTripsWithoutUnreadableProfile() {
        val store = CharacterStore(folder.root, owner)
        val regions = (1..12).map { CharacterRegion("part_$it", "部位", .1f, .1f, .1f, .1f) }
        val d = draft().copy(pack = draft().pack.copy(regions = regions),
            interactions = CharacterInteractions(lines = regions.associate { it.id to List(10) { "昕".repeat(240) } }))
        val saved = store.install(d, 0)
        assertEquals(saved, store.read())
        val out = ByteArrayOutputStream(); store.export(out, true)
        assertEquals(d.interactions, CharacterArchive.read(out.toByteArray().inputStream()).interactions)
    }
}
