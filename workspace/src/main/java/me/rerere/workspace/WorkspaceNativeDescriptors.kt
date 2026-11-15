package me.rerere.workspace

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.IOException

/** Atomic CLOEXEC/no-follow opens, without a newer Java API or a dup-then-fcntl race. */
@Keep
internal object WorkspaceNativeDescriptors {
    private val library = runCatching { System.loadLibrary("workspace") }

    fun open(path: String, directory: Boolean): ParcelFileDescriptor {
        library.exceptionOrNull()?.let { throw IOException("workspace_native_open_unavailable", it) }
        val fd = try {
            nativeOpen(WorkspaceNativeFiles.encodeUtf8(path), directory)
        } catch (failure: UnsatisfiedLinkError) {
            throw IOException("workspace_native_open_unavailable", failure)
        }
        check(fd >= 0) { "workspace_native_open_failed" }
        return ParcelFileDescriptor.adoptFd(fd)
    }

    private external fun nativeOpen(path: ByteArray, directory: Boolean): Int
}
