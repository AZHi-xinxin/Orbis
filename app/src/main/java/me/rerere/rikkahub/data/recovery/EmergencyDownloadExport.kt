package me.rerere.rikkahub.data.recovery

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** A newly created download only, never an existing user file. No publication before readback. */
internal interface EmergencyDownloadDestination {
    fun openNew(): OutputStream
    fun readBack(): InputStream
    fun publish(receipt: EmergencyArchiveExport.Receipt): String
    fun discardIncomplete()
}

internal data class EmergencyDownloadReceipt(val displayName: String, val archive: EmergencyArchiveExport.Receipt)

internal fun saveEmergencyDownload(archive: File, destination: EmergencyDownloadDestination,
    onProgress: (EmergencyArchiveProgress) -> Unit = {}): EmergencyDownloadReceipt {
    try {
        val receipt = EmergencyArchiveExport.saveVerified(archive, destination::openNew, destination::readBack,
            onProgress = onProgress)
        val displayName = destination.publish(receipt)
        return EmergencyDownloadReceipt(displayName, receipt)
    } catch (failure: Throwable) {
        // Only the row created by this operation may be removed. The internal original is retained.
        try { destination.discardIncomplete() } catch (_: Exception) { /* A pending row may remain; no success claimed. */ }
        throw failure
    }
}
