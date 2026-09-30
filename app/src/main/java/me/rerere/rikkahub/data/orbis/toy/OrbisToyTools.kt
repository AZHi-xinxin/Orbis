package me.rerere.rikkahub.data.orbis.toy

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** Only register for an explicitly enabled assistant. No scan/connect tools exist. */
fun buildOrbisToyTools(context: Context): List<Tool> {
    val controller = OrbisToyController.get(context)
    val capturedScope = controller.approvalScope()
    return listOf(
        Tool(name = "toy_bluetooth_status", description = "读取 Orbis 蓝牙 Toy 的本地连接列表与持续强度状态。不会扫描、连接或启动设备。",
            parameters = { InputSchema.Obj(buildJsonObject {}) },
            execute = { listOf(UIMessagePart.Text(controller.state.value.asToolJson())) }),
        Tool(name = "toy_bluetooth_set", description = "控制本人已在 Orbis 手动选择并连接的 BLE Toy 集合。intensity 为 0–100：0 是停止且不要求审批；若旧工具因连接名单变化失效，停止时请直接调用 toy_bluetooth_stop。1–20、21–40、41–60、61–80、81–100 对应原版五档。非零强度每 1.4 秒向当前连接设备重发，持续到明确停止，没有额外时长上限。不能扫描或自动连接。系统后台限制可能中断重发，GATT 写入成功不代表已验证物理状态。",
            parameters = { InputSchema.Obj(buildJsonObject {
                put("intensity", buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 100) })
            }, required = listOf("intensity")) },
            needsApproval = { input -> runCatching { input.jsonObject["intensity"]?.jsonPrimitive?.intOrNull != 0 }.getOrDefault(true) },
            hostApproval = HostToolApproval(stableId = "toy_bluetooth_set", revision = "toy/v2:${capturedScope ?: "disconnected"}",
                label = "当前手动连接的蓝牙 Toy 集合", rememberable = capturedScope != null),
            isApprovalCurrent = { capturedScope != null && controller.approvalScope() == capturedScope },
            execute = { input ->
                try {
                    val args = input.jsonObject
                    require(args.keys == setOf("intensity")) { "只接受 intensity。" }
                    val intensity = requireNotNull(args["intensity"]?.jsonPrimitive?.intOrNull) { "缺少整数强度。" }
                    val result = if (intensity == 0) controller.stop() else {
                        check(capturedScope != null && capturedScope == controller.approvalScope()) { "设备连接集合已改变或未连接，请重新获取工具。" }
                        controller.set(intensity, capturedScope)
                    }
                    listOf(UIMessagePart.Text(result.asToolJson()))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    listOf(UIMessagePart.Text(buildJsonObject { put("ok", false); put("error", e.message ?: "蓝牙指令失败。") }.toString()))
                }
            }),
        Tool(name = "toy_bluetooth_stop", description = "立即请求停止当前蓝牙 Toy，不要求工具审批。蓝牙断开或指令超时不能保证物理停机；请提示用户检查实体开关。",
            parameters = { InputSchema.Obj(buildJsonObject {}) }, needsApproval = { false },
            execute = { listOf(UIMessagePart.Text(controller.stop().asToolJson())) }),
    )
}

private fun ToyState.asToolJson() = buildJsonObject {
    put("connected", connected); put("device_name", deviceName)
    put("device_count", deviceNames.size)
    put("requested_level", level); put("requested_intensity", requestedIntensity)
    put("continuous", requestedIntensity != null)
    put("busy", busy); put("message", message); put("physical_stop_uncertain", physicalStopUncertain)
    put("physical_state_verified", false)
}.toString()
