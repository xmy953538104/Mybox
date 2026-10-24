// SPDX-License-Identifier: Apache-2.0
#include "../native_io.h"
#include "../safe_memory.h"
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <pthread.h>
#include <poll.h>
#include <signal.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

extern "C" char mybox_raw_syscall_end[];

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

int signal_ack[2];
pthread_t signal_target;
std::atomic<long> signal_target_tid{0};
constexpr char logical_fifo[] = "/data/data/org.mybox.nativefixture/blocked-fifo";

void NestedUserSignal(int) {
    const int saved_errno = errno;
    int fd = open(logical_file, O_RDONLY);
    char result = fd >= 0 ? 'o' : 'e';
    if (fd >= 0) close(fd);
    if (write(signal_ack[1], &result, 1) != 1) _exit(70);
    errno = saved_errno;
}

void ReadSignalAck() {
    pollfd ready{signal_ack[0], POLLIN, 0};
    CHECK(poll(&ready, 1, 5000) == 1);
    char result = 0;
    CHECK(read(signal_ack[0], &result, 1) == 1 && result == 'o');
}

bool InBlockedRawOpen(long tid, std::uintptr_t* stack = nullptr) {
    char name[96];
    std::snprintf(name, sizeof(name), "/proc/self/task/%ld/syscall", tid);
    FILE* state = std::fopen(name, "r");
    CHECK(state != nullptr);
    long number = -1;
    unsigned long args[6]{}, sp = 0, pc = 0;
    int fields = std::fscanf(state, "%ld %lx %lx %lx %lx %lx %lx %lx %lx", &number,
            &args[0], &args[1], &args[2], &args[3], &args[4], &args[5], &sp, &pc);
    CHECK(std::fclose(state) == 0);
    auto begin = reinterpret_cast<std::uintptr_t>(&mybox_raw_syscall);
    auto end = reinterpret_cast<std::uintptr_t>(mybox_raw_syscall_end);
    bool blocked = fields == 9 && number == SYS_openat && pc >= begin && pc < end;
    if (blocked && stack) *stack = sp;
    return blocked;
}

void ReleaseNestedOpen() {
    long tid = signal_target_tid.load(std::memory_order_acquire);
    bool blocked = false;
    for (int i = 0; i < 5000 && !blocked; ++i) {
        blocked = InBlockedRawOpen(tid);
        if (!blocked) usleep(1000);
    }
    CHECK(blocked); // Proves the signal interrupts an active SIGSYS dispatch, not ordinary code.
    CHECK(pthread_kill(signal_target, SIGUSR1) == 0);
    ReadSignalAck();
    int writer = open(logical_fifo, O_WRONLY);
    CHECK(writer >= 0 && close(writer) == 0);
}

void BlockInRedirectedOpen() {
    int fd = open(logical_fifo, O_RDONLY);
    CHECK(fd >= 0 && close(fd) == 0);
}

void* NestedSignalWorker(void* target_worker) {
    if (target_worker) signal_target_tid.store(syscall(SYS_gettid), std::memory_order_release);
    pthread_barrier_wait(&barrier);
    if (target_worker) BlockInRedirectedOpen();
    else ReleaseNestedOpen();
    return nullptr;
}

void SignalReentryTests(bool target_worker) {
    alarm(15); // A broken restart or dispatch must fail this subprocess instead of hanging CI.
    char directory[] = "/tmp/mybox-mylite-signal-XXXXXX";
    CHECK(mkdtemp(directory) != nullptr);
    char file[256], fifo[256];
    std::snprintf(file, sizeof(file), "%s/value.txt", directory);
    std::snprintf(fifo, sizeof(fifo), "%s/blocked-fifo", directory);
    int fd = open(file, O_CREAT | O_WRONLY | O_EXCL, 0600);
    CHECK(fd >= 0 && write(fd, "data", 4) == 4 && close(fd) == 0);
    CHECK(mkfifo(fifo, 0600) == 0 && pipe(signal_ack) == 0);
    CHECK(mybox::ConfiguredPaths().Add(logical, directory) == 0);
    struct sigaction requested{}, before[2]{};
    requested.sa_handler = NestedUserSignal;
    requested.sa_flags = SA_RESTART;
    CHECK(sigfillset(&requested.sa_mask) == 0);
    const int signals[]{SIGUSR1, SIGRTMIN + 3};
    for (int i = 0; i < 2; ++i) {
        CHECK(sigaction(signals[i], &requested, nullptr) == 0);
        CHECK(sigaction(signals[i], nullptr, &before[i]) == 0);
        CHECK(sigismember(&before[i].sa_mask, SIGSYS) == 1);
    }
    CHECK(pthread_barrier_init(&barrier, nullptr, 2) == 0);
    pthread_t worker;
    if (!target_worker) {
        signal_target = pthread_self();
        signal_target_tid.store(syscall(SYS_gettid), std::memory_order_release);
    }
    CHECK(pthread_create(&worker, nullptr, NestedSignalWorker,
            target_worker ? reinterpret_cast<void*>(1) : nullptr) == 0);
    if (target_worker) signal_target = worker;
    // InstallIo must unblock its own preexisting mask; the already-created worker is unblocked.
    sigset_t trap;
    CHECK(sigemptyset(&trap) == 0 && sigaddset(&trap, SIGSYS) == 0);
    CHECK(pthread_sigmask(SIG_BLOCK, &trap, nullptr) == 0);
    CHECK(mybox::InstallIo() == 0);
    for (int i = 0; i < 2; ++i) {
        struct sigaction after{};
        CHECK(sigaction(signals[i], nullptr, &after) == 0);
        CHECK(after.sa_handler == before[i].sa_handler && after.sa_flags == before[i].sa_flags);
        for (int signal = 1; signal < NSIG; ++signal) {
            int expected = signal == SIGSYS ? 0 : sigismember(&before[i].sa_mask, signal);
            CHECK(sigismember(&after.sa_mask, signal) == expected);
        }
        CHECK(raise(signals[i]) == 0);
        ReadSignalAck();
    }
    pthread_barrier_wait(&barrier);
    if (target_worker) ReleaseNestedOpen();
    else BlockInRedirectedOpen();
    CHECK(pthread_join(worker, nullptr) == 0);
    CHECK(close(signal_ack[0]) == 0 && close(signal_ack[1]) == 0);
    CHECK(unlink(file) == 0 && unlink(fifo) == 0 && rmdir(directory) == 0);
    alarm(0);
}

void IsolatedSignalTests() {
    for (int worker = 0; worker < 2; ++worker) {
        pid_t child = fork();
        CHECK(child >= 0);
        if (child == 0) { SignalReentryTests(worker); _exit(0); }
        int status = 0;
        CHECK(waitpid(child, &status, 0) == child);
        if (!WIFEXITED(status) || WEXITSTATUS(status) != 0)
            std::fprintf(stderr, "Signal reentry failed: worker=%d wait_status=%d\n", worker, status);
        CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
    std::puts("PASS: preinstalled signal masks/flags, realtime signal and nested I/O on main/TSYNC worker");
}

struct PoolWorker {
    pthread_t thread{};
    std::atomic<long> tid{0};
    char fifo[256]{};
};

void* OccupyStack(void* argument) {
    auto* worker = static_cast<PoolWorker*>(argument);
    worker->tid.store(syscall(SYS_gettid), std::memory_order_release);
    pthread_barrier_wait(&barrier);
    int fd = open(worker->fifo, O_RDONLY);
    CHECK(fd >= 0 && close(fd) == 0);
    return nullptr;
}

void StackPoolExhaustionTest() {
    alarm(15);
    constexpr unsigned workers = 10;
    char directory[] = "/tmp/mybox-mylite-pool-XXXXXX";
    CHECK(mkdtemp(directory) != nullptr);
    char file[256], fifos[workers][256];
    std::snprintf(file, sizeof(file), "%s/value.txt", directory);
    int fd = open(file, O_CREAT | O_WRONLY | O_EXCL, 0600);
    CHECK(fd >= 0 && write(fd, "data", 4) == 4 && close(fd) == 0);
    CHECK(mybox::ConfiguredPaths().Add(logical, directory) == 0);
    CHECK(pipe(signal_ack) == 0);
    struct sigaction action{};
    action.sa_handler = NestedUserSignal;
    action.sa_flags = SA_RESTART;
    CHECK(sigemptyset(&action.sa_mask) == 0 && sigaction(SIGUSR1, &action, nullptr) == 0);
    CHECK(pthread_barrier_init(&barrier, nullptr, workers + 1) == 0);
    PoolWorker worker[workers];
    for (unsigned i = 0; i < workers; ++i) {
        std::snprintf(fifos[i], sizeof(fifos[i]), "%s/blocked-%u", directory, i);
        std::snprintf(worker[i].fifo, sizeof(worker[i].fifo), "%s/blocked-%u", logical, i);
        CHECK(mkfifo(fifos[i], 0600) == 0);
        CHECK(pthread_create(&worker[i].thread, nullptr, OccupyStack, &worker[i]) == 0);
    }
    for (auto& current : worker) {
        for (int attempt = 0; attempt < 5000 && current.tid.load(std::memory_order_acquire) == 0; ++attempt)
            usleep(1000);
        CHECK(current.tid.load(std::memory_order_acquire) > 0);
    }
    CHECK(mybox::InstallIo() == 0);
    auto initial = mybox::GetIoStackStats();
    CHECK(initial.capacity == 8 && initial.leased == 0 && initial.initialization_error == 0);
    pthread_barrier_wait(&barrier);
    std::uintptr_t stacks[workers]{};
    for (unsigned i = 0; i < workers; ++i) {
        bool blocked = false;
        for (int attempt = 0; attempt < 5000 && !blocked; ++attempt) {
            blocked = InBlockedRawOpen(worker[i].tid.load(std::memory_order_acquire), &stacks[i]);
            if (!blocked) usleep(1000);
        }
        CHECK(blocked);
        for (unsigned earlier = 0; earlier < i; ++earlier) CHECK(stacks[i] != stacks[earlier]);
    }
    auto full = mybox::GetIoStackStats();
    CHECK(full.capacity == 8 && full.leased == 8 && full.fallback_requests >= 2 && full.fallback_failures == 0);
    // Every pool slot is still held in a real blocked syscall; nested I/O must use a distinct fallback stack.
    CHECK(pthread_kill(worker[0].thread, SIGUSR1) == 0);
    ReadSignalAck();
    auto nested = mybox::GetIoStackStats();
    CHECK(nested.leased == 8 && nested.fallback_requests > full.fallback_requests && nested.fallback_failures == 0);
    for (unsigned i = 0; i < workers; ++i) {
        int writer = open(worker[i].fifo, O_WRONLY);
        CHECK(writer >= 0 && close(writer) == 0);
        CHECK(pthread_join(worker[i].thread, nullptr) == 0);
    }
    auto released = mybox::GetIoStackStats();
    CHECK(released.leased == 0 && released.fallback_failures == 0);
    for (int i = 0; i < 1000; ++i) Worker(nullptr);
    auto reused = mybox::GetIoStackStats();
    CHECK(reused.leased == 0 && reused.fallback_requests == released.fallback_requests);
    CHECK(close(signal_ack[0]) == 0 && close(signal_ack[1]) == 0);
    CHECK(pthread_barrier_destroy(&barrier) == 0);
    for (auto& fifo : fifos) CHECK(unlink(fifo) == 0);
    CHECK(unlink(file) == 0 && rmdir(directory) == 0);
    std::printf("PASS: stack pool capacity=%u concurrent=%u leased_peak=%u fallback_requests=%llu "
            "nested_fallback_delta=%llu reuse_iterations=1000 failures=%llu\n",
            full.capacity, workers, full.leased, static_cast<unsigned long long>(reused.fallback_requests),
            static_cast<unsigned long long>(nested.fallback_requests - full.fallback_requests),
            static_cast<unsigned long long>(reused.fallback_failures));
    CHECK(std::fflush(stdout) == 0);
    alarm(0);
}

void StackPoolAllocationFailureTest() {
    alarm(15);
    char directory[] = "/tmp/mybox-mylite-pool-failure-XXXXXX";
    CHECK(mkdtemp(directory) != nullptr);
    char file[256];
    std::snprintf(file, sizeof(file), "%s/value.txt", directory);
    int fd = open(file, O_CREAT | O_WRONLY | O_EXCL, 0600);
    CHECK(fd >= 0 && write(fd, "data", 4) == 4 && close(fd) == 0);
    CHECK(mybox::ConfiguredPaths().Add(logical, directory) == 0);
    rlimit original{};
    CHECK(getrlimit(RLIMIT_AS, &original) == 0);
    rlimit restricted{0, original.rlim_max};
    CHECK(setrlimit(RLIMIT_AS, &restricted) == 0);
    CHECK(mybox::InstallIo() == 0 && mybox::IoEnabled());
    auto failed_pool = mybox::GetIoStackStats();
    CHECK(failed_pool.capacity == 0 && failed_pool.leased == 0 && failed_pool.initialization_error == ENOMEM);
    errno = 0;
    CHECK(syscall(SYS_openat, AT_FDCWD, logical_file, O_RDONLY, 0) == -1 && errno == ENOMEM);
    auto failed_call = mybox::GetIoStackStats();
    CHECK(failed_call.fallback_requests == 1 && failed_call.fallback_failures == 1);
    CHECK(setrlimit(RLIMIT_AS, &original) == 0);
    Worker(nullptr);
    auto recovered = mybox::GetIoStackStats();
    CHECK(recovered.capacity == 0 && recovered.leased == 0 && recovered.fallback_requests == 2
            && recovered.fallback_failures == 1);
    CHECK(unlink(file) == 0 && rmdir(directory) == 0);
    std::printf("PASS: stack pool initialization errno=%d; fallback requests=%llu failures=%llu; I/O recovered\n",
            failed_pool.initialization_error, static_cast<unsigned long long>(recovered.fallback_requests),
            static_cast<unsigned long long>(recovered.fallback_failures));
    CHECK(std::fflush(stdout) == 0);
    alarm(0);
}

void IsolatedStackPoolTests() {
    CHECK(std::fflush(stdout) == 0);
    for (int allocation_failure = 0; allocation_failure < 2; ++allocation_failure) {
        pid_t child = fork();
        CHECK(child >= 0);
        if (child == 0) {
            if (allocation_failure) StackPoolAllocationFailureTest();
            else StackPoolExhaustionTest();
            _exit(0);
        }
        int status = 0;
        CHECK(waitpid(child, &status, 0) == child);
        if (!WIFEXITED(status) || WEXITSTATUS(status) != 0)
            std::fprintf(stderr, "Stack pool failed: allocation_failure=%d wait_status=%d\n", allocation_failure, status);
        CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
}
}

int main() {
    IsolatedSignalTests();
    IsolatedStackPoolTests();
    RuleTests();
    MemoryTests();
    char directory[] = "/tmp/mybox-mylite-native-XXXXXX";
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
