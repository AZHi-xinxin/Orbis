package me.rerere.asr.providers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.BatchVoiceRecognitionException
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SiliconFlowTranscriberTest {
    @Test fun settingsRoundTripAndProviderRegistration() {
        val setting: ASRProviderSetting = ASRProviderSetting.SiliconFlow(apiKey="synthetic")
        assertTrue(ASRProviderSetting.Types.contains(ASRProviderSetting.SiliconFlow::class))
        val text=Json.encodeToString(ASRProviderSetting.serializer(),setting)
        assertEquals(setting,Json.decodeFromString(ASRProviderSetting.serializer(),text))
        assertTrue(setting.supportsVoiceCall)
        assertFalse(setting.supportsServerVadVoiceMode)
        assertEquals("synthetic",(setting.copyProvider(setting.id,"renamed") as ASRProviderSetting.SiliconFlow).apiKey)
    }

    @Test fun multipartUsesDocumentedFieldsAndExactPcmWav() {
        val pcm=ByteArray(640){it.toByte()}
        val request=batchVoiceRequest(ASRProviderSetting.SiliconFlow(apiKey="synthetic"),pcm)
        assertEquals("/v1/audio/transcriptions",request.url.encodedPath)
        assertEquals("Bearer synthetic",request.header("Authorization"))
        val body=request.body as MultipartBody
        assertEquals(2,body.parts.size)
        assertEquals("FunAudioLLM/SenseVoiceSmall",Buffer().also{body.part(0).body.writeTo(it)}.readUtf8())
        assertTrue(body.part(1).headers.toString().contains("filename=\"speech.wav\""))
        val wav=Buffer().also{body.part(1).body.writeTo(it)}.readByteArray()
        assertEquals("RIFF",String(wav,0,4,Charsets.US_ASCII))
        assertEquals(16000,ByteBuffer.wrap(wav,24,4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertArrayEquals(pcm,wav.copyOfRange(44,wav.size))
        assertThrows(IllegalArgumentException::class.java){batchVoiceRequest(ASRProviderSetting.SiliconFlow(sampleRate=24000),pcm)}
    }

    @Test fun invalidAndProviderErrorResultsCannotBecomeTranscripts() {
        assertEquals("你好",parseSiliconFlowResponse("""{"text":" 你好 "}"""))
        assertEquals("",parseSiliconFlowResponse("""{"text":""}"""))
        listOf("{}","{\"text\":3}","{\"text\":\"private\",\"error\":\"failed\"}","not json",
            "{\"text\":\"${"x".repeat(64001)}\"}").forEach {
            val error=assertThrows(BatchVoiceRecognitionException::class.java){parseSiliconFlowResponse(it)}
            assertFalse(error.message.orEmpty().contains("private"))
        }
    }

    @Test fun transportAcceptsSuccessAndRejectsOversizedBodyWithoutReplay()=runBlocking<Unit> {
        for (oversized in listOf(false,true)) {
            var calls=0
            val client=OkHttpClient.Builder().addInterceptor{chain->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                    .body((if(oversized) "x".repeat(1_048_577) else "{\"text\":\"synthetic result\"}").toResponseBody()).build()
            }.build()
            try {
                val result=runCatching{BatchVoiceTranscriber(client,ASRProviderSetting.SiliconFlow(apiKey="synthetic")).transcribe(ByteArray(640))}
                if(oversized) assertTrue(result.exceptionOrNull() is BatchVoiceRecognitionException)
                else assertEquals("synthetic result",result.getOrThrow())
                assertEquals(1,calls)
            } finally {client.connectionPool.evictAll();client.dispatcher.executorService.shutdownNow()}
        }
    }
}
