package me.rerere.rikkahub.data.orbis.toy.lab

import kotlinx.serialization.json.*
import java.util.UUID

/** Data adapter for ble-channel-lab exports. No JavaScript, probing or grant is imported.
 * Packet/ramp interpretation follows story-device-ctrl, MIT; see bundled third-party notice.
 * Only explicit OFF packets are used: the upstream driver's guessed broadcast stops are not used.
 */
internal fun decodeLabProfile(text: String, report: LabDeviceReport): LabProfile {
    require(text.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "方案超过512KiB。" }
    return decodeLabProfile(ToyLabJson.parseToJsonElement(text).jsonObject, report)
}

internal fun decodeLabProfile(value: JsonObject, report: LabDeviceReport): LabProfile {
    val profile = if ("functions" in value) importStoryDeviceProfile(value, report)
        else ToyLabJson.decodeFromJsonElement<LabProfile>(value)
    return profile.also { it.validate(report) }
}

private fun importStoryDeviceProfile(value: JsonObject, report: LabDeviceReport): LabProfile {
    fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
    fun JsonObject.number(key: String, fallback: Int? = null): Int =
        if (key !in this) fallback ?: error("缺少 $key，无法确定档位字节；请重新导出完整JSON。")
        else (get(key) as? JsonPrimitive)?.intOrNull ?: error("$key 必须是整数。")
    val schema = value.text("profileSchema")
    require(schema == "story-device-ctrl/1" || (schema.isEmpty() && value.text("tool") == "ble-channel-lab")) {
        "无法识别带functions的格式；请导入信道台完整导出JSON。"
    }
    require(value.number("version", 1) in 1..2) { "此信道台档案版本尚未支持。" }
    val functions = value["functions"] as? JsonObject ?: error("functions须为功能对象。")
    require(functions.size in 1..12) { "档案须含1–12个已标注功能。" }
    fun uuid(text: String): String {
        val expanded = when {
            text.matches(Regex("[a-fA-F0-9]{4}")) -> "0000$text-0000-1000-8000-00805f9b34fb"
            text.matches(Regex("[a-fA-F0-9]{8}")) -> "$text-0000-1000-8000-00805f9b34fb"
            else -> text
        }
        return UUID.fromString(expanded).toString()
    }
    fun cleanHex(text: String): String {
        require(text.matches(Regex("[a-fA-F0-9\\s]+"))) { "开关包须为十六进制字节，不能是脚本或模板。" }
        val clean = text.filterNot(Char::isWhitespace)
        require(clean.length in 2..128 && clean.length % 2 == 0) { "开关包长度须为1–64字节。" }
        return clean.chunked(2).joinToString(" ").uppercase()
    }
    var noStop = false
    val actions = functions.entries.flatMapIndexed { index, (key, element) ->
        try {
            val fn = element as? JsonObject ?: error("功能内容须为对象。")
            val service = uuid(fn.text("serviceUuid")); val characteristic = uuid(fn.text("characteristicUuid"))
            val mode = when (fn.text("writeMode").lowercase()) {
                "without-response", "withoutresponse", "writewithoutresponse", "nr", "no_response" -> "no_response"
                "with-response", "withresponse", "writewithresponse", "wr", "response" -> "response"
                else -> error("缺少或不支持writeMode，请使用信道台实测后导出的写入方式。")
            }
            var on = fn.text("onPacketHex")
            var off = fn.text("offPacketHex").ifBlank { fn.text("offHex") }
            (fn["samplePacketsHex"] as? JsonArray).orEmpty().forEach { sample ->
                val s = (sample as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                if (s.startsWith("开:")) on = s.substringAfter(':').trim()
                if (s.startsWith("关:")) off = s.substringAfter(':').trim()
            }
            val base = LabPacket(service, characteristic, cleanHex(on), mode).also { it.validate(report) }
            val bytes = base.bytes()
            val storedIndex = fn.number("levelByteIndex")
            val position = if (storedIndex < 0) bytes.size + storedIndex else storedIndex
            require(position in bytes.indices) { "levelByteIndex越界，未改用其他字节。" }
            val scale = fn.number("levelScale", 1)
            require(scale == 1 || scale == 10) { "只支持显式的1或10倍率。" }
            val lo = fn.number("levelMin", 1); val hi = fn.number("levelMax", 10)
            require(lo in 1..10 && hi in lo..10) { "信道台档位范围须在1–10内。" }
            val stop = if (off.isBlank()) { noStop = true; emptyList() } else listOf(base.copy(hex = cleanHex(off)))
            fun packet(level: Int): LabPacket {
                val b = bytes.copyOf(); b[position] = (level * scale).toByte()
                // Defined by story-device-ctrl's packetWithLevel, not inferred for other profiles.
                if (b.size >= 6 && b[0] == 0x55.toByte()) b[b.size - 3] = 1
                return base.copy(hex = b.joinToString(" ") { "%02X".format(it.toInt() and 255) })
            }
            val label = fn.text("label").ifBlank { fn.text("note") }.ifBlank { key }.take(45)
            (lo..hi).map { level ->
                val ramp = if (level >= 5) listOf(3, 5) else emptyList()
                LabAction("fn${index + 1}_level$level", "$label · $level 档", packet(level),
                    meaning = "导入功能 $key；档位字节$position，倍率$scale。来源记录不等于本机已实测。",
                    startup = ramp.map { LabStep(packet(it), 60) },
                    stopPackets = stop, repeatMs = 500)
            }
        } catch (e: IllegalArgumentException) { throw IllegalArgumentException("功能 $key：${e.message}", e) }
        catch (e: IllegalStateException) { throw IllegalArgumentException("功能 $key：${e.message}", e) }
    }
    return LabProfile(
        name = value.text("deviceName").ifBlank { report.name }.take(120) + " · 信道台",
        model = report.model.ifBlank { "待核实" }, firmware = report.firmware.ifBlank { "待核实" },
        protocol = "story-device-ctrl/1", source = "信道台导出；戏控台MIT协议解释（059e17e7）",
        actions = actions, maxRunSeconds = 10,
        notes = "保留各功能、档位字节/倍率，阶梯启动间隔60ms、保活500ms。只使用档案明确提供的关包，不推测广播停止。" +
            (if (noStop) "部分功能没有关包，只能请求断开；请本人确认实际停止。" else "") +
            "原始导出不含完整鉴权/通知握手，若设备依赖这些仍需补齐方案。导入只保存，不发送蓝牙指令。",
    )
}
