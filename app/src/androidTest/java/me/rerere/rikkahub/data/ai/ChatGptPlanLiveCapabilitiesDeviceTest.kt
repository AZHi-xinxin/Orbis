package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.ReasoningLevel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.provider.chatgpt.ChatGptFailureKind
import me.rerere.ai.provider.chatgpt.ChatGptPlanAccounts
import me.rerere.ai.provider.chatgpt.ChatGptPlanFailure
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.*
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Explicit live acceptance only. Uses the phone vault, never copies tokens out.
 * Synthetic image/messages only: no chat DB, memories, private tools, paid API or settings writes.
 * Maximum four inference requests per selected model. No automatic retries; stop on rate limits.
 * Only public model IDs, finite error categories and pass/fail evidence leave the test process.
 */
class ChatGptPlanLiveCapabilitiesDeviceTest {
    private val allowedModels = listOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6-sol", "gpt-6-luna",
        "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")
    private var requests = 0
    private var stop = false
    private var networkFailures = 0

    @Test fun explicitlyAuthorizedSyntheticCapabilityChecks() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("livePlanAcceptance") == "user-approved-dev90-capabilities-20261014")
        check(instrumentation is IsolatedGenerationLoopRunner) { "isolated_runner_required" }
        val context = instrumentation.targetContext
        check(context.packageName == "org.orbis.agent.dev")
        check(context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode == 263L)
        val selectedModels = args.getString("models").orEmpty().split(',')
        check(selectedModels.isNotEmpty() && selectedModels.size <= 7 &&
            selectedModels.distinct().size == selectedModels.size && selectedModels.all { it in allowedModels })
        val account = ChatGptPlanAccounts(context).accounts().single {
            it.id.startsWith("f28b82f2") && it.signedIn && it.planEnabled
        }
        val setting = ProviderSetting.OpenAI(chatGptAccountId = account.id)
        val provider = OpenAIProvider(OkHttpClient.Builder().retryOnConnectionFailure(false).build(), context)
        val syntheticImage = fixtureImage()
        val schemaOnlyTool = Tool("diagnostic_echo", "Return the provided diagnostic marker.", parameters = {
            InputSchema.Obj(buildJsonObject { putJsonObject("marker") { put("type", "string") } }, listOf("marker"))
        }, execute = { error("Live acceptance must never execute a real local tool") })
        for (slug in selectedModels) {
            // Intentionally retain legacy native-image flags. Production transport must project them.
            val model = Model(modelId = slug, abilities = listOf(ModelAbility.TOOL),
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                tools = setOf(BuiltInTools.ImageGeneration, BuiltInTools.UrlContext))
            val params = TextGenerationParams(model, reasoningLevel = ReasoningLevel.LOW,
                maxAutomaticContinuations = 0)
            probe(slug, "buffered_text_image") {
                val message = UIMessage(role = MessageRole.USER, parts = listOf(
                    UIMessagePart.Text("Name the two colored shapes in this image in English. Do not use tools. Keep the reply under 20 words."),
                    UIMessagePart.Image(syntheticImage)))
                countRequest()
                val result = withTimeout(90_000) { provider.generateText(setting, listOf(message), params) }
                val text = result.message.toText().lowercase()
                buildJsonObject {
                    put("reply_present", text.isNotBlank())
                    put("image_understood", listOf("red", "square", "blue", "circle").all { it in text })
                    put("success", listOf("red", "square", "blue", "circle").all { it in text })
                }
            }
            probe(slug, "streamed_function_roundtrip") {
                val marker = "ORBIS_ECHO_260"
                val input = listOf(UIMessage.user("Call diagnostic_echo once with marker $marker. After receiving its result, reply with the result marker only."))
                val toolParams = params.copy(tools = listOf(schemaOnlyTool))
                val initial = stream(provider, setting, input, toolParams)
                val calls = initial.messages.flatMap { it.getTools() }
                val validCall = calls.size == 1 && calls.single().toolName == schemaOnlyTool.name &&
                    calls.single().inputAsJson().jsonObject["marker"]?.jsonPrimitive?.contentOrNull == marker
                if (!validCall) return@probe buildJsonObject {
                    put("success", false); put("requested_expected_function", false)
                }
                val outputMarker = "ORBIS_RESULT_260"
                val completed = initial.messages.map { message -> message.copy(parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == calls.single().toolCallId)
                        part.copy(output = listOf(UIMessagePart.Text(outputMarker))) else part
                }) }
                val followup = stream(provider, setting, input + completed, toolParams)
                buildJsonObject {
                    put("requested_expected_function", true)
                    put("result_roundtrip", followup.messages.any { outputMarker in it.toText() })
                    put("success", followup.messages.any { outputMarker in it.toText() } &&
                        followup.messages.flatMap { it.getTools() }.isEmpty())
                    put("real_local_tool_executions", 0)
                }
            }
            probe(slug, "native_web_search") {
                val result = stream(provider, setting,
                    listOf(UIMessage.user("Use web search to find the official OpenAI Sign in with ChatGPT token sharing preview limitations documentation. Give one official source URL and one sentence. Search now, do not rely only on memory.")),
                    params.copy(model = model.copy(tools = model.tools + BuiltInTools.Search)))
                val tools = result.messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.ServerTool>()
                val searchCompleted = tools.any { it.toolName == "web_search" && it.status == ServerToolStatus.COMPLETED }
                val text = result.messages.joinToString(" ") { it.toText() }
                buildJsonObject {
                    put("completed_search_call", searchCompleted)
                    put("citations_present", result.messages.any { message ->
                        message.annotations.any { it is UIMessageAnnotation.UrlCitation }
                    })
                    put("reply_present", text.isNotBlank())
                    put("success", searchCompleted && text.isNotBlank())
                }
            }
        }
        emit(buildJsonObject {
            put("summary", true); put("inference_requests_started", requests)
            put("stopped_early", stop); put("automatic_retry", false)
            put("private_chat_or_memory_access", false); put("real_local_tool_execution", false)
        })
    }

    private data class StreamResult(val messages: List<UIMessage>)
    private suspend fun stream(provider: OpenAIProvider, setting: ProviderSetting.OpenAI,
        input: List<UIMessage>, params: TextGenerationParams): StreamResult = withTimeout(90_000) {
        countRequest()
        val handler = StreamChunkHandler(params.model)
        var output = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), modelId = params.model.id))
        var finished = false
        var chunks = 0
        provider.streamText(setting, input, params).collect { chunk ->
            check(++chunks <= 5000) { "diagnostic_limit" }
            output = handler.handle(output, chunk)
            check(output.size <= 64 && output.sumOf { it.toText().length } < 65_536) { "diagnostic_limit" }
            if (chunk is StreamChunk.Finish) finished = true
        }
        check(finished) { "diagnostic_no_terminal" }
        StreamResult(output)
    }

    private fun countRequest() { check(++requests <= 28) { "diagnostic_request_limit" } }
    private suspend fun probe(model: String, kind: String, action: suspend () -> JsonObject) {
        if (stop) {
            emit(buildJsonObject { put("model", model); put("check", kind); put("state", "NOT_RUN_AFTER_LIMIT_OR_NETWORK_FAILURE") })
            return
        }
        val result = try { action().also { networkFailures = 0 } }
        catch (_: TimeoutCancellationException) {
            networkFailures++; if (networkFailures >= 3) stop = true
            buildJsonObject { put("success", false); put("error", "TIMEOUT") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val safe = ChatGptPlanFailure.from(error)
            if (safe.kind == ChatGptFailureKind.LIMIT) stop = true
            if (safe.kind == ChatGptFailureKind.NETWORK) networkFailures++ else networkFailures = 0
            if (networkFailures >= 3) stop = true
            buildJsonObject {
                put("success", false); put("error", safe.kind.name)
                safe.httpStatus?.let { put("http", it) }
                safe.errorCode?.let { put("code", it) }
                safe.parameter?.let { put("parameter", it) }
                put("stage", safe.stage.name)
            }
        }
        emit(buildJsonObject { put("model", model); put("check", kind); put("result", result) })
    }
    private fun emit(value: JsonObject) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("stream", "ORBIS_PLAN_ACCEPTANCE $value\n") })

    private fun fixtureImage(): String {
        val bitmap = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.RED
        canvas.drawRect(40f, 40f, 216f, 216f, paint)
        paint.color = Color.BLUE
        canvas.drawCircle(384f, 128f, 88f, paint)
        val encoded = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }
        bitmap.recycle()
        return "data:image/png;base64,$encoded"
    }
}
