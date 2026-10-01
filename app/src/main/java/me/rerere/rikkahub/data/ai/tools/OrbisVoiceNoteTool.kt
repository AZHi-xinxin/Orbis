package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceNotes
import me.rerere.rikkahub.data.model.ORBIS_VOICE_NOTE_MAX_TEXT_CHARS

internal fun createOrbisVoiceNoteTool(notes: OrbisVoiceNotes, settings: () -> Settings) = Tool(
    name = "orbis_voice_note",
    description = "发送一条独立、可点击播放的原生语音气泡（不是立即朗读）。text 为你要说的正文，1–$ORBIS_VOICE_NOTE_MAX_TEXT_CHARS 字符。沿用人类设置的TTS/音色，可能消耗其语音额度。成功后气泡已经发出，无需再复制正文、文件路径或发送占位文字。用户可长按转文字和查看生成详情。失败不会自动重试。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("text", buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", ORBIS_VOICE_NOTE_MAX_TEXT_CHARS) })
    }, required = listOf("text")) },
    needsApproval = { true },
    hostApproval = HostToolApproval("orbis:voice_note", "voice-note-v1", "发送语音条 · 使用当前语音服务"),
    execute = { arguments ->
        val text = (arguments as? JsonObject)?.get("text")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
        if (text.isNullOrBlank() || text.length > ORBIS_VOICE_NOTE_MAX_TEXT_CHARS) listOf(UIMessagePart.Text("语音条参数错误：text 需为 1–$ORBIS_VOICE_NOTE_MAX_TEXT_CHARS 字符"))
        else {
            val provider = settings().getSelectedTTSProvider()
            if (provider == null) listOf(UIMessagePart.Text("未配置语音服务。请指导人类到系统设置→语音与朗读配置，未发送语音条。"))
            else try {
                listOf(notes.synthesize(provider, text))
            } catch (_: TimeoutCancellationException) {
                listOf(UIMessagePart.Text("语音条生成超时，未发送语音条；未自动重试，语音服务可能已计费。"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { listOf(UIMessagePart.Text("语音条生成失败；请核对语音服务、音色、额度和联网。未自动重试。")) }
        }
    },
)
