// SPDX-License-Identifier: Apache-2.0
#include "../native_io.h"
#include "../safe_memory.h"
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#define CHECK(expression) do { if (!(expression)) { \
    std::fprintf(stderr, "FAILED line %d: %s (errno=%d)\n", __LINE__, #expression, errno); \
    std::abort(); } } while (0)

namespace {
constexpr char logical[] = "/data/data/org.mybox.nativefixture";
constexpr char logical_file[] = "/data/data/org.mybox.nativefixture/value.txt";
pthread_barrier_t barrier;
volatile sig_atomic_t signal_result = 0;

void* Worker(void* wait) {
    if (wait) pthread_barrier_wait(&barrier);
    int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, logical_file, O_RDONLY, 0));
    CHECK(fd >= 0);
    char bytes[4];
    CHECK(read(fd, bytes, 4) == 4 && std::memcmp(bytes, "data", 4) == 0);
    CHECK(close(fd) == 0);
    return nullptr;
}
void UserSignal(int) {
    int fd = open(logical_file, O_RDONLY);
    if (fd >= 0) { signal_result = 1; close(fd); }
}
void RuleTests() {
    mybox::PathRules table;
    CHECK(table.Add("/data/data/a", "/host/guest/a") == 0);
    CHECK(table.Add("/data/data/a/lib", "/host/libraries/a") == 0);
    CHECK(table.Add("/data/data/a", "/other") == EEXIST);
    CHECK(table.Add("relative", "/other") == EINVAL);
    CHECK(table.Add("/data/../other", "/other") == EINVAL);
    char out[mybox::kPathSize];
    CHECK(table.Rewrite("/data/data/a/file", out) > 0 && std::strcmp(out, "/host/guest/a/file") == 0);
    CHECK(table.Rewrite("/data/data/ab/file", out) > 0 && std::strcmp(out, "/data/data/ab/file") == 0);
    CHECK(table.Rewrite("/data/data/a/lib/core.so", out) > 0 && std::strcmp(out, "/host/libraries/a/core.so") == 0);
    CHECK(table.Rewrite("/host/guest/a/data/data/a", out) > 0
            && std::strcmp(out, "/host/guest/a/data/data/a") == 0);
    CHECK(table.Rewrite("relative/file", out) > 0 && std::strcmp(out, "relative/file") == 0);
    CHECK(table.Rewrite("/host/guest/a/file", out, true) > 0 && std::strcmp(out, "/data/data/a/file") == 0);
    char large[mybox::kPathSize + 1];
    std::memset(large, 'x', sizeof(large)); large[sizeof(large) - 1] = 0;
    CHECK(table.Rewrite(large, out) == -ENAMETOOLONG);
}
void MemoryTests() {
    char source[] = "bounded self read", destination[sizeof(source)]{};
    CHECK(mybox::ReadSelfMemory(source, destination, sizeof(source)) == 0);
    CHECK(std::memcmp(source, destination, sizeof(source)) == 0);
    CHECK(mybox::ReadSelfMemory(reinterpret_cast<void*>(1), destination, 1) == EFAULT);
    long page = sysconf(_SC_PAGESIZE);
    CHECK(page > 0);
    auto* mapping = static_cast<char*>(mmap(nullptr, 2 * page, PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    CHECK(mapping != MAP_FAILED);
    mapping[page - 1] = 'v';
    CHECK(mprotect(mapping + page, page, PROT_NONE) == 0);
    CHECK(mybox::ReadSelfMemory(mapping + page - 1, destination, 1) == 0 && destination[0] == 'v');
    CHECK(mybox::ReadSelfMemory(mapping + page - 1, destination, 2) == EFAULT);
    CHECK(mybox::ReadSelfMemory(mapping + page, destination, 1) == EFAULT);
    CHECK(munmap(mapping, 2 * page) == 0);
    std::puts("PASS: typed self-memory syscall, invalid address, protected page and incomplete read");
}
}

int main() {
    RuleTests();
    MemoryTests();
    char directory[] = "/tmp/mybox-vlite-native-XXXXXX";
    CHECK(mkdtemp(directory) != nullptr);
    CHECK(mybox::ConfiguredPaths().Add(logical, directory) == 0);
    CHECK(pthread_barrier_init(&barrier, nullptr, 2) == 0);
    pthread_t existing;
    CHECK(pthread_create(&existing, nullptr, Worker, reinterpret_cast<void*>(1)) == 0);
    int status = mybox::InstallIo();
    CHECK(status == 0 && mybox::IoEnabled());
    CHECK(mybox::InstallIo() == 0);
    int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, logical_file, O_CREAT | O_RDWR | O_EXCL, 0600));
    CHECK(fd >= 0 && write(fd, "data", 4) == 4 && fsync(fd) == 0);
    struct stat information{};
    CHECK(fstat(fd, &information) == 0 && information.st_size == 4);
    CHECK(close(fd) == 0);
    CHECK(stat(logical_file, &information) == 0 && information.st_size == 4);
    pthread_barrier_wait(&barrier);
    CHECK(pthread_join(existing, nullptr) == 0);
    pthread_t future;
    CHECK(pthread_create(&future, nullptr, Worker, nullptr) == 0);
    CHECK(pthread_join(future, nullptr) == 0);

    int dir = open(logical, O_DIRECTORY | O_RDONLY);
    CHECK(dir >= 0);
    fd = openat(dir, "value.txt", O_RDONLY);
    CHECK(fd >= 0); CHECK(close(fd) == 0); CHECK(close(dir) == 0);
    CHECK(symlink(logical_file, "/data/data/org.mybox.nativefixture/link") == 0);
    char link[4096]{};
    auto n = readlink("/data/data/org.mybox.nativefixture/link", link, sizeof(link));
    CHECK(n == sizeof(logical_file) - 1 && std::memcmp(link, logical_file, n) == 0);
    CHECK(readlink("/data/data/org.mybox.nativefixture/link", link, 3) == 3 && std::memcmp(link, "/da", 3) == 0);

    errno = 0;
    CHECK(syscall(SYS_openat, AT_FDCWD, reinterpret_cast<void*>(1), O_RDONLY, 0) == -1 && errno == EFAULT);
    sigset_t full, previous, actual;
    sigfillset(&full);
    CHECK(pthread_sigmask(SIG_SETMASK, &full, &previous) == 0);
    CHECK(pthread_sigmask(SIG_SETMASK, nullptr, &actual) == 0 && sigismember(&actual, SIGSYS) == 0);
    CHECK(pthread_sigmask(SIG_SETMASK, &previous, nullptr) == 0);
    struct sigaction action{};
    action.sa_handler = UserSignal;
    sigfillset(&action.sa_mask);
    CHECK(sigaction(SIGUSR1, &action, nullptr) == 0);
    CHECK(raise(SIGUSR1) == 0 && signal_result == 1);
    errno = 0;
    CHECK(sigaction(SIGSYS, &action, nullptr) == -1 && errno == EBUSY);

    CHECK(rename(logical_file, "/data/data/org.mybox.nativefixture/renamed") == 0);
    CHECK(stat("/data/data/org.mybox.nativefixture/renamed", &information) == 0);
    CHECK(unlink("/data/data/org.mybox.nativefixture/renamed") == 0);
    CHECK(unlink("/data/data/org.mybox.nativefixture/link") == 0);
    errno = 0;
    CHECK(stat(logical_file, &information) == -1 && errno == ENOENT);
    CHECK(rmdir(directory) == 0);
    std::puts("PASS: path boundaries, syscall routing, TSYNC/new threads, dirfd, stat, rename, symlink/readlink, EFAULT, signal masks and unlink");
}
