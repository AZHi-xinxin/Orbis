package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID
import me.rerere.ai.core.TokenUsage

/** Counts only leave this probe. Content hashes live in a bounded process-local map, never disk. */
@Serializable data class PrefixObservation(val slot:String,val sequence:Int,val atMillis:Long,
    val model:String,val compared:Boolean,val toolsSame:Boolean,val optionsSame:Boolean,
    val commonMessages:Int,val commonMessageBytes:Int,val firstChangedMessage:Int?,val firstChangedRole:String?,
    val messageCount:Int,val messageBytes:Int,
    val firstChangedSources:List<String> = emptyList(), val changedMessageBytes:Int? = null,
    val imagePartCount:Int = 0, val inlineMediaBytes:Int = 0,
    val phase:String = "request", val usageAtMillis:Long? = null,
    val promptTokens:Int? = null, val completionTokens:Int? = null, val cachedTokens:Int? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
    val protocol:String = "chat_completions",
    val previousChangedSources:List<String> = emptyList(), val previousChangedMessageBytes:Int? = null,
    val usageSnapshots:List<PrefixUsageSnapshot> = emptyList(),
    val nativeCache:me.rerere.ai.provider.providers.claude.NativeCacheEvidence? = null)

/** Only allowlisted numeric usage fields leave the process; no response body or provider IDs. */
@Serializable data class PrefixUsageSnapshot(
    val fields:Map<String,Int>, val cachedTokensSource:String? = null,
)

internal fun prefixUsageSnapshot(usage:JsonObject, native:Boolean = false):PrefixUsageSnapshot {
    val fields=linkedMapOf<String,Int>()
    for(key in listOf("prompt_tokens","completion_tokens","total_tokens","cached_tokens",
        "prompt_cache_hit_tokens","prompt_cache_miss_tokens","input_tokens","output_tokens",
        "cache_read_input_tokens","cache_creation_input_tokens")) {
        (usage[key] as? JsonPrimitive)?.intOrNull?.let { fields[key]=it }
    }
    ((usage["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)
        ?.intOrNull?.let { fields["prompt_tokens_details.cached_tokens"]=it }
    for(key in listOf("ephemeral_5m_input_tokens", "ephemeral_1h_input_tokens")) {
        ((usage["cache_creation"] as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull
            ?.let { fields["cache_creation.$key"]=it }
    }
    val candidates=if(native) listOf("cache_read_input_tokens") else
        listOf("prompt_tokens_details.cached_tokens","cached_tokens","prompt_cache_hit_tokens")
    return PrefixUsageSnapshot(fields,candidates.firstOrNull { it in fields })
}

internal fun PrefixObservation.withUsage(usage: TokenUsage,
    snapshots: List<PrefixUsageSnapshot> = emptyList()): PrefixObservation = copy(
    phase = "usage", usageAtMillis = System.currentTimeMillis(),
    promptTokens = usage.promptTokens, completionTokens = usage.completionTokens,
    cachedTokens = usage.cachedTokens.takeIf { usage.cachedTokensReported },
    usageSnapshots = snapshots.toList(),
)

internal class DeepSeekPrefixProbe {
    private data class Part(val role:String,val bytes:Int,val blocks:List<String>,val sources:List<String>)
    private data class Stamp(val slot:String,val sequence:Int,val tools:String,val options:String,val messages:List<Part>)
    private val previous=linkedMapOf<String,Stamp>()
    private fun hash(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString(""){"%02x".format(it)}
    private fun digest(value:JsonElement?)=hash(value.toString().toByteArray())
    @Synchronized fun observe(key:String,body:JsonObject,sourceHints:Map<String,List<String>> = emptyMap()):PrefixObservation {
        val model=(body["model"] as? JsonPrimitive)?.content.orEmpty()
        val messages=(body["messages"] as? JsonArray).orEmpty().map { value ->
            val bytes=value.toString().toByteArray()
            val role=(value as? JsonObject)?.get("role")?.jsonPrimitive?.content
                ?.takeIf { it in setOf("system","developer","user","assistant","tool") } ?: "unknown"
            val message=value as? JsonObject
            val sources=message?.let {
                sourceHints[it["content"].toString()] ?: if (it["name"]?.jsonPrimitive?.contentOrNull == "orbis_host_data_v1")
                    listOf("host_context") else listOf(if(role=="system") "system_unknown" else role)
            }.orEmpty()
            Part(role,bytes.size,(bytes.indices step 4096).map { hash(bytes.copyOfRange(it,minOf(it+4096,bytes.size))) },sources)
        }
        val old=previous.remove(key)
        val current=Stamp(old?.slot ?: UUID.randomUUID().toString(),(old?.sequence ?: 0)+1,digest(body["tools"]),
            digest(JsonObject(body.filterKeys { it !in setOf("messages","tools","stream","stream_options") })),messages)
        previous[key]=current
        while(previous.size>12) previous.remove(previous.keys.first())
        var count=0;var bytes=0
        if(old!=null) for(i in 0 until minOf(old.messages.size,messages.size)) {
            val a=old.messages[i];val b=messages[i]
            // Provenance labels are diagnostic metadata, not part of the wire prefix.
            if(a.role==b.role && a.bytes==b.bytes && a.blocks==b.blocks){count++;bytes+=b.bytes}
            else { for(j in 0 until minOf(a.blocks.size,b.blocks.size)) {
                if(a.blocks[j]!=b.blocks[j]) break
                bytes+=minOf(4096,b.bytes-j*4096)
            };break }
        }
        val index=if(old!=null && count<minOf(old.messages.size,messages.size)) count else null
        val rawMessages=(body["messages"] as? JsonArray).orEmpty()
        val sources=index?.let { messages[it].sources }.orEmpty()
        var imageCount=0;var mediaBytes=0
        fun countMedia(value:JsonElement) { when(value) {
            is JsonObject -> {
                if ((value["type"] as? JsonPrimitive)?.contentOrNull in setOf("image_url","input_image","image")) imageCount++
                value.values.forEach(::countMedia)
            }
            is JsonArray -> value.forEach(::countMedia)
            is JsonPrimitive -> if (value.isString && listOf("data:image/","data:audio/","data:video/").any { value.content.startsWith(it) }) mediaBytes+=value.content.toByteArray().size
        } }
        rawMessages.forEach(::countMedia)
        val family=when {
            model.contains("deepseek",true) -> if(model.contains("pro",true)) "pro" else if(model.contains("flash",true)) "flash" else "deepseek"
            model.contains("gpt",true) -> "gpt"
            model.contains("claude",true) -> "claude"
            model.contains("gemini",true) -> "gemini"
            else -> "other"
        }
        return PrefixObservation(current.slot,current.sequence,System.currentTimeMillis(),family,
            old!=null,old?.tools==current.tools,old?.options==current.options,count,bytes,index,
            index?.let { messages[it].role },messages.size,messages.sumOf { it.bytes },
            sources,index?.let { messages[it].bytes },imageCount,mediaBytes,
            previousChangedSources=index?.let { old?.messages?.get(it)?.sources }.orEmpty(),
            previousChangedMessageBytes=index?.let { old?.messages?.get(it)?.bytes })
    }
    companion object { val shared=DeepSeekPrefixProbe() }
}
