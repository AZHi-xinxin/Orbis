#include <jni.h>
#include <cerrno>
#include <string>
#include <new>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <linux/fs.h>

namespace {
void fail(JNIEnv* env, const char* type, const char* message) {
    jclass exception = env->FindClass(type);
    if (exception != nullptr) env->ThrowNew(exception, message);
}

bool component(JNIEnv* env, jbyteArray value, std::string& name) {
    if (value == nullptr) {
        fail(env, "java/lang/IllegalArgumentException", "workspace_path_denied");
        return false;
    }
    const jsize length = env->GetArrayLength(value);
    if (length < 1 || length > 4096) {
        fail(env, "java/lang/IllegalArgumentException", "workspace_path_denied");
        return false;
    }
    name.resize(static_cast<size_t>(length));
    env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte*>(&name[0]));
    if (env->ExceptionCheck()) return false;
    if (name == "." || name == ".." || name.find_first_of("/\\") != std::string::npos) {
        fail(env, "java/lang/IllegalArgumentException", "workspace_path_denied");
        return false;
    }
    for (unsigned char byte : name) {
        if (byte < 32 || byte == 127) {
            fail(env, "java/lang/IllegalArgumentException", "workspace_path_denied");
            return false;
        }
    }
    return true;
}
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_workspace_WorkspaceAtomicRename_nativeRenameNoReplace(
        JNIEnv* env, jobject, jint directoryFd, jbyteArray sourceBytes, jbyteArray targetBytes) {
    try {
    std::string source;
    std::string target;
    if (!component(env, sourceBytes, source) || !component(env, targetBytes, target)) return;
    if (source == target || source.rfind(".orbis-write-", 0) != 0) {
        fail(env, "java/lang/IllegalArgumentException", "workspace_path_denied");
        return;
    }
    struct stat directory{};
    if (directoryFd < 0 || fstat(directoryFd, &directory) != 0 || !S_ISDIR(directory.st_mode)) {
        fail(env, "java/io/IOException", "workspace_atomic_create_unavailable");
        return;
    }
#if defined(SYS_renameat2)
    // No check-then-rename and no link fallback: destination absence is checked
    // atomically by the kernel. The Kotlin caller holds directoryFd throughout.
    if (syscall(SYS_renameat2, directoryFd, source.c_str(), directoryFd,
                target.c_str(), RENAME_NOREPLACE) == 0) return;
    const int failure = errno;
    if (failure == EEXIST) {
        fail(env, "java/lang/IllegalArgumentException", "workspace_file_exists");
    } else if (failure == ENOSYS || failure == EINVAL || failure == EOPNOTSUPP ||
               failure == EACCES || failure == EPERM) {
        fail(env, "java/io/IOException", "workspace_atomic_create_unavailable");
    } else {
        fail(env, "java/io/IOException", "workspace_atomic_create_failed");
    }
#else
    fail(env, "java/io/IOException", "workspace_atomic_create_unavailable");
#endif
    } catch (const std::bad_alloc&) {
        fail(env, "java/lang/OutOfMemoryError", "workspace_atomic_create_allocation_failed");
    }
}
