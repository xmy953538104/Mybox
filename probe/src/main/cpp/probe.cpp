#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>

extern "C" JNIEXPORT jint JNICALL
Java_io_github_xmy953538104_mybox_probe_ProbeActivity_nativeWrite(JNIEnv* env, jclass, jstring filename) {
    const char* path = env->GetStringUTFChars(filename, nullptr);
    if (!path) return ENOMEM;
    int fd = openat(AT_FDCWD, path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    env->ReleaseStringUTFChars(filename, path);
    if (fd < 0) return errno;
    const char data[] = "mybox-native-io";
    int error = 0;
    if (write(fd, data, strlen(data)) != static_cast<ssize_t>(strlen(data))) error = errno ? errno : EIO;
    if (!error && fsync(fd) != 0) error = errno;
    close(fd);
    return error;
}
