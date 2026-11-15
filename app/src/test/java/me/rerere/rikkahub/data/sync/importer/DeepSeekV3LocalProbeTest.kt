package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.uuid.Uuid

/** Explicit opt-in. Compare every source message locally; never log text, titles, IDs or attachment names. */
class DeepSeekV3LocalProbeTest {
    @Test fun originalV3ContentBranchesAndRepeatImport()=runBlocking {
        val path=System.getenv("ORBIS_DEEPSEEK_V3_PROBE_ZIP")
        assumeTrue(!path.isNullOrBlank())
        val file=File(checkNotNull(path));val preview=DeepSeekArchive.inspect(file)
        var nodes=0;var fragments=0;var selected=0;var refs=0;var available=0
        ZipFile(file).use { zip -> DeepSeekArchive.open(file){}.use { archive ->
            for(c in archive.conversations()) {
                val raw=Json.parseToJsonElement(zip.getInputStream(zip.getEntry("chat_sessions/${c.sourceId}.json")).reader().readText()).jsonObject
                assertTrue(c.selectedLeafId==raw["current_message_id"]!!.jsonPrimitive.content)
                for(m in raw["messages"]!!.jsonArray) {
                    val obj=m.jsonObject;val node=c.nodes[obj["id"]!!.jsonPrimitive.content]!!;val message=node.message!!
                    assertTrue(message.role.name==obj["role"]!!.jsonPrimitive.content)
                    assertTrue(node.parentId==(obj["parent_id"]!!.takeUnless{it==JsonNull}?.jsonPrimitive?.content ?: c.rootId))
                    val fs=obj["fragments"]!!.jsonArray
                    assertEquals(fs.size,message.fragments.size)
                    fs.zip(message.fragments).forEach { (original,parsed) ->
                        val o=original.jsonObject
                        assertTrue(o["type"]!!.jsonPrimitive.content==parsed.type)
                        assertTrue(o["content"]?.jsonPrimitive?.content==parsed.content)
                        // Raw fields are all retained; only v3 FILE id compatibility is added.
                        if(parsed.type!="FILE") assertTrue(o==parsed.raw)
                        fragments++
                    };nodes++
                }
                for(n in c.pathTo(c.selectedLeafId)) for(f in n.message!!.fragments) for(a in (f.raw["files"] as? JsonArray).orEmpty()) {
                    val ep=a.jsonObject["export_path"]?.takeUnless{it==JsonNull}?.jsonPrimitive?.content
                    if(ep!=null && zip.getEntry(ep)!=null) available++
                }
                val converted=DeepSeekChatImporter.convert(c,c.selectedLeafId,Uuid.parse("11111111-1111-4111-8111-111111111111"))
                assertEquals(c.pathTo(c.selectedLeafId).size,converted.conversation.messageNodes.size)
                selected+=converted.conversation.messageNodes.size;refs+=converted.attachmentReferences
            }
        } }
        val ids=hashSetOf<Uuid>();var savedMessages=0
        val sink=object:DeepSeekImportSink {
            override suspend fun exists(id:Uuid)=id in ids
            override suspend fun insert(conversation:Conversation):Boolean {check(ids.add(conversation.id));savedMessages+=conversation.messageNodes.size;return true}
        }
        val picks=preview.conversations.associate{it.sourceId to it.defaultLeafId}
        val work=java.nio.file.Files.createTempDirectory("orbis-private-ds-v3-").toFile()
        var restoredFiles=0;var restoredBytes=0L
        val store=object:DeepSeekAttachmentStore {
            override val cacheDir=work
            override suspend fun save(file:File,name:String):DeepSeekStoredAttachment {
                assertTrue(file.isFile);restoredFiles++;restoredBytes+=file.length()
                return DeepSeekStoredAttachment(restoredFiles.toLong(),"file:///synthetic/$restoredFiles.png")
            }
            override suspend fun delete(id:Long){fail("committed synthetic file rolled back")}
        }
        val importer=DeepSeekChatImporter(sink,store)
        try {
        val first=importer.import(file,Uuid.parse("11111111-1111-4111-8111-111111111111"),picks)
        assertEquals(preview.conversations.size,first.imported);assertEquals(selected,savedMessages);assertEquals(refs,first.attachmentReferences);assertEquals(available,first.restoredAttachments)
        val repeat=importer.import(file,Uuid.parse("11111111-1111-4111-8111-111111111111"),picks)
        assertEquals(0,repeat.imported);assertEquals(first.imported,repeat.skipped);assertEquals(selected,savedMessages)
        assertTrue(work.listFiles().isNullOrEmpty())
        println("DEEPSEEK_V3_PRIVATE_PROBE conversations=${first.imported} source_messages=$nodes fragments=$fragments selected_messages=$selected selected_attachment_refs=$refs repeated_skips=${repeat.skipped} restored_references=${first.restoredAttachments} restored_files=$restoredFiles restored_bytes=$restoredBytes; all_text_and_parent_links_equal=true; no_live_database_writes=true")
        } finally { work.deleteRecursively() }
    }
}
