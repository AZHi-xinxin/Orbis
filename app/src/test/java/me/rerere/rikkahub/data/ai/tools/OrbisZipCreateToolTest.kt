package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class OrbisZipCreateToolTest {
    private val args = Json.parseToJsonElement("""{"name":"作品.zip","files":[{"path":"文档/说明.md","text":"正文"}]}""")
    private val document = UIMessagePart.Document("file:///app/files/upload/fresh.zip", "作品.zip", "application/zip")

    @Test fun successfulSaveReturnsExactRealDocumentAndBoundScope() = runBlocking {
        var saved = 0
        var checks = 0
        val tool = createOrbisZipCreateTool("owner", "conversation", { checks++; true }, {
            saved++; assertEquals("作品.zip", it.name); SavedGeneratedZip(17, document)
        }, { fail("must not delete successful file") })
        val result = tool.execute(args)
        assertEquals(document, result.filterIsInstance<UIMessagePart.Document>().single())
        assertEquals(1, saved); assertEquals(3, checks)
        assertTrue(tool.hostApproval!!.stableId.endsWith(":owner:conversation"))
    }

    @Test fun invalidArgumentsAndMissingOwnerNeverSave() = runBlocking {
        val tool = createOrbisZipCreateTool("owner", "conversation", { true }, { error("must not save") }, { error("must not discard") })
        for (bad in listOf("{}", "[]", """{"name":"a.zip","files":[{"path":"../private.txt","text":"x"}]}""",
            """{"name":"a.zip","files":[{"path":"x.txt","text":2}]}""",
            """{"name":"a.zip","files":[{"path":"x.txt","text":"x","url":"file:///private"}]}""")) {
            assertTrue(tool.execute(Json.parseToJsonElement(bad)).none { it is UIMessagePart.Document })
        }
        assertTrue(createOrbisZipCreateTool("owner", "conversation", { false }, { error("must not save") }, {})
            .execute(args).none { it is UIMessagePart.Document })
    }

    @Test fun ownerChangeBeforePersistenceCreatesNothing() = runBlocking {
        var checks = 0
        val tool = createOrbisZipCreateTool("owner", "conversation", { ++checks < 2 }, { error("must not save") }, {})
        assertTrue(tool.execute(args).none { it is UIMessagePart.Document })
        assertEquals(2, checks)
    }

    @Test fun ownerChangeAfterPersistenceDiscardsOnlyFreshArtifact() = runBlocking {
        var checks = 0
        val deleted = mutableListOf<Long>()
        val tool = createOrbisZipCreateTool("owner", "conversation", { ++checks < 3 }, { SavedGeneratedZip(17, document) }, { deleted += it.id })
        assertTrue(tool.execute(args).none { it is UIMessagePart.Document })
        assertEquals(listOf(17L), deleted)
    }

    @Test fun cancellationAfterSaveDiscardsAndRemainsCancellation() = runBlocking {
        var checks = 0
        val deleted = mutableListOf<Long>()
        val tool = createOrbisZipCreateTool("owner", "conversation", {
            if (++checks == 3) throw CancellationException("synthetic"); true
        }, { SavedGeneratedZip(17, document) }, { deleted += it.id })
        try { tool.execute(args); fail("must cancel") } catch (_: CancellationException) { }
        assertEquals(listOf(17L), deleted)
    }

    @Test fun saveFailureNeverProducesSuccessDocument() = runBlocking {
        val result = createOrbisZipCreateTool("owner", "conversation", { true }, { error("private error") }, { fail("not saved") }).execute(args)
        assertTrue(result.none { it is UIMessagePart.Document })
        assertFalse(result.filterIsInstance<UIMessagePart.Text>().single().text.contains("private error"))
    }
}
