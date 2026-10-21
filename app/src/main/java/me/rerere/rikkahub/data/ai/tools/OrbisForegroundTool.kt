package me.rerere.rikkahub.data.ai.tools

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import com.lover.connect.DeviceCompatibility
import com.lover.connect.LCAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.orbis.companiontools.ForegroundChatReceipt
import me.rerere.rikkahub.data.orbis.companiontools.orbisForegroundChat

internal fun createOrbisForegroundTool(context: Context, conversationId: String): Tool =
    foregroundChatTool {
        val app = context.applicationContext
        val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        when {
            keyguard.isDeviceLocked -> ForegroundChatReceipt(false, "device_locked")
            !DeviceCompatibility.activeAppInterventionsSupported() -> ForegroundChatReceipt(false, "device_control_unsupported")
            else -> orbisForegroundChat.request(conversationId) {
                withContext(Dispatchers.Main.immediate) {
                    val intent = foregroundChatIntent(app, conversationId)
                    // Use the already user-enabled accessibility service when
                    // available. Never enable it or grant an OEM permission.
                    (LCAccessibilityService.instance ?: app).startActivity(intent)
                }
            }
        }
    }

internal fun foregroundChatIntent(context: Context, conversationId: String) =
    Intent(context, RouteActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        putExtra("conversationId", conversationId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

internal fun foregroundChatTool(request: suspend () -> ForegroundChatReceipt) = Tool(
    name = "orbis_return_to_chat",
    description = "立即请求把当前 Orbis 对话带到手机前台；目标由宿主绑定，不接受别的窗口ID。只切换一次，不设置持续拦截规则、不锁定手机、不挂断通话。ok=true 仅在目标聊天已获得前台焦点时返回；foreground_not_observed 表示系统未确认切换，不要声称人类已经回来。锁屏时不执行。",
    parameters = { InputSchema.Obj(buildJsonObject {}, required = emptyList()) },
    needsApproval = { true },
    hostApproval = HostToolApproval("orbis:return_to_chat", "foreground-chat-v1", "把当前聊天切回手机前台"),
    execute = { arguments ->
        val receipt = if (arguments is JsonObject && arguments.isEmpty()) request()
            else ForegroundChatReceipt(false, "invalid_arguments")
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", receipt.confirmed)
            put("status", receipt.status)
            put("foreground_switch_confirmed", receipt.confirmed)
            put("automatic_retry", false)
            if (receipt.status == "foreground_not_observed")
                put("message", "已发起切换，但未确认目标聊天到达前台；请检查系统的后台弹出界面权限和无障碍服务状态。")
        }.toString()))
    },
)
