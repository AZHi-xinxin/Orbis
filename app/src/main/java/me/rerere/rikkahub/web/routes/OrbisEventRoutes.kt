package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.orbis.ORBIS_EVENT_SOURCES
import me.rerere.rikkahub.data.orbis.OrbisIncomingEvent
import me.rerere.rikkahub.data.orbis.OrbisInboxEvent
import me.rerere.rikkahub.data.orbis.sentinel.parseOrbisTouchInput
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.web.dto.ConversationDto
import me.rerere.rikkahub.web.dto.ConversationListDto
import me.rerere.rikkahub.web.dto.ErrorResponse
import me.rerere.rikkahub.web.dto.toDto
import me.rerere.rikkahub.web.dto.toListDto
import kotlin.uuid.Uuid
import java.io.ByteArrayOutputStream

@Serializable
private data class EventReceiptDto(val id: String, val source: String, val event_id: String,
    val state: String, val received_at: Long, val assistant_id: String, val conversation_id: String,
    val error: String? = null, val duplicate: Boolean = false, val occurred_at: Long? = null)

private fun OrbisInboxEvent.receipt(duplicate: Boolean = false) = EventReceiptDto(
    id, source, eventId, state, receivedAt, assistantId, conversationId, error, duplicate, occurredAt)

@Serializable
private data class EventTargetDto(val source: String, val bound: Boolean, val valid: Boolean,
    val assistant_id: String? = null, val conversation_id: String? = null, val protocol: Int = 1)

@Serializable
private data class EventContextDto(val conversation: ConversationDto, val items: List<ConversationListDto>)

@Serializable
private data class TouchReceiptDto(val event_id: String, val state: String, val delivered: Int, val duplicate: Boolean)
@Serializable
private data class TouchStatusDto(val protocol: Int = 1, val human_enabled: Boolean, val enabled_rules: Int)

/** Dedicated device credential, independent of whether the ordinary Web UI uses JWT.
 * A caller may only address an explicitly bound source, never choose an arbitrary AI/window.
 * Probe and receipt endpoints never enqueue messages or call a model.
 */
fun Route.orbisEventRoutes(service: ChatService, conversations: ConversationRepository, settings: SettingsStore) {
    suspend fun ApplicationCall.authorized(): Boolean {
        val header = request.headers[HttpHeaders.Authorization]
        val token = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        val allowed = withContext(Dispatchers.IO) { runCatching { service.orbisEvents.authenticate(token) }.getOrDefault(false) }
        if (!allowed) respond(HttpStatusCode.Unauthorized, ErrorResponse("event_unauthorized", 401))
        return allowed
    }
    suspend fun target(source: String): Conversation? {
        val binding = service.orbisEvents.inbox.binding(source) ?: return null
        if (settings.settingsFlow.value.assistants.none { it.id.toString() == binding.assistantId }) return null
        return conversations.getConversationById(Uuid.parse(binding.conversationId))
            ?.takeIf { it.assistantId.toString() == binding.assistantId }
    }
    suspend fun ApplicationCall.status() {
        val source = request.queryParameters["source"].orEmpty()
        if (source !in ORBIS_EVENT_SOURCES) {
            respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_event_source", 400)); return
        }
        val binding = service.orbisEvents.inbox.binding(source)
        respond(EventTargetDto(source, binding != null, target(source) != null, binding?.assistantId, binding?.conversationId))
    }
    get("/orbis/events/status") { if (call.authorized()) call.status() }
    post("/orbis/events/probe") { if (call.authorized()) call.status() }
    // Physical-touch input has no text, arbitrary source or target fields. The AI-owned rule
    // supplies the prompt and destination. Same credential as the existing event receiver.
    get("/orbis/sentinels/touch/status") {
        if (!call.authorized()) return@get
        val (enabled, count) = service.orbisTouchStatus()
        call.respond(TouchStatusDto(human_enabled = enabled, enabled_rules = count))
    }
    get("/orbis/sentinels/touch/receipt") {
        if (!call.authorized()) return@get
        val receipt = service.orbisTouchReceipt(call.request.queryParameters["event_id"].orEmpty())
        if (receipt == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("event_not_found", 404))
        else call.respond(TouchReceiptDto(receipt.eventId, receipt.status, receipt.delivered, true))
    }
    post("/orbis/sentinels/touch") {
        if (!call.authorized()) return@post
        try {
            val channel = call.receiveChannel()
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (true) {
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count < 0) break
                require(bytes.size() + count <= 2048) { "event_request_too_large" }
                bytes.write(buffer, 0, count)
            }
            val input = parseOrbisTouchInput(bytes.toString(Charsets.UTF_8.name()))
            val (receipt, duplicate) = service.acceptOrbisTouch(input.event_id, input.occurred_at)
            call.respond(if (duplicate) HttpStatusCode.OK else HttpStatusCode.Accepted,
                TouchReceiptDto(receipt.eventId, receipt.status, receipt.delivered, duplicate))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val (code, status) = orbisEventRequestFailure(error)
            call.respond(HttpStatusCode.fromValue(status), ErrorResponse(code, status))
        }
    }
    get("/orbis/events/context") {
        if (!call.authorized()) return@get
        val source = call.request.queryParameters["source"].orEmpty()
        val conversation = if (source in ORBIS_EVENT_SOURCES) target(source) else null
        if (conversation == null) call.respond(HttpStatusCode.Conflict, ErrorResponse("event_target_unavailable", 409))
        else {
            val dto = conversation.toDto()
            // Old LC report parsing must not mistake a sentinel for a human report.
            val context = dto.copy(messages = dto.messages.map { node -> node.copy(messages = node.messages.map {
                if (it.orbisEvent != null) it.copy(role = "EVENT") else it
            }) })
            call.respond(EventContextDto(context, listOf(conversation.toListDto())))
        }
    }
    get("/orbis/events/receipt") {
        if (!call.authorized()) return@get
        val event = service.orbisEvents.inbox.receipt(call.request.queryParameters["source"].orEmpty(),
            call.request.queryParameters["event_id"].orEmpty())
        if (event == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("event_not_found", 404))
        else call.respond(event.receipt())
    }
    post("/orbis/events") {
        if (!call.authorized()) return@post
        try {
            val declaredLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > 128 * 1024) {
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("event_request_too_large", 413)); return@post
            }
            val channel = call.receiveChannel()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count < 0) break
                if (count == 0) continue
                require(output.size() + count <= 128 * 1024) { "event_request_too_large" }
                output.write(buffer, 0, count)
            }
            val body = output.toString(Charsets.UTF_8.name())
            val input = Json.decodeFromString<OrbisIncomingEvent>(body)
            require(input.source in ORBIS_EVENT_SOURCES) { "invalid_event_source" }
            require(input.localImage == null) { "invalid_event_attachment" }
            val (event, duplicate) = service.acceptOrbisEvent(input)
            call.respond(if (duplicate) HttpStatusCode.OK else HttpStatusCode.Accepted, event.receipt(duplicate))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val (code, statusCode) = orbisEventRequestFailure(error)
            val status = HttpStatusCode.fromValue(statusCode)
            call.respond(status, ErrorResponse(code, status.value))
        }
    }
}

/** Known input errors are definitive rejection, never ambiguous delivery or raw exception text. */
internal fun orbisEventRequestFailure(error: Exception): Pair<String, Int> {
    val code = if (error is kotlinx.serialization.SerializationException) "invalid_event_request" else
        error.message?.takeIf { it in setOf("invalid_event_source", "invalid_event_id", "invalid_event_text",
            "invalid_event_time", "invalid_event_attachment", "event_id_conflict", "event_target_changed", "event_source_not_bound", "event_target_invalid",
            "event_assistant_missing", "event_inbox_full", "event_request_too_large") } ?: "event_request_failed"
    return code to when (code) {
        "event_inbox_full" -> 507
        "event_request_too_large" -> 413
        "event_id_conflict", "event_target_changed", "event_source_not_bound", "event_target_invalid", "event_assistant_missing" -> 409
        "event_request_failed" -> 503
        else -> 400
    }
}
