package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NovelAiImageApiTest {
    private val setting = ProviderSetting.OpenAI(baseUrl = NovelAiImageApi.BASE_URL, apiKey = "pst-synthetic")
    private val model = NovelAiImageApi.model()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aV7sAAAAASUVORK5CYII=")
    private val result = """{"images":[{"image":"${Base64.getEncoder().encodeToString(png)}","seed":123,"index":0}]}"""
    private fun response(request: Request, code: Int, data: String = result) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
        .header("Content-Type", "application/json")
        .body(data.toResponseBody("application/json".toMediaType())).build()
    private fun client(block: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { block(it.request()) }.build()

    @Test fun officialOriginAndImageCapabilitiesAreExact() {
        assertTrue(NovelAiImageApi.isConnection(setting.copy(baseUrl = setting.baseUrl + "/")))
        listOf("http://image.novelai.net", "https://image.novelai.net.evil.invalid",
            "https://image.novelai.net:444", "https://user:pass@image.novelai.net", setting.baseUrl + "/v1",
            setting.baseUrl + "?x=y", setting.baseUrl + "#x").forEach {
            assertFalse(it, NovelAiImageApi.isConnection(setting.copy(baseUrl = it)))
        }
        assertFalse(NovelAiImageApi.isConnection(setting.copy(chatGptAccountId = "account")))
        assertEquals(ModelType.IMAGE, model.type)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), model.inputModalities)
    }

    @Test fun fetchingModelsChecksAccountWithoutGeneratingAnImage() = runBlocking {
        var calls = 0
        val actual = OpenAIProvider(client { req ->
            calls++
            assertEquals("GET", req.method)
            assertEquals(NovelAiImageApi.BASE_URL + "/user/subscription", req.url.toString())
            response(req, 200, """{"tier":1,"active":true}""")
        }).listModels(setting)
        assertEquals(1, calls); assertEquals(NovelAiImageApi.supportedModels.keys, actual.map { it.modelId }.toSet())
        assertEquals("workshop_image_auth", runCatching {
            OpenAIProvider(client { response(it, 401) }).listModels(setting)
        }.exceptionOrNull()?.message)
    }

    @Test fun nativeRequestContainsCurrentPromptInBothRequiredLocations() {
        val body = NovelAiImageApi.requestBody(ImageGenerationParams(model, "a starry garden", customBody = listOf(
            CustomBody("seed", JsonPrimitive(4294967295L)), CustomBody("negative_prompt", JsonPrimitive("text")))))
        assertEquals(JsonPrimitive("generate"), body["action"])
        assertFalse(body.containsKey("prompt")); assertFalse(body.containsKey("n"))
        val p = body["parameters"]!!.jsonObject
        assertEquals(JsonPrimitive(1024), p["width"]); assertEquals(JsonPrimitive(1), p["n_samples"])
        assertEquals(body["input"], p["v4_prompt"]!!.jsonObject["caption"]!!.jsonObject["base_caption"])
        assertEquals(p["negative_prompt"], p["v4_negative_prompt"]!!.jsonObject["caption"]!!.jsonObject["base_caption"])
        assertEquals(JsonPrimitive(4294967295L), p["seed"])
    }

    @Test fun v5UsesNativeVersionAndModelSpecificDefaultsWithoutChangingLegacy() {
        for (id in NovelAiImageApi.supportedModels.keys) {
            val body = NovelAiImageApi.requestBody(ImageGenerationParams(NovelAiImageApi.model(id), "a blue galaxy"))
            assertEquals(JsonPrimitive(id), body["model"])
            val p = body.getValue("parameters").jsonObject
            assertEquals(JsonPrimitive(if (id == NovelAiImageApi.MODEL) 3 else 4), p["params_version"])
            assertEquals(JsonPrimitive(if (id == NovelAiImageApi.MODEL) 5.0 else 7.0), p["scale"])
            assertEquals(JsonPrimitive("a blue galaxy"), p.getValue("v4_prompt").jsonObject.getValue("caption").jsonObject["base_caption"])
        }
        assertFalse(NovelAiImageApi.supportsModel("nai-diffusion-5-full-inpainting"))
    }

    @Test fun paidRequestCannotBeChangedIntoBatchEditOrUnboundedGeneration() {
        listOf("model" to JsonPrimitive("other"), "n_samples" to JsonPrimitive(2),
            "image" to JsonPrimitive("private"), "action" to JsonPrimitive("img2img"),
            "width" to JsonPrimitive(1000), "height" to JsonPrimitive(8192),
            "steps" to JsonPrimitive(51), "steps" to JsonPrimitive("23"),
            "scale" to JsonPrimitive(100), "seed" to JsonPrimitive(-1),
            "sampler" to JsonPrimitive("unsupported")).forEach { (key, value) ->
            assertTrue(key, runCatching { NovelAiImageApi.requestBody(ImageGenerationParams(model, "test",
                customBody = listOf(CustomBody(key, value)))) }.isFailure)
        }
        assertTrue(runCatching { NovelAiImageApi.requestBody(ImageGenerationParams(model, "test", numOfImages = 2)) }.isFailure)
        assertTrue(runCatching { NovelAiImageApi.requestBody(ImageGenerationParams(model, "test", size = "garbage")) }.isFailure)
    }

    @Test fun nativeHttpReturnsOriginalBytesAndIsOneShot() = runBlocking {
        var calls = 0
        val provider = OpenAIProvider(client { req ->
            calls++
            assertEquals(NovelAiImageApi.BASE_URL + "/ai/generate-image", req.url.toString())
            assertEquals("Bearer pst-synthetic", req.header("Authorization"))
            assertEquals("application/json", req.header("Accept"))
            assertTrue(req.body!!.isOneShot())
            val b = Buffer(); req.body!!.writeTo(b)
            assertEquals(JsonPrimitive("native test"), Json.parseToJsonElement(b.readUtf8()).jsonObject["input"])
            response(req, 200)
        })
        val items = provider.generateImage(setting, ImageGenerationParams(model, "native test")).toList()
        assertEquals(1, calls); assertArrayEquals(png, Base64.getDecoder().decode(items.single().data))
    }

    @Test fun paidFailuresKeepSpecificSafeCodeAndNeverReplay() = runBlocking {
        for (code in listOf(401, 402, 403, 429, 500, 503, 302)) {
            var calls = 0
            val failure = runCatching { NovelAiImageApi.generate(client { req ->
                calls++; response(req, code, "private provider error containing key or prompt")
            }, setting, ImageGenerationParams(model, "test")) }.exceptionOrNull()
            assertEquals(NovelAiImageApi.errorCode(code), failure?.message); assertEquals(1, calls)
        }
        var calls = 0
        assertTrue(runCatching { NovelAiImageApi.generate(client { calls++; throw IOException("network lost") },
            setting, ImageGenerationParams(model, "test")) }.isFailure)
        assertEquals(1, calls)
    }

    @Test fun multipleReferencesRefusedBeforeSendingOrOpeningAnyFile() = runBlocking {
        var calls = 0
        val provider = OpenAIProvider(client { calls++; response(it, 200) })
        assertEquals("workshop_single_reference_only", runCatching {
            provider.editImage(setting, ImageEditParams(model, "edit", listOf("/private/missing.png", "/private/other.png"))).toList()
        }.exceptionOrNull()?.message)
        assertEquals(0, calls)
    }

    @Test fun referenceRequestUsesCorrectActionAndRestrictsPreciseModeTo45() {
        for (id in NovelAiImageApi.supportedModels.keys) {
            val params = ImageEditParams(NovelAiImageApi.model(id), "same character beside a lake", listOf("source.png"),
                customBody = listOf(CustomBody("strength", JsonPrimitive(0.35)), CustomBody("seed", JsonPrimitive(7))))
            val body = NovelAiImageApi.editRequestBody(params, "cGljdHVyZQ==", "832x1216")
            assertEquals(JsonPrimitive("img2img"), body["action"])
            val p = body.getValue("parameters").jsonObject
            assertEquals(JsonPrimitive("cGljdHVyZQ=="), p["image"])
            assertEquals(JsonPrimitive(0.35), p["strength"])
            assertEquals(JsonPrimitive(7), p["extra_noise_seed"])
            assertEquals(JsonPrimitive(832), p["width"])
            val precise = params.copy(customBody = params.customBody + CustomBody("reference_mode", JsonPrimitive("character&style")))
            if (id == NovelAiImageApi.MODEL) {
                val b = NovelAiImageApi.editRequestBody(precise, "cGljdHVyZQ==")
                assertEquals(JsonPrimitive("generate"), b["action"])
                val p2=b.getValue("parameters").jsonObject
                assertFalse(p2.containsKey("image"));assertEquals(1,p2.getValue("director_reference_images").jsonArray.size)
            } else assertEquals("workshop_reference_mode", runCatching { NovelAiImageApi.editRequestBody(precise,"cGljdHVyZQ==") }.exceptionOrNull()?.message)
            for (value in listOf(JsonPrimitive(-0.1),JsonPrimitive(1.1),JsonPrimitive("0.4")))
                assertTrue(runCatching { NovelAiImageApi.editRequestBody(params.copy(customBody=listOf(CustomBody("strength",value))),"cGljdHVyZQ==") }.isFailure)
        }
        for ((w,h) in listOf(8000 to 1000,1000 to 8000,2048 to 2048,832 to 1216,10 to 10)) {
            val (x,y)=novelAiReferenceSize(w,h)
            assertTrue(x%64==0 && y%64==0 && x*y<=1_048_576 && maxOf(x,y)<=2048)
        }
    }

    @Test fun zipFallbackIsBoundedAndNeverExtractsPaths() {
        fun zip(vararg names: String): ByteArray = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> names.forEach { z.putNextEntry(ZipEntry(it)); z.write(png); z.closeEntry() } }
        }.toByteArray()
        assertArrayEquals(png, Base64.getDecoder().decode(NovelAiImageApi.decode(zip("image_0.png"), "application/zip").single().data))
        for (b in listOf(zip("../image.png"), zip("a.png", "b.png")))
            assertTrue(runCatching { NovelAiImageApi.decode(b, "application/zip") }.isFailure)
        assertTrue(runCatching { NovelAiImageApi.decode("<html>error</html>".toByteArray(), "text/html") }.isFailure)
        assertTrue(runCatching { NovelAiImageApi.decode("""{"images":[]}""".toByteArray(), "application/json") }.isFailure)
        assertTrue(runCatching { NovelAiImageApi.decode("""{"images":[{"image":"bm90IHB uZw=="}]}""".toByteArray(), "application/json") }.isFailure)
    }
}
