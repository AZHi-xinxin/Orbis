package me.rerere.rikkahub.data.orbis.toy.lab

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal const val TOY_LAB_GUIDE = "北斗导航→工具娱乐→蓝牙Toy→自定义设备调试台，与原直连平行。人类点扫描、选择自己的设备，只读检查后可自行试调、选择是否记录。档案页打开‘允许当前助手读取并配置’才给所属AI注册toy_lab工具；不常驻注入档案。AI可以读取已分享通道/方案/本人观察，安装修改声明式方案，重连本人已选设备，按明确动作/版本/时长请求控制，停止。AI不扫描附近设备、不自行授予分享，不把发送成功当物理动作或停机，不替人类标注。支持直接install信道台ble-channel-lab导出的完整JSON（profileSchema=story-device-ctrl/1），人类也可在调试页粘贴或档案页导入，不需手填型号和固件。各功能会展开为独立档位，保留写入模式、档位字节和倍率、60毫秒阶梯启动及500毫秒保活；缺失关包不推测停止。原导出未包含的鉴权仍需补齐。Orbis动作支持startup和cycle步骤数组[{packet,delayAfterMs}]、动作独立stopPackets和repeatMs，可表达顺序启停和脉冲，所有包须有依据。每次只运行选中的动作；需多通道组合时显式编排cycle，不可声称自动并行控制。方案字段为name/model/firmware/protocol/source/actions[{id,label,packet:{service,characteristic,hex,mode},meaning}]/handshake[packet]/notifications[{service,characteristic}]/stopMethod/stop/repeatMs/maxRunSeconds/notes；mode为response或no_response，hex为完整1–64字节不带占位符。stopMethod为disconnect或packet_then_disconnect（须有stop包）。没有协议依据时先解释缺什么，不枚举未知包、不用00/FF猜测强度或停止。动态鉴权、需要解码通知才能发包的协议本版不支持，应明确告知；可保留只读诊断。每次连接不执行握手，只有试调时才按方案发送；每次结束请求停止并断开，不自动续跑；调试台只在Orbis前台试调，退到后台请求停止。系统强杀或休眠仍不能保证物理停机，需可操作实体开关。未知/导入方案试调最多10秒，本机人类记录有反应且确认停机后可按方案上限（最多300秒）。原YUTU/FFE0直连参数不变；YUTU0不是停止，专用握手与固件校验请沿用原直连。导入不继承授权或本机实测结论，硬件固件或通道变化需重新选设备。重开App需本人重新选择并授权；保存的档案可在档案页重新打开。"

internal fun buildToyLabTools(context: Context, owner: String, controller: ToyLabController = ToyLabController.get(context)): List<Tool> {
    val grant = controller.approvalScope(owner) ?: return emptyList()
    val selection = controller.selectionScope()
    fun current() = controller.approvalScope(owner) == grant
    val props = buildJsonObject {
        put("action",buildJsonObject { put("type","string"); put("enum",JsonArray(listOf("guide","status","install","connect","run").map(::JsonPrimitive))) })
        put("profile",buildJsonObject { put("type","object"); put("description","install使用完整声明式方案；先查询guide。不会执行代码。") })
        put("expected_revision",buildJsonObject { put("type","integer"); put("description","install/run必填，当前已读取的方案版本；首次安装填0") })
        put("action_id",buildJsonObject { put("type","string"); put("description","run使用方案中的确切动作ID") })
        put("seconds",buildJsonObject { put("type","integer"); put("minimum",1); put("maximum",300) })
    }
    return listOf(
        Tool(name = "toy_lab",description = "自定义蓝牙设备调试台。仅当前人类已分享的设备。按需guide/status，安装或修改方案install，重连已选设备connect，按明确方案版本和动作run。不能扫描、自动猜包或替人类标注反应；发送成功不是实物已动作。",
            parameters = { InputSchema.Obj(props,required = listOf("action")) },
            needsApproval = { (it as? JsonObject)?.get("action")?.jsonPrimitive?.contentOrNull == "run" },
            hostApproval = HostToolApproval(stableId = "toy_lab",revision = grant,label = "已分享设备的单次试调",rememberable = false),
            isApprovalCurrent = ::current,
            execute = { rawInput ->
                val result = try {
                    val input = rawInput.jsonObject
                    check(current()) { "人类已收回授权或更换设备。" }
                    require(input.keys.all { it in setOf("action","profile","expected_revision","action_id","seconds") })
                    val archive = controller.sharedArchive(owner) ?: error("没有已分享档案")
                    fun revision(): Int = input["expected_revision"]?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull ?: error("expected_revision必须是整数")
                    when(input["action"]?.jsonPrimitive?.content) {
                        "guide" -> TOY_LAB_GUIDE
                        "status" -> buildJsonObject {
                            put("connected",controller.state.value.connected); put("busy",controller.runState.value.busy)
                            put("archive",ToyLabJson.encodeToJsonElement(archive))
                            put("last_trial",controller.runState.value.trial?.let { ToyLabJson.encodeToJsonElement(it) } ?: JsonNull)
                            put("physical_state_verified",false)
                        }.toString()
                        "install" -> {
                            check(revision() == (archive.latest()?.revision ?: 0)) { "方案版本已改变，请重新读status。" }
                            val profile = decodeLabProfile((input["profile"] ?: error("缺少profile")).jsonObject, archive.report)
                            controller.install(profile,owner,revision())
                            buildJsonObject { put("ok",true); put("revision",controller.state.value.archive!!.latest()!!.revision); put("device_started",false) }.toString()
                        }
                        "connect" -> { controller.reconnect(owner); buildJsonObject {
                            put("ok",controller.state.value.connected); put("message",controller.state.value.message); put("device_started",false)
                        }.toString() }
                        "run" -> {
                            val seconds = input["seconds"]?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull ?: error("seconds必须是整数")
                            val trial = controller.start(input["action_id"]?.jsonPrimitive?.content ?: error("缺少action_id"),seconds,owner,grant,revision())
                            buildJsonObject { put("accepted",true); put("trial_id",trial.id); put("physical_state_verified",false); put("next","等待status回执；请人类观察，不能自动填写观察记录。") }.toString()
                        }
                        else -> error("未知action")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    buildJsonObject { put("ok",false); put("error",e.message?.take(500) ?: "操作失败") }.toString()
                }
                listOf(UIMessagePart.Text(result))
            }),
        Tool(name = "toy_lab_stop",description = "停止独立调试台当前试调并断开，不操作原直连。无需审批；物理停止仍由人类确认。",
            parameters = { InputSchema.Obj(buildJsonObject {}) },needsApproval = { false },
            execute = {
                // An old assistant cannot stop a replacement assistant's session.
                val allowed = controller.selectionScope() == selection && (controller.state.value.sharedWith == owner || controller.state.value.sharedWith == null)
                if (allowed) controller.stop()
                listOf(UIMessagePart.Text(buildJsonObject { put("ok",allowed); put("physical_state_verified",false); put("message",if (allowed) controller.runState.value.message else "设备授权已改变，未执行。") }.toString()))
            }),
    )
}
