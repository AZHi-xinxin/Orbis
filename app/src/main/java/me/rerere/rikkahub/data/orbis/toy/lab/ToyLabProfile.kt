package me.rerere.rikkahub.data.orbis.toy.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

/** Declarative packets only. An imported file is data, never a script or a permission grant. */
@Serializable
data class LabChannel(val service: String, val characteristic: String, val properties: Int) {
    val key: String get() = "${service.lowercase()}/${characteristic.lowercase()}"
}
@Serializable
data class LabDeviceReport(
    val name: String, val channels: List<LabChannel>, val model: String = "", val firmware: String = "",
    val capturedAt: Long = 0, val detail: String = "只读发现完成；未发送控制指令",
) {
    fun fingerprint(): String = sha256(ToyLabJson.encodeToString(copy(name = "", capturedAt = 0, detail = "",
        channels = channels.sortedBy { it.key })))
}
@Serializable
data class LabPacket(val service: String, val characteristic: String, val hex: String, val mode: String = "response") {
    fun bytes(): ByteArray {
        require(hex.matches(Regex("(?:[0-9a-fA-F]{2} ?){1,64}"))) { "数据必须为1–64字节十六进制，不能包含表达式或占位符。" }
        return hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    fun validate(report: LabDeviceReport) {
        UUID.fromString(service); UUID.fromString(characteristic); bytes()
        require(mode in setOf("response", "no_response")) { "写入方式必须为 response 或 no_response。" }
        val matches = report.channels.filter { it.key == "${service.lowercase()}/${characteristic.lowercase()}" }
        require(matches.size == 1) { "指定通道不存在或不唯一，不会猜选其他通道。" }
        val properties = matches.single().properties
        require(properties and (if (mode == "response") 8 else 4) != 0) {
            val supported = listOfNotNull(if (properties and 8 != 0) "response（有应答）" else null,
                if (properties and 4 != 0) "no_response（无应答）" else null).joinToString("、").ifBlank { "没有可写方式" }
            "通道不支持所选写入方式：方案为 $mode；手机读到 $supported（属性0x${properties.toString(16)}）。" +
                "这不是字段格式错误。本次未发包，也未替换写入方式；请导出设备档案核对，勿只为保存而改模式。"
        }
    }
}
@Serializable
data class LabNotify(val service: String, val characteristic: String) {
    fun validate(report: LabDeviceReport) {
        UUID.fromString(service); UUID.fromString(characteristic)
        require(report.channels.count { it.key == "${service.lowercase()}/${characteristic.lowercase()}" && it.properties and 16 != 0 } == 1) {
            "通知通道不存在、不唯一或不支持通知。"
        }
    }
}
@Serializable
data class LabStep(val packet: LabPacket, val delayAfterMs: Long = 0) {
    fun validate(report: LabDeviceReport) {
        packet.validate(report)
        require(delayAfterMs in 0..10000) { "步骤等待时间须为0–10000毫秒。" }
    }
}
@Serializable
data class LabAction(
    val id: String, val label: String, val packet: LabPacket, val meaning: String = "",
    val startup: List<LabStep> = emptyList(),
    val cycle: List<LabStep> = emptyList(),
    val stopPackets: List<LabPacket> = emptyList(),
    val repeatMs: Long? = null,
)
@Serializable
data class LabProfile(
    val name: String,
    val model: String = "待核实",
    val firmware: String = "待核实",
    val protocol: String,
    val source: String,
    val actions: List<LabAction>,
    val handshake: List<LabPacket> = emptyList(),
    val notifications: List<LabNotify> = emptyList(),
    val stop: LabPacket? = null,
    val stopMethod: String = "disconnect",
    val repeatMs: Long = 0,
    val maxRunSeconds: Int = 10,
    val notes: String = "",
) {
    fun validate(report: LabDeviceReport) {
        require(listOf(name, model, firmware, protocol, source).all { it.isNotBlank() && it.length <= 160 }) {
            "请填写名称、适用型号、固件、协议版本和参数来源；未知型号或固件请明确写“待核实”。"
        }
        require(notes.length <= 2000 && actions.size in 1..128 && handshake.size <= 8 && notifications.size <= 4)
        require(actions.map { it.id }.distinct().size == actions.size)
        require(actions.all { it.id.matches(Regex("[a-zA-Z0-9_-]{1,32}")) && it.label.isNotBlank() && it.label.length <= 60 && it.meaning.length <= 300 })
        require(repeatMs == 0L || repeatMs in 100L..10000L) { "重复间隔为0（单发）或100–10000毫秒。" }
        require(maxRunSeconds in 1..300) { "本调试台单次时长为1–300秒，初次试调最多10秒。" }
        require(stopMethod in setOf("packet_then_disconnect", "disconnect"))
        require((stopMethod == "packet_then_disconnect") == (stop != null)) { "停止方式与停止数据必须对应；不推定0就是停止。" }
        actions.forEach { action ->
            action.packet.validate(report)
            require(action.startup.size <= 16 && action.cycle.size <= 32 && action.stopPackets.size <= 8)
            action.startup.forEach { it.validate(report) }; action.cycle.forEach { it.validate(report) }
            action.stopPackets.forEach { it.validate(report) }
            val interval = action.repeatMs ?: repeatMs
            require(interval == 0L || interval in 100..10000) { "动作重复间隔为0或100–10000毫秒。" }
            require(action.cycle.isEmpty() || interval > 0 || action.cycle.sumOf { it.delayAfterMs } >= 100) {
                "循环动作必须有至少100毫秒的等待，不能无间隔写入。"
            }
        }; handshake.forEach { it.validate(report) }
        stop?.validate(report); notifications.forEach { it.validate(report) }
        require(notifications.distinct().size == notifications.size)
    }
    fun digest(): String = sha256(ToyLabJson.encodeToString(this))
}
@Serializable
data class LabProfileRevision(val revision: Int, val profile: LabProfile, val createdAt: Long, val author: String)
@Serializable
data class LabTrial(
    val id: String, val revision: Int, val actionId: String, val seconds: Int, val startedAt: Long,
    val writeSucceeded: Boolean = false, val transportResult: String = "", val stopRequestSucceeded: Boolean = false,
)
@Serializable
data class LabObservation(val trial: LabTrial, val reaction: String, val note: String,
    val stopped: String, val recordedAt: Long, val origin: String = "human_local") {
    fun validate() {
        require(reaction in setOf("有反应", "没反应", "不确定"))
        require(stopped in setOf("确认已停", "未停止", "不确定"))
        require(origin in setOf("human_local", "imported"))
        require(note.length <= 1000)
    }
}
@Serializable
data class LabArchive(
    val format: String = "orbis-toy-lab", val version: Int = 1,
    val report: LabDeviceReport, val revisions: List<LabProfileRevision> = emptyList(),
    val observations: List<LabObservation> = emptyList(),
) {
    fun validate() {
        require(format == "orbis-toy-lab" && version == 1) { "不支持的档案格式或版本。" }
        require(report.name.length <= 120 && report.model.length <= 120 && report.firmware.length <= 120 && report.detail.length <= 500)
        require(report.channels.size <= 256 && report.channels.map { it.key }.distinct().size == report.channels.size)
        report.channels.forEach { UUID.fromString(it.service); UUID.fromString(it.characteristic) }
        require(revisions.size <= 40 && observations.size <= 300) { "档案已达上限，请另存新档案。" }
        require(revisions.map { it.revision }.distinct().size == revisions.size && revisions.all { it.revision > 0 && it.author.length <= 100 })
        revisions.forEach { it.profile.validate(report) }
        observations.forEach { o ->
            o.validate()
            val p = revisions.singleOrNull { it.revision == o.trial.revision }?.profile
            require(p != null && p.actions.any { it.id == o.trial.actionId }) { "记录引用的方案或动作不存在。" }
            require(o.trial.id.length <= 60 && o.trial.seconds in 1..300 && o.trial.transportResult.length <= 500)
        }
    }
    fun latest(): LabProfileRevision? = revisions.maxByOrNull { it.revision }
    fun addProfile(profile: LabProfile, author: String, now: Long): LabArchive {
        profile.validate(report)
        require(revisions.size < 40) { "此档案已有40个版本，请导出后新建档案。" }
        return copy(revisions = revisions + LabProfileRevision((latest()?.revision ?: 0) + 1, profile, now, author.take(100)))
    }
}

internal val ToyLabJson = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }
internal fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun decodeLabArchive(text: String): LabArchive {
    require(text.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "档案超过512KiB。" }
    return ToyLabJson.decodeFromString<LabArchive>(text).also { it.validate() }
}

/** Suggestions are explicit candidates, never proof of physical compatibility. */
internal fun labCandidates(report: LabDeviceReport): List<LabProfile> {
    val service = "0000ffe0-0000-1000-8000-00805f9b34fb"
    val channels = report.channels.filter { it.service.equals(service, true) && it.properties and 12 != 0 }
    if (channels.size != 1) return emptyList()
    val ch = channels.single()
    val mode = if (ch.properties and 8 != 0) "response" else "no_response"
    fun packet(hex: String) = LabPacket(ch.service, ch.characteristic, hex, mode)
    return listOf(LabProfile("FFE0 兼容候选", report.model.ifBlank { "待核实" }, report.firmware.ifBlank { "待核实" },
        "ffe0-55-04-v1", "Orbis已支持的FFE0帧格式；通道相同不等于型号兼容", listOf(50,100,160,210,255).mapIndexed { i,v ->
            LabAction("level_${i+1}", "${i+1}档", packet("55 04 00 00 01 %02X AA".format(v)), "离散档位；不是百分比或频率")
        }, stop = packet("55 04 00 00 00 00 AA"), stopMethod = "packet_then_disconnect", repeatMs = 1400,
        notes = "仅为候选。由设备拥有者确认适用协议、实际反应及停止；原直连入口不受影响。"))
}
