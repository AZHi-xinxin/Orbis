package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.withVoiceNoteTranscripts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolInvocationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.approval.awaitHostApproval
import me.rerere.rikkahub.data.ai.approval.matchesHostApproval
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.withoutDeletedToolRecordData
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.ai.compaction.COMPACT_TOOL_NAME
import me.rerere.rikkahub.data.ai.compaction.ConversationCompactionControl
import me.rerere.rikkahub.data.ai.compaction.CompactionReplacement
import me.rerere.rikkahub.data.ai.compaction.AppliedCompaction
import me.rerere.rikkahub.data.ai.compaction.PreparedCompaction
import me.rerere.rikkahub.data.ai.compaction.buildCompactionReplacement
import me.rerere.rikkahub.data.ai.compaction.compactionError
import me.rerere.rikkahub.data.ai.compaction.estimateCurrentContext
import me.rerere.rikkahub.data.ai.compaction.isCompactionSummary
import me.rerere.rikkahub.data.ai.compaction.prepareCompaction
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.MessageNodeCapacityException
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.OrbisConversationPrompt
import me.rerere.rikkahub.data.model.appendOrbisConversationPrompt
import me.rerere.rikkahub.utils.takeAtCodePointBoundary
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024
private const val MAX_PROVIDER_NETWORK_RETRIES = 3
private const val INITIAL_PROVIDER_RETRY_DELAY_MS = 1_000L

private class StreamChunkHandlingException(cause: Throwable) : RuntimeException(cause)

/** Storage failures must escape tool error handling: another external action is not safe. */
internal class GenerationDurabilityException(cause: Throwable) :
    IllegalStateException("本轮记录未能安全保存，已停止继续调用；请勿重复执行结果未知的工具。", cause)

internal class GenerationToolOutcomeUnknownException :
    IllegalStateException("工具已开始但未收到可靠结果，后续调用已暂停；请核对外部结果，不要直接重试。")

/**
 * A tool-owned, side-effect-free preflight rejected the request before its mutation callback.
 * [publicReason] is deliberately supplied by the tool and must be safe to show to the model.
 */
internal class ToolRejectedBeforeExecutionException(
    val publicReason: String,
    cause: Throwable? = null,
) : IllegalArgumentException("Tool request was rejected before execution", cause)

internal fun toolOutcomeIsUnknown(
    executionStarted: Boolean,
    durableCheckpoints: Boolean,
    failure: Throwable,
): Boolean = executionStarted && failure !is ToolRejectedBeforeExecutionException &&
    (durableCheckpoints || failure is MessageNodeCapacityException)

internal fun rejectedToolPayload(failure: ToolRejectedBeforeExecutionException) = buildJsonObject {
    put("status", JsonPrimitive("failed"))
    put("reason_code", JsonPrimitive("tool_rejected_before_execution"))
    put("execution_performed", JsonPrimitive(false))
    put("message", JsonPrimitive(failure.publicReason.ifBlank {
        "工具在写入前拒绝了请求；请核对当前参数后再试。"
    }.take(300)))
}

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>,
        val contextEpoch: Long? = null,
    ) : GenerationChunk
    data class DurableBoundary(
        val messages: List<UIMessage>,
        val contextEpoch: Long,
        val toolCallId: String? = null,
        val toolName: String? = null,
        val toolCompleted: Boolean? = null,
        val result: CompletableDeferred<Unit>,
    ) : GenerationChunk
    data class CompactionCommit(
        val before: List<UIMessage>,
        val replacement: CompactionReplacement,
        val contextEpoch: Long,
        val result: CompletableDeferred<AppliedCompaction>,
    ) : GenerationChunk
    /** Consultation-only opt-in. Consumers must wait for their final durable commit. */
    data class TerminalResponse(val evidence: GenerationTerminalEvidence) : GenerationChunk
    /** Host capacity pause only; never a provider terminal/completion claim. */
    data object HistoryBudgetStop : GenerationChunk
    /** Completed tool results have no next request because the host step budget is exhausted. */
    data object ToolStepLimitStop : GenerationChunk
}

class GenerationLoop(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val localMemory: me.rerere.rikkahub.data.orbis.memory.OrbisMemoryRuntime? = null,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        orbisPrompt: OrbisConversationPrompt = OrbisConversationPrompt(),
        compactionControl: ConversationCompactionControl? = null,
        initialContextEpoch: Long = 0,
        includeCompactionReminder: Boolean = false,
        durableCheckpoints: Boolean = false,
        outputFrozenPrefixCount: Int? = null,
        maxAutomaticContinuations: Int = 5,
        consultationBusyWaitUntilMillis: Long? = null,
        emitTerminalEvidence: Boolean = false,
        // Group and AI-to-AI invocations must neither inject private notes nor advance human turns.
        allowLocalMemory: Boolean = true,
        admitOutput: suspend (List<UIMessage>) -> Boolean = { false },
        onGatewayRequest: ((me.rerere.ai.util.OrbisGatewayRequest) -> Unit)? = null,
        requireActiveTurn: () -> Unit = {},
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        // One snapshot per collected generation, not per conversation. A new human wake (or the
        // existing approval-resume path) reevaluates current settings, memory and input transforms.
        val inputSnapshot = GenerationInputSnapshot(enableUserMessageTime = assistant.enableUserMessageTime)
        val localMemoryTurn = if (allowLocalMemory && me.rerere.rikkahub.BuildConfig.ORBIS_ENABLED)
            localMemory?.begin(assistant, conversationId, messages) else null
        val tools = snapshotGenerationTools(tools)
        val privatePresentation = PrivateRoomGenerationPresentation(
            enabled = tools.any { it.name == "orbis_private_room_write" },
        )
        val schemaText = tools.joinToString("\n") { it.name + it.description + it.parameters().toString() }

        var messages: List<UIMessage> = messages
        privatePresentation.classify(messages, completed = false) // also cover approval/resume turns
        var contextEpoch = initialContextEpoch
        var lastVisualEmissionNanos = 0L
        var historySoftReached = false
        suspend fun admit(candidate: List<UIMessage>) {
            requireBoundedWorkingMessage(candidate.lastOrNull())
            historySoftReached = admitOutput(candidate)
        }
        var outputScope = outputFrozenPrefixCount?.let { count ->
            require(count in 0..messages.size)
            OutputGenerationScope(messages.take(count))
        }
        suspend fun transformOutput(
            snapshot: List<UIMessage>, transform: suspend (List<UIMessage>) -> List<UIMessage>,
        ): List<UIMessage> = outputScope?.apply(snapshot, transform) ?: transform(snapshot)

        suspend fun durableBoundary(
            snapshot: List<UIMessage>,
            tool: UIMessagePart.Tool? = null,
            completed: Boolean? = null,
        ) {
            admit(snapshot)
            if (!durableCheckpoints) return
            val acknowledgement = CompletableDeferred<Unit>()
            emit(GenerationChunk.DurableBoundary(snapshot, contextEpoch,
                tool?.toolCallId, tool?.toolName, completed, acknowledgement))
            try {
                acknowledgement.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw GenerationDurabilityException(failure)
            }
        }

        for (stepIndex in 0 until maxSteps) {
            requireActiveTurn()
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val protectedResume = privatePresentation.protectOutstandingTools(messages)
            if (protectedResume !== messages) {
                messages = protectedResume
                admit(messages)
                emit(GenerationChunk.Messages(messages, contextEpoch))
            }

            // Check if we have tool calls ready to continue after user interaction.
            val lastTools = messages.lastOrNull()?.getTools().orEmpty()
            if (lastTools.any { it.isPending }) break
            admit(messages) // Seed from persisted branches on a resumed/continued generation.
            if (canStopAtHistoryBudget(historySoftReached, messages)) {
                emit(GenerationChunk.HistoryBudgetStop)
                break
            }
            val pendingTools = resumedApprovalBatch(lastTools)

            val toolsToProcess: List<UIMessagePart.Tool>
            var responseForContinuation: UIMessage? = null

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                responseForContinuation = generateInternal(
                    requireActiveTurn = requireActiveTurn,
                    onGatewayRequest = onGatewayRequest,
                    inputSnapshot = inputSnapshot,
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        val classified = privatePresentation.classify(it, completed = false)
                        val candidate = transformOutput(classified) { active -> active.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        ) }
                        requireBoundedWorkingMessage(candidate.lastOrNull())
                        // Persisted UI publication is bounded to 10Hz. The working stream still
                        // merges every token; final/provider and tool boundaries are never skipped.
                        val nowNanos = System.nanoTime()
                        if (!durableCheckpoints || lastVisualEmissionNanos == 0L ||
                            nowNanos - lastVisualEmissionNanos >= 100_000_000L) {
                        lastVisualEmissionNanos = nowNanos
                        val visible = transformOutput(candidate) { active -> active.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                ) }
                        admit(visible)
                        emit(GenerationChunk.Messages(visible, contextEpoch))
                        }
                        messages = candidate
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = tools,
                    schemaText = schemaText,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationId = conversationId,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                    orbisPrompt = orbisPrompt,
                    compactionControl = compactionControl,
                    includeCompactionReminder = includeCompactionReminder,
                    localMemoryTurn = localMemoryTurn,
                    maxAutomaticContinuations = maxAutomaticContinuations,
                    consultationBusyWaitUntilMillis = consultationBusyWaitUntilMillis.takeIf {
                        stepIndex == 0 && messages.lastOrNull()?.role == MessageRole.USER
                    },
                )
                messages = transformOutput(messages) { active -> active.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                ) }
                messages = transformOutput(messages) { active -> active.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                ) }
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                messages = privatePresentation.classify(messages, completed = true)
                admit(messages)
                emit(GenerationChunk.Messages(messages, contextEpoch))

                val toolCalls = messages.last().getTools().filter { !it.isExecuted }
                if (toolCalls.isEmpty()) {
                    val rawTerminalResponse = responseForContinuation
                    if (emitTerminalEvidence && rawTerminalResponse != null &&
                        !generationResponseHasToolParts(rawTerminalResponse)) {
                        // Use only this provider step, never the full merged bubble. Remove
                        // attachments before the same output pipeline to avoid duplicate file writes.
                        val terminalResponse = listOf(generationTerminalTextInput(rawTerminalResponse))
                            .transforms(outputTransformers, context, model, assistant, settings)
                            .visualTransforms(outputTransformers, context, model, assistant, settings)
                            .onGenerationFinish(outputTransformers, context, model, assistant, settings)
                            .singleOrNull()
                        terminalResponse?.let { final ->
                            createGenerationTerminalEvidence(rawTerminalResponse, final, messages.last(), contextEpoch)
                                ?.let { emit(GenerationChunk.TerminalResponse(it)) }
                        }
                    }
                    // no tool calls, break
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = toolCalls.map { tool ->
                    val protected = privatePresentation.protectTool(tool)
                    val toolDef = tools.find { it.name == tool.toolName }
                    when {
                        protected !== tool -> protected
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.awaitHostApproval(toolDef)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != toolCalls) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    admit(messages)
                    emit(GenerationChunk.Messages(messages, contextEpoch))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
                inputSnapshot.validateResponseTools(responseForContinuation, toolsToProcess)
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = pendingTools.map(privatePresentation::protectTool)
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            fun withCurrentToolResults(): List<UIMessage> {
                if (executedTools.isEmpty()) return messages
                val completedById = executedTools.associateBy { it.toolCallId }
                return messages.dropLast(1) + messages.last().copy(parts = messages.last().parts.map { part ->
                    if (part is UIMessagePart.Tool) completedById[part.toolCallId] ?: part else part
                })
            }
            var requestedCompaction: Pair<UIMessagePart.Tool, PreparedCompaction>? = null
            toolsToProcess.forEach { tool ->
                requireActiveTurn()
                var executionStarted = false
                if (tool.toolName == COMPACT_TOOL_NAME && compactionControl != null &&
                    tool.approvalState !is ToolApprovalState.Denied && !tool.isPending) {
                    val output = runCatching {
                        check(!privatePresentation.hasPrivateOperation) {
                            "本轮包含私密操作，不能把私密内容压入普通聊天摘要；请在下一次普通聊天中再整理。"
                        }
                        check(requestedCompaction == null) { "一批工具只执行一次compact，请先读取这次回执。" }
                        check(inputSnapshot.isPrepared && responseForContinuation != null) {
                            "本轮在恢复旧工具审批，请在接下来的自动续轮重新调用compact；原文未改变。"
                        }
                        val prepared = prepareCompaction(json.parseToJsonElement(tool.input.ifBlank { "{}" }), messages, responseForContinuation)
                        requestedCompaction = tool to prepared
                        "{\"status\":\"pending_batch_completion\",\"context_changed\":false}"
                    }.getOrElse { compactionError(it.message ?: "参数无效，本次未整理。") }
                    executedTools += tool.withGenerationToolOutput(listOf(UIMessagePart.Text(output)))
                    return@forEach
                }
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        runCatching {
                            executedTools += tool.dispatchProvidedTool(tools) { toolDef ->
                                check(toolDef.isApprovalCurrent()) { "工具目标或授权已变化，请重新发起调用；此次未执行。" }
                                if (tool.approvalState is ToolApprovalState.Approved) {
                                    check(tool.matchesHostApproval(toolDef)) { "审批快照已过期或参数已改变，请重新发起调用；此次未执行。" }
                                } else if (toolDef.needsApproval(tool.inputAsJson())) {
                                    // A remembered grant may have been revoked after model generation.
                                    return@dispatchProvidedTool tool.awaitHostApproval(toolDef)
                                }
                                val cloudNative = toolDef.name.startsWith("cloud_orbis_") ||
                                    toolDef.name.startsWith("cloud_reading_") || toolDef.name.startsWith("cloud_turtlesoup_")
                                val args = runCatching {
                                    json.parseToJsonElement(tool.input.ifBlank { "{}" })
                                }.getOrElse {
                                    error("Invalid tool arguments JSON")
                                }
                                Log.i(TAG, "generateText: executing tool ${toolDef.name}; arguments withheld")
                                durableBoundary(withCurrentToolResults(), tool, completed = false)
                                requireActiveTurn()
                                executionStarted = true
                                val result = if (cloudNative || toolDef.name == me.rerere.rikkahub.data.ai.tools.ORBIS_MEMORY_TOOL) {
                                    withContext(CloudToolInvocationContext(tool.toolCallId, messages.last().id.toString(), conversationId?.toString())) { toolDef.execute(args) }
                                } else toolDef.execute(args)
                                val hasShellAccess = tools.any { it.name == "workspace_shell" }
                                val completed = tool.withGenerationToolOutput(
                                    // Private results must not be spilled as ordinary workspace files.
                                    output = if (me.rerere.rikkahub.data.orbis.privateroom.isPrivateRoomToolName(toolDef.name) ||
                                        toolDef.name == me.rerere.rikkahub.data.ai.tools.ORBIS_MEMORY_TOOL) result
                                        else maybeTruncateToolOutput(tool.toolCallId, result, hasShellAccess)
                                )
                                val previous = withCurrentToolResults()
                                admit(previous.dropLast(1) + previous.last().copy(parts = previous.last().parts.map {
                                    if (it is UIMessagePart.Tool && it.toolCallId == completed.toolCallId) completed else it
                                }))
                                completed
                            }
                        }.onFailure {
                            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (it is CancellationException) throw it
                            if (it is GenerationDurabilityException) throw it
                            if (toolOutcomeIsUnknown(executionStarted, durableCheckpoints, it)) {
                                // A transport/implementation exception after dispatch is not proof
                                // that the external effect failed. Keep STARTED, never synthesize
                                // a completed receipt or advance to another external action.
                                throw GenerationToolOutcomeUnknownException()
                            }
                            if (it is MessageNodeCapacityException) throw it
                            Log.w(TAG, "Tool failed (${it.javaClass.simpleName}); private details withheld")
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        if (it is ToolRejectedBeforeExecutionException) rejectedToolPayload(it).toString() else json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(buildString {
                                                        append("工具未完成 [${it.javaClass.simpleName}]。")
                                                        append("目标、权限或参数可能已变化；请检查工具设置。若结果不确定，不要自动重试。")
                                                    })
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
                // One receipt barrier per completed tool, before the next external action or
                // continuation request. Unknown remote effects remain unknown on interruption.
                val receipt = executedTools.lastOrNull { it.toolCallId == tool.toolCallId }
                if (receipt != null) {
                    durableBoundary(withCurrentToolResults(),
                        tool = tool.takeIf { executionStarted },
                        completed = true.takeIf { executionStarted })
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            if (executedTools.any { it.isPending } && requestedCompaction != null) {
                val compactCall = requestedCompaction!!.first
                val index = executedTools.indexOfFirst { it.toolCallId == compactCall.toolCallId }
                if (index >= 0) executedTools[index] = compactCall.withGenerationToolOutput(listOf(UIMessagePart.Text(
                    compactionError("同批另一个工具的授权已变化，本次未整理；处理该工具后可再次调用compact。"),
                )))
                requestedCompaction = null
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            // Publish actual completed results before continuation validation can refuse another
            // request. In particular, an empty-content invocation must not hide its batch peers.
            messages = transformOutput(messages) { active -> active.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
            ) }
            admit(messages)
            emit(GenerationChunk.Messages(messages, contextEpoch))
            if (executedTools.any { it.isPending }) break
            val requested = requestedCompaction
            var rebased = false
            if (requested != null && compactionControl != null && responseForContinuation != null) {
                val response = responseForContinuation
                val uiMessageId = messages.last().id
                try {
                    val replacement = buildCompactionReplacement(requested.second, messages, requested.first.toolCallId,
                        captureLocalMemory = allowLocalMemory && localMemory != null && me.rerere.rikkahub.BuildConfig.ORBIS_ENABLED)
                    val finalTools = replacement.messages.last().getTools().filter { result ->
                        executedTools.any { it.toolCallId == result.toolCallId }
                    }
                    val rebasedInput = inputSnapshot.prepareCompactionInput(replacement.messages, response, finalTools, uiMessageId)
                    // flowOn buffers emissions. The ordered collector must consume prior message
                    // chunks before it commits, then acknowledge the durable page + session epoch.
                    val acknowledgement = CompletableDeferred<AppliedCompaction>()
                    emit(GenerationChunk.CompactionCommit(messages, replacement, contextEpoch, acknowledgement))
                    val applied = acknowledgement.await()
                    withContext(NonCancellable) {
                        messages = applied.messages
                        contextEpoch = applied.epoch
                        if (outputScope != null) outputScope = OutputGenerationScope(
                            messages.take(frozenOutputPrefixCount(messages)))
                        inputSnapshot.acceptCompactionInput(rebasedInput, response, finalTools, uiMessageId)
                        compactionControl.observeCompactedInput(rebasedInput, schemaText)
                        rebased = true
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error is GenerationDurabilityException) throw error
                    val failure = requested.first.withGenerationToolOutput(listOf(UIMessagePart.Text(
                        compactionError("整理未提交 [${error.javaClass.simpleName}]：${error.message.orEmpty().take(300)}"),
                    )))
                    val index = executedTools.indexOfFirst { it.toolCallId == failure.toolCallId }
                    if (index >= 0) executedTools[index] = failure
                    messages = messages.dropLast(1) + messages.last().copy(parts = messages.last().parts.map {
                        if (it is UIMessagePart.Tool && it.toolCallId == failure.toolCallId) failure else it
                    })
                }
                admit(messages)
                emit(GenerationChunk.Messages(messages, contextEpoch))
            }
            if (!rebased) responseForContinuation?.let { response ->
                inputSnapshot.appendCompletedResponse(response, executedTools, messages.last().id)
            }
            if (canStopAtHistoryBudget(historySoftReached, messages)) {
                emit(GenerationChunk.HistoryBudgetStop)
                break
            }
            if (stepIndex == maxSteps - 1) emit(GenerationChunk.ToolStepLimitStop)
        }

    }.orderedGenerationFlow(Dispatchers.IO)

    private suspend fun generateInternal(
        inputSnapshot: GenerationInputSnapshot,
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        schemaText: String,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        orbisPrompt: OrbisConversationPrompt = OrbisConversationPrompt(),
        compactionControl: ConversationCompactionControl? = null,
        includeCompactionReminder: Boolean = false,
        localMemoryTurn: me.rerere.rikkahub.data.orbis.memory.OrbisMemoryTurn? = null,
        maxAutomaticContinuations: Int = 5,
        consultationBusyWaitUntilMillis: Long? = null,
        onGatewayRequest: ((me.rerere.ai.util.OrbisGatewayRequest) -> Unit)? = null,
        requireActiveTurn: () -> Unit = {},
    ): UIMessage {
        var initialEstimate: Long? = null
        var initialSelection: GenerationContextSelection? = null
        inputSnapshot.initializeInput {
            // Human display redaction must never erase the owning assistant's tool history.
            // Keep the ordinary context-selection policy; no privacy placeholder is model input.
            val selection = GenerationContextSelection(messages, assistant.contextMessageLimit)
            initialSelection = selection
            // A changed message-count projection cannot inherit the previous full request's usage.
            if (selection.includesWholeBranch) {
                initialEstimate = estimateCurrentContext(messages, model.id).tokens
            }
            buildList {
                val system = appendOrbisConversationPrompt(buildString {
                    val effectiveSystemPrompt =
                        if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                            conversationSystemPrompt
                        } else {
                            assistant.systemPrompt
                        }
                    if (effectiveSystemPrompt.isNotBlank()) {
                        append(effectiveSystemPrompt)
                    }

                    // 记忆
                    if (legacyMemoryEnabled(assistant.enableMemory)) {
                        appendLine()
                        append(buildMemoryPrompt(memories = memories))
                    }
                    // 工具prompt
                    tools.forEach { tool ->
                        appendLine()
                        append(tool.systemPrompt(model, messages))
                    }
                }, orbisPrompt)
                if (system.isNotBlank()) {
                    add(UIMessage.system(prompt = system).copy(isSynthetic = true))
                }
                addAll(selection.requestMessages())
            }.transforms(
                transformers = transformers,
                context = context,
                model = model,
                assistant = assistant,
                settings = settings,
                conversationModeInjectionIds = conversationModeInjectionIds,
                conversationLorebookIds = conversationLorebookIds,
                processingStatus = processingStatus,
                workspaceCwd = workspaceCwd,
            ).let { projected -> localMemoryTurn?.project(projected) ?: projected }.also { projected ->
                // A reversible local pruning projection must not retain the old, larger usage
                // anchor. The snapshot will estimate its actual bounded request plus tools.
                if (shouldResetProjectedUsageEstimate(messages, projected)) initialEstimate = null
            }
        }
        // This runs outside the one-time transform block: tools can cross the threshold mid-wake.
        // Regenerating a historical slice may remind without granting whole-conversation compact.
        val prepared = inputSnapshot.prepareRequest(
            schemaText = schemaText,
            thresholdTokens = compactionControl?.thresholdTokens ?: assistant.compactionThresholdTokens,
            includeCompactionReminder = includeCompactionReminder,
            initialEstimate = initialEstimate,
        )
        compactionControl?.observeRequestEstimate(prepared.estimatedTokens)
        // Reversible tool deletions are private local bookkeeping, never provider input.
        val internalMessages = prepared.messages.map { it.withoutDeletedToolRecordData().withVoiceNoteTranscripts() }
        // Resolve expiring frame handles anew for each HTTP attempt; never freeze image bytes
        // into history, the continuation snapshot, or a retry that may outlive their TTL.
        suspend fun providerMessages(): List<UIMessage> {
            // Host-bound identity survives in-turn compaction and message-count projection.
            requireActiveTurn()
            return me.rerere.rikkahub.data.ai.transformers.projectOrbisVideoRequestImages(
                context, assistant.id.toString(), localMemoryTurn?.project(internalMessages) ?: internalMessages,
                modelSupportsImages = model.inputModalities.contains(me.rerere.ai.provider.Modality.IMAGE))
        }
        // Publish once per new invocation, not on tool continuations. Counts remain local, never
        // alter prompts/cache keys, and deliberately do not claim upstream delivery or recall.
        if (conversationId != null) initialSelection?.let { selection ->
            generationContextReceipts.record(conversationId.toString(),
                selection.receipt(internalMessages, System.currentTimeMillis()))
        }

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            },
            sessionId = conversationId?.toString(),
            orbisConversationId = conversationId?.toString(),
            maxAutomaticContinuations = maxAutomaticContinuations,
            onGatewayRequest = onGatewayRequest,
        )
        try {
            if (stream) {
                // 每次重试都从本次模型调用开始前的消息快照重新合并，避免将重试响应
                // 追加到已经展示的半截回复后面。预先创建助手消息可让所有尝试复用同一 ID，
                // ChatService 因而会覆盖当前分支，而不是创建新的候选消息。
                val responseBaseMessages =
                    if (messages.lastOrNull()?.role == MessageRole.ASSISTANT) {
                        messages
                    } else {
                        messages + UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                            modelId = model.id,
                        )
                    }
                var retryCount = 0
                var sawAnyChunk = false

                while (true) {
                    val streamChunkHandler = StreamChunkHandler(model)
                    val responseChunkHandler = StreamChunkHandler(model)
                    var attemptResponse = listOf(UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = emptyList(),
                        modelId = model.id,
                    ))
                    var attemptMessages = responseBaseMessages
                    try {
                        providerImpl.streamText(
                            providerSetting = provider,
                            messages = providerMessages(),
                            params = params
                        ).collect { chunk ->
                            sawAnyChunk = true
                            try {
                                if (retryCount > 0) {
                                    processingStatus.value = null
                                }
                                requireBoundedGenerationChunk(chunk)
                                val candidate = streamChunkHandler.handle(attemptMessages, chunk)
                                requireBoundedWorkingMessage(candidate.lastOrNull())
                                attemptResponse = responseChunkHandler.handle(attemptResponse, chunk)
                                onUpdateMessages(candidate)
                                attemptMessages = candidate
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                // 下游消息转换或 UI 更新失败不属于网络故障，不能重放模型请求。
                                throw StreamChunkHandlingException(error)
                            }
                        }
                        // Only the successful attempt becomes a continuation segment. Retried
                        // partial streams never enter the input snapshot or execute their tools.
                        return copyGenerationMessage(attemptResponse.last().finishReasoning())
                    } catch (error: Throwable) {
                        if (error is StreamChunkHandlingException) {
                            throw error.cause ?: error
                        }
                        currentCoroutineContext().ensureActive()
                        val busyDelay = consultationBusyDelayMillis(error,
                            consultationBusyWaitUntilMillis, System.currentTimeMillis(), sawAnyChunk)
                        if (busyDelay != null) {
                            processingStatus.value = "咨询室正在等待同一网关的当前对话完成…"
                            delay(busyDelay)
                            continue
                        }
                        val classified = KnownEmptyCompletionFailure.classify(error, attemptResponse.last())
                        if (classified is KnownEmptyCompletionFailure) {
                            // ST may have already closed its wake. Replaying this tool continuation
                            // could lose context or repeat earlier side effects; never restart it.
                            throw classified
                        }
                        retryCount = awaitNetworkRetryOrThrow(
                            error = error,
                            retryCount = retryCount,
                            processingStatus = processingStatus,
                            enabled = settings.networkSetting.enableAutoRetry,
                        )
                    }
                }
            } else {
                val result = try {
                    executeProviderRequestWithRetry(
                        processingStatus = processingStatus,
                        enabled = settings.networkSetting.enableAutoRetry,
                        consultationBusyWaitUntilMillis = consultationBusyWaitUntilMillis,
                    ) {
                        providerImpl.generateText(
                            providerSetting = provider,
                            messages = providerMessages(),
                            params = params,
                        )
                    }
                } catch (error: Throwable) {
                    currentCoroutineContext().ensureActive()
                    throw KnownEmptyCompletionFailure.classify(error, null)
                }
                messages = messages.handleTextGenerationResult(result = result, model = model)
                onUpdateMessages(messages)
                return copyGenerationMessage(result.message.copy(
                    modelId = model.id,
                    usage = result.usage ?: result.message.usage,
                ).finishReasoning())
            }
        } finally {
            processingStatus.value = null
        }
    }

    private suspend fun <T> executeProviderRequestWithRetry(
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
        consultationBusyWaitUntilMillis: Long? = null,
        block: suspend () -> T,
    ): T {
        var retryCount = 0
        while (true) {
            try {
                return block()
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                val busyDelay = consultationBusyDelayMillis(error,
                    consultationBusyWaitUntilMillis, System.currentTimeMillis(), sawOutput = false)
                if (busyDelay != null) {
                    processingStatus.value = "咨询室正在等待同一网关的当前对话完成…"
                    delay(busyDelay)
                    continue
                }
                retryCount = awaitNetworkRetryOrThrow(
                    error = error,
                    retryCount = retryCount,
                    processingStatus = processingStatus,
                    enabled = enabled,
                )
            }
        }
    }

    private suspend fun awaitNetworkRetryOrThrow(
        error: Throwable,
        retryCount: Int,
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
    ): Int {
        // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
        // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
        currentCoroutineContext().ensureActive()
        if (!enabled || error !is IOException || retryCount >= MAX_PROVIDER_NETWORK_RETRIES) {
            throw error
        }

        val nextRetryCount = retryCount + 1
        val retryDelay = INITIAL_PROVIDER_RETRY_DELAY_MS shl retryCount
        processingStatus.value = context.getString(
            R.string.chat_generation_network_retrying,
            getNetworkErrorMessage(error),
            nextRetryCount,
            MAX_PROVIDER_NETWORK_RETRIES,
        )
        Log.w(
            TAG,
            "Provider connection failed, retrying in ${retryDelay}ms " +
                    "($nextRetryCount/$MAX_PROVIDER_NETWORK_RETRIES)",
            error,
        )
        delay(retryDelay)
        return nextRetryCount
    }

    private fun getNetworkErrorMessage(error: IOException): String {
        val messageRes = when (error) {
            is UnknownHostException -> R.string.chat_generation_network_unknown_host
            is SocketTimeoutException -> R.string.chat_generation_network_timeout
            is ConnectException, is NoRouteToHostException -> R.string.chat_generation_network_unreachable
            else -> R.string.chat_generation_network_disconnected
        }
        return context.getString(messageRes)
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.takeAtCodePointBoundary(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

}
