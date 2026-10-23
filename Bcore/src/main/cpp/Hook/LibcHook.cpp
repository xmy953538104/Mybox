// Copyright 2026 Mybox contributors. SPDX-License-Identifier: Apache-2.0
#include "LibcHook.h"
#include "IO.h"
#include "Log.h"
#include "dobby.h"

#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <mutex>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/time.h>
#include <unistd.h>

namespace {
thread_local bool inside_hook = false;

struct CallScope {
    bool outer = !inside_hook;
    CallScope() { inside_hook = true; }
    ~CallScope() { inside_hook = !outer; }
};

struct MappedPath {
    char buffer[PATH_MAX];
    const char *value;
    bool valid;
    MappedPath(const char *path, bool map) : value(path), valid(true) {
        const int saved_errno = errno;
        if (map) valid = IO::rules().redirect(path, buffer, sizeof(buffer), value);
        errno = valid ? saved_errno : ENAMETOOLONG;
    }
};

template <typename T> T original(T &function) {
    return __atomic_load_n(&function, __ATOMIC_ACQUIRE);
}

#define PATH_HOOK(result, name, signature, failure, arguments) \
    static result (*orig_##name) signature = nullptr; \
    static result hook_##name signature { \
        CallScope scope; \
        MappedPath mapped(path, scope.outer); \
        if (!mapped.valid) return failure; \
        return original(orig_##name) arguments; \
    }

#define TWO_PATH_HOOK(result, name, signature, arguments) \
    static result (*orig_##name) signature = nullptr; \
    static result hook_##name signature { \
        CallScope scope; \
        MappedPath first(path1, scope.outer), second(path2, scope.outer); \
        if (!first.valid || !second.valid) { errno = ENAMETOOLONG; return -1; } \
        return original(orig_##name) arguments; \
    }

static bool needsMode(int flags) {
    return (flags & O_CREAT) != 0 || (flags & O_TMPFILE) == O_TMPFILE;
}

#define OPEN_HOOK(name) \
    static int (*orig_##name)(const char *, int, ...) = nullptr; \
    static int hook_##name(const char *path, int flags, ...) { \
        mode_t mode = 0; \
        if (needsMode(flags)) { va_list args; va_start(args, flags); mode = va_arg(args, int); va_end(args); } \
        CallScope scope; MappedPath mapped(path, scope.outer); \
        if (!mapped.valid) return -1; \
        return original(orig_##name)(mapped.value, flags, mode); \
    }

#define OPENAT_HOOK(name) \
    static int (*orig_##name)(int, const char *, int, ...) = nullptr; \
    static int hook_##name(int fd, const char *path, int flags, ...) { \
        mode_t mode = 0; \
        if (needsMode(flags)) { va_list args; va_start(args, flags); mode = va_arg(args, int); va_end(args); } \
        CallScope scope; MappedPath mapped(path, scope.outer); \
        if (!mapped.valid) return -1; \
        return original(orig_##name)(fd, mapped.value, flags, mode); \
    }

OPEN_HOOK(open)
OPEN_HOOK(open64)
OPENAT_HOOK(openat)
OPENAT_HOOK(openat64)
PATH_HOOK(int, __open_2, (const char *path, int flags), -1, (mapped.value, flags))
PATH_HOOK(int, __open64_2, (const char *path, int flags), -1, (mapped.value, flags))
PATH_HOOK(int, __openat_2, (int fd, const char *path, int flags), -1, (fd, mapped.value, flags))
PATH_HOOK(int, __openat64_2, (int fd, const char *path, int flags), -1, (fd, mapped.value, flags))
PATH_HOOK(int, creat, (const char *path, mode_t mode), -1, (mapped.value, mode))
PATH_HOOK(int, creat64, (const char *path, mode_t mode), -1, (mapped.value, mode))
PATH_HOOK(FILE *, fopen, (const char *path, const char *mode), nullptr, (mapped.value, mode))
PATH_HOOK(FILE *, fopen64, (const char *path, const char *mode), nullptr, (mapped.value, mode))
PATH_HOOK(FILE *, freopen, (const char *path, const char *mode, FILE *stream), nullptr, (mapped.value, mode, stream))
PATH_HOOK(FILE *, freopen64, (const char *path, const char *mode, FILE *stream), nullptr, (mapped.value, mode, stream))
PATH_HOOK(DIR *, opendir, (const char *path), nullptr, (mapped.value))
PATH_HOOK(int, stat, (const char *path, struct stat *info), -1, (mapped.value, info))
PATH_HOOK(int, stat64, (const char *path, struct stat64 *info), -1, (mapped.value, info))
PATH_HOOK(int, lstat, (const char *path, struct stat *info), -1, (mapped.value, info))
PATH_HOOK(int, lstat64, (const char *path, struct stat64 *info), -1, (mapped.value, info))
PATH_HOOK(int, fstatat, (int fd, const char *path, struct stat *info, int flags), -1, (fd, mapped.value, info, flags))
PATH_HOOK(int, fstatat64, (int fd, const char *path, struct stat64 *info, int flags), -1, (fd, mapped.value, info, flags))
PATH_HOOK(int, statfs, (const char *path, struct statfs *info), -1, (mapped.value, info))
PATH_HOOK(int, statfs64, (const char *path, struct statfs64 *info), -1, (mapped.value, info))
PATH_HOOK(int, access, (const char *path, int mode), -1, (mapped.value, mode))
PATH_HOOK(int, faccessat, (int fd, const char *path, int mode, int flags), -1, (fd, mapped.value, mode, flags))
PATH_HOOK(int, mkdir, (const char *path, mode_t mode), -1, (mapped.value, mode))
PATH_HOOK(int, mkdirat, (int fd, const char *path, mode_t mode), -1, (fd, mapped.value, mode))
PATH_HOOK(int, unlink, (const char *path), -1, (mapped.value))
PATH_HOOK(int, unlinkat, (int fd, const char *path, int flags), -1, (fd, mapped.value, flags))
PATH_HOOK(int, rmdir, (const char *path), -1, (mapped.value))
PATH_HOOK(int, remove, (const char *path), -1, (mapped.value))
PATH_HOOK(int, chmod, (const char *path, mode_t mode), -1, (mapped.value, mode))
PATH_HOOK(int, fchmodat, (int fd, const char *path, mode_t mode, int flags), -1, (fd, mapped.value, mode, flags))
PATH_HOOK(int, chown, (const char *path, uid_t uid, gid_t gid), -1, (mapped.value, uid, gid))
PATH_HOOK(int, lchown, (const char *path, uid_t uid, gid_t gid), -1, (mapped.value, uid, gid))
PATH_HOOK(int, fchownat, (int fd, const char *path, uid_t uid, gid_t gid, int flags), -1, (fd, mapped.value, uid, gid, flags))
PATH_HOOK(int, truncate, (const char *path, off_t size), -1, (mapped.value, size))
PATH_HOOK(int, truncate64, (const char *path, off64_t size), -1, (mapped.value, size))
PATH_HOOK(int, utimes, (const char *path, const struct timeval *times), -1, (mapped.value, times))
PATH_HOOK(int, utimensat, (int fd, const char *path, const struct timespec *times, int flags), -1, (fd, mapped.value, times, flags))
PATH_HOOK(int, chdir, (const char *path), -1, (mapped.value))
PATH_HOOK(int, inotify_add_watch, (int fd, const char *path, uint32_t mask), -1, (fd, mapped.value, mask))
PATH_HOOK(ssize_t, readlink, (const char *path, char *output, size_t size), -1, (mapped.value, output, size))
PATH_HOOK(ssize_t, readlinkat, (int fd, const char *path, char *output, size_t size), -1, (fd, mapped.value, output, size))
TWO_PATH_HOOK(int, rename, (const char *path1, const char *path2), (first.value, second.value))
TWO_PATH_HOOK(int, renameat, (int fd1, const char *path1, int fd2, const char *path2), (fd1, first.value, fd2, second.value))
TWO_PATH_HOOK(int, renameat2, (int fd1, const char *path1, int fd2, const char *path2, unsigned flags), (fd1, first.value, fd2, second.value, flags))
TWO_PATH_HOOK(int, link, (const char *path1, const char *path2), (first.value, second.value))
TWO_PATH_HOOK(int, linkat, (int fd1, const char *path1, int fd2, const char *path2, int flags), (fd1, first.value, fd2, second.value, flags))
TWO_PATH_HOOK(int, symlink, (const char *path1, const char *path2), (first.value, second.value))
TWO_PATH_HOOK(int, symlinkat, (const char *path1, int fd, const char *path2), (first.value, fd, second.value))

struct HookSpec {
    const char *name;
    void *replacement;
    void **trampoline;
    bool required;
    void *address = nullptr;
};
#define HOOK(name) {#name, reinterpret_cast<void *>(hook_##name), reinterpret_cast<void **>(&orig_##name), true}
#define OPTIONAL(name) {#name, reinterpret_cast<void *>(hook_##name), reinterpret_cast<void **>(&orig_##name), false}

bool install() {
    CallScope setup;
    void *libc = dlopen("libc.so", RTLD_NOW | RTLD_LOCAL);
    if (!libc) { ALOGE("Cannot open libc for native I/O hooks"); return false; }
    HookSpec hooks[] = {
        HOOK(open), OPTIONAL(open64), HOOK(openat), OPTIONAL(openat64),
        OPTIONAL(__open_2), OPTIONAL(__open64_2), OPTIONAL(__openat_2), OPTIONAL(__openat64_2),
        HOOK(creat), OPTIONAL(creat64), HOOK(fopen), OPTIONAL(fopen64), HOOK(freopen), OPTIONAL(freopen64),
        HOOK(opendir), HOOK(stat), OPTIONAL(stat64), HOOK(lstat), OPTIONAL(lstat64),
        HOOK(fstatat), OPTIONAL(fstatat64), HOOK(statfs), OPTIONAL(statfs64), HOOK(access), HOOK(faccessat),
        HOOK(mkdir), HOOK(mkdirat), HOOK(unlink), HOOK(unlinkat), HOOK(rmdir), HOOK(remove),
        HOOK(chmod), HOOK(fchmodat), HOOK(chown), HOOK(lchown), HOOK(fchownat),
        HOOK(truncate), OPTIONAL(truncate64), HOOK(utimes), HOOK(utimensat), HOOK(chdir),
        HOOK(inotify_add_watch), HOOK(readlink), HOOK(readlinkat), HOOK(rename), HOOK(renameat), OPTIONAL(renameat2),
        HOOK(link), HOOK(linkat), HOOK(symlink), HOOK(symlinkat)
    };
    for (auto &hook : hooks) {
        hook.address = dlsym(libc, hook.name);
        if (!hook.address && hook.required) {
            ALOGE("Missing required native I/O symbol: %s", hook.name);
            dlclose(libc);
            return false;
        }
    }
    size_t installed = 0;
    for (auto &hook : hooks) {
        if (!hook.address) continue;
        bool alias = false;
        for (auto *previous = hooks; previous < &hook; ++previous) {
            if (previous->address == hook.address) {
                __atomic_store_n(hook.trampoline, __atomic_load_n(previous->trampoline, __ATOMIC_ACQUIRE), __ATOMIC_RELEASE);
                alias = true;
                break;
            }
        }
        if (alias) continue;
        if (DobbyHook(hook.address, hook.replacement, hook.trampoline) != 0) {
            ALOGE("Native I/O hook failed: %s", hook.name);
            dlclose(libc);
            return false;
        }
        ++installed;
    }
    dlclose(libc);
    ALOGD("Native I/O installed: %zu unique libc entries", installed);
    return true;
}
}

bool LibcHook::init() {
    static std::once_flag once;
    static bool success = false;
    std::call_once(once, [] { success = install(); });
    return success;
}
