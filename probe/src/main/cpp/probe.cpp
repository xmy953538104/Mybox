#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <cstdio>
#include <string>
#include <sys/stat.h>

extern "C" JNIEXPORT jint JNICALL
Java_io_github_xmy953538104_mybox_probe_ProbeActivity_nativeWrite(JNIEnv* env, jclass, jstring filename) {
    const char* path = env->GetStringUTFChars(filename, nullptr);
    if (!path) return ENOMEM;
    std::string original(path);
    env->ReleaseStringUTFChars(filename, path);
    std::string renamed = original + ".renamed";
    int fd = openat(AT_FDCWD, original.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) return errno;
    const char data[] = "mybox-native-io";
    int error = 0;
    if (write(fd, data, strlen(data)) != static_cast<ssize_t>(strlen(data))) error = errno ? errno : EIO;
    if (!error && fsync(fd) != 0) error = errno;
    close(fd);
    if (error) return error;
    struct stat info{};
    if (stat(original.c_str(), &info) != 0) return errno;
    if (info.st_size != static_cast<off_t>(strlen(data))) return EIO;
    if (rename(original.c_str(), renamed.c_str()) != 0) return errno;
    auto slash = renamed.find_last_of('/');
    int directory = openat(AT_FDCWD, renamed.substr(0, slash).c_str(), O_RDONLY | O_DIRECTORY);
    if (directory < 0) return errno;
    fd = openat(directory, renamed.substr(slash + 1).c_str(), O_RDONLY);
    error = fd < 0 ? errno : 0;
    close(directory);
    if (error) return error;
    char actual[sizeof(data)]{};
    if (read(fd, actual, sizeof(actual)) != static_cast<ssize_t>(strlen(data))
            || memcmp(actual, data, strlen(data)) != 0) error = EIO;
    close(fd);
    if (error) return error;
    if (unlink(renamed.c_str()) != 0) return errno;
    if (stat(renamed.c_str(), &info) == 0 || errno != ENOENT) return EIO;
    return 0;
}
