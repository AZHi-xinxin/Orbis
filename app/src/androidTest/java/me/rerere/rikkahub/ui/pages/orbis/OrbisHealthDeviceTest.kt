package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.health.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OrbisHealthDeviceTest {
    @get:Rule val compose=createShellComposeRule()
    private class Storage:HealthStorage{var text:String?=null;override fun read()=text;override fun write(value:String){text=value}}
    @Test fun humanReviewsAndCorrectsProposalBeforeTheDailyRecordChanges(){
        val inst=InstrumentationRegistry.getInstrumentation();check(inst is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java,inst.targetContext.applicationContext.javaClass)
        val repo=HealthRepository(Storage()){123}
        val date=java.time.LocalDate.now().toString()
        repo.propose(date,0,mapOf("sleepMinutes" to JsonPrimitive(588),"steps" to JsonPrimitive(293)),"screenshot","ai:fixture","合成截图测试")
        compose.setContent{MaterialTheme{HealthPageContent(repo,{})}}
        compose.onNodeWithTag("health-dashboard").performScrollToNode(hasText("检查建议"))
        assertEquals(0,repo.day(date).revision)
        compose.onNodeWithText("检查建议").performScrollTo().performClick()
        compose.onNode(hasText("睡眠时长") and hasSetTextAction()).performScrollTo().performTextReplacement("590")
        compose.onNodeWithText("确认保存").performClick()
        compose.waitUntil(5000){repo.day(date).revision==1}
        assertEquals(590,repo.day(date).fields["sleepMinutes"]?.value?.int)
        assertEquals("human",repo.day(date).fields["sleepMinutes"]?.actor)
        assertFalse(repo.day(date).fields.containsKey("spo2"))
        assertTrue(repo.snapshot().proposals.isEmpty())
    }
    @Test fun bundledOcrReadsSyntheticScreenshotWithoutWritingAnyRecord()=runBlocking{
        val inst=InstrumentationRegistry.getInstrumentation();check(inst is IsolatedGenerationLoopRunner)
        val context=inst.targetContext
        val bitmap=Bitmap.createBitmap(1000,850,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=64f}
        listOf("总睡眠 9小时48分钟","步数 293 步","平均心率 80 bpm","血氧 98%").forEachIndexed{i,line->canvas.drawText(line,50f,140f+i*170f,paint)}
        val file=File(context.cacheDir,"synthetic-health-test.png")
        try{
            file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            val text=recognizeHealthScreenshot(context,Uri.fromFile(file))
            val values=parseHealthScreenshot(text)
            assertEquals("Recognized fields: ${values.keys}",588,values["sleepMinutes"]?.int)
            assertEquals(293,values["steps"]?.int)
            assertEquals(80,values["averageHeart"]?.int)
        }finally{bitmap.recycle();file.delete()}
    }
}
