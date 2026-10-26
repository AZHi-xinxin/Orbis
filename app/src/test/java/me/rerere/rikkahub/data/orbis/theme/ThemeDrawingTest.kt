package me.rerere.rikkahub.data.orbis.theme

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ThemeDrawingTest {
    @Test fun relativeCurvesAndRepeatedCoordinatesBecomeAbsolutePaths() {
        val p=parseThemePath("M 10,10 20 20 h10 v5 q 5 -5 10 0 c1 2 3 4 5 6 z")
        assertEquals(listOf('M','L','L','L','Q','C','Z'),p.map { it.op })
        assertEquals(listOf(35f,20f,40f,25f),p[4].values)
        assertEquals(listOf(41f,27f,43f,29f,45f,31f),p[5].values)
    }
    @Test fun malformedOrUnboundedPathsFailBeforeStorage() {
        listOf("", "L0 0", "Z", "M0", "M0 0 Q2 2", "M0 0 A1 1 0 0 0 2 2", "M0 0<script>",
            "M0 0 LNaN 2", "M0 0 L1e99 2", "M0 0 L201 0", "M0 0 l150 0 150 0", "M0 0 Z 1 2",
            "M0 0 "+"L1 1 ".repeat(161)).forEach { value ->
            assertThrows("must reject $value",Exception::class.java) { parseThemePath(value) }
        }
        assertThrows(Exception::class.java) { ThemeDrawing(outline="M0 0 L100 0 L0 100").validate() }
    }
    @Test fun keyframesInterpolateAndAlternateWithoutChangingStoredValues() {
        val m=ThemeMotion(durationMs=1000,frames=listOf(ThemeKeyframe(0f),ThemeKeyframe(.5f,x=20f,opacity=.5f),ThemeKeyframe(1f,x=40f,rotation=60f)))
        m.validate()
        assertEquals(10f,m.at(250).x,.001f);assertEquals(.75f,m.at(250).opacity,.001f)
        assertEquals(30f,m.at(750).x,.001f);assertEquals(30f,m.at(1250).x,.001f)
        assertEquals(0f,m.at(2000).x,.001f);assertEquals(0f,m.frames.first().x,0f)
        assertThrows(Exception::class.java) { m.copy(frames=listOf(ThemeKeyframe(0f),ThemeKeyframe(0f),ThemeKeyframe(1f))).validate() }
        assertThrows(Exception::class.java) { m.copy(durationMs=0).validate() }
        assertThrows(Exception::class.java) { m.copy(frames=listOf(ThemeKeyframe(0f),ThemeKeyframe(1f,scale=Float.NaN))).validate() }
    }
    @Test fun guideExampleAndIndependentTypographyRoundTrip() {
        val p=themeDrawingExample();val restored=parseTheme(themeJson.encodeToString(p))
        assertEquals(p,restored);assertEquals(500,restored.bubbles.assistant.typography?.weight)
        assertEquals(600,restored.bubbles.user.typography?.weight)
        assertTrue(restored.bubbles.assistant.drawing!!.layers.single().motion!=null)
        listOf(ThemeTypography(weight=999),ThemeTypography(letterSpacing=1f),ThemeTypography(fontSizeScale=Float.NaN)).forEach {
            assertThrows(Exception::class.java) { p.copy(typography=it).validate() }
        }
    }
    @Test fun patchChangesOneFieldOnTheSameEntryAndPreservesTheRest() = fixture { s,root ->
        val d=s.write(themeJson.encodeToString(themeDrawingExample()));val v=s.publish(d.id,d.revision)
        s.confirm(ThemeRef(v.id,v.revision))
        val args=buildJsonObject { put("action","patch");put("id",v.id);put("expected_revision",v.revision)
            put("body","{\"bubbles\":{\"assistant\":{\"opacity\":0.55}}}") }
        val response=executeThemeTool(s,args);assertEquals(v.id,response["id"]!!.jsonPrimitive.content)
        val draft=s.snapshot().entries.single().draft!!
        assertEquals(.55f,draft.bubbles.assistant.opacity,0f)
        assertEquals(themeDrawingExample().bubbles.user,draft.bubbles.user)
        assertEquals(themeDrawingExample().bubbles.assistant.drawing,draft.bubbles.assistant.drawing)
        assertEquals(1f,s.snapshot().resolve()!!.bubbles.assistant.opacity,0f)
        val next=s.publish(v.id,response["revision"]!!.jsonPrimitive.int)
        assertFalse(s.requestApply(ThemeRef(next.id,next.revision)));s.confirm(ThemeRef(next.id,next.revision))
        assertEquals(.55f,ThemeRepository(root.resolve("orbis-themes/owner")).snapshot().resolve()!!.bubbles.assistant.opacity,0f)
        s.revert();assertEquals(1f,s.snapshot().resolve()!!.bubbles.assistant.opacity,0f)
        assertEquals(1,s.snapshot().entries.size)
    }
    @Test fun stalePatchesAndPermissionChangesAreAtomicRejections() = fixture { s,_ ->
        val d=s.write(themeJson.encodeToString(themeDrawingExample()));val before=s.snapshot()
        for (patch in listOf("{\"allowAiApply\":true}","{\"bubbles\":{\"assistant\":{\"opacity\":2}}}",
            "{\"bubbles\":{\"assistant\":{\"drawing\":{\"layers\":[{\"path\":\"bad\",\"fill\":\"#FFFFFF\"}]}}}}")) {
            assertThrows(Exception::class.java){s.patch(patch,d.id,d.revision)};assertEquals(before,s.snapshot())
        }
        assertThrows(Exception::class.java){s.patch("{}",d.id,d.revision-1)}
        val changed=s.patch("{\"bubbles\":{\"assistant\":{\"drawing\":null}}}",d.id,d.revision)
        assertNull(changed.draft!!.bubbles.assistant.drawing);assertNotNull(changed.draft!!.bubbles.assistant.typography)
    }
    @Test fun manyEditsStayOneThemeAndRetainCurrentPendingAndUsableUndoVersions() = fixture { s,_ ->
        val d=s.write("{\"name\":\"同一套\"}");var v=s.publish(d.id,d.revision);val first=ThemeRef(v.id,v.revision)
        s.confirm(first)
        repeat(26) { n ->
            val e=s.patch("{\"name\":\"同一套-$n\"}",v.id,v.revision);v=s.publish(e.id,e.revision)
            if(n>10)s.confirm(ThemeRef(v.id,v.revision))
        }
        val state=s.snapshot();assertEquals(1,state.entries.size);assertEquals(20,state.entries.single().versions.size)
        assertEquals("同一套-25",state.resolve()!!.name)
        while(s.snapshot().previous.isNotEmpty()) { s.revert();s.snapshot() }
        val read=executeThemeTool(s,buildJsonObject {put("action","guide")})
        assertTrue(read.containsKey("example_body"));assertEquals(1,s.snapshot().entries.size)
    }
    private fun fixture(block:(ThemeRepository,java.io.File)->Unit) {
        val root=Files.createTempDirectory("theme-drawing").toFile()
        try {block(ThemeRepository(root.resolve("orbis-themes/owner")),root)} finally {root.deleteRecursively()}
    }
}
