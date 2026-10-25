// SPDX-License-Identifier: Apache-2.0
#include "../native_io.h"
#include "../native_install_guard.h"
#include "../safe_memory.h"
#include "test_environment.h"
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
#include <sys/inotify.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

extern "C" char mybox_raw_syscall_end[];
void WaitMaskTests();

#define CHECK(expression) do { if (!(expression)) { \
    std::fprintf(stderr, "FAILED line %d: %s (errno=%d)\n", __LINE__, #expression, errno); \
    std::abort(); } } while (0)

namespace {
constexpr char logical[] = "/data/data/org.mybox.nativefixture";
constexpr char logical_file[] = "/data/data/org.mybox.nativefixture/value.txt";
pthread_barrier_t barrier;
std::atomic<bool> worker_ready{false};
volatile sig_atomic_t signal_result = 0;

void Stage(const char* name) {
    // stderr survives a fatal signal; keep logging outside the create-to-InstallIo race window.
    std::fprintf(stderr, "STAGE pid=%ld: %s\n", static_cast<long>(getpid()), name);
}

void* Worker(void* wait) {
    if (wait) {
        worker_ready.store(true, std::memory_order_release);
        pthread_barrier_wait(&barrier);
    }
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
void WatchAndAttributeTests(const char* directory) {
    char physical_file[256], physical_link[256], physical_watch[256];
    std::snprintf(physical_file, sizeof(physical_file), "%s/value.txt", directory);
    std::snprintf(physical_link, sizeof(physical_link), "%s/link", directory);
    std::snprintf(physical_watch, sizeof(physical_watch), "%s/watch.txt", directory);
    constexpr char watched[] = "/data/data/org.mybox.nativefixture/watch.txt";
    int notify = inotify_init1(IN_CLOEXEC | IN_NONBLOCK);
    CHECK(notify >= 0);
    int watch = static_cast<int>(syscall(SYS_inotify_add_watch, notify, logical, IN_CREATE));
    CHECK(watch >= 0);
    CHECK(syscall(SYS_inotify_add_watch, notify, directory, IN_CREATE) == watch);
    errno = 0;
    CHECK(syscall(SYS_inotify_add_watch, notify, logical_file, IN_ONLYDIR) == -1 && errno == ENOTDIR);
    errno = 0;
    CHECK(syscall(SYS_inotify_add_watch, notify, reinterpret_cast<void*>(1), IN_CREATE) == -1 && errno == EFAULT);
    int fd = open(watched, O_CREAT | O_EXCL | O_WRONLY, 0600);
    CHECK(fd >= 0 && close(fd) == 0);
    pollfd ready{notify, POLLIN, 0};
    CHECK(poll(&ready, 1, 1000) == 1 && (ready.revents & POLLIN));
    alignas(inotify_event) char events[4096]{};
    ssize_t count = read(notify, events, sizeof(events));
    CHECK(count >= static_cast<ssize_t>(sizeof(inotify_event)));
    auto* event = reinterpret_cast<const inotify_event*>(events);
    CHECK(event->wd == watch && (event->mask & IN_CREATE) && event->len >= sizeof("watch.txt")
            && std::strcmp(event->name, "watch.txt") == 0);
    struct stat information{};
    CHECK(stat(physical_watch, &information) == 0);
    CHECK(unlink(watched) == 0 && inotify_rm_watch(notify, watch) == 0 && close(notify) == 0);

    constexpr char attribute[] = "user.mylite-fixture";
    constexpr unsigned char value[]{'m', 0, 'y', 'l'};
    errno = 0;
    long physical = syscall(SYS_setxattr, physical_file, attribute, value, sizeof(value), 0);
    int physical_error = errno;
    errno = 0;
    long virtual_result = syscall(SYS_setxattr, logical_file, attribute, value, sizeof(value), 0);
    CHECK(virtual_result == physical && (physical >= 0 || errno == physical_error));
    char actual[1024]{}, expected[1024]{};
    errno = 0;
    long size = syscall(SYS_getxattr, physical_file, attribute, expected, sizeof(expected));
    physical_error = errno;
    errno = 0;
    virtual_result = syscall(SYS_getxattr, logical_file, attribute, actual, sizeof(actual));
    CHECK(virtual_result == size && (size >= 0 ? std::memcmp(actual, expected, size) == 0 : errno == physical_error));
    if (physical == 0) {
        CHECK(size == sizeof(value) && std::memcmp(actual, value, sizeof(value)) == 0);
        errno = 0;
        CHECK(syscall(SYS_setxattr, logical_file, attribute, value, sizeof(value), XATTR_CREATE) == -1 && errno == EEXIST);
        errno = 0;
        CHECK(syscall(SYS_getxattr, logical_file, attribute, actual, 1) == -1 && errno == ERANGE);
    }
    errno = 0;
    size = syscall(SYS_listxattr, physical_file, expected, sizeof(expected));
    physical_error = errno;
    errno = 0;
    virtual_result = syscall(SYS_listxattr, logical_file, actual, sizeof(actual));
    CHECK(virtual_result == size && (size >= 0 ? std::memcmp(actual, expected, size) == 0 : errno == physical_error));
    if (physical == 0) CHECK(syscall(SYS_removexattr, logical_file, attribute) == 0);
    else {
        errno = 0;
        long removed = syscall(SYS_removexattr, physical_file, attribute);
        physical_error = errno;
        errno = 0;
        CHECK(syscall(SYS_removexattr, logical_file, attribute) == removed && (removed >= 0 || errno == physical_error));
    }
    errno = 0;
    CHECK(syscall(SYS_setxattr, reinterpret_cast<void*>(1), attribute, value, sizeof(value), 0) == -1 && errno == EFAULT);

    // Symlink user attributes are commonly denied; preserve that actual filesystem/SELinux decision.
    errno = 0;
    long link_result = syscall(SYS_lsetxattr, physical_link, attribute, value, sizeof(value), 0);
    physical_error = errno;
    errno = 0;
    CHECK(syscall(SYS_lsetxattr, "/data/data/org.mybox.nativefixture/link", attribute, value, sizeof(value), 0)
            == link_result && (link_result >= 0 || errno == physical_error));
    errno = 0;
    size = syscall(SYS_lgetxattr, physical_link, attribute, expected, sizeof(expected));
    physical_error = errno;
    errno = 0;
    virtual_result = syscall(SYS_lgetxattr, "/data/data/org.mybox.nativefixture/link", attribute, actual, sizeof(actual));
    CHECK(virtual_result == size && (size >= 0 ? std::memcmp(actual, expected, size) == 0 : errno == physical_error));
    errno = 0;
    size = syscall(SYS_llistxattr, physical_link, expected, sizeof(expected));
    physical_error = errno;
    errno = 0;
    virtual_result = syscall(SYS_llistxattr, "/data/data/org.mybox.nativefixture/link", actual, sizeof(actual));
    CHECK(virtual_result == size && (size >= 0 ? std::memcmp(actual, expected, size) == 0 : errno == physical_error));
    if (link_result == 0) CHECK(syscall(SYS_lremovexattr, "/data/data/org.mybox.nativefixture/link", attribute) == 0);
    else {
        errno = 0;
        long removed = syscall(SYS_lremovexattr, physical_link, attribute);
        physical_error = errno;
        errno = 0;
        CHECK(syscall(SYS_lremovexattr, "/data/data/org.mybox.nativefixture/link", attribute) == removed
                && (removed >= 0 || errno == physical_error));
    }
    std::printf("PASS: actual inotify event; pathname xattr supported=%d symlink-xattr supported=%d; raw errors preserved\n",
            physical == 0, link_result == 0);
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

struct GuardReport { std::uint64_t mask, pending; long tid; };
struct GuardCommand { int operation, signal; };
struct GuardWorker {
    pthread_t thread{};
    int command[2]{}, report[2]{};
    bool block_sys = true, block_candidates = false, full_mask = false;
    std::uint64_t resume_mask = 0;
    GuardWorker* release_after_restore = nullptr;
    const char* physical_file = nullptr;
    GuardReport initial{};
};
GuardReport ReportMask() {
    GuardReport report{};
    CHECK(mybox_raw_syscall(SYS_rt_sigprocmask, SIG_SETMASK, 0,
            reinterpret_cast<long>(&report.mask), 8, 0, 0) == 0);
    CHECK(mybox_raw_syscall(SYS_rt_sigpending, reinterpret_cast<long>(&report.pending), 8, 0, 0, 0, 0) == 0);
    report.tid = syscall(SYS_gettid);
    return report;
}
void WriteReport(GuardWorker& worker) {
    GuardReport report = ReportMask();
    CHECK(write(worker.report[1], &report, sizeof(report)) == sizeof(report));
}
GuardReport ReadReport(GuardWorker& worker) {
    GuardReport report{};
    CHECK(read(worker.report[0], &report, sizeof(report)) == sizeof(report));
    return report;
}
void* GuardWorkerMain(void* argument) {
    auto& worker = *static_cast<GuardWorker*>(argument);
    sigset_t blocked;
    CHECK(sigemptyset(&blocked) == 0 && sigaddset(&blocked, SIGUSR2) == 0);
    if (worker.block_sys) CHECK(sigaddset(&blocked, SIGSYS) == 0);
    if (worker.block_candidates) {
        CHECK(sigaddset(&blocked, SIGURG) == 0 && sigaddset(&blocked, SIGWINCH) == 0);
    }
    CHECK(pthread_sigmask(SIG_BLOCK, &blocked, nullptr) == 0);
    worker.resume_mask = ReportMask().mask;
    if (worker.full_mask) {
        const std::uint64_t all = UINT64_MAX;
        CHECK(mybox_raw_syscall(SYS_rt_sigprocmask, SIG_SETMASK, reinterpret_cast<long>(&all), 0, 8, 0, 0) == 0);
    }
    WriteReport(worker);
    for (;;) {
        GuardCommand command{};
        CHECK(read(worker.command[0], &command, sizeof(command)) == sizeof(command));
        if (command.operation == 0) return nullptr;
        if (command.operation == 1 || command.operation == 2) {
            sigset_t mask;
            CHECK(sigemptyset(&mask) == 0 && sigaddset(&mask, command.signal) == 0);
            CHECK(pthread_sigmask(command.operation == 1 ? SIG_BLOCK : SIG_UNBLOCK, &mask, nullptr) == 0);
        } else if (command.operation == 4) {
            Worker(nullptr); // Actual redirected I/O after successful TSYNC.
        } else if (command.operation == 5) {
            int fd = open(worker.physical_file, O_RDONLY);
            char value[4];
            CHECK(fd >= 0 && read(fd, value, sizeof(value)) == sizeof(value)
                    && std::memcmp(value, "data", sizeof(value)) == 0 && close(fd) == 0);
        } else if (command.operation == 6) {
            CHECK(mybox_raw_syscall(SYS_rt_sigprocmask, SIG_SETMASK,
                    reinterpret_cast<long>(&worker.resume_mask), 0, 8, 0, 0) == 0);
            if (worker.release_after_restore) {
                // Match bionic's order: restore the creator's mask BEFORE releasing its child.
                const GuardCommand release{6, 0};
                CHECK(write(worker.release_after_restore->command[1], &release, sizeof(release)) == sizeof(release));
            }
        }
        WriteReport(worker);
    }
}
void StartGuardWorker(GuardWorker& worker) {
    CHECK(pipe(worker.command) == 0 && pipe(worker.report) == 0);
    CHECK(pthread_create(&worker.thread, nullptr, GuardWorkerMain, &worker) == 0);
    worker.initial = ReadReport(worker);
}
GuardReport CommandGuardWorker(GuardWorker& worker, int operation, int signal = 0) {
    GuardCommand command{operation, signal};
    CHECK(write(worker.command[1], &command, sizeof(command)) == sizeof(command));
    return ReadReport(worker);
}
void StopGuardWorker(GuardWorker& worker) {
    GuardCommand command{0, 0};
    CHECK(write(worker.command[1], &command, sizeof(command)) == sizeof(command));
    CHECK(pthread_join(worker.thread, nullptr) == 0);
    for (int fd : worker.command) CHECK(close(fd) == 0);
    for (int fd : worker.report) CHECK(close(fd) == 0);
}
void UnusedSignal(int) {}
void ChangedSignalOwner(int) {}
void SameAction(const struct sigaction& before, const struct sigaction& after) {
    CHECK(before.sa_handler == after.sa_handler && before.sa_flags == after.sa_flags
            && before.sa_restorer == after.sa_restorer);
    for (int signal = 1; signal < NSIG; ++signal)
        CHECK(sigismember(&before.sa_mask, signal) == sigismember(&after.sa_mask, signal));
}
struct GuardAction { std::uintptr_t handler, flags, restorer; std::uint64_t mask; };
GuardAction ReadGuardAction(int signal) {
    GuardAction action;
    CHECK(mybox_raw_syscall(SYS_rt_sigaction, signal, 0, reinterpret_cast<long>(&action), 8, 0, 0) == 0);
    return action;
}
void SameGuardActions(const GuardAction* before) {
    for (int signal = 1; signal < NSIG; ++signal) {
        const auto after = ReadGuardAction(signal);
        CHECK(before[signal].handler == after.handler && before[signal].flags == after.flags
                && before[signal].restorer == after.restorer && before[signal].mask == after.mask);
    }
}
struct GuardHook {
    int kind, selected = 0;
    GuardWorker* primary;
    GuardWorker* extra;
    const GuardAction* before = nullptr;
    std::uint64_t installer_mask = 0;
};
void ReadinessGuard(void* argument) {
    auto& hook = *static_cast<GuardHook*>(argument);
    CHECK(mybox::InstallGuardAcknowledgementsForTest() == 0 && !mybox::IoEnabled());
    SameGuardActions(hook.before); // Including reserved RT masks/restorers and the SIGSYS action.
    CHECK(ReportMask().mask == hook.installer_mask);
    if (hook.kind == 9) StartGuardWorker(*hook.extra);
    const GuardCommand resume{6, 0};
    CHECK(write(hook.primary->command[1], &resume, sizeof(resume)) == sizeof(resume));
}
void PreparedGuard(int selected, void* argument) {
    auto& hook = *static_cast<GuardHook*>(argument);
    hook.selected = selected;
    CHECK(selected == SIGURG || selected == SIGWINCH);
    if (hook.kind == 2 || hook.kind == 6) {
        auto report = CommandGuardWorker(*hook.primary, 1, selected);
        CHECK((report.mask & (std::uint64_t{1} << (selected - 1))) != 0);
        if (hook.kind == 6) {
            // Standard signals coalesce. A foreign request must not count as our authenticated ACK.
            CHECK(syscall(SYS_tgkill, static_cast<long>(getpid()), hook.primary->initial.tid,
                    static_cast<long>(selected)) == 0);
        }
    } else if (hook.kind == 3) {
        StartGuardWorker(*hook.extra); // A real creator appears after the initial cohort was recorded.
    } else if (hook.kind == 4 || hook.kind == 7) {
        struct sigaction replacement{};
        replacement.sa_handler = ChangedSignalOwner;
        CHECK(sigemptyset(&replacement.sa_mask) == 0);
        CHECK(sigaction(hook.kind == 7 ? SIGSYS : selected, &replacement, nullptr) == 0);
    }
}
void InstallGuardTest(int kind) {
    alarm(5);
    char directory[mybox::test::kTempDirectorySize];
    CHECK(mybox::test::MakeTempDirectory(directory, "guard"));
    char file[256];
    std::snprintf(file, sizeof(file), "%s/value.txt", directory);
    int fd = open(file, O_CREAT | O_WRONLY | O_EXCL, 0600);
    CHECK(fd >= 0 && write(fd, "data", 4) == 4 && close(fd) == 0);
    CHECK(mybox::ConfiguredPaths().Add(logical, directory) == 0);

    const int signals[]{SIGURG, SIGWINCH, SIGRTMIN + 3};
    struct sigaction before[3]{};
    for (unsigned i = 0; i < 3; ++i) {
        struct sigaction action{};
        action.sa_handler = i == 2 || kind == 1 ? UnusedSignal : i == 0 ? SIG_IGN : SIG_DFL;
        action.sa_flags = SA_RESTART;
        CHECK(sigemptyset(&action.sa_mask) == 0 && sigaddset(&action.sa_mask, SIGUSR2) == 0);
        // Recoverable aborts must restore the masks changed by preflight, including reserved RT owners.
        if (i == 2 && kind != 0 && kind != 4 && kind != 7 && kind != 8 && kind != 9)
            CHECK(sigaddset(&action.sa_mask, SIGSYS) == 0);
        CHECK(sigaction(signals[i], &action, nullptr) == 0 && sigaction(signals[i], nullptr, &before[i]) == 0);
    }
    GuardWorker primary, extra;
    primary.block_candidates = kind == 5 || kind == 10;
    primary.full_mask = kind == 8 || kind == 9;
    extra.full_mask = kind == 8;
    if (kind == 8) primary.release_after_restore = &extra;
    primary.physical_file = extra.physical_file = file;
    StartGuardWorker(primary);
    if (kind == 2 || kind == 6 || kind == 8) StartGuardWorker(extra);
    CHECK((primary.initial.mask & (std::uint64_t{1} << (SIGSYS - 1))) != 0);
    GuardHook hook{kind, 0, &primary, &extra};
    if (kind == 2 || kind == 3 || kind == 4 || kind == 6 || kind == 7)
        mybox::SetInstallGuardPreparedHookForTest(PreparedGuard, &hook);

    GuardAction all_before[NSIG];
    for (int signal = 1; signal < NSIG; ++signal) all_before[signal] = ReadGuardAction(signal);
    if (kind == 8 || kind == 9) {
        hook.before = all_before;
        hook.installer_mask = ReportMask().mask;
        mybox::SetInstallGuardReadinessHookForTest(ReadinessGuard, &hook);
    }
    const auto carrier_bits = (std::uint64_t{1} << (SIGURG - 1)) | (std::uint64_t{1} << (SIGWINCH - 1));
    if (kind == 10) {
        for (unsigned i = 0; i < 2; ++i)
            CHECK(syscall(SYS_tgkill, static_cast<long>(getpid()), primary.initial.tid,
                    static_cast<long>(signals[i])) == 0);
        CHECK((CommandGuardWorker(primary, 3).pending & carrier_bits) == carrier_bits);
    }

    int error = mybox::InstallIo();
    const int expected[]{0, EBUSY, ETIMEDOUT, EAGAIN, EOWNERDEAD, ETIMEDOUT, ETIMEDOUT, EOWNERDEAD,
            0, 0, EBUSY};
    CHECK(error == expected[kind] && mybox::IoEnabled() == (kind == 0 || kind == 8 || kind == 9));
    const unsigned expected_acks[]{1, 0, 1, 1, 0, 0, 1, 1, 2, 2, 0};
    CHECK(mybox::InstallGuardAcknowledgementsForTest() == expected_acks[kind]);
    const auto diagnosis = mybox::GetInstallGuardDiagnostics();
    const auto& io_diagnosis = mybox::GetIoInstallDiagnostics();
    CHECK(diagnosis.acknowledged == expected_acks[kind]);
    CHECK(io_diagnosis.seccomp_called == (kind == 0 || kind == 8 || kind == 9));
    if (kind == 1 || kind == 5 || kind == 10) SameGuardActions(all_before);
    if (kind == 1 || kind == 10) {
        CHECK(std::strcmp(diagnosis.stage, "candidate-preflight") == 0 && diagnosis.cause_errno == EBUSY);
        for (const auto& candidate : diagnosis.candidates) {
            CHECK(std::strcmp(candidate.reason, kind == 1 ? "owned-handler" : "pending-signal") == 0);
            if (kind == 10) {
                CHECK(candidate.tid == primary.initial.tid);
                CHECK((candidate.pending & (std::uint64_t{1} << (candidate.signal - 1))) != 0);
            }
        }
    } else if (kind == 5) {
        CHECK(std::strcmp(diagnosis.stage, "candidate-readiness") == 0 && diagnosis.cause_errno == ETIMEDOUT);
        CHECK(diagnosis.readiness_waits > 0 && diagnosis.readiness_elapsed_ns >= 250000000);
        for (const auto& candidate : diagnosis.candidates) {
            CHECK(std::strcmp(candidate.reason, "blocked-signal") == 0);
            CHECK(candidate.tid == primary.initial.tid);
            CHECK((candidate.blocked & (std::uint64_t{1} << (candidate.signal - 1))) != 0);
        }
    } else if (kind == 2 || kind == 6) {
        CHECK(std::strcmp(diagnosis.stage, "wait-acknowledgements") == 0);
        CHECK(diagnosis.tid == primary.initial.tid && diagnosis.mask_errno == 0);
        CHECK((diagnosis.blocked & (std::uint64_t{1} << (hook.selected - 1))) != 0);
    }
    if (kind == 8 || kind == 9) {
        CHECK(diagnosis.readiness_waits > 0 && diagnosis.readiness_checks >= 2);
        CHECK(diagnosis.readiness_first_tid != 0 && diagnosis.readiness_first_mask ==
                (UINT64_MAX & ~(std::uint64_t{1} << (SIGKILL - 1)) & ~(std::uint64_t{1} << (SIGSTOP - 1))));
        (void)ReadReport(primary); // The explicit raw mask-restoration command completed.
        if (kind == 8) (void)ReadReport(extra);
    }
    auto report = CommandGuardWorker(primary, 3);
    if (kind == 0 || kind == 8 || kind == 9) {
        const auto original = kind == 8 || kind == 9 ? primary.resume_mask : primary.initial.mask;
        CHECK(report.mask == (original & ~(std::uint64_t{1} << (SIGSYS - 1))));
        CommandGuardWorker(primary, 4);
        if (kind == 8 || kind == 9) {
            const auto extra_original = kind == 8 ? extra.resume_mask : extra.initial.mask;
            CHECK(CommandGuardWorker(extra, 3).mask == (extra_original & ~(std::uint64_t{1} << (SIGSYS - 1))));
            CommandGuardWorker(extra, 4);
        }
    } else if (kind == 2 || kind == 6) {
        const auto candidate = std::uint64_t{1} << (hook.selected - 1);
        CHECK(report.mask == (primary.initial.mask | candidate) && (report.pending & candidate) == 0);
        report = CommandGuardWorker(primary, 2, hook.selected);
        CHECK(report.mask == primary.initial.mask);
        CommandGuardWorker(primary, 5); // Unmasking a late request must leave the thread alive.
    } else {
        CHECK(report.mask == primary.initial.mask);
    }
    if (kind == 5 || kind == 10) {
        CHECK((report.pending & carrier_bits) == (kind == 10 ? carrier_bits : 0));
        for (unsigned i = 0; i < 2; ++i) CommandGuardWorker(primary, 2, signals[i]);
        CHECK((CommandGuardWorker(primary, 3).pending & carrier_bits) == 0);
        CommandGuardWorker(primary, 5); // Permanent-block timeout and old pending delivery both survive.
    }
    if (kind == 2 || kind == 6) {
        // This peer ACKed and parked while the primary timed out; ABORT must release it unchanged.
        CHECK(CommandGuardWorker(extra, 3).mask == extra.initial.mask);
        CommandGuardWorker(extra, 5);
    }
    for (unsigned i = 0; i < 3; ++i) {
        struct sigaction after{};
        CHECK(sigaction(signals[i], nullptr, &after) == 0);
        if (kind == 4 && signals[i] == hook.selected) CHECK(after.sa_handler == ChangedSignalOwner);
        else SameAction(before[i], after);
    }
    if (kind == 7) {
        struct sigaction after{};
        CHECK(sigaction(SIGSYS, nullptr, &after) == 0 && after.sa_handler == ChangedSignalOwner);
    }
    if (kind == 2 || kind == 3 || kind == 6 || kind == 8 || kind == 9) StopGuardWorker(extra);
    StopGuardWorker(primary);
    CHECK(unlink(file) == 0 && rmdir(directory) == 0);
    std::fprintf(stderr, "PASS: install guard case=%d errno=%d acknowledgements=%u readiness_checks=%u "
            "waits=%u elapsed_ns=%llu; live mask/actions preserved; no wrong filter\n", kind, error,
            expected_acks[kind], diagnosis.readiness_checks, diagnosis.readiness_waits,
            static_cast<unsigned long long>(diagnosis.readiness_elapsed_ns));
    alarm(0);
}
void IsolatedInstallGuardTests() {
    for (int kind = 0; kind < 11; ++kind) {
        Stage("fork install guard case");
        pid_t child = fork();
        CHECK(child >= 0);
        if (child == 0) { InstallGuardTest(kind); _exit(0); }
        int status = 0;
        CHECK(waitpid(child, &status, 0) == child);
        if (!WIFEXITED(status) || WEXITSTATUS(status) != 0)
            std::fprintf(stderr, "Install guard failed: case=%d wait_status=%d\n", kind, status);
        CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
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

bool ProcNumber(const char*& cursor, unsigned base, std::uintptr_t& value) {
    while (*cursor == ' ' || *cursor == '\t') ++cursor;
    if (base == 16 && cursor[0] == '0' && cursor[1] == 'x') cursor += 2;
    value = 0;
    unsigned digits = 0;
    while (*cursor != 0 && *cursor != '\n' && *cursor != ' ' && *cursor != '\t') {
        const char c = *cursor++;
        const unsigned digit = c >= '0' && c <= '9' ? c - '0'
                : c >= 'a' && c <= 'f' ? c - 'a' + 10 : base;
        if (digit >= base || value > (UINTPTR_MAX - digit) / base) return false;
        value = value * base + digit;
        ++digits;
    }
    return digits != 0;
}
bool InBlockedRawOpen(long tid, std::uintptr_t* stack = nullptr) {
    char name[96];
    std::snprintf(name, sizeof(name), "/proc/self/task/%ld/syscall", tid);
    // The observer must not borrow a pool slot while workers are filling that same pool.
    long fd = mybox_raw_syscall(SYS_openat, AT_FDCWD, reinterpret_cast<long>(name), O_RDONLY | O_CLOEXEC, 0, 0, 0);
    CHECK(fd >= 0);
    char text[256];
    unsigned length = 0;
    for (;;) {
        long read = mybox_raw_syscall(SYS_read, fd, reinterpret_cast<long>(text + length),
                sizeof(text) - 1 - length, 0, 0, 0);
        CHECK(read >= 0);
        if (read == 0) break;
        length += static_cast<unsigned>(read);
        CHECK(length < sizeof(text) - 1);
    }
    CHECK(mybox_raw_syscall(SYS_close, fd, 0, 0, 0, 0, 0) == 0);
    text[length] = 0;
    const char* cursor = text;
    std::uintptr_t number = 0, ignored, sp = 0, pc = 0;
    bool parsed = ProcNumber(cursor, 10, number);
    for (int i = 0; i < 6 && parsed; ++i) parsed = ProcNumber(cursor, 16, ignored);
    parsed = parsed && ProcNumber(cursor, 16, sp) && ProcNumber(cursor, 16, pc);
    auto begin = reinterpret_cast<std::uintptr_t>(&mybox_raw_syscall);
    auto end = reinterpret_cast<std::uintptr_t>(mybox_raw_syscall_end);
    bool blocked = parsed && number == SYS_openat && pc >= begin && pc < end;
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
    worker_ready.store(true, std::memory_order_release);
    pthread_barrier_wait(&barrier);
    if (target_worker) BlockInRedirectedOpen();
    else ReleaseNestedOpen();
    return nullptr;
}

void SignalReentryTests(bool target_worker) {
    alarm(15); // A broken restart or dispatch must fail this subprocess instead of hanging CI.
    char directory[mybox::test::kTempDirectorySize];
    CHECK(mybox::test::MakeTempDirectory(directory, "signal"));
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
    worker_ready.store(false, std::memory_order_release);
    if (!target_worker) {
        signal_target = pthread_self();
        signal_target_tid.store(syscall(SYS_gettid), std::memory_order_release);
    }
    CHECK(pthread_create(&worker, nullptr, NestedSignalWorker,
            target_worker ? reinterpret_cast<void*>(1) : nullptr) == 0);
    while (!worker_ready.load(std::memory_order_acquire)) usleep(1000);
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
        Stage(worker ? "fork signal reentry: worker target" : "fork signal reentry: main target");
        pid_t child = fork();
        CHECK(child >= 0);
        if (child == 0) {
            Stage("signal reentry child entered");
            SignalReentryTests(worker);
            Stage("signal reentry child passed");
            _exit(0);
        }
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
    char directory[mybox::test::kTempDirectorySize];
    CHECK(mybox::test::MakeTempDirectory(directory, "pool"));
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
    char diagnostic[192];
    int diagnostic_size = std::snprintf(diagnostic, sizeof(diagnostic),
            "POOL full: capacity=%u leased=%u fallback=%llu failures=%llu initialization_errno=%d\n",
            full.capacity, full.leased, static_cast<unsigned long long>(full.fallback_requests),
            static_cast<unsigned long long>(full.fallback_failures), full.initialization_error);
    CHECK(diagnostic_size > 0 && static_cast<unsigned>(diagnostic_size) < sizeof(diagnostic));
    CHECK(mybox_raw_syscall(SYS_write, STDERR_FILENO, reinterpret_cast<long>(diagnostic),
            diagnostic_size, 0, 0, 0) == diagnostic_size);
    CHECK(full.capacity == 8 && full.leased == 8 && full.fallback_requests >= 2 && full.fallback_failures == 0);
    for (auto& current : worker) CHECK(InBlockedRawOpen(current.tid.load(std::memory_order_acquire)));
    auto observed = mybox::GetIoStackStats();
    CHECK(observed.leased == full.leased && observed.fallback_requests == full.fallback_requests
            && observed.fallback_failures == full.fallback_failures);
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
    char directory[mybox::test::kTempDirectorySize];
    CHECK(mybox::test::MakeTempDirectory(directory, "pool-failure"));
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
        Stage(allocation_failure ? "fork pool allocation failure" : "fork pool exhaustion");
        pid_t child = fork();
        CHECK(child >= 0);
        if (child == 0) {
            Stage("stack pool child entered");
            if (allocation_failure) StackPoolAllocationFailureTest();
            else StackPoolExhaustionTest();
            Stage("stack pool child passed");
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
#ifdef MYLITE_ANDROID_STANDALONE
    CHECK(mybox::test::RecordAndroidEnvironment());
#endif
    Stage("main entered: isolated install guard tests");
    IsolatedInstallGuardTests();
    Stage("install guard tests passed: isolated temporary wait-mask tests");
    WaitMaskTests();
    Stage("install guard tests passed: isolated signal tests");
    IsolatedSignalTests();
    Stage("isolated signal tests passed: stack pool tests");
    IsolatedStackPoolTests();
    Stage("isolated stack pool tests passed: path rules and safe memory");
    RuleTests();
    MemoryTests();
    char directory[mybox::test::kTempDirectorySize];
    CHECK(mybox::test::MakeTempDirectory(directory, "native"));
    CHECK(mybox::ConfiguredPaths().Add(logical, directory) == 0);
    CHECK(pthread_barrier_init(&barrier, nullptr, 2) == 0);
    pthread_t existing;
    worker_ready.store(false, std::memory_order_release);
    Stage("main wait for existing worker readiness, then install TSYNC");
    CHECK(pthread_create(&existing, nullptr, Worker, reinterpret_cast<void*>(1)) == 0);
    while (!worker_ready.load(std::memory_order_acquire)) usleep(1000);
    int status = mybox::InstallIo();
    CHECK(status == 0 && mybox::IoEnabled());
    Stage("main TSYNC installed: pathname I/O");
    CHECK(mybox::InstallIo() == 0);
    int fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, logical_file, O_CREAT | O_RDWR | O_EXCL, 0600));
    CHECK(fd >= 0 && write(fd, "data", 4) == 4 && fsync(fd) == 0);
    struct stat information{};
    CHECK(fstat(fd, &information) == 0 && information.st_size == 4);
    CHECK(close(fd) == 0);
    CHECK(stat(logical_file, &information) == 0 && information.st_size == 4);
    Stage("main release and join existing worker");
    pthread_barrier_wait(&barrier);
    CHECK(pthread_join(existing, nullptr) == 0);
    pthread_t future;
    Stage("main create and join future worker");
    CHECK(pthread_create(&future, nullptr, Worker, nullptr) == 0);
    CHECK(pthread_join(future, nullptr) == 0);

    Stage("main directory, links, signals and cleanup");
    int dir = open(logical, O_DIRECTORY | O_RDONLY);
    CHECK(dir >= 0);
    fd = openat(dir, "value.txt", O_RDONLY);
    CHECK(fd >= 0); CHECK(close(fd) == 0); CHECK(close(dir) == 0);
    CHECK(symlink(logical_file, "/data/data/org.mybox.nativefixture/link") == 0);
    char link[4096]{};
    auto n = readlink("/data/data/org.mybox.nativefixture/link", link, sizeof(link));
    CHECK(n == sizeof(logical_file) - 1 && std::memcmp(link, logical_file, n) == 0);
    CHECK(readlink("/data/data/org.mybox.nativefixture/link", link, 3) == 3 && std::memcmp(link, "/da", 3) == 0);
    WatchAndAttributeTests(directory);

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
    Stage("main passed");
    std::puts("PASS: path boundaries, syscall routing, TSYNC/new threads, dirfd, stat, rename, symlink/readlink, EFAULT, signal masks and unlink");
}
