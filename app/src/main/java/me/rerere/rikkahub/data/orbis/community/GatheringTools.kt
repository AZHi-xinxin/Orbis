package me.rerere.rikkahub.data.orbis.community

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*

internal val GATHERING_ACTIONS=listOf("list","detail","options","create","signup","withdraw","cancel","reschedule","join","reconcile").map {"gathering_$it"}
internal suspend fun gatheringTool(context:Context,assistant:String,conversation:String,actor:String,b:JsonObject):JsonObject {
    val api=GatheringClient(context);val op=b.ms("action").removePrefix("gathering_")
    if(op=="list")return api.get("list",mapOf("mine" to b.mb("only_mine").toString(),"offset" to b.mi("offset").toString()))
    if(op=="options")return api.get("options")
    if(op=="detail")return gatheringEntryGuidance(api.get("detail",mapOf("table" to b.ms("target"))))
    if(op=="reconcile")return api.reconcile(actor)
    GatheringDelivery.target(context,assistant,conversation)
    val body=buildJsonObject {
        put("ai",actor)
        if(b.ms("target").isNotBlank())put("table",b.ms("target"))
        for(k in listOf("game","mode","script","starts_at","timezone","revision")) b[k]?.let {put(k,it)}
    }
    val result=api.post(if(op=="join") "resolve" else op,actor,body)
    if(op=="join")return GatheringDelivery.join(context,assistant,conversation,result)
    var delivered=true
    try {GatheringDelivery.receive(context,preferredAssistant=assistant,preferredConversation=conversation)}
    catch(e:CancellationException){throw e} catch(_:Exception){delivered=false}
    return JsonObject(result+mapOf("receipt_delivery_checked" to JsonPrimitive(delivered),"sentinel_help" to JsonPrimitive(GATHERING_HELP)))
}
