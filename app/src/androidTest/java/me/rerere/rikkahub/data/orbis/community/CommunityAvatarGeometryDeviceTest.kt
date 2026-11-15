package me.rerere.rikkahub.data.orbis.community

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Bundled synthetic outfits only; never reads or changes a person's wardrobe. */
class CommunityAvatarGeometryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun isolated() { check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner) }
    private fun only(vararg pairs: Pair<String, String>) = buildJsonObject {
        for (key in listOf("skin", "tops", "pants", "skirts", "shoes", "hair", "faces")) put(key, "")
        for ((key, value) in pairs) put(key, value)
    }
    @Test fun shoesRemainUnderEveryOpaqueTrouserAndSkirtHem() {
        var overlap = 0
        for (category in listOf("pants", "skirts")) for (n in 1..10) {
            val base = only(category to "$category-${n.toString().padStart(2, '0')}")
            val hem = CommunityAvatarRenderer.render(context, base)
            for (shoe in 1..10) {
                val dressed = CommunityAvatarRenderer.render(context, JsonObject(base + ("shoes" to JsonPrimitive("shoes-${shoe.toString().padStart(2, '0')}"))))
                for (y in 113*4 until 119*4) for (x in 32*4 until 64*4) {
                    if (Color.alpha(hem.getPixel(x,y)) == 255) {
                        assertEquals("$category-$n / shoe-$shoe at $x,$y", hem.getPixel(x,y), dressed.getPixel(x,y)); overlap++
                    }
                }
            }
        }
        assertTrue("Real overlapping opaque hem pixels were checked", overlap > 1000)
        val bare = CommunityAvatarRenderer.render(context, only("skin" to "skin-02"))
        val shod = CommunityAvatarRenderer.render(context, only("skin" to "skin-02", "shoes" to "shoes-08"))
        assertFalse("Shoes are still drawn over bare feet", bare.sameAs(shod))
    }
    @Test fun rearPonytailLeavesTheNeckInFront() {
        val base = only("skin" to "skin-02")
        val bare = CommunityAvatarRenderer.render(context, base)
        val hair = CommunityAvatarRenderer.render(context, JsonObject(base + ("hair" to JsonPrimitive("hair-11"))))
        for (y in 61*4 until 64*4) for (x in 45*4 until 51*4) {
            val before=bare.getPixel(x,y);val after=hair.getPixel(x,y)
            // Generated source interiors include alpha 254: a rear layer may
            // contribute one colour step, but must not paint over the skin.
            assertTrue(Color.alpha(before)>=253)
            for (channel in listOf(Color::red,Color::green,Color::blue)) {
                assertTrue("Rear tail must not become a beard at $x,$y",kotlin.math.abs(channel(before)-channel(after))<=3)
            }
        }
    }
    @Test fun hatAndHeadpieceDepthDoesNotDependOnSelectionOrder() {
        fun look(ids: List<String>) = buildJsonObject { put("accessories", JsonArray(ids.map(::JsonPrimitive))) }
        val ids = listOf("accessories-08", "accessories-07")
        assertTrue(CommunityAvatarRenderer.render(context,look(ids)).sameAs(CommunityAvatarRenderer.render(context,look(ids.reversed()))))
    }
    @Test fun everySkinHairPairAndAllCatalogThumbnailsRender() {
        val root = File(context.filesDir,"avatar-geometry-review").apply {mkdirs()}
        val paint = Paint().apply {color=Color.BLACK;textSize=15f}
        for (page in 0..3) {
            val sheet=Bitmap.createBitmap(960,1400,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(sheet);canvas.drawColor(0xffe5ebdd.toInt())
            for (row in 0..4) for (skin in 1..5) {
                val hair=page*5+row+1
                val config=buildJsonObject {put("skin","skin-0$skin");put("hair","hair-${hair.toString().padStart(2,'0')}")}
                val rendered=CommunityAvatarRenderer.render(context,config)
                canvas.save();canvas.translate((skin-1)*192f,row*280f);canvas.drawText("hair-$hair / skin-$skin",4f,18f,paint)
                canvas.translate(0f,22f);canvas.scale(.5f,.5f);canvas.drawBitmap(rendered,0f,0f,null);canvas.restore()
                assertTrue(rendered.getPixel(48*4,80*4) != Color.TRANSPARENT)
            }
            File(root,"hair-skins-$page.png").outputStream().use {assertTrue(sheet.compress(Bitmap.CompressFormat.PNG,100,it))};sheet.recycle()
        }
        val items=CommunityAvatarRenderer.catalog(context)["items"]!!.jsonArray
        assertEquals(124,items.size)
        for (item in items) {
            val id=item.jsonObject["id"]!!.jsonPrimitive.content
            val thumb=CommunityAvatarRenderer.thumbnail(context,id)
            val pixels=IntArray(160*160);thumb.getPixels(pixels,0,160,0,0,160,160)
            assertTrue("Empty thumbnail $id",pixels.any {Color.alpha(it)>0})
        }
    }
}
