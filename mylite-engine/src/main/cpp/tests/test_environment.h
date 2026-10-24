// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>

namespace mybox::test {
// Keep space for each test's filename in its existing 256-byte physical-path buffer.
constexpr unsigned kTempDirectorySize = 200;
inline bool MakeTempDirectory(char (&directory)[kTempDirectorySize], const char* name) {
    const char* base = std::getenv("TMPDIR");
    if (!base || !*base) base = "/tmp";
    if (*base != '/') { errno = EINVAL; return false; }
    const int length = std::snprintf(directory, sizeof(directory), "%s/mybox-mylite-%s-XXXXXX", base, name);
    if (length < 0 || static_cast<unsigned>(length) >= sizeof(directory)) {
        errno = ENAMETOOLONG;
        return false;
    }
    return mkdtemp(directory) != nullptr;
}

#ifdef MYLITE_ANDROID_STANDALONE
inline bool RecordAndroidEnvironment() {
    const char* expected = std::getenv("MYLITE_EXPECTED_UID");
    char* end = nullptr;
    const long uid = expected ? std::strtol(expected, &end, 10) : -1;
    if (!expected || end == expected || *end || uid < 10000
            || uid != getuid() || uid != geteuid()) return false;
    std::fprintf(stderr, "PROFILE native pid=%ld uid=%ld euid=%ld gid=%ld egid=%ld pgid=%ld sid=%ld page_size=%ld\n",
            static_cast<long>(getpid()), static_cast<long>(getuid()), static_cast<long>(geteuid()),
            static_cast<long>(getgid()), static_cast<long>(getegid()), static_cast<long>(getpgrp()),
            static_cast<long>(getsid(0)), sysconf(_SC_PAGESIZE));
    const char* paths[]{"/proc/self/status", "/proc/self/attr/current"};
    for (const char* path : paths) {
        FILE* file = std::fopen(path, "r");
        if (!file) return false;
        char line[512];
        std::fprintf(stderr, "PROFILE begin %s\n", path);
        while (std::fgets(line, sizeof(line), file)) std::fputs(line, stderr);
        const bool read = !std::ferror(file);
        if (std::fclose(file) != 0 || !read) return false;
        std::fprintf(stderr, "PROFILE end %s\n", path);
    }
    return std::fflush(stderr) == 0;
}
#endif
}
