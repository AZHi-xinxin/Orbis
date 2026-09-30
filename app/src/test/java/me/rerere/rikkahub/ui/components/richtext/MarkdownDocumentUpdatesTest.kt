package me.rerere.rikkahub.ui.components.richtext

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test

class MarkdownDocumentUpdatesTest {
    @Test fun firstHtmlGenerationAndDomParseUseTheBackgroundPipeline() = runBlocking {
        val collectingThread = Thread.currentThread()
        val document = markdownDocumentUpdates(flow {
            assertNotSame(collectingThread, Thread.currentThread())
            emit("**synthetic strong**\n\n<div>synthetic html</div>")
        }).single().getOrThrow()
        assertEquals("synthetic strong", document.select("strong").text())
        assertEquals("synthetic html", document.select("div").text())
    }

    @Test fun laterHtmlUpdatePublishesANewCompleteDocumentWithoutMutatingTheOldOne() = runBlocking {
        val firstCollected = CompletableDeferred<Unit>()
        val documents = mutableListOf<Document>()
        markdownDocumentUpdates(flow {
            emit("<div>first</div>")
            firstCollected.await()
            emit("<div>first and second</div>")
        }).collect { result ->
            documents.add(result.getOrThrow())
            firstCollected.complete(Unit)
        }
        assertEquals(2, documents.size)
        assertEquals("first", documents[0].select("div").text())
        assertEquals("first and second", documents[1].select("div").text())
        assertNotSame(documents[0], documents[1])
    }
}
