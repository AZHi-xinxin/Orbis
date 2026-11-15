package me.rerere.rikkahub.data.orbis.tabletop

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.health.recognizeHealthScreenshotLayout
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit local-only document extraction probe. No private fixture is packaged in the APK. */
class TabletopSourceOcrDeviceTest {
    @Test fun optionalLocalSourcePages()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tabletopOcr")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="org.orbis.agent.bodylab") { "Only the isolated test package may read these fixtures" }
        val root=File(context.getExternalFilesDir(null),"tabletop-source-fixtures")
        val pages=root.listFiles().orEmpty().filter { it.extension in setOf("png","jpg") }.sortedBy { it.name }
        assertTrue("explicit fixture directory must contain pages",pages.isNotEmpty())
        pages.forEach { page ->
            val result=File(root,page.name+".ocr.json")
            if(!result.exists()) {
                val text=recognizeHealthScreenshotLayout(context,Uri.fromFile(page))
                result.writeText(buildJsonObject {
                    put("file",page.name);put("text",text.text)
                    put("lines",buildJsonArray { text.lines.forEach { line->add(buildJsonObject {
                        put("text",line.text);put("left",line.left);put("top",line.top);put("right",line.right);put("bottom",line.bottom)
                    }) } })
                }.toString())
            }
        }
    }
}
