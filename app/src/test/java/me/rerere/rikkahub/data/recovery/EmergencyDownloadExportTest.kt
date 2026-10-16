package me.rerere.rikkahub.data.recovery

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class EmergencyDownloadExportTest {
    @get:Rule val temp = TemporaryFolder()
    private fun archive(): File {
        val app = temp.newFolder()
        val roots = EmergencyArchive.ROOT_NAMES.map { EmergencyArchiveRoot(it, File(app, it).apply { mkdirs() }) }
        File(app, "files/synthetic.txt").writeText("synthetic test, no private data")
        return File(temp.root, "${app.name}.zip").also {
            EmergencyArchive.create(roots, it, EmergencyArchiveMetadata("synthetic.orbis", "test", 1))
        }
    }
    private class Destination : EmergencyDownloadDestination {
        val bytes = ByteArrayOutputStream()
        var opened = false
        var read = false
        var published = false
        var discarded = false
        var corrupt = false
        var publicationFails = false
        override fun openNew(): OutputStream { opened = true; return bytes }
        override fun readBack(): InputStream { read = true; return ByteArrayInputStream(if (corrupt) byteArrayOf(0) else bytes.toByteArray()) }
        override fun publish(receipt: EmergencyArchiveExport.Receipt): String {
            check(read)
            if (publicationFails) throw IOException("synthetic publication failure")
            published = true
            return "synthetic.zip"
        }
        override fun discardIncomplete() { discarded = true }
    }

    @Test fun publishesOnlyAfterCompleteReadbackAndRetainsInternalOriginal() {
        val source = archive(); val original = source.readBytes(); val destination = Destination()
        val receipt = saveEmergencyDownload(source, destination)
        assertEquals("synthetic.zip", receipt.displayName)
        assertEquals(source.length(), receipt.archive.sizeBytes)
        assertTrue(destination.read && destination.published)
        assertFalse(destination.discarded)
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun wrongReadbackCannotPublishOrDeleteOriginal() {
        val source = archive(); val original = source.readBytes(); val destination = Destination().apply { corrupt = true }
        assertThrows(IllegalStateException::class.java) { saveEmergencyDownload(source, destination) }
        assertTrue(destination.discarded)
        assertFalse(destination.published)
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun failedPublicationDoesNotReturnSuccessfulReceipt() {
        val source = archive(); val destination = Destination().apply { publicationFails = true }
        assertThrows(IOException::class.java) { saveEmergencyDownload(source, destination) }
        assertTrue(destination.read && destination.discarded)
        assertFalse(destination.published)
        assertTrue(source.isFile)
    }

    @Test fun badSourceDoesNotCreateDownload() {
        val source = temp.newFile().apply { writeText("not a backup") }; val destination = Destination()
        assertThrows(Exception::class.java) { saveEmergencyDownload(source, destination) }
        assertFalse(destination.opened)
        assertFalse(destination.published)
        assertTrue(source.exists())
    }

    @Test fun fixedDestinationCodesCannotIncludeProviderOrPath() {
        listOf("app_private_directory", "not_local_provider", "invalid_document_id", "invalid_scheme", "secret/provider/path", null).forEach {
            val code = emergencyDestinationDiagnostic(it)
            assertTrue(code.matches(Regex("ORBIS-RESCUE/E_DESTINATION_[A-Z_]+ stage=destination")))
            assertFalse(code.contains("secret"))
        }
        assertTrue(emergencyDestinationDiagnostic("not_local_provider").contains("E_DESTINATION_PROVIDER"))
    }
}
