package me.rerere.rikkahub.web

import me.rerere.rikkahub.data.sync.importer.ArchiveFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class WebArchiveErrorTest {
    @Test fun usesFiniteCodesWithoutSourceExcerpts() {
        assertEquals("archive_too_large", webArchiveError(ArchiveFailure.SIZE_LIMIT))
        assertEquals("conversation_too_large", webArchiveError(ArchiveFailure.WINDOW_LIMIT))
        assertEquals("insufficient_storage", webArchiveError(ArchiveFailure.INSUFFICIENT_SPACE))
        assertEquals("archive_checksum_failed", webArchiveError(ArchiveFailure.CHECKSUM))
        assertEquals("archive_encoding_invalid", webArchiveError(ArchiveFailure.INVALID_UTF8))
        assertEquals("archive_unsafe_path", webArchiveError(ArchiveFailure.UNSAFE_PATH))
        assertEquals("invalid_archive", webArchiveError(null))
        assertEquals("import_incomplete", webArchiveError(null, "import_incomplete"))
    }
}
