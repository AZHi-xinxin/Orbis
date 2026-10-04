package me.rerere.rikkahub.data.orbis.privateroom

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.findProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID

/** Public routing consent only. No credentials, private records or recovery material are stored here. */
@Serializable
internal data class PrivateRoomModelChoice(val version: Int = 1, val modelId: String,
    val binding: String, val directApi: Boolean = false)

internal fun privateRoomModelBinding(provider: ProviderSetting, model: Model): String {
    // Changes to credentials, origin, model or its overrides invalidate consent before any private request.
    val text = Json.encodeToString<ProviderSetting>(provider.copyProvider(models = emptyList())) + "\n" +
        Json.encodeToString(model)
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun PrivateRoomModelChoice.matches(provider: ProviderSetting, model: Model): Boolean =
    version == 1 && modelId == model.id.toString() && binding == privateRoomModelBinding(provider, model)

internal fun privateRoomEnabledProvider(model: Model, providers: List<ProviderSetting>): ProviderSetting? {
    if (model.findProvider(providers, checkOverwrite = false)?.enabled != true) return null
    return model.findProvider(providers)?.takeIf { it.enabled }
}

/** Origin only, never credentials, query parameters or an endpoint's potentially private path. */
internal fun privateRoomProviderOrigin(provider: ProviderSetting): String {
    val base = when (provider) {
        is ProviderSetting.OpenAI -> provider.baseUrl
        is ProviderSetting.Claude -> provider.baseUrl
        is ProviderSetting.Google -> provider.baseUrl
    }.toHttpUrlOrNull() ?: return "无效地址"
    val host = if (':' in base.host) "[${base.host}]" else base.host
    return "${base.scheme}://$host:${base.port}"
}

/** This local consent is outside the vault export. A new installation must select/confirm its route again. */
internal class PrivateRoomModelChoiceStore(directory: File, assistantId: String, trustedRoot: File) {
    private val base = trustedRoot.canonicalFile.toPath()
    private val directory: File
    private val file: File
    init {
        require(UUID.fromString(assistantId).toString() == assistantId) { "private_route_invalid_owner" }
        val suppliedBase = trustedRoot.toPath().toAbsolutePath().normalize()
        val suppliedDirectory = directory.toPath().toAbsolutePath().normalize()
        require(suppliedDirectory.startsWith(suppliedBase)) { "private_route_unsafe_path" }
        this.directory = base.resolve(suppliedBase.relativize(suppliedDirectory)).toFile()
        file = File(this.directory, "$assistantId.json")
    }
    fun read(): PrivateRoomModelChoice? = synchronized(lock) {
        rejectLinks()
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return@synchronized null
        check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= 2048) { "private_route_invalid_choice" }
        val bytes = Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(2049)
            var total = 0
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count < 0) break
                if (count > 0) total += count
            }
            buffer.copyOf(total)
        }
        check(bytes.size <= 2048) { "private_route_invalid_choice" }
        val choice = Json.decodeFromString<PrivateRoomModelChoice>(bytes.toString(Charsets.UTF_8))
        validate(choice)
        choice
    }
    fun save(choice: PrivateRoomModelChoice) = synchronized(lock) {
        validate(choice); rejectLinks()
        Files.createDirectories(directory.toPath())
        rejectLinks()
        val temporary = File(directory, ".route-${UUID.randomUUID()}.tmp")
        try {
            Files.newOutputStream(temporary.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use {
                it.write(Json.encodeToString(choice).toByteArray(Charsets.UTF_8))
            }
            rejectLinks()
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }
    fun clear() = synchronized(lock) { rejectLinks(); Files.deleteIfExists(file.toPath()); Unit }
    private fun validate(choice: PrivateRoomModelChoice) {
        check(choice.version == 1 && UUID.fromString(choice.modelId).toString() == choice.modelId &&
            choice.binding.matches(Regex("[0-9a-f]{64}"))) { "private_route_invalid_choice" }
    }
    private fun rejectLinks() {
        var path = file.toPath().toAbsolutePath().normalize()
        check(path.startsWith(base) && path != base) { "private_route_unsafe_path" }
        while (true) {
            check(!Files.isSymbolicLink(path)) { "private_route_unsafe_path" }
            if (path == base) break
            path = checkNotNull(path.parent)
        }
    }
    companion object { private val lock = Any() }
}
