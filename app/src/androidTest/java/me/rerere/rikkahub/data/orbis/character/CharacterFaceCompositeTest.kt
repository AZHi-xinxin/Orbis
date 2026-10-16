package me.rerere.rikkahub.ui.widget

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.orbis.character.CharacterFaceAnchor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterFaceCompositeTest {
    @Test fun expressionsCannotMoveBodyHandsOrPunchTransparencyHoles() {
        val base = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val face = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val anchor = CharacterFaceAnchor("same", .2f, .2f, .6f, .4f)
        val result = characterFaceComposite(base, face, anchor, anchor)
        assertEquals(Color.RED, result.getPixel(50, 40))
        for (y in 60..99) for (x in 0..99) assertEquals(base.getPixel(x,y), result.getPixel(x,y))
        assertEquals(Color.BLUE, base.getPixel(50,40))
        face.eraseColor(Color.TRANSPARENT)
        val transparent = characterFaceComposite(base, face, anchor, anchor)
        assertEquals(Color.BLUE, transparent.getPixel(50,40))
        listOf(base,face,result,transparent).forEach(Bitmap::recycle)
    }
}
