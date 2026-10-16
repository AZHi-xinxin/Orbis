package me.rerere.rikkahub.data.orbis.toy

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Public input contract for the existing Svakom five-level adapter, not a generic BLE scale. */
internal object ToyLevelContract {
    const val GUIDE = "司沃康仅支持 level 整数 0–5：0 停止，1–5 为五个离散档位；不是百分比，也不是频率。相同档位不会改变输出。不要传 intensity、frequency、mode 或 30、35 等数值。"
    const val APPROVAL_REVISION = "toy/v3-level"

    val levels get() = buildJsonArray { (0..5).forEach { add(it) } }
    val properties get() = buildJsonObject {
        put("level", buildJsonObject {
            put("type", "integer")
            put("minimum", 0)
            put("maximum", 5)
            put("enum", levels)
            put("description", GUIDE)
        })
    }

    fun parse(input: JsonElement): Int {
        val args = input as? JsonObject ?: throw IllegalArgumentException(GUIDE)
        require(args.keys == setOf("level")) { GUIDE }
        val value = args["level"] as? JsonPrimitive
        val level = value?.takeUnless { it.isString }?.intOrNull
        require(level != null && level in 0..5) { GUIDE }
        return level
    }

    fun needsApproval(input: JsonElement): Boolean = runCatching { parse(input) != 0 }.getOrDefault(true)
}

/** Requested command state only: a successful Bluetooth write does not measure physical output. */
internal fun ToyState.asToolJson() = buildJsonObject {
    put("connected", connected)
    put("device_name", deviceName)
    put("device_count", deviceNames.size)
    val yutu = profile == ToyProfile.YUTU_V2
    put("control_contract", if (yutu) "yutu_v2_target_value" else "svakom_five_levels")
    put("input_field", if (yutu) "strength" else "level")
    if (yutu) {
        put("min_strength", 1); put("max_strength", 100)
        put("allowed_strengths", YutuControlContract.valuesJson)
        put("requested_strength", requestedYutuStrength)
        put("requested_duration_seconds", durationSeconds)
        put("allowed_duration_seconds", YutuControlContract.durationsJson)
        put("min_duration_seconds", YutuControlContract.durationPresets.first())
        put("max_duration_seconds", YutuControlContract.MAX_SECONDS)
        put("strength_notice", YutuControlContract.STRENGTH_GUIDE)
        put("automatic_duration_renewal", false)
        put("stop_method", "disconnect_no_automatic_reconnect")
        put("independent_motor_control_supported", false)
    } else {
        put("allowed_levels", ToyLevelContract.levels)
        put("requested_level", level)
        put("stop_method", "stop_packet")
    }
    put("frequency_control_supported", false)
    put("pattern_control_supported", false)
    put("input_guide", if (yutu) YutuControlContract.GUIDE else ToyLevelContract.GUIDE)
    put("continuous", requestedIntensity != null || requestedYutuStrength != null)
    put("busy", busy)
    put("message", message)
    put("physical_stop_uncertain", physicalStopUncertain)
    put("physical_state_verified", false)
}.toString()
