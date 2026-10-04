package me.rerere.rikkahub.data.recovery

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CancellationException

class EmergencyArchiveExportTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(): File {
        val app = temporary.newFolder()
        val roots = EmergencyArchive.ROOT_NAMES.map { EmergencyArchiveRoot(it, File(app, it).apply { mkdirs() }) }
        val data = ByteArray(180_000).also { Random(42).nextBytes(it) }
        File(app, "files/synthetic.bin").writeBytes(data)
        return File(temporary.root, "${app.name}.zip").also {
            EmergencyArchive.create(roots, it, EmergencyArchiveMetadata("synthetic.orbis", "test", 1))
        }
    }

    @Test fun receiptRequiresClosingAndReopeningTheCompleteExternalCopy() {
        val source = archive()
        val original = source.readBytes()
        var closed = false
        var reopened = false
        val destination = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        val receipt = EmergencyArchiveExport.saveVerified(source, { destination }, {
            assertTrue(closed); reopened = true; ByteArrayInputStream(destination.toByteArray())
        })
        assertTrue(reopened)
        assertEquals(source.length(), receipt.sizeBytes)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }, receipt.sha256)
        assertArrayEquals(original, destination.toByteArray())
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun corruptSourceIsRejectedBeforeTheChosenDocumentIsOpened() {
        val source = temporary.newFile().apply { writeText("not an archive") }
        var opened = false
        assertThrows(Exception::class.java) {
            EmergencyArchiveExport.saveVerified(source, { opened = true; ByteArrayOutputStream() }, { ByteArrayInputStream(byteArrayOf()) })
        }
        assertFalse(opened)
        assertEquals("not an archive", source.readText())
    }

    @Test fun cancelledBeforeOrDuringCopyNeverReturnsAReceiptAndKeepsSource() {
        val source = archive()
        val original = source.readBytes()
        var opened = false
        assertThrows(CancellationException::class.java) {
            EmergencyArchiveExport.saveVerified(source, { opened = true; ByteArrayOutputStream() },
                { ByteArrayInputStream(byteArrayOf()) }, { throw CancellationException("synthetic cancel") })
        }
        assertFalse(opened)
        val destination = ByteArrayOutputStream()
        var destinationChecks = 0
        assertThrows(CancellationException::class.java) {
            EmergencyArchiveExport.saveVerified(source, { opened = true; destination },
                { error("must not reach readback") }, {
                    if (opened && ++destinationChecks == 2) throw CancellationException("synthetic mid-copy cancel")
                })
        }
        assertTrue(destination.size() in 1 until original.size)
        assertArrayEquals(original, source.readBytes())
        assertEquals("complete", EmergencyArchive.verify(source).status)
    }

    @Test fun fullDiskOrReadbackFailureCannotClaimExternalBackupSuccess() {
        val source = archive()
        val original = source.readBytes()
        assertThrows(IOException::class.java) {
            EmergencyArchiveExport.saveVerified(source, { object : OutputStream() {
                override fun write(value: Int) { throw IOException("synthetic ENOSPC") }
            } }, { error("must not reach readback") })
        }
        assertThrows(IOException::class.java) {
            EmergencyArchiveExport.saveVerified(source, { ByteArrayOutputStream() }, { throw IOException("readback unavailable") })
        }
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun truncatedAndSameLengthCorruptedDestinationsAreRejected() {
        val source = archive()
        val original = source.readBytes()
        val corrupt = original.copyOf().also { it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 1).toByte() }
        listOf(original.copyOf(original.size - 1), corrupt).forEach { bad ->
            assertThrows(IllegalStateException::class.java) {
                EmergencyArchiveExport.saveVerified(source, { ByteArrayOutputStream() }, { ByteArrayInputStream(bad) })
            }
        }
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun sourceChangedWhileOpeningDestinationIsDetectedBeforeAnySuccessReceipt() {
        val source = archive()
        var readBack = false
        assertThrows(IllegalStateException::class.java) {
            EmergencyArchiveExport.saveVerified(source, {
                source.appendText("synthetic concurrent modification")
                ByteArrayOutputStream()
            }, { readBack = true; source.inputStream() })
        }
        assertFalse(readBack)
        assertTrue(source.exists()) // The exporter never tries to repair/delete a changed source.
    }

    @Test fun allExpensiveStagesReportProgressWithoutClaimingCompleteBeforeReadback() {
        val source = archive()
        val destination = ByteArrayOutputStream()
        val events = mutableListOf<EmergencyArchiveProgress>()
        var reopened = false
        val receipt = EmergencyArchiveExport.saveVerified(source, { destination }, {
            reopened = true
            ByteArrayInputStream(destination.toByteArray())
        }, onProgress = {
            if (it.phase == "readback" && it.completedBytes > 0) assertTrue(reopened)
            events += it
        })
        val phases = events.map { it.phase }.distinct()
        assertEquals(listOf("source_hash", "source_verify", "source_recheck", "save", "readback"), phases)
        for (phase in listOf("source_hash", "source_recheck", "save", "readback")) {
            val stage = events.filter { it.phase == phase }
            assertEquals(0L, stage.first().completedBytes)
            assertTrue(stage.size > 2)
            assertTrue(stage.zipWithNext().all { (a, b) -> b.completedBytes >= a.completedBytes })
            assertEquals(receipt.sizeBytes, stage.last().completedBytes)
            assertTrue(stage.all { it.totalBytes == receipt.sizeBytes })
        }
        assertFalse(events.any { it.phase == "complete" })
    }

    @Test fun progressCancellationPreservesSourceAndDoesNotOpenDestination() {
        val source = archive()
        val original = source.readBytes()
        var opened = false
        assertThrows(CancellationException::class.java) {
            EmergencyArchiveExport.saveVerified(source, { opened = true; ByteArrayOutputStream() },
                { error("must not reopen") }, onProgress = {
                    if (it.phase == "source_hash") throw CancellationException("synthetic")
                })
        }
        assertFalse(opened)
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun replacingTheSourceAfterSemanticValidationCannotEstablishANewBaseline() {
        val source = archive()
        val replacement = ByteArray(source.length().toInt()) { 42 }
        var opened = false
        assertThrows(IllegalStateException::class.java) {
            EmergencyArchiveExport.saveVerified(source, { opened = true; ByteArrayOutputStream() },
                { error("must not reopen") }, onProgress = {
                    if (it.phase == "source_recheck" && it.completedBytes == 0L) source.writeBytes(replacement)
                })
        }
        assertFalse(opened)
        assertArrayEquals(replacement, source.readBytes()) // Never delete or repair changed source.
    }

    @Test fun corruptReplacementAtInitialHashStillMustPassSemanticValidation() {
        val source = archive()
        var opened = false
        assertThrows(Exception::class.java) {
            EmergencyArchiveExport.saveVerified(source, { opened = true; ByteArrayOutputStream() },
                { error("must not reopen") }, onProgress = {
                    if (it.phase == "source_hash" && it.completedBytes == 0L) source.writeBytes(ByteArray(180_000) { 42 })
                })
        }
        assertFalse(opened)
    }
}
