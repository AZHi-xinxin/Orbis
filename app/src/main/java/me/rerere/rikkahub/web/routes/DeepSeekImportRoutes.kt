package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.importer.DeepSeekArchive
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.*
import me.rerere.rikkahub.web.dto.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Both optional-password modes share the importer; password-enabled mode still enforces login. */
internal fun Route.deepSeekImportRoutes(imports: WebDeepSeekImports, settings: SettingsStore) {
    chatArchiveImportRoutes(imports, settings, "deepseek", "application/zip")
}

internal fun Route.chatArchiveImportRoutes(imports: WebDeepSeekImports, settings: SettingsStore,
    source: String, contentType: String) {
    route("/imports/$source") {
        post {
            val owner = call.requireSensitiveWebAccess(settings, allowPasswordFreeImport = true)
            val request = call.receiveBoundedWebJson<WebImportCreateRequest>(4096)
            val assistant = request.assistantId.toUuid("assistantId")
            if (settings.settingsFlow.value.assistants.none { it.id == assistant }) throw NotFoundException("assistant_not_found")
            call.respond(HttpStatusCode.Created, imports.create(owner, assistant, source))
        }
        put("/{id}/archive") {
            val owner = call.requireSensitiveWebAccess(settings, allowPasswordFreeImport = true)
            val limit = imports.requireSource(owner, call.importId(), source)
            if (call.request.headers[HttpHeaders.ContentType]?.substringBefore(';') != contentType) {
                throw BadRequestException("archive_content_type_required")
            }
            val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared !in 1..limit) {
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("archive_too_large", 413))
                return@put
            }
            val result = imports.upload(owner, call.importId(), call.receiveChannel())
            val status = when (result.error) {
                "archive_too_large" -> HttpStatusCode.PayloadTooLarge
                "upload_timeout" -> HttpStatusCode.RequestTimeout
                null -> HttpStatusCode.OK
                else -> HttpStatusCode.BadRequest
            }
            // A failure remains available from GET even if this response is lost.
            call.respond(status, result)
        }
        get("/{id}") {
            val owner = call.requireSensitiveWebAccess(settings, allowPasswordFreeImport = true)
            imports.requireSource(owner, call.importId(), source)
            call.respond(imports.status(owner, call.importId()))
        }
        get("/{id}/conversations") {
            val owner = call.requireSensitiveWebAccess(settings, allowPasswordFreeImport = true)
            imports.requireSource(owner, call.importId(), source)
            call.respond(imports.conversations(owner, call.importId(), call.pageOffset(), call.pageLimit()))
        }
        get("/{id}/branches") {
            val owner = call.requireSensitiveWebAccess(settings, allowPasswordFreeImport = true)
            imports.requireSource(owner, call.importId(), source)
            val index = call.request.queryParameters["conversation"]?.toIntOrNull()?.takeIf { it >= 0 }
                ?: throw BadRequestException("invalid_conversation_selection")
            call.respond(imports.branches(owner, call.importId(), index, call.pageOffset(), call.pageLimit()))
        }
        post("/{id}/commit") {
            val owner = call.requireSensitiveWebAccess(settings, allowPasswordFreeImport = true)
            imports.requireSource(owner, call.importId(), source)
            val request = call.receiveBoundedWebJson<WebImportCommitRequest>(128 * 1024)
            val current = imports.status(owner, call.importId())
            if (settings.settingsFlow.value.assistants.none { it.id.toString() == current.assistantId }) {
                throw ConflictException("assistant_changed")
            }
            call.respond(HttpStatusCode.Accepted, imports.commit(owner, call.importId(), request))
        }
        delete("/{id}") {
            val owner = call.requireSensitiveWebAccess(settings, allowPasswordFreeImport = true)
            imports.requireSource(owner, call.importId(), source)
            call.respond(imports.cancel(owner, call.importId()))
        }
    }
}

private fun ApplicationCall.importId(): String = parameters["id"] ?: throw BadRequestException("missing_import_id")
private fun ApplicationCall.pageOffset(): Int = request.queryParameters["offset"]?.let {
    it.toIntOrNull()?.takeIf { value -> value in 0..100_000 } ?: throw BadRequestException("invalid_offset")
} ?: 0
private fun ApplicationCall.pageLimit(): Int = request.queryParameters["limit"]?.let {
    it.toIntOrNull()?.takeIf { value -> value in 1..100 } ?: throw BadRequestException("invalid_limit")
} ?: 50

/** Fixed request limits and errors: never echo arbitrary JSON, source identities or parser exceptions. */
internal suspend inline fun <reified T> ApplicationCall.receiveBoundedWebJson(maxBytes: Int): T {
    if (request.headers[HttpHeaders.ContentType]?.substringBefore(';') != "application/json") {
        throw BadRequestException("json_content_type_required")
    }
    val channel = receiveChannel()
    val output = ByteArrayOutputStream()
    withTimeout(15_000) {
        val buffer = ByteArray(4096)
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) continue
            if (output.size() + count > maxBytes) throw BadRequestException("request_too_large")
            output.write(buffer, 0, count)
        }
    }
    return try {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
        JsonInstant.decodeFromString<T>(text)
    } catch (_: Exception) { throw BadRequestException("invalid_request") }
}
