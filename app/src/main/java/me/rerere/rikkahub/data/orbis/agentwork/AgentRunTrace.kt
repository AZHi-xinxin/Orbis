package me.rerere.rikkahub.data.orbis.agentwork

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationDurabilityException

/** One trace per collected generation; a human pause cannot be bypassed by selecting another task. */
internal class AgentRunTrace(private val store: AgentTaskStore, private val conversation: String) {
    private var bound: String? = store.active(conversation)?.takeUnless { it.state == "paused" }?.id
    fun check() {
        if (bound == null) bound = store.active(conversation)?.takeUnless { it.state == "paused" }?.id
        bound?.let { id ->
            try { store.requireRunnable(id) }
            catch (e: IllegalStateException) {
                if (e.message == "task_paused_or_closed") throw CancellationException("Task paused or cancelled")
                throw GenerationDurabilityException(e)
            }
        }
    }
    suspend fun execute(callId: String, name: String, block: suspend () -> List<UIMessagePart>): List<UIMessagePart> {
        check()
        val id = bound ?: return block()
        fun record(state: String) {
            try { store.receipt(id, callId, name, state) }
            catch (e: Exception) { throw GenerationDurabilityException(e) }
        }
        val claimed = try { store.claim(id,callId,name) } catch(e: Exception) {
            if (e is IllegalStateException && e.message == "task_paused_or_closed") throw CancellationException("Task paused or cancelled")
            throw GenerationDurabilityException(e)
        }
        if (!claimed) throw me.rerere.rikkahub.data.ai.ToolRejectedBeforeExecutionException(
            "该调用已有执行记录，请先核对任务回执；没有重放操作。")
        val result = try { block() }
        catch (e: Throwable) {
            // If writing this fails, the durable 'started' record still communicates uncertainty.
            runCatching { record("unknown") }
            throw e
        }
        val failed = result.filterIsInstance<UIMessagePart.Text>().any { part ->
            if (part.text.length > 64 * 1024) false else runCatching {
                val obj = Json.parseToJsonElement(part.text) as? JsonObject
                obj?.get("ok") == JsonPrimitive(false) || obj?.get("status") == JsonPrimitive("failed") ||
                    obj?.get("isError") == JsonPrimitive(true)
            }.getOrDefault(false)
        }
        record(if (failed) "failed" else "returned")
        return result
    }
}
