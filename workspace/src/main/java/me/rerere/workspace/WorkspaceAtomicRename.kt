package me.rerere.workspace

import androidx.annotation.Keep
import java.io.IOException

/** Synchronous descriptor-relative, atomic create-if-absent. Never weakens to check + rename. */
@Keep
internal object WorkspaceAtomicRename {
    private val library = runCatching { System.loadLibrary("workspace") }

    fun noReplace(directoryFd: Int, source: String, target: String) {
        require(validWorkspaceComponent(source) && validWorkspaceComponent(target) && source != target &&
            source.startsWith(".orbis-write-")) { "workspace_path_denied" }
        library.exceptionOrNull()?.let { throw IOException("workspace_atomic_create_unavailable", it) }
        // JNI GetStringUTFChars uses modified UTF-8, not filesystem UTF-8. Pass strict
        // encoded bytes so supplementary-plane characters in filenames remain intact.
        try {
            nativeRenameNoReplace(directoryFd, WorkspaceNativeFiles.encodeUtf8(source), WorkspaceNativeFiles.encodeUtf8(target))
        } catch (failure: UnsatisfiedLinkError) {
            throw IOException("workspace_atomic_create_unavailable", failure)
        }
    }

    private external fun nativeRenameNoReplace(directoryFd: Int, source: ByteArray, target: ByteArray)
}
