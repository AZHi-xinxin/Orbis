package me.rerere.rikkahub.data.orbis.consultation

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val CONSULTATION_REFERENCE_DIRECTORY = "orbis-reference-v02"
internal const val CONSULTATION_REFERENCE_PREFIX = "/workspace/$CONSULTATION_REFERENCE_DIRECTORY/"
internal const val CONSULTATION_REFERENCE_MAX_BYTES = 16 * 1024
internal const val CONSULTATION_REFERENCE_MAX_RESULT_BYTES = 20 * 1024

/** Older ParcelFileDescriptor.dup can clear close-on-exec. Only this reader is gated. */
internal fun requireConsultationReferencePlatform(sdkInt: Int) {
    check(sdkInt >= 29) {
        "consultation_reference_android_version_unsupported: 此查书通道需要 Android 10 或更高版本。"
    }
}

/** Each child must be opened relative to the held parent handle, without following links. */
internal interface ConsultationReferenceDirectory : Closeable {
    fun directory(name: String): ConsultationReferenceDirectory
    fun readRegularFile(name: String, maxBytes: Int): ByteArray
}

/** A lookup is data only. No file contents, extra arguments or saved grants expand this policy. */
internal fun consultationReferencePath(arguments: JsonElement): String? {
    val objectValue = arguments as? JsonObject ?: return null
    if (objectValue.keys != setOf("path")) return null
    val primitive = objectValue["path"] as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    val path = primitive.content
    if (!path.startsWith(CONSULTATION_REFERENCE_PREFIX)) return null
    val name = path.removePrefix(CONSULTATION_REFERENCE_PREFIX)
    if (name.length > 128 || ".." in name ||
        !Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(?:md|txt)").matches(name)) return null
    return path
}

/** The root is derived from WorkspaceManager, never supplied by the model. */
internal fun readConsultationReference(
    workspaceFilesDir: File,
    arguments: JsonElement,
    openTrustedRoot: (File) -> ConsultationReferenceDirectory,
): String {
    val path = consultationReferencePath(arguments) ?: error("consultation_reference_path_denied")
    val name = path.removePrefix(CONSULTATION_REFERENCE_PREFIX)
    val workspaceDir = workspaceFilesDir.parentFile ?: error("consultation_reference_root_invalid")
    val workspacesDir = workspaceDir.parentFile ?: error("consultation_reference_root_invalid")
    val appFilesDir = workspacesDir.parentFile ?: error("consultation_reference_root_invalid")
    check(workspaceFilesDir.name == "files" && workspacesDir.name == "workspaces" &&
        Regex("[A-Za-z0-9_-]{1,100}").matches(workspaceDir.name)) { "consultation_reference_root_invalid" }
    // Platform aliases above filesDir are trusted. Every application-owned descendant
    // is resolved one component at a time through an already-open directory handle.
    val bytes = openTrustedRoot(appFilesDir).use { app ->
        app.directory("workspaces").use { workspaces ->
            workspaces.directory(workspaceDir.name).use { workspace ->
                workspace.directory("files").use { files ->
                    files.directory(CONSULTATION_REFERENCE_DIRECTORY).use { library ->
                        library.readRegularFile(name, CONSULTATION_REFERENCE_MAX_BYTES)
                    }
                }
            }
        }
    }
    check(bytes.size <= CONSULTATION_REFERENCE_MAX_BYTES) { "consultation_reference_too_large" }
    val content = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    return buildJsonObject {
        put("instruction_authority", "none")
        put("path", path)
        put("text", content)
    }.toString().also {
        check(it.toByteArray(Charsets.UTF_8).size <= CONSULTATION_REFERENCE_MAX_RESULT_BYTES) {
            "consultation_reference_result_too_large"
        }
    }
}
