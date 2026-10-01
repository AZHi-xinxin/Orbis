package me.rerere.tts.provider.providers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import me.rerere.common.http.SseEvent
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.provider.TTSProviderException
import java.util.Base64

/** Transport EOF is not a synthesis success marker. Never catch downstream emit failures. */
internal fun miniMaxAudioChunks(events: Flow<SseEvent>, model: String, voice: String): Flow<AudioChunk> = flow {
    var complete = false
    events.collect { event ->
        when (event) {
            is SseEvent.Event -> {
                val chunk = parseMiniMaxAudioFrame(event.data, model, voice)
                check(!complete) { "MiniMax 在完成标记后返回了额外音频。" }
                if (chunk.isLast) complete = true
                emit(chunk)
            }
            is SseEvent.Failure -> {
                val status = event.response?.code
                if (status != null) throw TTSProviderException("MiniMax 朗读连接失败：HTTP $status", status)
                error("MiniMax 朗读连接中断，本轮未完成。")
            }
            SseEvent.Open, SseEvent.Closed -> Unit
        }
    }
    check(complete) { "MiniMax 未返回合成完成标记，本轮未完成。" }
}

internal fun parseMiniMaxAudioFrame(payload: String, model: String, voice: String): AudioChunk = safeTtsFrame("MiniMax") {
    val root = Json.parseToJsonElement(payload).jsonObject
    val errorCode = root["base_resp"]?.jsonObject?.get("status_code")?.jsonPrimitive?.intOrNull
    require(errorCode == null || errorCode == 0)
    val data = root.getValue("data").jsonObject
    val status = data.getValue("status").jsonPrimitive.int
    require(status in 1..2)
    val audio = data["audio"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.let { require(it.isString); it.content }.orEmpty()
    val hex = audio.filterNot(Char::isWhitespace)
    require(hex.length % 2 == 0 && hex.all { it.digitToIntOrNull(16) != null })
    AudioChunk(ByteArray(hex.length / 2) { index ->
        ((hex[index * 2].digitToInt(16) shl 4) or hex[index * 2 + 1].digitToInt(16)).toByte()
    }, AudioFormat.MP3, 32000, status == 2,
        mapOf("provider" to "minimax", "model" to model, "voice" to voice, "status" to status.toString()))
}

/** Empty final frames are meaningful; missing audio on sentence metadata is not an error. */
internal fun parseQwenAudioFrame(payload: String, format: String, sampleRate: Int, model: String, voice: String): AudioChunk? =
    safeTtsFrame("Qwen") {
        if (payload.trim() == "[DONE]") return@safeTtsFrame null
        val root = Json.parseToJsonElement(payload).jsonObject
        val status = root["status_code"]?.jsonPrimitive?.intOrNull
        require(status == null || status in 200..299)
        val errorCode = root["code"]?.jsonPrimitive?.contentOrNull
        require(errorCode.isNullOrBlank())
        val output = root["output"]?.takeUnless { it is JsonNull }?.jsonObject ?: return@safeTtsFrame null
        val finish = output["finish_reason"]?.jsonPrimitive?.contentOrNull
        require(finish == null || finish.isEmpty() || finish == "null" || finish == "stop")
        val audio = output["audio"]?.takeUnless { it is JsonNull }?.jsonObject?.get("data")
            ?.takeUnless { it is JsonNull }?.jsonPrimitive?.let { require(it.isString); it.content }.orEmpty()
        if (audio.isEmpty() && finish != "stop") return@safeTtsFrame null
        val decoded = if (audio.isEmpty()) byteArrayOf() else Base64.getDecoder().decode(audio.filterNot(Char::isWhitespace))
        AudioChunk(decoded, when (format.lowercase()) {
            "mp3" -> AudioFormat.MP3; "pcm" -> AudioFormat.PCM; "opus" -> AudioFormat.OPUS; else -> AudioFormat.WAV
        }, sampleRate, finish == "stop", mapOf("provider" to "qwen", "model" to model, "voice" to voice,
            "format" to format, "sampleRate" to sampleRate.toString()))
    }

private inline fun <T> safeTtsFrame(provider: String, parse: () -> T): T = try { parse() }
catch (_: Exception) { throw IllegalArgumentException("$provider 返回了错误或无效的音频数据，本轮未完成。") }
