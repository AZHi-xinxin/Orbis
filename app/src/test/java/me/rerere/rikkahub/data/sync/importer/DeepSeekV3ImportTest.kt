package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

class DeepSeekV3ImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val time = "2026-11-13T12:00:00Z"
    private fun message(id: Int, parent: Int?, role: String, vararg fragments: JsonObject) = buildJsonObject {
        put("id",id); put("parent_id",parent?.let(::JsonPrimitive) ?: JsonNull); put("role",role)
        put("status","FINISHED"); put("inserted_at",time); put("fragments",JsonArray(fragments.toList()))
    }
    private fun fragment(type: String, text: String) = buildJsonObject { put("type",type);put("content",text) }
    private val attachment = buildJsonObject { put("type","FILE"); put("files",buildJsonArray {
        add(buildJsonObject {put("id","f");put("file_name","image.png");put("file_size",3);put("is_image",true);put("export_path","chat_sessions/c_files/f.png")})
        add(buildJsonObject {put("id","missing");put("file_name","lost.png");put("file_size",4);put("is_image",true);put("export_path",JsonNull)})
    }) }
    private fun session(): JsonObject = buildJsonObject {
        put("id","c");put("title","Synthetic export");put("inserted_at",time);put("updated_at",time);put("current_message_id",3)
        put("messages",buildJsonArray {
            // Intentionally unordered, with another root and a newer alternative answer.
            add(message(3,1,"ASSISTANT",fragment("THINK","thought"),fragment("TEMPLATE_RESPONSE","selected"),fragment("TIP","tip")))
            add(message(1,null,"USER",fragment("REQUEST","question"),fragment("CONTENT_RISK","risk"),attachment))
            add(message(2,1,"ASSISTANT",fragment("RESPONSE","alternative")))
            add(message(4,null,"USER",fragment("REQUEST","other root")))
        })
    }
    private fun zip(raw: JsonObject = session(), extra: Map<String,ByteArray> = emptyMap(), prefix: String = ""): File {
        val index = buildJsonArray {add(buildJsonObject {put("id","c");put("message_count",raw["messages"]!!.jsonArray.size)})}
        val entries = linkedMapOf("manifest.json" to "{\"export_format_version\":3}".toByteArray(),
            "chat_session_index.json" to index.toString().toByteArray(), "user.json" to byteArrayOf(-1),
            "chat_sessions/c.json" to raw.toString().toByteArray(), "chat_sessions/c_files/f.png" to byteArrayOf(1,2,3)) + extra
        return temporary.newFile().also { file -> ZipOutputStream(file.outputStream()).use { out -> entries.forEach { (name,data) ->
            out.putNextEntry(ZipEntry(prefix+name));out.write(data);out.closeEntry()
        } } }
    }
    private class Sink : DeepSeekImportSink {
        val saved=linkedMapOf<Uuid,Conversation>()
        override suspend fun exists(id:Uuid)=id in saved
        override suspend fun insert(conversation:Conversation):Boolean {saved[conversation.id]=conversation;return true}
    }
    @Test fun indexAndSelectedBranchRespectExplicitRolesAndMultipleRoots() {
        val file=zip();val before=file.readBytes();val preview=DeepSeekArchive.inspect(file).conversations.single()
        assertEquals("3",preview.defaultLeafId);assertEquals(4,preview.messageCount);assertEquals(2,preview.branches.single{it.isDefault}.messageCount)
        DeepSeekArchive.open(file){}.use { archive ->
            val c=archive.conversations().single();assertEquals(listOf("1","3"),c.pathTo("3").map{it.sourceId})
            assertEquals(MessageRole.USER,c.nodes["1"]!!.message!!.role)
            val converted=DeepSeekChatImporter.convert(c,"3",owner).conversation.currentMessages
            assertEquals("thought",(converted[1].parts[0] as UIMessagePart.Reasoning).reasoning)
            assertTrue(converted[0].toText().contains("risk"));assertTrue(converted[1].toText().contains("selected"));assertTrue(converted.all{it.getTools().isEmpty()})
        }
        assertArrayEquals(before,file.readBytes());assertEquals(preview,DeepSeekArchive.inspect(zip(prefix="export/")).conversations.single())
    }
    @Test fun selectedLocalAttachmentsRestoreAndRepeatSkipsWithoutSavingAgain()=runBlocking {
        val sink=Sink();var saves=0;val store=object:DeepSeekAttachmentStore {
            override val cacheDir=temporary.newFolder()
            override suspend fun save(file:File,name:String):DeepSeekStoredAttachment {assertArrayEquals(byteArrayOf(1,2,3),file.readBytes());saves++;return DeepSeekStoredAttachment(1,"file:///synthetic/image.png")}
            override suspend fun delete(id:Long){fail("committed attachment was deleted")}
        }
        val importer=DeepSeekChatImporter(sink,store);val file=zip();val result=importer.import(file,owner,mapOf("c" to "3"))
        assertEquals(1,result.imported);assertEquals(2,result.attachmentReferences);assertEquals(1,result.restoredAttachments)
        assertEquals(1,saves);val parts=sink.saved.values.single().currentMessages.first().parts
        assertEquals("file:///synthetic/image.png",parts.filterIsInstance<UIMessagePart.Image>().single().url)
        assertTrue(parts.filterIsInstance<UIMessagePart.Text>().any{it.text.contains("lost.png")})
        assertEquals(1,importer.import(file,owner,mapOf("c" to "3")).skipped);assertEquals(1,saves)
    }
    @Test fun failedConversationRollsBackOnlyItsNewAttachments()=runBlocking {
        var deleted=0
        val store=object:DeepSeekAttachmentStore {
            override val cacheDir=temporary.newFolder()
            override suspend fun save(file:File,name:String)=DeepSeekStoredAttachment(7,"file:///synthetic/f.png")
            override suspend fun delete(id:Long){assertEquals(7L,id);deleted++}
        }
        val sink=object:DeepSeekImportSink {override suspend fun exists(id:Uuid)=false;override suspend fun insert(conversation:Conversation):Boolean=error("synthetic failure")}
        assertThrows(DeepSeekImportException::class.java){runBlocking{DeepSeekChatImporter(sink,store).import(zip(),owner,mapOf("c" to "3"))}}
        assertEquals(1,deleted)
    }
    @Test fun invalidIndexOrUnrelatedPathRejectsBeforeAnyWrites() {
        for(extra in listOf(mapOf("chat_session_index.json" to "[{\"id\":\"c\",\"message_count\":99}]".toByteArray()),
            mapOf("../outside" to byteArrayOf()),mapOf("chat_sessions/other/f.png" to byteArrayOf()),
            mapOf("manifest.json" to "{\"export_format_version\":4}".toByteArray()))) {
            assertThrows(IllegalArgumentException::class.java){DeepSeekArchive.inspect(zip(extra=extra))}
        }
    }
    @Test fun duplicateIdsCyclesAndUnknownFragmentsRemainErrors() {
        val raw=session();val ms=raw["messages"]!!.jsonArray
        val variants=listOf(JsonArray(ms+ms.first()),JsonArray(ms.map {m -> if(m.jsonObject["id"]==JsonPrimitive(1)) JsonObject(m.jsonObject+("parent_id" to JsonPrimitive(3))) else m}),
            JsonArray(ms+message(5,3,"ASSISTANT",fragment("UNRECOGNIZED_FUTURE_TYPE","keep original"))))
        variants.forEach {bad->assertThrows(IllegalArgumentException::class.java){DeepSeekArchive.inspect(zip(JsonObject(raw+("messages" to bad))))}}
    }
}
