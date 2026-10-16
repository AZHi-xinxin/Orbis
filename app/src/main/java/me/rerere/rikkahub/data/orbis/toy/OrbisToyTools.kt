package me.rerere.rikkahub.data.orbis.toy

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** Only register for an explicitly enabled assistant. No scan/connect tools exist. */
fun buildOrbisToyTools(context: Context): List<Tool> {
    val controller = OrbisToyController.get(context)
    val capturedScope = controller.approvalScope()
    val yutu = controller.state.value.profile == ToyProfile.YUTU_V2
    return listOf(
        Tool(name = "toy_bluetooth_status", description = "读取 Orbis 蓝牙 Toy 的本地连接列表、支持的参数与请求档位。不会扫描、连接或启动设备。",
            parameters = { InputSchema.Obj(buildJsonObject {}) },
            execute = { listOf(UIMessagePart.Text(controller.state.value.asToolJson())) }),
        Tool(name = "toy_bluetooth_set", description = if (yutu) YutuControlContract.GUIDE + " 仅控制本人手动连接且授权的设备，蓝牙写入成功不代表物理状态已验证。"
            else "控制本人已在 Orbis 手动选择并连接的 BLE Toy 集合。${ToyLevelContract.GUIDE} 0 停止且不要求审批；若旧工具因连接名单变化失效，停止时请直接调用 toy_bluetooth_stop。非零档位每 1.4 秒向当前连接设备重发，持续到明确停止，没有额外时长上限。不能扫描或自动连接。系统后台限制可能中断重发，GATT 写入成功不代表已验证物理状态。",
            parameters = { if (yutu) InputSchema.Obj(YutuControlContract.properties, required = listOf("strength", "duration_seconds"))
                else InputSchema.Obj(ToyLevelContract.properties, required = listOf("level")) },
            needsApproval = { input -> yutu || ToyLevelContract.needsApproval(input) },
            hostApproval = HostToolApproval(stableId = "toy_bluetooth_set", revision = "${if (yutu) YutuControlContract.APPROVAL_REVISION else ToyLevelContract.APPROVAL_REVISION}:${capturedScope ?: "disconnected"}",
                label = "当前手动连接的蓝牙 Toy 集合", rememberable = capturedScope != null),
            isApprovalCurrent = { capturedScope != null && controller.approvalScope() == capturedScope },
            execute = { input ->
                try {
                    if (yutu) {
                        val command = YutuControlContract.parse(input)
                        check(capturedScope != null && capturedScope == controller.approvalScope()) { "设备连接已改变，请重新获取工具。" }
                        listOf(UIMessagePart.Text(controller.setYutu(command, capturedScope).asToolJson()))
                    } else {
                    val level = ToyLevelContract.parse(input)
                    val result = if (level == 0) controller.stop() else {
                        check(capturedScope != null && capturedScope == controller.approvalScope()) { "设备连接集合已改变或未连接，请重新获取工具。" }
                        controller.setLevel(level, capturedScope)
                    }
                    listOf(UIMessagePart.Text(result.asToolJson()))
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    listOf(UIMessagePart.Text(buildJsonObject { put("ok", false); put("error", e.message ?: "蓝牙指令失败。") }.toString()))
                }
            }),
        Tool(name = "toy_bluetooth_stop", description = "立即请求停止当前蓝牙 Toy，不要求工具审批。司沃康发送停止包；YUTU 直接断开 BLE（不会发送数值 0），需本人手动重连才能再次使用。无法保证物理停机；请提示用户检查实体开关。",
            parameters = { InputSchema.Obj(buildJsonObject {}) }, needsApproval = { false },
            execute = { listOf(UIMessagePart.Text(controller.stop().asToolJson())) }),
    )
}
