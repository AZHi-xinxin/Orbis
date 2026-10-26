package me.rerere.rikkahub.data.orbis.health

import kotlinx.serialization.json.int
import org.junit.Assert.*
import org.junit.Test

class HealthScreenshotLayoutTest {
    private fun line(text:String,x:Int,y:Int,width:Int=100)=HealthOcrLine(text,x,y,x+width,y+35)
    @Test fun columnsAndGoalDenominatorsDoNotFollowReadingOrder() {
        val lines=listOf(line("卡路里",60,30),line("步数",360,30),line("活动时长",660,30),
            line("216",30,90),line("/500千卡",30,140),line("2781",340,90),line("/7000步",340,140),
            line("17",640,90),line("/40分钟",640,140),line("睡眠",30,400),line("8时12分",30,450),
            line("心率",500,400),line("76次/分",500,450),line("12:37",500,500))
        val actual=parseHealthScreenshot(HealthScreenshotRecognition("irrelevant reading order",lines.reversed()))
        assertEquals(216,actual["calories"]?.content?.toDouble()?.toInt())
        assertEquals(2781,actual["steps"]?.int);assertEquals(17,actual["activeMinutes"]?.int)
        assertEquals(492,actual["sleepMinutes"]?.int);assertEquals(76,actual["heartRate"]?.int)
        assertFalse(actual.containsKey("averageHeart"));assertFalse(actual.containsKey("restingHeart"))
    }
    @Test fun goalOnlyMissingAndAdjacentCardCannotInventAValue() {
        val lines=listOf(line("步数",30,30),line("/7000步",30,90),line("卡路里",400,30),line("216",400,90),
            line("血氧",30,400),line("佩戴设备后测量",30,450),line("心率",500,400),line("76次/分",500,450))
        val actual=parseHealthScreenshot(HealthScreenshotRecognition("步数 7000 步",lines))
        assertFalse(actual.containsKey("steps"));assertFalse(actual.containsKey("spo2"))
        assertEquals(76,actual["heartRate"]?.int)
    }
    @Test fun handwrittenConfusionsRequireNearbyMeasurementUnits() {
        val labels=listOf(line("名率",30,20),line("76",30,70))
        assertTrue(parseHealthScreenshot(HealthScreenshotRecognition("",labels)).isEmpty())
        val supported=listOf(line("名率",30,20),line("76次/分",30,70),line("步教",400,20),line("293",400,70),line("/6000步",400,120))
        val values=parseHealthScreenshot(HealthScreenshotRecognition("",supported))
        assertEquals(76,values["heartRate"]?.int);assertEquals(293,values["steps"]?.int)
    }
    @Test fun inlineAndConflictingLabelsRemainConservative() {
        val result=parseHealthScreenshot(HealthScreenshotRecognition("",listOf(line("平均心率 82 bpm",30,20,260),
            line("步数",30,200),line("293",30,245),line("步数",30,450),line("600",30,495))))
        assertEquals(82,result["averageHeart"]?.int);assertFalse(result.containsKey("heartRate"))
        assertFalse(result.containsKey("steps"))
        assertTrue(parseHealthScreenshot(HealthScreenshotRecognition("",listOf(line("睡眠",20,20),line("8时99分",20,70)))).isEmpty())
    }
}
