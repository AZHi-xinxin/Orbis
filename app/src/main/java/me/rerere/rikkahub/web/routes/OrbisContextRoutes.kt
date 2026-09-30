package me.rerere.rikkahub.web.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.OrbisManualContextPreview
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.ConflictException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.requireSensitiveWebAccess
import kotlin.uuid.Uuid

@Serializable internal data class WebManualContextRequest(val archiveThroughCount: Int, val summary: String = "")
@Serializable internal data class WebManualContextApply(val previewId: String, val confirmed: Boolean = false)
@Serializable internal data class WebManualContextRow(val number: Int, val role: String, val createdAt: String,
    val text: String, val branchCount: Int)
@Serializable internal data class WebManualContextInfo(val conversationId: String, val totalCount: Int,
    val boundaries: List<WebManualContextRow>)
@Serializable internal data class WebManualContextPreview(val previewId: String, val requestedArchiveCount: Int,
    val archivedCount: Int, val keptCount: Int, val beforeTokens: Long, val afterTokens: Long,
    val summaryText: String, val boundaries: List<WebManualContextRow>)
@Serializable internal data class WebManualContextResult(val status: String, val archiveId: String)

/** At most two transient previews, no source history or credentials serialized back to the browser.
 * Repeated confirmation for the same ticket returns its receipt rather than compacting twice.
 */
internal class OrbisContextPreviewStore<T>(private val now: () -> Long = System::currentTimeMillis) {
    private data class Ticket<T>(val owner: String, val conversationId: String, val created: Long,
        var preview: T?, var archiveId: String? = null)
    private val mutex = Mutex()
    private val tickets = linkedMapOf<String, Ticket<T>>()
    private fun prune() { tickets.entries.removeAll { now() - it.value.created > 10 * 60_000L } }

    suspend fun put(owner: String, conversationId: String, preview: T): String = mutex.withLock {
        prune()
        // Same browser replacing a preview is intentional. Receipts remain briefly idempotent.
        tickets.entries.removeAll { it.value.owner == owner && it.value.archiveId == null }
        if (tickets.size >= 8) tickets.entries.firstOrNull { it.value.archiveId != null }?.let { tickets.remove(it.key) }
        check(tickets.values.count { it.preview != null } < 2 && tickets.size < 8) {
            "另一个整理预览正在使用，请稍后重试。"
        }
        val id = Uuid.random().toString()
        tickets[id] = Ticket(owner, conversationId, now(), preview)
        id
    }

    suspend fun apply(owner: String, conversationId: String, id: String, commit: suspend (T) -> String): String = mutex.withLock {
        prune()
        val ticket = tickets[id]?.takeIf { it.owner == owner && it.conversationId == conversationId }
            ?: throw NotFoundException("预览已失效，请重新预览。")
        ticket.archiveId?.let { return@withLock it }
        val preview = ticket.preview ?: throw ConflictException("预览已失效，请重新预览。")
        // Complete durable commit + receipt together even if the browser disconnects.
        withContext(NonCancellable) {
            try {
                commit(preview).also { ticket.archiveId = it; ticket.preview = null }
            } catch (failure: Exception) {
                ticket.preview = null
                throw failure
            }
        }
    }
}

/** Register ONLY inside authenticate("auth-jwt"). The live policy is rechecked on every request. */
fun Route.orbisContextRoutes(chatService: ChatService, settingsStore: SettingsStore) {
    val previews = OrbisContextPreviewStore<OrbisManualContextPreview>()
    // Prevent concurrent multi-megabyte estimates from exhausting the phone heap.
    val preparation = Mutex()
    route("/orbis/context/{id}") {
        get {
            call.requireSensitiveWebAccess(settingsStore)
            val id = call.parameters["id"].toUuid("conversation id")
            chatService.initializeConversation(id, selectAssistant = false)
            val conversation = chatService.getConversationFlow(id).value
            val end = call.request.queryParameters["end"]?.toIntOrNull()
                ?: (conversation.messageNodes.size - 32).coerceAtLeast(1)
            call.respond(WebManualContextInfo(id.toString(), conversation.messageNodes.size, contextBoundaries(conversation, end)))
        }
        post("/preview") {
            val owner = call.requireSensitiveWebAccess(settingsStore)
            val id = call.parameters["id"].toUuid("conversation id")
            val request = call.receiveBoundedWebJson<WebManualContextRequest>(128 * 1024)
            if (request.summary.length > 20_000) throw BadRequestException("摘要最多 20000 个字符。")
            if (!preparation.tryLock()) throw ConflictException("正在准备另一个预览，请稍后重试。")
            try {
                val preview = chatService.previewManualContext(id, request.archiveThroughCount, request.summary)
                val source = chatService.getConversationFlow(id).value
                check(withContext(Dispatchers.Default) {
                    me.rerere.rikkahub.data.model.manualContextFingerprint(source) == preview.expectedFingerprint
                }) { "预览期间窗口已变化，请重新预览。" }
                val ticket = previews.put(owner, id.toString(), preview)
                call.respond(WebManualContextPreview(ticket, preview.requestedArchiveCount, preview.archivedCount,
                    preview.keptCount, preview.beforeTokens, preview.afterTokens, preview.summaryText,
                    contextBoundaries(source, preview.archivedCount)))
            } catch (failure: CancellationException) { throw failure }
            catch (failure: IllegalArgumentException) { throw BadRequestException(failure.message ?: "范围无效。") }
            catch (failure: IllegalStateException) { throw ConflictException(failure.message ?: "窗口已变化，请重新预览。") }
            finally { preparation.unlock() }
        }
        post("/apply") {
            val owner = call.requireSensitiveWebAccess(settingsStore)
            val id = call.parameters["id"].toUuid("conversation id")
            val request = call.receiveBoundedWebJson<WebManualContextApply>(4096)
            if (!request.confirmed) throw BadRequestException("必须明确确认保存原文存档并整理当前窗口。")
            try {
                val archive = previews.apply(owner, id.toString(), request.previewId) {
                    chatService.applyManualContext(id, it).toString()
                }
                call.respond(WebManualContextResult("committed", archive))
            } catch (failure: CancellationException) { throw failure }
            catch (failure: IllegalArgumentException) { throw BadRequestException(failure.message ?: "预览无效。") }
            catch (failure: IllegalStateException) { throw ConflictException("窗口或生成状态已变化，本次未整理，请重新预览。") }
        }
    }
}

private fun contextBoundaries(conversation: Conversation, end: Int): List<WebManualContextRow> =
    listOf(0, end - 1, end).distinct().mapNotNull { index ->
        conversation.messageNodes.getOrNull(index)?.let { node ->
            val message = node.currentMessage
            val text = message.parts.asSequence().filterIsInstance<UIMessagePart.Text>()
                .map { it.text.take(160) }.firstOrNull { it.isNotBlank() } ?: "[附件、思考或工具记录]"
            WebManualContextRow(index + 1, message.role.name, message.createdAt.toString(), text, node.messages.size)
        }
    }
