package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.contextpruning.CONTEXT_PRUNING_RECEIPT_TYPE
import me.rerere.rikkahub.data.ai.contextpruning.CONTEXT_PRUNING_TOOL_NAME
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningBatch
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningMode
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningRepository
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningRejected
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningRefusal
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/** Opt-in and binding are supplied by ChatToolFactory, not by model arguments. */
fun createOrbisContextPruningTool(
    repository: ContextPruningRepository,
    readConversation: suspend () -> Conversation?,
    isEnabled: () -> Boolean,
): Tool = Tool(
    name = CONTEXT_PRUNING_TOOL_NAME,
    description = "在手机本地可恢复地排除本对话旧轮的思考和完整工具调用/结果对，不依赖网关。" +
        "先preview查看数量和plan_id，再apply同一plan_id；mode=reasoning/tools/both，max_messages默认100、最多200。" +
        "保留最近两个用户轮、当前工具链、签名或加密思考、服务端或旧式工具协议；不删原始历史、正文、通话或附件。" +
        "本次冻结的工具链不变，下一次独立生成才生效。成功清理动作自身以后自动折叠，不要调用本工具清理本工具。" +
        "restore加batch_id恢复该批上下文可见性，不会重新执行历史工具。未成功或没有合格旧记录不能声称已删除。" +
        "status只读查询本窗口已保存的批次，可用plan_id或batch_id对账，不读出正文、不执行历史工具。" +
        "回执未确认时先status，不自动重试；已恢复的旧plan不能再apply，须重新preview。",
    parameters = {
        InputSchema.Obj(buildJsonObject {
            put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("status", "preview", "apply", "restore").map(::JsonPrimitive))) })
            put("mode", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("reasoning", "tools", "both").map(::JsonPrimitive))) })
            put("max_messages", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 200) })
            put("plan_id", buildJsonObject { put("type", "string"); put("maxLength", 64) })
            put("batch_id", buildJsonObject { put("type", "string"); put("maxLength", 36) })
        }, required = listOf("action"))
    },
    needsApproval = { (it as? JsonObject)?.string("action") !in setOf("preview", "status") },
    hostApproval = HostToolApproval("orbis:context_prune", "context-pruning-v1", "可恢复地清理旧思考和工具上下文"),
    isApprovalCurrent = isEnabled,
    execute = { value -> withContext(Dispatchers.IO) {
        check(isEnabled()) { "context_pruning_tool_not_enabled" }
        val args = value as? JsonObject ?: error("context_pruning_invalid_arguments")
        require(args.toString().length <= 4096) { "context_pruning_invalid_arguments" }
        val conversation = readConversation() ?: error("context_pruning_conversation_missing")
        check(conversation.id.toString() == repository.conversationId &&
            conversation.assistantId.toString() == repository.assistantId) { "context_pruning_owner_changed" }
        val action = args.string("action") ?: error("context_pruning_invalid_arguments")
        val result = try { when (action) {
            "status" -> {
                require(args.keys.all { it in setOf("action", "plan_id", "batch_id") } &&
                    !("plan_id" in args && "batch_id" in args)) { "context_pruning_invalid_arguments" }
                val planId = args.string("plan_id")
                val batchId = args.string("batch_id")
                if ("plan_id" in args) require(planId != null && planId.length == 64 &&
                    planId.all { it in '0'..'9' || it in 'a'..'f' }) { "context_pruning_invalid_digest" }
                if ("batch_id" in args) require(batchId != null && batchId.length == 36 &&
                    runCatching { Uuid.parse(batchId).toString() == batchId }.getOrDefault(false)) { "context_pruning_invalid_id" }
                // Never use the initial StateFlow value as evidence; verify the persisted policy.
                val state = repository.snapshot()
                val matches = when {
                    planId != null -> state.batches.filter { it.planId == planId }
                    batchId != null -> state.batches.filter { it.id == batchId }
                    else -> state.batches.takeLast(20)
                }
                buildJsonObject {
                    put("status", "ok"); put("read_only", true); put("state_verified", true)
                    put("source_preserved", true); put("historical_tools_executed", false)
                    put("effective_from", "next_independent_generation")
                    put("active_batches", state.batches.count { !it.restored })
                    put("restored_batches", state.batches.count { it.restored })
                    put("total_batches", state.batches.size)
                    put("returned_batches", matches.size)
                    if (planId != null || batchId != null) put("query_state", matches.singleOrNull()?.let {
                        if (it.restored) "restored" else "applied"
                    } ?: "not_found")
                    put("batches", buildJsonArray { matches.forEach { batch -> add(buildJsonObject {
                        put("batch_id", batch.id); put("plan_id", batch.planId)
                        put("state", if (batch.restored) "restored" else "applied")
                        put("stored_reasoning_parts", batch.reasoningCount); put("stored_tool_pairs", batch.toolCount)
                    }) } })
                    put("note", "计数表示已保存的排除策略，不保证原记录被编辑后仍匹配；未找到不授权自动重试。恢复后须重新preview。")
                }
            }
            "preview", "apply" -> {
                val allowed = if (action == "apply") setOf("action", "mode", "max_messages", "plan_id")
                    else setOf("action", "mode", "max_messages")
                require(args.keys.all { it in allowed }) { "context_pruning_invalid_arguments" }
                val mode = when (args.string("mode") ?: if ("mode" !in args) "both" else "invalid") {
                    "reasoning" -> ContextPruningMode.REASONING
                    "tools" -> ContextPruningMode.TOOLS
                    "both" -> ContextPruningMode.BOTH
                    else -> error("context_pruning_invalid_arguments")
                }
                val limit = if ("max_messages" !in args) 100 else
                    (args["max_messages"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                        ?: error("context_pruning_invalid_arguments")
                require(limit in 1..200) { "context_pruning_invalid_limit" }
                if (action == "preview") {
                    val plan = repository.preview(conversation.currentMessages, mode, limit)
                    buildJsonObject {
                        put("status", "preview"); put("plan_id", plan.planId)
                        put("mode", mode.name.lowercase()); put("max_messages", limit)
                        put("candidate_messages", plan.marks.size); put("eligible_messages", plan.eligibleMessages)
                        put("reasoning_parts", plan.reasoningCount); put("tool_pairs", plan.toolCount)
                        put("approximate_characters", plan.excludedCharacters)
                        put("protected_recent_user_turns", 2); put("source_preserved", true)
                        put("effective_from", "next_independent_generation")
                        put("recent_batches", buildJsonArray {
                            repository.snapshot().batches.takeLast(20).forEach { batch -> add(buildJsonObject {
                                put("batch_id", batch.id); put("restored", batch.restored)
                                put("reasoning_parts", batch.reasoningCount); put("tool_pairs", batch.toolCount)
                            }) }
                        })
                    }
                } else {
                    check(isEnabled()) { "context_pruning_tool_not_enabled" }
                    val planId = args.string("plan_id") ?: error("context_pruning_preview_required")
                    val applied = repository.applyDetailed(conversation.currentMessages, mode, limit, planId)
                    contextPruningAppliedReceipt(applied.batch, applied.reused)
                }
            }
            "restore" -> {
                require(args.keys == setOf("action", "batch_id")) { "context_pruning_invalid_arguments" }
                check(isEnabled()) { "context_pruning_tool_not_enabled" }
                val batch = repository.restore(args.string("batch_id") ?: error("context_pruning_invalid_arguments"))
                buildJsonObject {
                    put("status", "restored"); put("batch_id", batch.id); put("source_preserved", true)
                    put("historical_tools_executed", false); put("effective_from", "next_independent_generation")
                }
            }
            else -> error("context_pruning_invalid_arguments")
        } } catch (refused: ContextPruningRejected) {
            buildJsonObject {
                put("status", "rejected"); put("reason", refused.refusal.reason)
                put("state_changed", false); put("source_preserved", true)
                put("historical_tools_executed", false)
                put("next_action", when (refused.refusal) {
                    ContextPruningRefusal.PLAN_RESTORED, ContextPruningRefusal.PREVIEW_CHANGED -> "preview"
                    else -> "status"
                })
            }
        }
        listOf(UIMessagePart.Text(result.toString()))
    } },
)

/** Fixed short host receipt; the UI additionally requires a matching locally committed batch. */
fun contextPruningAppliedReceipt(batch: ContextPruningBatch, reused: Boolean = false): JsonObject = buildJsonObject {
    put("receipt_type", CONTEXT_PRUNING_RECEIPT_TYPE); put("status", "applied"); put("batch_id", batch.id)
    put("excluded_reasoning_parts", batch.reasoningCount); put("excluded_tool_pairs", batch.toolCount)
    put("source_preserved", true); put("effective_from", "next_independent_generation")
    put("reused_existing_batch", reused)
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
