package me.rerere.rikkahub.data.sync

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ConsultationBackupPolicyTest {
    @Test fun onlyDirectUploadChildrenCanBeExcluded() {
        val root = File("build/synthetic-backup-files").canonicalFile
        assertEquals("upload/owned.png", backupUploadPath(root, File(root, "upload/owned.png").toURI().toString()))
        assertNull(backupUploadPath(root, File(root, "upload/subfolder/owned.png").toURI().toString()))
        assertNull(backupUploadPath(root, File(root, "skills/owned.png").toURI().toString()))
        assertNull(backupUploadPath(root, File(root.parentFile, "outside.png").toURI().toString()))
        assertNull(backupUploadPath(root, "https://example.org/upload/owned.png"))
        assertNull(backupUploadPath(root, "file://remote-host/upload/owned.png"))
    }

    @Test fun sharedReferencesInJsonTextAndEncodedUrlsAreProtected() {
        assertTrue(backupTextReferencesUpload("{\"url\":\"file:///data/app/files/upload/shared.png\"}", "upload/shared.png"))
        assertTrue(backupTextReferencesUpload("keep shared.png as a normal attachment", "upload/shared.png"))
        assertTrue(backupTextReferencesUpload("file:///data/upload/with%20space.png", "upload/with space.png"))
        assertTrue(backupTextReferencesUpload("{\"url\":\"file:///data/upload/\\u4e2d.png\"}", "upload/中.png"))
        assertFalse(backupTextReferencesUpload("unrelated normal content", "upload/exclusive.png"))
    }

    @Test fun favoriteOwnershipRequiresExactConsistentReference() {
        assertEquals("hidden", backupFavoriteConversation("node:hidden:node-id", "{\"conversationId\":\"hidden\"}"))
        assertEquals("hidden", backupFavoriteConversation("message:hidden:message-id", "{}"))
        assertEquals("normal", backupFavoriteConversation("", "{\"conversationId\":\"normal\"}"))
        assertNull(backupFavoriteConversation("unattributed", "{}"))
        assertThrows(IllegalStateException::class.java) {
            backupFavoriteConversation("node:normal:node-id", "{\"conversationId\":\"hidden\"}")
        }
    }

    @Test fun unicodeSharedFilesRemainProtectedAcrossPercentEscapeCaseAndJsonEncoding() {
        assertTrue(backupTextReferencesUpload("file:///data/upload/%E4%B8%AD.png", "upload/中.png"))
        assertTrue(backupTextReferencesUpload("file:///data/upload/%e4%b8%ad.png", "upload/中.png"))
        assertTrue(backupTextReferencesUpload("{\"url\":\"file:///data/upload/%E4%B8%AD%20name.png\"}", "upload/中 name.png"))
        assertTrue(backupTextReferencesUpload("{\"url\":\"file:///data/upload/\\u4e2d name.png\"}", "upload/中 name.png"))
        assertFalse(backupTextReferencesUpload("file:///data/upload/%E6%96%87.png", "upload/中.png"))
    }

    @Test fun legacyFavoriteSnapshotFindsNestedFileUrlsButNotExternalUrls() {
        val values = backupJsonStrings("""{"messages":[{"parts":[{"type":"image","url":"file:///data/upload/old.png"}]}],"metadata":"preview"}""")
        assertTrue("file:///data/upload/old.png" in values)
        assertTrue("preview" in values)
        assertTrue(backupJsonStrings("not-json").isEmpty())
    }

    @Test fun arbitraryPercentEscapesProtectSharedNamesWithoutFormDecodingOrRecursion() {
        listOf("%73hared.png", "sh%61red.png", "%73%68%61%72%65%64%2e%70%6e%67").forEach { encoded ->
            assertTrue(backupTextReferencesUpload("file:///data/upload/$encoded", "upload/shared.png"))
        }
        assertTrue(backupTextReferencesUpload("{\"url\":\"file:///data/upload/\\u002573hared.png\"}", "upload/shared.png"))
        assertTrue(backupTextReferencesUpload("malformed %zz; file:///data/upload/%73hared.png", "upload/shared.png"))
        assertTrue(backupTextReferencesUpload("file:///data/upload/%61+b.png", "upload/a+b.png"))
        assertTrue(backupTextReferencesUpload("file:///data/upload/a%2Bb.png", "upload/a+b.png"))
        assertFalse(backupTextReferencesUpload("file:///data/upload/%61+b.png", "upload/a b.png"))
        assertFalse(backupTextReferencesUpload("file:///data/upload/%2573hared.png", "upload/shared.png"))
        assertTrue(backupTextReferencesUpload("file:///data/upload/%2573hared.png", "upload/%73hared.png"))
        assertFalse(backupTextReferencesUpload("file:///data/upload/%6fther.png", "upload/shared.png"))
    }
}
