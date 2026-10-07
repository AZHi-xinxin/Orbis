package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.OrbisVideoCallRuntime

/** Transient transport only: old camera frames are never embedded into the stored chat history. */
object OrbisVideoCallTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        if (me.rerere.ai.provider.Modality.IMAGE !in ctx.model.inputModalities) return messages
        val lastUser = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUser < 0 || messages[lastUser].orbisVoiceCallKind !in setOf("turn", "visual", "opening")) return messages
        val callId = messages[lastUser].orbisVoiceCallId ?: return messages
        // A normal text/image/voice request is not permission to initialize video storage.
        val runtime = OrbisVideoCallRuntime.getIfInitialized() ?: return messages
        val current = runtime.state.value
        if (current.callId != callId || current.assistantId != ctx.assistant.id.toString() ||
            !current.cameraEnabled || !current.foreground) return messages
        if (!runtime.permitsLiveRequest(ctx.assistant.id.toString(), current.conversationId ?: return messages, callId)) return messages
        // A queued tick keeps the exact captured frame, even if an immediate tool captured
        // another frame while that tick was waiting behind an existing generation.
        val frameId = if (messages[lastUser].orbisVoiceCallKind == "visual")
            Regex("frame_id=([0-9a-fA-F-]{36})").find(messages[lastUser].parts
                .filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })?.groupValues?.get(1)
                ?: return messages
        else current.lastFrameId ?: return messages
        val capturedAt = if (frameId == current.lastFrameId) current.lastFrameAtMs else
            runtime.listFrames(ctx.assistant.id.toString(), callId)
                .firstOrNull()?.frames?.firstOrNull { it.id == frameId }?.capturedAtMs
        return messages.mapIndexed { index, message -> if (index != lastUser) message else message.copy(parts = message.parts +
            UIMessagePart.Text("[最近视频抽帧 frame_id=$frameId captured_at_ms=$capturedAt；并非连续视频流，仅表示拍摄时刻。历史帧可用 orbis_video_frames / orbis_video_frame_read 重新查看；结束后 10 分钟过期，需保留时存入照片墙，每通最多 10 张。]") +
            UIMessagePart.Image("orbis-video-frame://$callId/$frameId")) }
    }
}

/** Run at EVERY provider boundary, after the frozen input snapshot is prepared. */
suspend fun projectOrbisVideoRequestImages(context: android.content.Context, assistantId: String,
    messages: List<UIMessage>, modelSupportsImages: Boolean = true): List<UIMessage> = projectVideoFrameHandles(messages,
    allowAutomaticFrame = { message ->
        val call = message.orbisVoiceCallId
        modelSupportsImages && (call == null || OrbisVideoCallRuntime.getIfInitialized()?.let { video ->
            video.permitsLiveRequest(assistantId, video.state.value.conversationId.orEmpty(), call)
        } == true)
    }) { callId, frameId ->
    check(modelSupportsImages) { "当前模型不支持图片，未发送临时画面。" }
    OrbisVideoCallRuntime.get(context).imageDataUrl(assistantId, callId, frameId)
}

internal suspend fun projectVideoFrameHandles(messages: List<UIMessage>,
    allowAutomaticFrame: (UIMessage) -> Boolean = { true },
    resolve: suspend (String, String) -> String): List<UIMessage> {
    val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
    suspend fun project(parts: List<UIMessagePart>, currentTurn: Boolean,
        allowFrame: () -> Boolean = { true }): List<UIMessagePart> = parts.map { part ->
        if (part is UIMessagePart.Image && part.url.startsWith("orbis-video-frame://")) {
            val match = Regex("orbis-video-frame://([0-9a-fA-F-]{36})/([0-9a-fA-F-]{36})").matchEntire(part.url)
            // Permission/runtime checks are lazy too: text, ordinary images, malformed handles
            // and archived frames must not initialize or consult the video subsystem.
            val data = if (match != null && currentTurn && allowFrame()) try {
                resolve(match.groupValues[1], match.groupValues[2])
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null } else null
            if (data != null) UIMessagePart.Image(data) else UIMessagePart.Text(
                "[历史视频图片未附带或已过期；仅保留画面标识。需要时通过视频工具重新读取。]")
        } else part
    }
    return messages.mapIndexed { index, message -> message.copy(parts = message.parts.map { part ->
        if (part is UIMessagePart.Tool) part.copy(output = project(part.output, lastUserIndex >= 0 && index > lastUserIndex))
        else project(listOf(part), lastUserIndex >= 0 && index >= lastUserIndex) { allowAutomaticFrame(message) }.single()
    }) }
}
