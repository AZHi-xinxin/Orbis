package me.rerere.rikkahub.data.orbis.consultation

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File

/** Android's public OsConstants omits O_DIRECTORY; open nonblocking and immediately fstat. */
internal class AndroidConsultationReferenceDirectory private constructor(
    private val descriptor: ParcelFileDescriptor,
) : ConsultationReferenceDirectory {
    private fun childPath(name: String): String {
        check(name.isNotEmpty() && name !in setOf(".", "..") && '/' !in name && '\\' !in name && '\u0000' !in name) {
            "consultation_reference_component_denied"
        }
        // This parent PFD stays alive until child open/dup completes. A renamed or
        // replaced pathname cannot retarget the kernel-held directory inode.
        return "/proc/self/fd/${descriptor.fd}/$name"
    }

    override fun directory(name: String): ConsultationReferenceDirectory =
        AndroidConsultationReferenceDirectory(openDescriptor(childPath(name), directory = true))

    override fun readRegularFile(name: String, maxBytes: Int): ByteArray =
        openDescriptor(childPath(name), directory = false).use { file ->
            val before = Os.fstat(file.fileDescriptor)
            check(before.st_size in 0..maxBytes.toLong()) { "consultation_reference_too_large" }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = Os.read(file.fileDescriptor, buffer, 0, buffer.size)
                if (count == 0) break
                check(output.size() + count <= maxBytes) { "consultation_reference_too_large" }
                output.write(buffer, 0, count)
            }
            val after = Os.fstat(file.fileDescriptor)
            check(OsConstants.S_ISREG(after.st_mode) && before.st_dev == after.st_dev && before.st_ino == after.st_ino &&
                before.st_size == after.st_size && before.st_mtime == after.st_mtime && output.size().toLong() == after.st_size) {
                "consultation_reference_changed_during_read"
            }
            output.toByteArray()
        }

    override fun close() = descriptor.close()

    companion object {
        // Linux UAPI asm-generic/fcntl.h: O_CLOEXEC=02000000. Both shipped ABIs
        // (arm64-v8a/x86_64) use this value; checked in NDK 28.2.13676358.
        // The public OsConstants field only exists from API 27; minSdk is 26.
        // Keep close-on-exec atomic with open, not a later fcntl race window.
        private const val LINUX_O_CLOEXEC = 0x80000

        fun openTrustedRoot(appFilesDir: File): ConsultationReferenceDirectory {
            // With this app's targetSdk >= 29, Android 10+ PFD.dup uses F_DUPFD_CLOEXEC.
            // Reject older platforms before any descriptor opens; no weaker fallback.
            requireConsultationReferencePlatform(Build.VERSION.SDK_INT)
            return AndroidConsultationReferenceDirectory(openDescriptor(appFilesDir.absolutePath, directory = true))
        }

        private fun openDescriptor(path: String, directory: Boolean): ParcelFileDescriptor {
            // NONBLOCK also prevents a FIFO replacement from hanging before fstat.
            val raw = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
                LINUX_O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
            try {
                val stat = Os.fstat(raw)
                check(if (directory) OsConstants.S_ISDIR(stat.st_mode) else OsConstants.S_ISREG(stat.st_mode)) {
                    "consultation_reference_file_type_denied"
                }
                return ParcelFileDescriptor.dup(raw)
            } finally {
                Os.close(raw)
            }
        }
    }
}
