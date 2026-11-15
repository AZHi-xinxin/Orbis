package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.common.deepseek.DeepSeekTools
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.*
import okio.ByteString

internal fun deepSeekSpeechText(text: String): String {
    require(text.isNotBlank() && text.codePointCount(0, text.length) <= 5000) { "DeepSeek 单段朗读最多 5000 字符，请缩短文字。" }
    val body = buildJsonObject { put("event", "text"); put("text", text) }.toString()
    require(body.toByteArray().size <= 64 * 1024) { "DeepSeek 单段朗读内容过大。" }
    return body
}

internal class DeepSeekSpeechFrames {
    var finished = false
        private set
    private var count = 0L
    fun audio(bytes: ByteArray): AudioChunk {
        check(!finished) { "DeepSeek 语音在结束后返回了音频。" }
        count += bytes.size
        check(count <= 32L * 1024 * 1024) { "DeepSeek 单段语音过大。" }
        return AudioChunk(bytes, AudioFormat.PCM, sampleRate = 24000)
    }
    fun finish(text: String): AudioChunk {
        val value = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        check(!finished && value?.get("event") == JsonPrimitive("finish") &&
            value["code"] == JsonPrimitive("success") && count > 0 && count % 2 == 0L) {
            "DeepSeek 语音未完整生成，未自动重试。"
        }
        finished = true
        return AudioChunk(byteArrayOf(), AudioFormat.PCM, sampleRate = 24000, isLast = true)
    }
}

/** A success finish frame is mandatory; a disconnected partial stream is never labelled complete. */
class DeepSeekTTSProvider : TTSProvider<TTSProviderSetting.DeepSeek> {
    private val http = DeepSeekTools.client(OkHttpClient())
    override fun generateSpeech(context: Context, providerSetting: TTSProviderSetting.DeepSeek,
        request: TTSRequest): Flow<AudioChunk> = callbackFlow {
        require(providerSetting.voice in setOf("stella", "tide")) { "请选择 DeepSeek 的 Stella 或 Tide 音色。" }
        val text = deepSeekSpeechText(request.text)
        val frames = DeepSeekSpeechFrames()
        val apiRequest = DeepSeekTools.request("/v1/tools/tts", providerSetting.apiKey)
            .url("wss://api.deepseek.com/v1/tools/tts?voice_id=${providerSetting.voice}&format=pcm&filter_markdown=true").build()
        val socket = http.newWebSocket(apiRequest, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(text) || !webSocket.send("{\"event\":\"finish_text\"}")) {
                    close(IllegalStateException("DeepSeek 语音请求未能发送。")); webSocket.cancel()
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try { trySend(frames.audio(bytes.toByteArray())).getOrThrow() }
                catch (error: Exception) { close(error); webSocket.cancel() }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { trySend(frames.finish(text)).getOrThrow(); close(); webSocket.close(1000, null) }
                catch (error: Exception) { close(error); webSocket.cancel() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.close()
                close(IllegalStateException("DeepSeek 语音连接失败或中断，未自动重试。请检查官方 Key、余额和网络。"))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!frames.finished) close(IllegalStateException("DeepSeek 语音提前结束，未自动重试。"))
                webSocket.close(code, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!frames.finished) close(IllegalStateException("DeepSeek 语音没有返回完成标记。"))
            }
        })
        val deadline = launch { delay(180_000); close(IllegalStateException("DeepSeek 语音生成超时。")); socket.cancel() }
        awaitClose { deadline.cancel(); socket.cancel() }
    }.buffer(Channel.UNLIMITED)
}
