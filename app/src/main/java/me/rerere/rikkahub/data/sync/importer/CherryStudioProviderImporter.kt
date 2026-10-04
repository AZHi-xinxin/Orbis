package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.common.http.jsonObjectOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

object CherryStudioProviderImporter {
    internal const val MAX_DATA_BYTES = 64L * ArchiveCapacity.MIB

    fun importProviders(file: File, checkCancelled: () -> Unit = {}): List<ProviderSetting> {
        ArchiveCapacity.requireSize(file.length(), ArchiveCapacity.MAX_ZIP_BYTES)
        val dataJson = ZipFile(file).use { zip ->
            val paths = hashSetOf<String>()
            var expanded = 0L
            for (item in zip.entries()) {
                checkCancelled()
                ArchiveCapacity.requireSafePath(item.name, item.isDirectory)
                if (paths.size >= ArchiveCapacity.MAX_ENTRIES || !paths.add(item.name))
                    throw ArchiveReadException(ArchiveFailure.UNSAFE_PATH)
                ArchiveCapacity.requireSize(item.size, ArchiveCapacity.MAX_DISK_ENTRY_BYTES, allowEmpty = true)
                expanded += item.size
                ArchiveCapacity.requireSize(expanded, ArchiveCapacity.MAX_EXPANDED_BYTES, allowEmpty = true)
            }
            val entry = zip.getEntry("data.json")
                ?: throw IllegalArgumentException("Invalid Cherry Studio backup: data.json not found")
            require(!entry.isDirectory)
            ArchiveCapacity.requireSize(entry.size, MAX_DATA_BYTES)
            val bytes = ByteArrayOutputStream(minOf(entry.size, 8192).toInt()).use { output ->
                ArchiveCapacity.copyZipEntry(zip, entry, output, MAX_DATA_BYTES, checkCancelled)
                output.toByteArray()
            }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }

        val root = DeepSeekStrictJson.parse(dataJson, checkCancelled).jsonObject
        val persistedRaw = root["localStorage"]
            ?.jsonObject
            ?.get("persist:cherry-studio")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: throw IllegalArgumentException("Invalid Cherry Studio backup: persist data missing")
        val persisted = DeepSeekStrictJson.parse(persistedRaw, checkCancelled).jsonObject

        val llmRaw = persisted["llm"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Invalid Cherry Studio backup: llm settings missing")
        val llm = DeepSeekStrictJson.parse(llmRaw, checkCancelled).jsonObject

        return llm["providers"]?.jsonArray
            ?.mapNotNull { it.jsonObjectOrNull?.let(::parseProvider) }
            ?.distinctBy { importedProviderKey(it) }
            .orEmpty()
    }

    private fun parseProvider(provider: JsonObject): ProviderSetting? {
        val apiKey = provider["apiKey"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (apiKey.isBlank()) return null

        val type = provider["type"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
        val name = provider["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "Cherry Studio"
        val apiHost = provider["apiHost"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val enabled = provider["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val models = parseModels(provider["models"]?.jsonArray)

        return when (type) {
            "anthropic" -> ProviderSetting.Claude(
                name = name,
                enabled = enabled,
                baseUrl = normalizeBaseUrl(
                    apiHost = apiHost,
                    suffix = "/v1",
                    fallback = ProviderSetting.Claude().baseUrl
                ),
                apiKey = apiKey,
                models = models,
            )

            "gemini", "vertexai" -> ProviderSetting.Google(
                name = name,
                enabled = enabled,
                baseUrl = normalizeBaseUrl(
                    apiHost = apiHost,
                    suffix = "/v1beta",
                    fallback = ProviderSetting.Google().baseUrl
                ),
                apiKey = apiKey,
                models = models,
            )

            else -> {
                val useResponseApi = type == "openai-response" || provider["models"]?.jsonArray?.any {
                    it.jsonObjectOrNull?.get("endpoint_type")?.jsonPrimitive?.contentOrNull == "openai-response"
                } == true
                ProviderSetting.OpenAI(
                    name = name,
                    enabled = enabled,
                    baseUrl = normalizeBaseUrl(
                        apiHost = apiHost,
                        suffix = "/v1",
                        fallback = ProviderSetting.OpenAI().baseUrl
                    ),
                    apiKey = apiKey,
                    models = models,
                    useResponseApi = useResponseApi,
                )
            }
        }
    }

    private fun parseModels(models: JsonArray?): List<Model> {
        if (models == null) return emptyList()
        return models.mapNotNull { modelElement ->
            val model = modelElement.jsonObjectOrNull ?: return@mapNotNull null
            val modelId = model["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (modelId.isBlank()) return@mapNotNull null

            val displayName = model["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { modelId } ?: modelId
            Model(
                modelId = modelId,
                displayName = displayName,
                inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId),
                outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId),
                abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId),
            )
        }
    }

    private fun normalizeBaseUrl(apiHost: String, suffix: String, fallback: String): String {
        val normalizedHost = apiHost.trim().trimEnd('/')
        if (normalizedHost.isBlank()) return fallback
        return if (normalizedHost.endsWith(suffix)) normalizedHost else "$normalizedHost$suffix"
    }

    private fun importedProviderKey(provider: ProviderSetting): String {
        return when (provider) {
            is ProviderSetting.OpenAI -> "openai|${provider.baseUrl}|${provider.apiKey}"
            is ProviderSetting.Google -> "google|${provider.baseUrl}|${provider.apiKey}"
            is ProviderSetting.Claude -> "claude|${provider.baseUrl}|${provider.apiKey}"
        }
    }
}
