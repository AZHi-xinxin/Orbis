package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.contact.IncomingCallAttempt
import me.rerere.rikkahub.data.orbis.contact.IncomingCallOutcome
import me.rerere.rikkahub.data.orbis.voice.OrbisVideoFrame

/** A generation may acquire only the exact call accepted through its own start tool. */
internal fun createOrbisLiveVideoCallTools(
    assistantId: String,
    conversationId: String,
    initialCallId: String?,
    requestCall: suspend (String) -> IncomingCallAttempt,
    captureFrame: suspend (String) -> OrbisVideoFrame,
): List<Tool> {
    var acceptedCallId: String? = null
    return listOf(
        Tool(name = "start_video_call", description = "向人类发起视频来电。人类明确接听且手机解锁后才开相机，沿用语音来电权限/冷却。默认每30秒看一帧，可连续语音；只看前台画面，后台和锁屏暂停相机。挂断使用end_voice_call。reason必填；不因拒接或无人接连续重试。",
            parameters = { InputSchema.Obj(buildJsonObject {
                put("reason", buildJsonObject { put("type", "string"); put("maxLength", 2000) })
            }, required = listOf("reason")) }, needsApproval = { false }, execute = { args ->
                val result = requestCall(args.jsonObject.getValue("reason").jsonPrimitive.content)
                if (initialCallId == null && acceptedCallId == null && result.video &&
                    result.assistantId == assistantId && result.conversationId == conversationId &&
                    result.outcome == IncomingCallOutcome.CONNECTED) {
                    acceptedCallId = result.connectedCallId?.takeIf { it.isNotBlank() }
                }
                listOf(UIMessagePart.Text(result.incomingResult().toString()))
            }),
        Tool(name = "orbis_video_frame_now", description = "正在视频通话时立即看一张当前相机画面，固定周期之外按需使用。仅当前助手、当前聊天、已接听且在前台并开启相机的通话；至少间隔3秒，不与周期抽帧重叠。返回真实图片及frame_id，不会开启相机或开始通话。",
            parameters = { InputSchema.Obj(buildJsonObject {}) }, needsApproval = { false }, execute = execute@{
                // Never resolve a later ambient call: a late old generation cannot capture it.
                val callId = initialCallId ?: acceptedCallId
                    ?: return@execute videoFrameFailure("video_call_not_bound", null,
                        "当前生成没有已接听的视频通话，未拍摄画面。", executionPerformed = false)
                try {
                    val frame = captureFrame(callId)
                    listOf(UIMessagePart.Text("call_id=$callId frame_id=${frame.id} captured_at_ms=${frame.capturedAtMs}"),
                        UIMessagePart.Image("orbis-video-frame://$callId/${frame.id}"))
                } catch (timeout: TimeoutCancellationException) {
                    // Our bounded capture may time out while the generation itself remains live.
                    // An outer cancellation must still stop the generation and its tool chain.
                    currentCoroutineContext().ensureActive()
                    videoFrameFailure("video_frame_timeout", callId, "本次画面等待超时，未提供图片；通话仍保留。")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Camera pause, capture contention and storage limits are local frame failures.
                    // Returning a receipt prevents the generation journal treating them as unknown
                    // external tool effects and pausing the still-queued call BEGIN marker.
                    videoFrameFailure("video_frame_unavailable", callId,
                        "本次画面暂不可用，未提供图片；请确认通话仍在前台且相机开启。")
                }
            }),
    )
}

private fun videoFrameFailure(code: String, callId: String?, message: String,
    executionPerformed: Boolean? = null): List<UIMessagePart> = listOf(UIMessagePart.Text(buildJsonObject {
    put("status", "failed")
    put("reason_code", code)
    put("call_id", callId?.let(::JsonPrimitive) ?: JsonNull)
    put("frame_available", false)
    put("execution_performed", executionPerformed?.let(::JsonPrimitive) ?: JsonNull)
    put("message", message)
}.toString()))
