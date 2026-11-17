package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.ai.provider.providers.openai.DeepSeekPrefixProbe
import me.rerere.ai.provider.providers.openai.PrefixObservation
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.Request
import java.security.MessageDigest

@Serializable data class NativeCachePoint(
    val section: String, val item: Int? = null, val block: Int? = null,
    val type: String, val ttl: String,
)

/** Wire counts remain in PrefixObservation. Content comparisons below omit only protocol cache
 * annotations, so moving a breakpoint cannot masquerade as a change to conversation text. */
@Serializable data class NativeCacheEvidence(
    val systemSame: Boolean?, val systemContentSame: Boolean?, val systemBytes: Int,
    val firstChangedSystemBlock: Int?,
    val toolsContentSame: Boolean?, val cacheControlSame: Boolean?,
    val headersSame: Boolean?, val endpointSame: Boolean?,
    val contentCommonMessages: Int, val contentCommonMessageBytes: Int,
    val contentFirstChangedMessage: Int?,
    val contentPreviousChangedSources: List<String>, val contentChangedSources: List<String>,
    val previousMessageCount: Int?, val cachePointCount: Int, val cachePoints: List<NativeCachePoint>,
)

internal class ClaudePrefixProbe {
    private data class Stamp(
        val system: String, val systemContent: String, val systemBlocks: List<String>,
        val toolsContent: String, val controls: String, val headers: String, val endpoint: String,
        val messageCount: Int,
    )
    private val previous = linkedMapOf<String, Stamp>()
    private val wireProbe = DeepSeekPrefixProbe()
    private val contentProbe = DeepSeekPrefixProbe()
    private fun digest(value: Any?) = MessageDigest.getInstance("SHA-256")
        .digest(value.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    private fun withoutControl(value: JsonElement): JsonElement =
        if (value is JsonObject) JsonObject(value.filterKeys { it != "cache_control" }) else value
    private fun blocks(value: JsonElement?): List<JsonElement> =
        when (value) { null -> emptyList(); is JsonArray -> value; else -> listOf(value) }

    @Synchronized fun observe(key: String, body: JsonObject, request: Request,
        messages: List<UIMessage>): PrefixObservation {
        val points = mutableListOf<NativeCachePoint>()
        val controls = mutableListOf<String>()
        fun point(value: JsonElement?, section: String, item: Int? = null, block: Int? = null) {
            if (value == null) return
            // Unknown custom values affect comparison, but are never exported verbatim.
            controls += "$section/$item/$block:$value"
            val obj = value as? JsonObject
            val type = (obj?.get("type") as? JsonPrimitive)?.contentOrNull
            val ttl = (obj?.get("ttl") as? JsonPrimitive)?.contentOrNull
            points += NativeCachePoint(section, item, block,
                if (type == "ephemeral") type else "other",
                when { obj != null && "ttl" !in obj -> "default"; ttl in setOf("5m", "1h") -> ttl!!; else -> "other" })
        }
        point(body["cache_control"], "request")
        val system = blocks(body["system"])
        system.forEachIndexed { i, b -> point((b as? JsonObject)?.get("cache_control"), "system", block = i) }
        val tools = (body["tools"] as? JsonArray).orEmpty()
        tools.forEachIndexed { i, t -> point((t as? JsonObject)?.get("cache_control"), "tools", item = i) }
        val wireMessages = (body["messages"] as? JsonArray).orEmpty()
        val contentMessages = wireMessages.mapIndexed { i, message ->
            val obj = message as? JsonObject ?: return@mapIndexed message
            val content = obj["content"] as? JsonArray ?: return@mapIndexed obj
            val stripped = content.mapIndexed { j, b ->
                point((b as? JsonObject)?.get("cache_control"), "messages", i, j)
                withoutControl(b)
            }
            JsonObject(obj + ("content" to JsonArray(stripped)))
        }
        // System and cache controls are reported separately from generation options.
        val wire = JsonObject(body.filterKeys { it != "system" && it != "cache_control" })
        val content = JsonObject(wire + ("messages" to JsonArray(contentMessages)) +
            if ("tools" in wire) mapOf("tools" to JsonArray(tools.map(::withoutControl))) else emptyMap())
        val old = previous.remove(key)
        val stamp = Stamp(digest(body["system"]), digest(system.map(::withoutControl)),
            system.map { digest(withoutControl(it)) }, digest(tools.map(::withoutControl)),
            digest(controls), digest(request.headers.toMultimap()), digest(request.url), wireMessages.size)
        previous[key] = stamp
        while (previous.size > 12) previous.remove(previous.keys.first())
        val sourceByText = nativeTextSources(messages)
        fun hints(values: List<JsonElement>) = values.mapNotNull { value ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val sources = blocks(obj["content"]).flatMap { block ->
                val text = ((block as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
                sourceByText[text].orEmpty()
            }.distinct().sorted()
            if (sources.isEmpty()) null else obj["content"].toString() to sources
        }.toMap()
        val actual = wireProbe.observe(key, wire, hints(wireMessages))
        val normalized = contentProbe.observe(key, content, hints(contentMessages))
        val changedSystem = old?.let {
            (0 until maxOf(it.systemBlocks.size, stamp.systemBlocks.size)).firstOrNull { i ->
                it.systemBlocks.getOrNull(i) != stamp.systemBlocks.getOrNull(i)
            }
        }
        return actual.copy(protocol = "anthropic_messages", nativeCache = NativeCacheEvidence(
            old?.let { it.system == stamp.system }, old?.let { it.systemContent == stamp.systemContent },
            body["system"]?.toString()?.toByteArray()?.size ?: 0, changedSystem,
            old?.let { it.toolsContent == stamp.toolsContent }, old?.let { it.controls == stamp.controls },
            old?.let { it.headers == stamp.headers }, old?.let { it.endpoint == stamp.endpoint },
            normalized.commonMessages, normalized.commonMessageBytes, normalized.firstChangedMessage,
            normalized.previousChangedSources, normalized.firstChangedSources,
            old?.messageCount, points.size, points.take(64),
        ))
    }
    companion object { val shared = ClaudePrefixProbe() }
}

private fun nativeTextSources(messages: List<UIMessage>): Map<String, List<String>> {
    val known = mapOf("orbis_user_message_time" to "message_time", "orbis_model_continuity" to "model_continuity",
        "orbis_local_memory" to "local_memory", "orbis_body_status" to "body_status",
        "orbis_community_hint" to "community", "orbis_observation" to "observation", "orbis_host" to "host_notice")
    val allowed = setOf("system_prompt", "worldbook", "mode_injection")
    val result = mutableMapOf<String, List<String>>()
    for (part in messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>()) {
        val source = (part.metadata?.get("source") as? JsonPrimitive)?.contentOrNull
        val sources = if (source == "orbis_prompt_injection")
            (part.metadata?.get("diagnostic_sources") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it in allowed }
        else listOfNotNull(known[source])
        if (sources.isNotEmpty()) result[part.text] = (result[part.text].orEmpty() + sources).distinct().sorted()
    }
    return result
}
