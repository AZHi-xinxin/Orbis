package me.rerere.rikkahub.data.orbis.health

import kotlinx.serialization.json.JsonPrimitive

/** Conservative extraction from local OCR. Ambiguous/missing labels stay empty for correction. */
internal fun parseHealthScreenshot(text:String):Map<String,JsonPrimitive> {
    require(text.length<=100_000){"health_ocr_too_large"}
    val result=mutableMapOf<String,JsonPrimitive>()
    fun number(id:String,label:String,units:String="") {
        val regex=Regex("(?:$label)[ \\t：:]*\\n?[ \\t]*([0-9]{1,6}(?:\\.[0-9]{1,2})?)[ \\t]*(?:$units)",RegexOption.IGNORE_CASE)
        val values=regex.findAll(text).map{it.groupValues[1]}.distinct().toList()
        if(values.size==1) runCatching{healthValue(healthFields.first{it.id==id},values.single())}.getOrNull()?.let{result[id]=it}
    }
    number("steps","步数|Steps","步|steps|(?=\\s|$)")
    number("activeMinutes","活动时长|活动时间|Active minutes","分钟|min")
    number("calories","活动热量|Active calories","千卡|kcal")
    number("restingHeart","静息心率|Resting heart rate","次/分钟|bpm|(?=\\s|$)")
    number("averageHeart","平均心率|Average heart rate","次/分钟|bpm|(?=\\s|$)")
    number("spo2","血氧|SpO2","%|％")
    val sleep=Regex("(?:总睡眠|睡眠时长|睡眠时间|Total sleep)[ \\t：:]*\\n?[ \\t]*(\\d{1,2})[ \\t]*(?:小时|h)[ \\t]*(?:(\\d{1,2})[ \\t]*(?:分钟|分|m))?",RegexOption.IGNORE_CASE)
        .findAll(text).filter { (it.groupValues[2].toIntOrNull()?:0) in 0..59 }.map { it.groupValues[1].toInt()*60+(it.groupValues[2].toIntOrNull()?:0) }.distinct().toList()
    if(sleep.size==1 && sleep.single() in 0..1440) result["sleepMinutes"]=JsonPrimitive(sleep.single())
    return result
}
