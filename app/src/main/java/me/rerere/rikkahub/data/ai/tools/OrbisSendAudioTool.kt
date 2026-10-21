package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceNotes
import me.rerere.rikkahub.data.repository.WorkspaceRepository

internal fun existingAudioExtension(path: String, bytes: ByteArray): String {
    require(bytes.size in 12..32 * 1024 * 1024)
    val extension = path.substringAfterLast('.').lowercase()
    fun magic(offset: Int, value: String) = bytes.size >= offset + value.length &&
        bytes.copyOfRange(offset, offset + value.length).toString(Charsets.US_ASCII) == value
    require(when(extension) {
        "wav" -> magic(0, "RIFF") && magic(8, "WAVE")
        "ogg" -> magic(0, "OggS")
        "mp3" -> magic(0, "ID3") || (bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 0xe0 == 0xe0)
        else -> false
    }) { "unsupported_audio" }
    return extension
}

internal fun createOrbisSendAudioTool(notes: OrbisVoiceNotes, workspaceId: String,
    repository: WorkspaceRepository, stillBound: () -> Boolean) = Tool(
    name = "orbis_send_audio",
    description = "把当前已绑定工作区里现成的 WAV/MP3/OGG 发成可点播的原生语音气泡，不调用TTS、不自动播放。path必须是/workspace/下的文件，最大32MiB，不接收URL或手机其他目录。transcript可选，只填已知准确转写，不知道就省略，不要猜。成功气泡即已发出，无需重复路径。可记住此助手、此工作区的发送授权；失败不自动重试。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("path", buildJsonObject { put("type", "string") })
        put("transcript", buildJsonObject { put("type", "string"); put("maxLength", 32000) })
    }, required = listOf("path")) },
    needsApproval = { true },
    hostApproval = HostToolApproval("orbis:send_audio:$workspaceId", "local-audio-v1", "发送工作区音频 · 不调用语音服务"),
    isApprovalCurrent = stillBound,
    execute = { args ->
        try {
            check(stillBound())
            val fields = args.jsonObject
            require(fields.keys.all { it in setOf("path", "transcript") })
            val path = (fields["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("path_required")
            require(path.startsWith("/workspace/") && path.length <= 4096)
            val transcript = fields["transcript"]?.let {
                (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: error("invalid_transcript")
            }.orEmpty()
            require(transcript.length <= 32000)
            val bytes = repository.readNativeAudio(workspaceId, path)
            val extension = existingAudioExtension(path, bytes)
            check(stillBound())
            listOf(notes.save(bytes, extension, transcript))
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { listOf(UIMessagePart.Text("音频未发送，原文件保留。需为当前绑定工作区/workspace/下完整的WAV、MP3或OGG，最大32MiB；不能使用网址、其他目录或符号链接。未调用TTS，未自动重试。")) }
    },
)
