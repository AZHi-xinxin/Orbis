package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationInputTransformersTest {
    @Test fun interruptionPreservesAllOrdinaryAttachmentAndPromptTransforms() {
        val ordinary = conversationInputTransformers()
        val continued = conversationInputTransformers(includeVoiceArchive = false, includeVideoCall = false)
        assertEquals(ordinary.filterNot { it === OrbisVoiceCallTransformer || it === OrbisVideoCallTransformer }, continued)
        assertTrue(continued.contains(DocumentAsPromptTransformer))
        assertTrue(continued.contains(OcrTransformer))
        assertTrue(continued.contains(OrbisEventTransformer))
        assertEquals(continued.size, continued.toSet().size)
    }

    @Test fun activeVideoContinuationRetainsVideoProjectionWithoutWaitingForVoiceArchive() {
        assertEquals(conversationInputTransformers().filterNot { it === OrbisVoiceCallTransformer },
            conversationInputTransformers(includeVoiceArchive = false))
    }
}
