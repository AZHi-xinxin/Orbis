package me.rerere.rikkahub.data.orbis.community
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*
import org.junit.Assert.*
import org.junit.Test
class CommunityWardrobeTest {
 private class Api:CommunityApi {
  override val owner="owner";val calls=mutableListOf<Pair<String,String>>();var badReceipt=false
  override suspend fun get(path:String,actor:String,quick:Boolean):JsonObject {calls+=path to actor;return buildJsonObject {put("ok",true)}}
  override suspend fun post(path:String,actor:String,body:JsonObject):JsonObject {calls+=path to actor;return buildJsonObject {put("saved",true);put("actor",if(badReceipt)"other" else actor);putJsonObject("wardrobe"){put("revision",body.cl("expected_revision")+1)}}}
 }
 private fun args()=buildJsonObject {put("action","wardrobe");put("operation","save");put("target","shared");put("expected_revision",0);putJsonObject("appearance"){put("hair","hair-09")}}
 @Test fun scopedSaveChecksReceiptAndRejectsForeignActorArguments()=runBlocking {
  val api=Api();assertTrue(communityWardrobe(api,"bound-ai",args()).mb("saved"));assertEquals(listOf("/wardrobe" to "bound-ai"),api.calls)
  assertTrue(runCatching{communityWardrobe(api,"bound-ai",JsonObject(args()+mapOf("actor" to JsonPrimitive("other"))))}.isFailure);assertEquals(1,api.calls.size)
  api.badReceipt=true;assertTrue(runCatching{communityWardrobe(api,"bound-ai",args())}.isFailure);assertEquals(2,api.calls.size)
 }
 @Test fun noWriteWhenRevisionMissingOrMalformed()=runBlocking {
  for(b in listOf(JsonObject(args()-"expected_revision"),JsonObject(args()+mapOf("expected_revision" to JsonPrimitive("0"))),JsonObject(args()+mapOf("expected_revision" to JsonPrimitive(-1))))) {
   val api=Api();assertTrue(runCatching{communityWardrobe(api,"ai",b)}.isFailure);assertTrue(api.calls.isEmpty())
  }
 }
 @Test fun catalogueIsPagedAndCategoryBound()=runBlocking {
  val api=Api();communityWardrobe(api,"ai",buildJsonObject {put("action","wardrobe");put("operation","catalog");put("target","accessories");put("offset",20);put("limit",20)})
  assertEquals(listOf("/wardrobe/catalog?category=accessories&offset=20&limit=20" to "ai"),api.calls)
 }
}
