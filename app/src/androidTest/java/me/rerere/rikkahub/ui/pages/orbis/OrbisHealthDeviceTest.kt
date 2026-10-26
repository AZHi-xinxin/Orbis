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
    @Test fun bundledOcrAssociatesThreeColumnsAndKeepsGoalsOut()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap=Bitmap.createBitmap(1100,900,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=46f}
        val fields=listOf(Triple("卡路里",50f,130f),Triple("步数",420f,130f),Triple("活动时长",770f,130f),
            Triple("216",50f,210f),Triple("2781",420f,210f),Triple("17",770f,210f),
            Triple("/500千卡",50f,270f),Triple("/7000步",420f,270f),Triple("/40分钟",770f,270f),
            Triple("睡眠",50f,510f),Triple("8时12分",50f,580f),Triple("心率",620f,510f),Triple("76次/分",620f,580f))
        fields.forEach{(text,x,y)->canvas.drawText(text,x,y,paint)}
        val file=File(context.cacheDir,"synthetic-health-columns.png")
        try{
            file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            val recognized=recognizeHealthScreenshotLayout(context,Uri.fromFile(file))
            File(context.getExternalFilesDir(null),"synthetic-health-ocr.json").writeText(buildJsonArray {
                recognized.lines.forEach{line->add(buildJsonObject{put("text",line.text);put("left",line.left);put("top",line.top);put("right",line.right);put("bottom",line.bottom)})}
            }.toString())
            val actual=parseHealthScreenshot(recognized)
            assertEquals(2781,actual["steps"]?.int);assertEquals(492,actual["sleepMinutes"]?.int)
            assertEquals(17,actual["activeMinutes"]?.int);assertEquals(76,actual["heartRate"]?.int)
            assertEquals(216.0,actual["calories"]?.double?:-1.0,0.0)
        }finally{bitmap.recycle();file.delete()}
    }
    @Test fun optionalPrivateScreenshotFixtureIsReadLocallyWithoutSavingRecords()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val fixture=args.getString("healthFixturePath")
        org.junit.Assume.assumeTrue(fixture!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(fixture!!)
        val expected=healthJson.parseToJsonElement(File(fixture+".json").readText()).jsonObject
        val recognized=recognizeHealthScreenshotLayout(context,Uri.fromFile(file))
        if(args.getString("healthFixtureDiagnostics")=="true") File(fixture+".ocr.json").writeText(buildJsonArray {
            recognized.lines.forEach { line -> add(buildJsonObject { put("text",line.text);put("left",line.left);put("top",line.top);put("right",line.right);put("bottom",line.bottom) }) }
        }.toString())
        val actual=parseHealthScreenshot(recognized)
        // The fixture and its measurements stay outside source and test logs.
        assertEquals("extracted field names",expected.keys,actual.keys)
        expected.forEach{(key,value)->assertTrue("field mismatch: $key",value.jsonPrimitive.double==actual.getValue(key).double)}
    }

    @Test fun deviceSyncNeedsHumanSourceSelectionAndKeepsManualFallback() {
        val server=me.rerere.rikkahub.data.ai.mcp.McpServerConfig.StreamableHTTPServer(
            commonOptions=me.rerere.rikkahub.data.ai.mcp.McpCommonOptions(name="测试健康设备",tools=listOf(
                me.rerere.rikkahub.data.ai.mcp.McpTool(name="read_health_data"))),url="https://health.example/mcp")
        var selected:String?=null
        compose.setContent{MaterialTheme{HealthSyncContent(HealthSyncSettings(),listOf(server),false,null,{},
            {source,enabled->if(enabled)selected=source?.id.toString()},{error("sync must stay disabled")})}}
        compose.onNodeWithText("立即同步").assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        assertEquals(server.id.toString(),selected)
        compose.onNodeWithText("手填和 AI 填写继续可用。",substring=true).assertExists()
    }
    @Test fun optionalDatedCloudFixtureIsParsedLocallyWithoutSendingToAModel() {
        val path=InstrumentationRegistry.getArguments().getString("healthCloudFixturePath")
        org.junit.Assume.assumeTrue(path!=null)
        val result=parseHealthCloudData(File(path!!).readText(),java.time.ZoneId.of("Asia/Shanghai"),
            java.time.LocalDate.parse("2026-10-20"),java.time.LocalDate.parse("2026-10-26"))
        assertTrue("dated records extracted",result.isNotEmpty())
        result.forEach{(day,fields)->assertTrue(validHealthDate(day));validateHealthFields(fields)}
        assertTrue("steps or sleep available",result.values.any{it.containsKey("steps")||it.containsKey("sleepMinutes")})
    }

}
