package me.rerere.rikkahub.data.sync.importer

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.CharacterCodingException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CherryStudioProviderCapacityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun preservesNestedProviderEnvelopeWithoutLoggingCredentials() {
        val archive = temporary.newFile()
        val persisted = buildJsonObject { put("llm", """{"providers":[]}""") }
        val root = buildJsonObject { put("localStorage", buildJsonObject { put("persist:cherry-studio", persisted.toString()) }) }
        ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(ZipEntry("data.json")); it.write(root.toString().toByteArray()); it.closeEntry()
        }
        assertTrue(CherryStudioProviderImporter.importProviders(archive).isEmpty())
    }

    @Test fun rejectsOversizedProviderDataBeforeAllocatingItsContents() {
        val archive = temporary.newFile()
        ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(ZipEntry("data.json"))
            val chunk = ByteArray(65536) { ' '.code.toByte() }
            repeat(1025) { _ -> it.write(chunk) }
            it.closeEntry()
        }
        val failure = assertThrows(ArchiveReadException::class.java) {
            CherryStudioProviderImporter.importProviders(archive)
        }
        assertEquals(ArchiveFailure.SIZE_LIMIT, failure.reason)
        assertEquals(CherryStudioProviderImporter.MAX_DATA_BYTES, failure.limitBytes)
    }

    @Test fun malformedUtf8DoesNotBecomeReplacementText() {
        val archive = temporary.newFile()
        ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(ZipEntry("data.json")); it.write(byteArrayOf(0xc3.toByte(), 0x28)); it.closeEntry()
        }
        assertThrows(CharacterCodingException::class.java) { CherryStudioProviderImporter.importProviders(archive) }
    }
}
