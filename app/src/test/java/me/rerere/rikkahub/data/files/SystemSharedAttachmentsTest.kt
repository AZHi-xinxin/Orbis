package me.rerere.rikkahub.data.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class SystemSharedAttachmentsTest {
    @Test fun zipByNameOrMimeAlwaysUsesArchiveCopyAndCanonicalDocumentMime() = runBlocking {
        val kinds = mutableListOf<SharedAttachmentKind>()
        val parts = prepareSystemSharedAttachments(listOf("bundle.ZIP", "no-extension"),
            describe = { SharedAttachmentMetadata(it, if (it.endsWith("ZIP")) "application/octet-stream" else " Application/X-Zip-Compressed; charset=utf-8 ") },
            save = { source, _, kind -> kinds += kind; "file:///upload/$source" },
            onRejected = { fail(it) })
        assertEquals(listOf(SharedAttachmentKind.ZIP, SharedAttachmentKind.ZIP), kinds)
        assertEquals(listOf("application/zip", "application/zip"), parts.map { (it as UIMessagePart.Document).mime })
    }

    @Test fun unsupportedAndFailedItemsNeverShiftTheMetadataOfLaterMedia() = runBlocking {
        val notices = mutableListOf<String>()
        val copied = mutableListOf<String>()
        val parts = prepareSystemSharedAttachments(listOf("unknown", "broken.zip", "photo", "video", "sound"),
            describe = { SharedAttachmentMetadata(it, when (it) {
                "photo" -> "image/png"; "video" -> "video/mp4"; "sound" -> "audio/mpeg"
                else -> "application/octet-stream"
            }) },
            save = { source, _, _ ->
                copied += source
                if (source == "broken.zip") throw ZipAttachmentException("zip_bad_archive")
                "file:///upload/$source"
            }, onRejected = notices::add)
        assertEquals(listOf("broken.zip", "photo", "video", "sound"), copied)
        assertEquals(listOf(UIMessagePart.Image("file:///upload/photo"), UIMessagePart.Video("file:///upload/video"),
            UIMessagePart.Audio("file:///upload/sound")), parts)
        assertEquals(2, notices.size)
        assertTrue(notices[0].startsWith("第 1 个")); assertTrue(notices[1].startsWith("第 2 个"))
    }

    @Test fun supportedDocumentWithMissingMimeRemainsADocument() = runBlocking {
        val parts = prepareSystemSharedAttachments(listOf("notes.md"),
            describe = { SharedAttachmentMetadata(it, "") },
            save = { _, metadata, kind ->
                assertEquals(SharedAttachmentKind.DOCUMENT, kind)
                assertEquals("application/octet-stream", metadata.mime)
                "file:///upload/notes.md"
            }, onRejected = { fail(it) })
        assertEquals("notes.md", (parts.single() as UIMessagePart.Document).fileName)
    }

    @Test fun everyFailureIsVisibleButDoesNotExposeUriOrProviderErrorBody() = runBlocking {
        val notices = mutableListOf<String>()
        val parts = prepareSystemSharedAttachments(listOf("secret"),
            describe = { throw IllegalStateException("content://private/token=secret") },
            save = { _, _, _ -> error("must not copy") }, onRejected = notices::add)
        assertTrue(parts.isEmpty()); assertEquals(1, notices.size)
        assertFalse(notices.single().contains("secret")); assertFalse(notices.single().contains("content://"))
    }

    @Test fun cancellationAbortsRatherThanReportingFailureAndContinuing() = runBlocking {
        var copies = 0
        try {
            prepareSystemSharedAttachments(listOf("a.zip", "b.zip"),
                describe = { SharedAttachmentMetadata(it, "application/zip") },
                save = { _, _, _ -> copies++; throw CancellationException("synthetic") },
                onRejected = { fail("Cancellation must propagate") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, copies)
    }

    @Test fun validOfficeContainerIsNotAnArbitraryZip() {
        assertEquals(SharedAttachmentKind.DOCUMENT, sharedAttachmentKind(SharedAttachmentMetadata("report.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")))
    }
}
