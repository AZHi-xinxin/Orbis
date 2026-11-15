package me.rerere.rikkahub.data.orbis.tabletop

/** The game belongs to an assistant. conversationId is a changeable reminder destination, not a lock. */
internal data class TabletopConversationBinding(
    val assistantId:String, val conversationId:String, val room:String, val title:String,
    val automatic:Boolean=false, val awaySince:Long?=null,
) {
    val source get()="tabletop.$assistantId.$conversationId"
    fun connected(now:Long)=automatic || (awaySince?.let { now-it<60*60*1000L } ?: true)
}
