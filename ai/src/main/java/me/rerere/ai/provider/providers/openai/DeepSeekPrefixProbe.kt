package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

/** Counts only leave this probe. Content hashes live in a bounded process-local map, never disk. */
@Serializable data class PrefixObservation(val slot:String,val sequence:Int,val atMillis:Long,
    val model:String,val compared:Boolean,val toolsSame:Boolean,val optionsSame:Boolean,
    val commonMessages:Int,val commonMessageBytes:Int,val firstChangedMessage:Int?,val firstChangedRole:String?,
    val messageCount:Int,val messageBytes:Int)

internal class DeepSeekPrefixProbe {
    private data class Part(val role:String,val bytes:Int,val blocks:List<String>)
    private data class Stamp(val slot:String,val sequence:Int,val tools:String,val options:String,val messages:List<Part>)
    private val previous=linkedMapOf<String,Stamp>()
    private fun hash(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString(""){"%02x".format(it)}
    private fun digest(value:JsonElement?)=hash(value.toString().toByteArray())
    @Synchronized fun observe(key:String,body:JsonObject):PrefixObservation {
        val model=(body["model"] as? JsonPrimitive)?.content.orEmpty()
        val messages=(body["messages"] as? JsonArray).orEmpty().map { value ->
            val bytes=value.toString().toByteArray()
            val role=(value as? JsonObject)?.get("role")?.jsonPrimitive?.content
                ?.takeIf { it in setOf("system","developer","user","assistant","tool") } ?: "unknown"
            Part(role,bytes.size,(bytes.indices step 4096).map { hash(bytes.copyOfRange(it,minOf(it+4096,bytes.size))) })
        }
        val old=previous.remove(key)
        val current=Stamp(old?.slot ?: UUID.randomUUID().toString(),(old?.sequence ?: 0)+1,digest(body["tools"]),
            digest(JsonObject(body.filterKeys { it !in setOf("messages","tools","stream","stream_options") })),messages)
        previous[key]=current
        while(previous.size>12) previous.remove(previous.keys.first())
        var count=0;var bytes=0
        if(old!=null) for(i in 0 until minOf(old.messages.size,messages.size)) {
            val a=old.messages[i];val b=messages[i]
            if(a==b){count++;bytes+=b.bytes}
            else { for(j in 0 until minOf(a.blocks.size,b.blocks.size)) {
                if(a.blocks[j]!=b.blocks[j]) break
                bytes+=minOf(4096,b.bytes-j*4096)
            };break }
        }
        val index=if(old!=null && count<minOf(old.messages.size,messages.size)) count else null
        return PrefixObservation(current.slot,current.sequence,System.currentTimeMillis(),
            if(model.contains("pro",true)) "pro" else if(model.contains("flash",true)) "flash" else "deepseek",
            old!=null,old?.tools==current.tools,old?.options==current.options,count,bytes,index,
            index?.let { messages[it].role },messages.size,messages.sumOf { it.bytes })
    }
    companion object { val shared=DeepSeekPrefixProbe() }
}
