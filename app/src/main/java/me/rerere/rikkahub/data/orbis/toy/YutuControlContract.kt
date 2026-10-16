package me.rerere.rikkahub.data.orbis.toy

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

enum class ToyProfile { SVAKOM, YUTU_V2 }

/** TO-G9 / firmware 1.0.0, from the owner's 2026-10-15 HCI trace and bench report.
 * This is a target value, NOT Hz, a linear percentage, or an independent motor selector.
 * Zero is deliberately unrepresentable: the bench report says it does NOT stop the motor.
 */
internal object YutuControlContract {
    const val REPEAT_MS = 100L // Bench-tested cadence; no need to copy the app's 35 ms polling.
    const val MAX_SECONDS = 3600
    const val STRENGTH_GUIDE = "目标值不是频率或线性百分比；换档不等于体感一定变化，相邻目标值可能感觉不出区别，不承诺每个值都可区分。"
    const val DURATION_GUIDE = "duration_seconds 只选 300、600、1200、1800、2400、3000、3600，分别是 5、10、20、30、40、50 分钟和 1 小时，不提供 1 分钟档。每次成功下发从本次指令重新计时，未到期换档不会先断开；到时请求断开，不会自动续时。"
    const val GUIDE = "YUTU V2 扶潮 TO-G9 使用 strength 目标值，只接受已测试的 1、2、3、5、10、20、50、100。" + STRENGTH_GUIDE + "51–99 尚未标定，不开放。" + DURATION_GUIDE + "仅支持设备原生输出；不支持指定波形、独立电机或原厂模式编号。0 不是停止！停止必须调用 toy_bluetooth_stop，可随时请求停止，无需等到时长结束；Orbis 会断开连接，再次使用需本人手动重连，禁止自动重连。系统休眠或蓝牙异常时不能保证物理停机。"
    const val APPROVAL_REVISION = "toy/v5-yutu-hour-presets"

    data class Command(val strength: Int, val durationSeconds: Int)
    val testedValues = listOf(1, 2, 3, 5, 10, 20, 50, 100)
    val durationPresets = listOf(300, 600, 1200, 1800, 2400, 3000, MAX_SECONDS)
    val valuesJson get() = kotlinx.serialization.json.JsonArray(testedValues.map(::JsonPrimitive))
    val durationsJson get() = kotlinx.serialization.json.JsonArray(durationPresets.map(::JsonPrimitive))

    fun durationLabel(seconds: Int): String = if (seconds == MAX_SECONDS) "1 小时" else "${seconds / 60} 分钟"

    val properties get() = buildJsonObject {
        put("strength", buildJsonObject {
            put("type", "integer"); put("minimum", 1); put("maximum", 100)
            put("enum", valuesJson)
            put("description", "仅接受已测试目标值 1、2、3、5、10、20、50、100。" + STRENGTH_GUIDE + "0 不能用于停止。")
        })
        put("duration_seconds", buildJsonObject {
            put("type", "integer"); put("minimum", durationPresets.first()); put("maximum", MAX_SECONDS)
            put("enum", durationsJson)
            put("description", DURATION_GUIDE)
        })
    }

    fun validate(strength: Int, durationSeconds: Int): Command {
        require(strength in testedValues && durationSeconds in durationPresets) { GUIDE }
        return Command(strength, durationSeconds)
    }

    fun parse(input: JsonElement): Command {
        val obj = input as? JsonObject ?: throw IllegalArgumentException(GUIDE)
        require(obj.keys == setOf("strength", "duration_seconds")) { GUIDE }
        fun integer(name: String): Int = (obj[name] as? JsonPrimitive)
            ?.takeUnless { it.isString }?.intOrNull ?: throw IllegalArgumentException(GUIDE)
        return validate(integer("strength"), integer("duration_seconds"))
    }

    fun packet(strength: Int): ByteArray {
        require(strength in testedValues) { GUIDE }
        return byteArrayOf(0xA0.toByte(), 0xA0.toByte(), strength.toByte())
    }
}
