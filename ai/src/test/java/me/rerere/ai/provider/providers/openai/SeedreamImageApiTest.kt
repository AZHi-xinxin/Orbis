package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** Synthetic HTTP only: never uses an account, a real key or a billable provider. */
class SeedreamImageApiTest {
    @get:Rule val folder = TemporaryFolder()
    private val model = Model(modelId = "doubao-seedream-4-0-250828")
    private val setting = ProviderSetting.OpenAI(baseUrl = "https://ark.cn-beijing.volces.com/api/v3", apiKey = "synthetic-key")
    private val result = """{"data":[{"b64_json":"c3ludGhldGlj"}]}"""

    @Test fun officialRouteRequiresExactOriginAndVersion() {
        assertTrue(SeedreamImageApi.usesArk(setting, model))
        assertTrue(SeedreamImageApi.usesArk(setting.copy(baseUrl = setting.baseUrl + "/"), model))
        for (base in listOf("http://ark.cn-beijing.volces.com/api/v3", "https://ark.cn-beijing.volces.com.evil.invalid/api/v3",
            "https://ark.cn-beijing.volces.com:444/api/v3", setting.baseUrl + "/images/generations",
            setting.baseUrl + "?x=y", setting.baseUrl + "#fragment", "https://user:pass@ark.cn-beijing.volces.com/api/v3")) {
            assertFalse(base, SeedreamImageApi.usesArk(setting.copy(baseUrl = base), model))
        }
        assertFalse(SeedreamImageApi.usesArk(setting.copy(chatGptAccountId = "synthetic-account"), model))
        assertFalse(SeedreamImageApi.usesArk(setting, model.copy(modelId = "gpt-image-2")))
    }

    @Test fun imageAndVideoModelsStayDistinct() {
        assertTrue(SeedreamImageApi.isImageModel(model.modelId))
        assertTrue(SeedreamImageApi.isImageModel("doubao-seedream-4-5-251128"))
        assertTrue(SeedreamImageApi.isImageModel("doubao-seedream-5-0-lite-260128"))
        assertTrue(SeedreamImageApi.isImageModel("doubao-seedream-5-0-pro-260628"))
        assertFalse(SeedreamImageApi.isImageModel("doubao-seedance-2-5-260628"))
        assertTrue(SeedreamImageApi.isVideoModel("doubao-seedance-2-5-260628"))
        assertFalse(SeedreamImageApi.isImageModel("doubao-seedream-3-0-t2i-250415"))
    }

    @Test fun discoveryDefaultsPreserveIdentityAndUserConfiguration() {
        val original = model.copy(displayName = "My maker", customBodies = listOf(CustomBody("watermark", JsonPrimitive(false))),
            providerOverwrite = setting)
        val updated = SeedreamImageApi.modelDefaults(original)
        assertEquals(original.id, updated.id)
        assertEquals(original.displayName, updated.displayName)
        assertEquals(original.customBodies, updated.customBodies)
        assertEquals(original.providerOverwrite, updated.providerOverwrite)
        assertEquals(ModelType.IMAGE, updated.type)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), updated.inputModalities)
        assertEquals(listOf(Modality.IMAGE), updated.outputModalities)
        assertTrue(updated.abilities.isEmpty())
        val ordinary = Model(modelId = "ordinary-chat")
        assertSame(ordinary, SeedreamImageApi.modelDefaults(ordinary))
    }

    @Test fun registryDoesNotOverwriteImageModalitiesWithChatDefaults() {
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId))
        assertEquals(listOf(Modality.IMAGE), ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId))
        assertTrue(ModelRegistry.MODEL_ABILITIES.getData(model.modelId).isEmpty())
    }

    @Test fun defaultRequestIsOneNonStreamingImageAtSupportedSize() {
        val body = SeedreamImageApi.requestBody(model, "synthetic prompt", "auto", 1, emptyList())
        assertEquals(JsonPrimitive("2K"), body["size"])
        assertEquals(JsonPrimitive(false), body["stream"])
        assertEquals(JsonPrimitive("b64_json"), body["response_format"])
        assertEquals(JsonPrimitive("disabled"), body["sequential_image_generation"])
        assertFalse(body.containsKey("n"))
        assertFalse(body.containsKey("image"))
    }

    @Test fun referencesAreJsonDataUrisNotMultipartFields() {
        val refs = listOf("data:image/png;base64,AQID", "data:image/jpeg;base64,BAUG")
        val body = SeedreamImageApi.requestBody(model, "synthetic", "4K", 1, emptyList(), refs)
        assertEquals(JsonArray(refs.map(::JsonPrimitive)), body["image"])
        assertEquals(JsonPrimitive("4K"), body["size"])
        assertFalse(body.containsKey("image[]"))
    }

    @Test fun flashAndProOmitUnsupportedSequentialField() {
        for (id in listOf("doubao-seedream-5-0-pro-260628", "doubao-seedream-5-0-flash-260628")) {
            val selected = model.copy(modelId = id)
            val body = SeedreamImageApi.requestBody(selected, "synthetic", "auto", 1, emptyList())
            assertFalse(body.containsKey("sequential_image_generation"))
            assertFalse(body.containsKey("stream"))
            assertTrue(runCatching { SeedreamImageApi.requestBody(selected, "synthetic", "auto", 1,
                listOf(CustomBody("stream", JsonPrimitive(false)))) }.isFailure)
            assertTrue(runCatching { SeedreamImageApi.requestBody(selected, "synthetic", "auto", 1,
                listOf(CustomBody("sequential_image_generation", JsonPrimitive("disabled")))) }.isFailure)
        }
    }

    @Test fun customParametersCannotEnableMultipleImagesOrChangeInputIdentity() {
        val invalid = listOf("model" to JsonPrimitive("different"), "prompt" to JsonPrimitive("different"),
            "image" to JsonPrimitive("https://unrelated.invalid"), "n" to JsonPrimitive(2), "stream" to JsonPrimitive(true),
            "layer_decomposition" to JsonPrimitive(true), "sequential_image_generation_options" to buildJsonObject { put("max_images", 9) },
            "sequential_image_generation" to JsonPrimitive("auto"), "response_format" to JsonPrimitive("unknown"))
        for ((key, value) in invalid) assertTrue(key, runCatching {
            SeedreamImageApi.requestBody(model, "synthetic", "auto", 1, listOf(CustomBody(key, value)))
        }.isFailure)
        assertTrue(runCatching { SeedreamImageApi.requestBody(model, "synthetic", "auto", 2, emptyList()) }.isFailure)
        assertTrue(runCatching { SeedreamImageApi.requestBody(model, "synthetic", "auto", 1, emptyList(), List(5) { "reference" }) }.isFailure)
    }

    @Test fun benignCustomParametersRemainAvailable() {
        val body = SeedreamImageApi.requestBody(model, "synthetic", "auto", 1,
            listOf(CustomBody("watermark", JsonPrimitive(false)), CustomBody("response_format", JsonPrimitive("url"))))
        assertEquals(JsonPrimitive(false), body["watermark"])
        assertEquals(JsonPrimitive("url"), body["response_format"])
    }

    @Test fun providerGenerateUsesArkJsonAndNeverInheritsChatPath() = runBlocking {
        var calls = 0
        val provider = provider { request ->
            calls++
            assertEquals("https://ark.cn-beijing.volces.com/api/v3/images/generations", request.url.toString())
            assertEquals("Bearer synthetic-key", request.header("Authorization"))
            assertTrue("Billed image requests must not be replayable", request.body!!.isOneShot())
            val body = requestJson(request)
            assertEquals(JsonPrimitive("2K"), body["size"])
            assertFalse(body.containsKey("n"))
            response(request, 200, result)
        }
        val items = provider.generateImage(setting.copy(baseUrl = setting.baseUrl + "/"), ImageGenerationParams(model, "synthetic")).toList()
        assertEquals(1, calls)
        assertEquals("c3ludGhldGlj", items.single().data)
    }

    @Test fun providerEditUsesSameEndpointWithBase64References() = runBlocking {
        val file = folder.newFile("reference.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var calls = 0
        val provider = provider { request ->
            calls++
            assertEquals("/api/v3/images/generations", request.url.encodedPath)
            assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
            assertEquals(JsonArray(listOf(JsonPrimitive("data:image/png;base64,AQID"))), requestJson(request)["image"])
            response(request, 200, result)
        }
        assertEquals(1, provider.editImage(setting, ImageEditParams(model, "synthetic", listOf(file.absolutePath))).toList().size)
        assertEquals(1, calls)
    }

    @Test fun replacementRevisionUsesConfiguredIdAndExistingImageFormatWithoutSilentMigration() = runBlocking {
        val replacement = SeedreamImageApi.modelDefaults(model.copy(modelId = "doubao-seedream-4-0-20260415"))
        assertEquals(model.id, replacement.id)
        assertEquals(ModelType.IMAGE, replacement.type)
        assertEquals(listOf(Modality.IMAGE), ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(replacement.modelId))
        var calls = 0
        val provider = provider { request ->
            calls++
            assertEquals("/api/v3/images/generations", request.url.encodedPath)
            val body = requestJson(request)
            assertEquals(JsonPrimitive(replacement.modelId), body["model"])
            assertEquals(JsonPrimitive("b64_json"), body["response_format"])
            assertEquals(JsonPrimitive("2K"), body["size"])
            assertEquals(JsonPrimitive("disabled"), body["sequential_image_generation"])
            response(request, 200, result)
        }
        val items = provider.generateImage(setting, ImageGenerationParams(replacement, "synthetic")).toList()
        assertEquals(1, calls)
        assertEquals("c3ludGhldGlj", items.single().data)
        // Existing customers may still use the old revision; never silently substitute a billed model.
        assertEquals(JsonPrimitive(model.modelId), SeedreamImageApi.requestBody(model, "synthetic", "auto", 1, emptyList())["model"])
    }

    @Test fun discoveryMarksSeedreamAsImageButKeepsOtherModelsUntouched() = runBlocking {
        val provider = provider { request -> response(request, 200,
            """{"data":[{"id":"doubao-seedream-4-0-250828"},{"id":"ordinary-chat"},{"id":"doubao-seedance-2-5-260628"}]}""") }
        val models = provider.listModels(setting)
        assertEquals(ModelType.IMAGE, models[0].type)
        assertEquals(listOf(Modality.IMAGE), models[0].outputModalities)
        assertEquals(ModelType.CHAT, models[1].type)
        assertFalse(SeedreamImageApi.isImageModel(models[2].modelId))
    }

    @Test fun unsupportedVideoAndSubscriptionNeverContactNetwork() = runBlocking {
        var calls = 0
        val provider = provider { request -> calls++; response(request, 200, result) }
        val cases = listOf(setting to model.copy(modelId = "doubao-seedance-2-5-260628"),
            setting.copy(chatGptAccountId = "synthetic-account") to model)
        for ((configured, selected) in cases) {
            assertTrue(runCatching { provider.generateImage(configured, ImageGenerationParams(selected, "synthetic")).toList() }.isFailure)
            assertTrue(runCatching { provider.editImage(configured, ImageEditParams(selected, "synthetic", listOf("unused"))).toList() }.isFailure)
        }
        assertEquals(0, calls)
    }

    @Test fun errorsAreSanitizedAndNeverAutomaticallyRetried() = runBlocking {
        for ((code, expected) in listOf(401 to "workshop_image_auth", 403 to "workshop_image_access", 404 to "workshop_image_model",
            429 to "workshop_image_limit", 500 to "workshop_provider_failed")) {
            var calls = 0
            val provider = provider { request -> calls++; response(request, code, "sensitive upstream content") }
            val failure = runCatching { provider.generateImage(setting, ImageGenerationParams(model, "synthetic")).toList() }.exceptionOrNull()
            assertEquals(expected, failure?.message)
            assertEquals(1, calls)
        }
    }

    @Test fun redirectIsNotFollowedAndCredentialsDoNotLeaveConfiguredHost() = runBlocking {
        var calls = 0
        val provider = provider { request -> calls++; response(request, 302, "").newBuilder()
            .header("Location", "https://unrelated.invalid/image").build() }
        assertTrue(runCatching { provider.generateImage(setting, ImageGenerationParams(model, "synthetic")).toList() }.isFailure)
        assertEquals(1, calls)
    }

    @Test fun transportFailureDoesNotTriggerAnotherImageRequest() = runBlocking {
        var calls = 0
        val provider = provider { calls++; throw IOException("synthetic transport failure") }
        assertTrue(runCatching { provider.generateImage(setting, ImageGenerationParams(model, "synthetic")).toList() }.isFailure)
        assertEquals(1, calls)
    }

    private fun provider(handler: (Request) -> Response) = OpenAIProvider(OkHttpClient.Builder()
        .addInterceptor { handler(it.request()) }.build())
    private fun requestJson(request: Request) = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
    private fun response(request: Request, code: Int, body: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(body.toResponseBody("application/json".toMediaType())).build()
}
