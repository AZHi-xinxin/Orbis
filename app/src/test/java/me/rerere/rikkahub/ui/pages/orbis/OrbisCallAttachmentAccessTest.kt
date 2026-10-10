package me.rerere.rikkahub.ui.pages.orbis

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrbisCallAttachmentAccessTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun exportCopiesExactBinaryZipBytesAndNeverOpensOtherPrivateFiles() {
        val uploads = temp.newFolder("export-upload")
        val bytes = byteArrayOf(0x50, 0x4b, 3, 4, 0, -1, -128, 13, 10)
        val file = File(uploads, "test.zip").apply { writeBytes(bytes) }
        val output = java.io.ByteArrayOutputStream()
        exportCallDocument(file.toURI().toString(), uploads, output)
        org.junit.Assert.assertArrayEquals(bytes, output.toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            exportCallDocument(temp.newFile("private.bin").toURI().toString(), uploads, output)
        }
        org.junit.Assert.assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun opensOnlyActualUploadFile() {
        val uploads = temp.newFolder("upload")
        val file = File(uploads, "需求.md").apply { writeText("synthetic") }
        assertEquals(file.canonicalFile, resolveCallUploadFile(file.toURI().toString(), uploads))
    }

    @Test fun rejectsPrivateSiblingAndPrefixLookalike() {
        val uploads = temp.newFolder("upload")
        val privateFile = temp.newFile("private-vault.bin")
        val lookalike = File(temp.newFolder("upload-other"), "a.txt").apply { writeText("synthetic") }
        for (file in listOf(privateFile, lookalike, uploads)) {
            assertThrows(IllegalArgumentException::class.java) {
                resolveCallUploadFile(file.toURI().toString(), uploads)
            }
        }
    }

    @Test fun rejectsContentProviderAndOtherSchemes() {
        val uploads = temp.newFolder("upload")
        for (url in listOf("content://org.orbis.agent.fileprovider/upload/private-vault.bin",
            "content://another.provider/document/x", "https://example.org/file", "file://remote/a.txt")) {
            assertThrows(IllegalArgumentException::class.java) { resolveCallUploadFile(url, uploads) }
        }
    }

    @Test fun rejectsTraversalToOtherPrivateFile() {
        val uploads = temp.newFolder("upload")
        temp.newFile("private.txt")
        val url = File(uploads, "../private.txt").toURI().toString()
        assertThrows(IllegalArgumentException::class.java) { resolveCallUploadFile(url, uploads) }
    }
}
