// SPDX-License-Identifier: Apache-2.0
// Independent mechanism probe. Link an explicitly selected Mylite core; never link the original VLite binary.
#include "native_io.h"
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <sys/epoll.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

extern "C" long mybox_raw_syscall(long, long, long, long, long, long, long);

namespace {
constexpr char logical_directory[] = "/data/data/org.mybox.waitmaskprobe";
constexpr char logical_file[] = "/data/data/org.mybox.waitmaskprobe/value";
constexpr std::uint64_t Bit(int signal) { return std::uint64_t{1} << (signal - 1); }
constexpr long wait_numbers[]{SYS_ppoll, SYS_pselect6, SYS_epoll_pwait, SYS_epoll_pwait2, SYS_rt_sigsuspend};
constexpr const char* wait_names[]{"ppoll", "pselect6", "epoll_pwait", "epoll_pwait2", "rt_sigsuspend", "old-frame"};
enum Kind { ReadyWait = 1, HandlerEnter, HandlerIo, WaitDone, HandlerReturn, ThreadReturn, Installed, Failure };
struct Event {
    int kind, detail;
    long value, auxiliary;
    std::uint64_t live_mask, saved_mask;
};
static_assert(sizeof(Event) <= 64, "Keep each pipe event atomic");
struct SigsetPack { const std::uint64_t* pointer; std::size_t size; };
static_assert(sizeof(SigsetPack) == 16, "Native 64-bit pselect6 ABI only");
int output_fd = -1, handler_ready[2], handler_release[2], worker_ready[2], worker_continue[2];
bool filtered, blocked, old_frame;
char physical_file[512];

long Raw(long n, long a = 0, long b = 0, long c = 0, long d = 0, long e = 0, long f = 0) {
    return mybox_raw_syscall(n, a, b, c, d, e, f);
}
void Emit(const Event& event) {
    if (Raw(SYS_write, output_fd, reinterpret_cast<long>(&event), sizeof(event)) != sizeof(event))
        Raw(SYS_exit_group, 70);
}
[[noreturn]] void Fail(int line) {
    Emit({Failure, line, errno, 0, 0, 0});
    Raw(SYS_exit_group, 71);
    __builtin_unreachable();
}
#define VERIFY(test) do { if (!(test)) Fail(__LINE__); } while (0)

std::uint64_t Mask() {
    std::uint64_t result;
    VERIFY(Raw(SYS_rt_sigprocmask, SIG_SETMASK, 0, reinterpret_cast<long>(&result), 8) == 0);
    return result;
}
void SetMask(std::uint64_t mask) {
    VERIFY(Raw(SYS_rt_sigprocmask, SIG_SETMASK, reinterpret_cast<long>(&mask), 0, 8) == 0);
}
void Byte(int fd, bool writing) {
    char value = 'x';
    long result;
    do { result = Raw(writing ? SYS_write : SYS_read, fd, reinterpret_cast<long>(&value), 1); }
    while (result == -EINTR);
    VERIFY(result == 1);
}
void MappedIo() {
    int fd = static_cast<int>(syscall(SYS_openat, static_cast<long>(AT_FDCWD),
            filtered ? logical_file : physical_file, static_cast<long>(O_RDONLY), 0L));
    char bytes[4];
    VERIFY(fd >= 0 && read(fd, bytes, sizeof(bytes)) == sizeof(bytes));
    VERIFY(bytes[0] == 'd' && bytes[1] == 'a' && bytes[2] == 't' && bytes[3] == 'a');
    VERIFY(close(fd) == 0);
}
void UserHandler(int, siginfo_t*, void* pointer) {
    const int saved_errno = errno;
    const auto* context = static_cast<const ucontext_t*>(pointer);
    const auto saved = *reinterpret_cast<const std::uint64_t*>(&context->uc_sigmask);
    Emit({HandlerEnter, 0, 0, 0, Mask(), saved});
    if (old_frame) {
        Byte(handler_ready[1], true);
        Byte(handler_release[0], false);
        Emit({HandlerReturn, 0, 0, 0, Mask(), saved});
    } else {
        MappedIo();
        Emit({HandlerIo, 0, 0, 0, Mask(), saved});
    }
    errno = saved_errno;
}
void Setup(const char* directory) {
    rlimit no_core{0, 0};
    VERIFY(setrlimit(RLIMIT_CORE, &no_core) == 0);
    alarm(6);
    SetMask(Bit(SIGUSR2));
    struct sigaction action{};
    action.sa_sigaction = UserHandler;
    action.sa_flags = SA_SIGINFO | SA_RESTART;
    VERIFY(sigemptyset(&action.sa_mask) == 0 && sigaction(SIGUSR1, &action, nullptr) == 0);
    VERIFY(mybox::ConfiguredPaths().Add(logical_directory, directory) == 0);
}
void Install() {
    int error = filtered ? mybox::InstallIo() : 0;
    Emit({Installed, filtered ? 1 : 0, error, mybox::IoEnabled() ? 1 : 0, Mask(), 0});
    VERIFY(error == 0 && mybox::IoEnabled() == filtered);
}
void WaitChild(int operation, const char* directory) {
    Setup(directory);
    int epoll = -1;
    if (operation == 2 || operation == 3) {
        epoll = epoll_create1(EPOLL_CLOEXEC);
        VERIFY(epoll >= 0);
    }
    Install();
    const std::uint64_t temporary = Bit(SIGTERM) | (blocked ? Bit(SIGSYS) : 0);
    SigsetPack pack{&temporary, sizeof(temporary)};
    timespec timeout{5, 0};
    epoll_event event{};
    Emit({ReadyWait, operation, wait_numbers[operation], 0, Mask(), temporary});
    errno = 0;
    long result = -2;
    switch (operation) {
        case 0: result = syscall(SYS_ppoll, nullptr, 0L, &timeout, &temporary, 8L); break;
        case 1: result = syscall(SYS_pselect6, 0L, nullptr, nullptr, nullptr, &timeout, &pack); break;
        case 2: result = syscall(SYS_epoll_pwait, static_cast<long>(epoll), &event, 1L, 5000L, &temporary, 8L); break;
        case 3: result = syscall(SYS_epoll_pwait2, static_cast<long>(epoll), &event, 1L, &timeout, &temporary, 8L); break;
        case 4: result = syscall(SYS_rt_sigsuspend, &temporary, 8L); break;
        default: Fail(__LINE__);
    }
    const int error = errno;
    Emit({WaitDone, operation, result, error, Mask(), temporary});
    VERIFY(result == -1 && error == EINTR && Mask() == Bit(SIGUSR2));
    VERIFY(temporary == (Bit(SIGTERM) | (blocked ? Bit(SIGSYS) : 0)));
    if (epoll >= 0) VERIFY(close(epoll) == 0);
    _exit(0);
}
void* FrameWorker(void*) {
    SetMask(Bit(SIGUSR2) | (blocked ? Bit(SIGSYS) : 0));
    Byte(worker_ready[1], true);
    Byte(worker_continue[0], false);
    Emit({ThreadReturn, 0, 0, 0, Mask(), 0});
    MappedIo();
    return nullptr;
}
void FrameChild(const char* directory) {
    Setup(directory);
    VERIFY(pipe(handler_ready) == 0 && pipe(handler_release) == 0
            && pipe(worker_ready) == 0 && pipe(worker_continue) == 0);
    pthread_t worker;
    VERIFY(pthread_create(&worker, nullptr, FrameWorker, nullptr) == 0);
    Byte(worker_ready[0], false);
    VERIFY(pthread_kill(worker, SIGUSR1) == 0);
    Byte(handler_ready[0], false); // Outer frame is now real and remains live throughout installation.
    Install();
    Byte(handler_release[1], true);
    Byte(worker_continue[1], true);
    VERIFY(pthread_join(worker, nullptr) == 0);
    _exit(0);
}

// Parent is unfiltered and is the child's real parent; /proc syscall reads need no root or ptrace attach.
bool ObserveWait(pid_t child, int operation) {
    char path[96];
    std::snprintf(path, sizeof(path), "/proc/%ld/syscall", static_cast<long>(child));
    timespec start{};
    if (clock_gettime(CLOCK_MONOTONIC, &start) != 0) return false;
    for (;;) {
        FILE* state = std::fopen(path, "r");
        long number = -1;
        if (state) {
            const int parsed = std::fscanf(state, "%ld", &number);
            std::fclose(state);
            if (parsed == 1 && number == wait_numbers[operation]) return true;
        }
        timespec now{};
        if (clock_gettime(CLOCK_MONOTONIC, &now) != 0
                || now.tv_sec - start.tv_sec >= 3) return false;
        usleep(1000);
    }
}
bool RunCase(int operation, int mode, const char* directory) {
    filtered = mode != 0;
    blocked = mode != 1;
    old_frame = operation == 5;
    int events[2];
    if (pipe(events) != 0) return false;
    pid_t child = fork();
    if (child < 0) { close(events[0]); close(events[1]); return false; }
    if (child == 0) {
        close(events[0]);
        output_fd = events[1];
        if (old_frame) FrameChild(directory); else WaitChild(operation, directory);
        _exit(72);
    }
    close(events[1]);
    bool observed = old_frame;
    if (!old_frame) {
        observed = ObserveWait(child, operation);
        if (observed) observed = syscall(SYS_tgkill, static_cast<long>(child), static_cast<long>(child),
                static_cast<long>(SIGUSR1)) == 0;
        if (!observed) kill(child, SIGKILL);
    }
    int status = 0;
    bool reaped = waitpid(child, &status, 0) == child;
    Event recorded[16];
    unsigned count = 0;
    ssize_t bytes;
    while (count < 16 && (bytes = read(events[0], &recorded[count], sizeof(Event))) > 0) {
        if (bytes != sizeof(Event)) { observed = false; break; }
        ++count;
    }
    close(events[0]);
    const Event *entered = nullptr, *returned = nullptr, *thread = nullptr, *completed = nullptr, *installed = nullptr;
    unsigned io_count = 0;
    bool failure = false;
    for (unsigned i = 0; i < count; ++i) {
        const auto& event = recorded[i];
        if (event.kind == HandlerEnter) entered = &event;
        if (event.kind == HandlerReturn) returned = &event;
        if (event.kind == ThreadReturn) thread = &event;
        if (event.kind == WaitDone) completed = &event;
        if (event.kind == Installed) installed = &event;
        if (event.kind == HandlerIo) ++io_count;
        if (event.kind == Failure) {
            failure = true;
            std::fprintf(stderr, "probe child failure line=%d errno=%ld\n", event.detail, event.value);
        }
    }
    const bool hazard = mode == 2;
    bool expected = observed && reaped && !failure && entered && installed && installed->value == 0
            && installed->auxiliary == (filtered ? 1 : 0)
            && (hazard ? WIFSIGNALED(status) && WTERMSIG(status) == SIGSYS
                       : WIFEXITED(status) && WEXITSTATUS(status) == 0);
    if (entered) {
        if (!old_frame) {
            expected &= entered->live_mask == (Bit(SIGTERM) | Bit(SIGUSR1) | (blocked ? Bit(SIGSYS) : 0))
                    && entered->saved_mask == Bit(SIGUSR2)
                    && (hazard ? io_count == 0 && !completed : io_count == 1 && completed
                        && completed->live_mask == Bit(SIGUSR2));
        } else {
            expected &= returned && thread
                    && entered->saved_mask == (Bit(SIGUSR2) | (blocked ? Bit(SIGSYS) : 0))
                    && ((returned->live_mask & Bit(SIGSYS)) != 0) == (!filtered && blocked)
                    && ((thread->live_mask & Bit(SIGSYS)) != 0) == blocked;
        }
    }
    std::printf("{\"case\":\"%s\",\"filter\":%s,\"requested_sigsys_blocked\":%s,"
            "\"wait_syscall_observed\":%s,\"wait_status\":%d,\"expected_sigsys_death\":%s,"
            "\"handler_live_mask\":%llu,\"handler_saved_mask\":%llu,\"handler_return_mask\":%llu,"
            "\"thread_after_return_mask\":%llu,\"evidence_matched\":%s}\n",
            wait_names[operation], filtered ? "true" : "false", blocked ? "true" : "false",
            old_frame ? "null" : observed ? "true" : "false", status, hazard ? "true" : "false",
            static_cast<unsigned long long>(entered ? entered->live_mask : 0),
            static_cast<unsigned long long>(entered ? entered->saved_mask : 0),
            static_cast<unsigned long long>(returned ? returned->live_mask : 0),
            static_cast<unsigned long long>(thread ? thread->live_mask : 0), expected ? "true" : "false");
    std::fflush(stdout);
    return expected;
}
}

int main() {
    char directory[] = "/tmp/mylite-waitmask-XXXXXX";
    if (!mkdtemp(directory)) return 1;
    std::snprintf(physical_file, sizeof(physical_file), "%s/value", directory);
    int fd = open(physical_file, O_CREAT | O_WRONLY | O_EXCL, 0600);
    bool prepared = fd >= 0 && write(fd, "data", 4) == 4;
    if (fd >= 0 && close(fd) != 0) prepared = false;
    bool matched = prepared;
    if (prepared) for (int operation = 0; operation < 6; ++operation)
        for (int mode = 0; mode < 3; ++mode) if (!RunCase(operation, mode, directory)) matched = false;
    bool cleaned = unlink(physical_file) == 0 && rmdir(directory) == 0;
    std::printf("{\"summary\":\"mechanism-probe\",\"all_expected_evidence_matched\":%s,\"cleanup\":%s,"
            "\"note\":\"Expected deaths confirm a gap; they are not product acceptance passes\"}\n",
            matched ? "true" : "false", cleaned ? "true" : "false");
    return matched && cleaned ? 0 : 1;
}
