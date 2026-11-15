#include <jni.h>
#include <cerrno>
#include <string>
#include <new>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <linux/fs.h>
#include <fcntl.h>

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

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_workspace_WorkspaceNativeDescriptors_nativeOpen(
        JNIEnv* env, jobject, jbyteArray pathBytes, jboolean directory) {
    try {
        if (pathBytes == nullptr) {
            fail(env, "java/lang/IllegalArgumentException", "workspace_path_denied");
            return -1;
        }
        const jsize length = env->GetArrayLength(pathBytes);
        if (length < 1 || length > 4096) {
            fail(env, "java/lang/IllegalArgumentException", "workspace_path_denied");
            return -1;
        }
        std::string path(static_cast<size_t>(length), '\0');
        env->GetByteArrayRegion(pathBytes, 0, length, reinterpret_cast<jbyte*>(&path[0]));
        if (env->ExceptionCheck()) return -1;
        if (path[0] != '/' || path.find('\0') != std::string::npos) {
            fail(env, "java/lang/IllegalArgumentException", "workspace_path_denied");
            return -1;
        }
        int fd;
        do {
            fd = open(path.c_str(), O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
        } while (fd < 0 && errno == EINTR);
        if (fd < 0) {
            fail(env, "java/io/IOException", "workspace_native_open_failed");
            return -1;
        }
        struct stat info{};
        if (fstat(fd, &info) != 0) {
            close(fd);
            fail(env, "java/io/IOException", "workspace_native_stat_failed");
            return -1;
        }
        if (directory ? !S_ISDIR(info.st_mode) : !S_ISREG(info.st_mode)) {
            close(fd);
            fail(env, "java/lang/IllegalStateException", "workspace_target_not_regular");
            return -1;
        }
        return fd; // Owned by ParcelFileDescriptor.adoptFd from here.
    } catch (const std::bad_alloc&) {
        fail(env, "java/lang/OutOfMemoryError", "workspace_native_open_allocation_failed");
        return -1;
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
