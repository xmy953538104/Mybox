// SPDX-License-Identifier: Apache-2.0
#include "../native_io.h"
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/epoll.h>
#include <sys/mman.h>
#include <sys/select.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

extern "C" long mybox_raw_syscall(long, long, long, long, long, long, long);
#define VERIFY(test) do { if (!(test)) { std::fprintf(stderr, \
    "WAIT MASK FAILED line=%d expression=%s errno=%d\n", __LINE__, #test, errno); _exit(90); } } while (0)

namespace {
constexpr char logical[] = "/data/data/org.mybox.waitmasktest";
constexpr char logical_file[] = "/data/data/org.mybox.waitmasktest/value";
constexpr long numbers[]{SYS_ppoll, SYS_pselect6, SYS_epoll_pwait, SYS_epoll_pwait2, SYS_rt_sigsuspend};
constexpr const char* names[]{"ppoll", "pselect6", "epoll_pwait", "epoll_pwait2", "rt_sigsuspend"};
constexpr std::uint64_t Bit(int signal) { return std::uint64_t{1} << (signal - 1); }
struct Pack { const std::uint64_t* pointer; std::size_t size; };
static_assert(sizeof(Pack) == 16, "Tests exercise the native 64-bit kernel pselect6 ABI");
volatile sig_atomic_t handler_calls, handler_io, handler_live, handler_saved;

long Raw(long n, long a = 0, long b = 0, long c = 0, long d = 0, long e = 0, long f = 0) {
    return mybox_raw_syscall(n, a, b, c, d, e, f);
}
std::uint64_t Mask() {
    std::uint64_t mask;
    VERIFY(Raw(SYS_rt_sigprocmask, SIG_SETMASK, 0, reinterpret_cast<long>(&mask), 8) == 0);
    return mask;
}
void Prepare(const char* directory) {
    alarm(6);
    const auto mask = Bit(SIGUSR2);
    VERIFY(Raw(SYS_rt_sigprocmask, SIG_SETMASK, reinterpret_cast<long>(&mask), 0, 8) == 0);
    VERIFY(mybox::ConfiguredPaths().Add(logical, directory) == 0);
}
void Install() {
    VERIFY(mybox::InstallIo() == 0 && mybox::IoEnabled());
}
void UserSignal(int, siginfo_t*, void* pointer) {
    const int saved_errno = errno;
    std::uint64_t mask = 0;
    if (Raw(SYS_rt_sigprocmask, SIG_SETMASK, 0, reinterpret_cast<long>(&mask), 8) != 0) _exit(91);
    handler_live = static_cast<sig_atomic_t>(mask);
    handler_saved = static_cast<sig_atomic_t>(
            *reinterpret_cast<const std::uint64_t*>(&static_cast<ucontext_t*>(pointer)->uc_sigmask));
    ++handler_calls;
    int fd = static_cast<int>(syscall(SYS_openat, static_cast<long>(AT_FDCWD), logical_file,
            static_cast<long>(O_RDONLY), 0L));
    char bytes[4];
    if (fd < 0 || read(fd, bytes, 4) != 4 || bytes[0] != 'd' || bytes[1] != 'a'
            || bytes[2] != 't' || bytes[3] != 'a' || close(fd) != 0) _exit(92);
    handler_io = 1;
    errno = saved_errno;
}
void InterruptedWait(int operation, const char* directory) {
    Prepare(directory);
    struct sigaction action{};
    action.sa_sigaction = UserSignal;
    action.sa_flags = SA_SIGINFO | SA_RESTART;
    VERIFY(sigemptyset(&action.sa_mask) == 0 && sigaction(SIGUSR1, &action, nullptr) == 0);
    const int epoll = epoll_create1(EPOLL_CLOEXEC);
    VERIFY(epoll >= 0);
    Install();
    const std::uint64_t temporary = Bit(SIGTERM) | Bit(SIGSYS);
    const Pack pack{&temporary, 8};
    timespec timeout{5, 0};
    epoll_event event{};
    errno = 0;
    long result;
    switch (operation) {
        case 0: result = syscall(SYS_ppoll, nullptr, 0L, &timeout, &temporary, 8L); break;
        case 1: result = syscall(SYS_pselect6, 0L, nullptr, nullptr, nullptr, &timeout, &pack); break;
        case 2: result = syscall(SYS_epoll_pwait, static_cast<long>(epoll), &event, 1L, 5000L, &temporary, 8L); break;
        case 3: result = syscall(SYS_epoll_pwait2, static_cast<long>(epoll), &event, 1L, &timeout, &temporary, 8L); break;
        default: result = syscall(SYS_rt_sigsuspend, &temporary, 8L); break;
    }
    const int error = errno;
    VERIFY(result == -1 && error == EINTR && handler_calls == 1 && handler_io == 1);
    VERIFY(handler_live == static_cast<sig_atomic_t>(Bit(SIGTERM) | Bit(SIGUSR1)));
    VERIFY(handler_saved == static_cast<sig_atomic_t>(Bit(SIGUSR2)) && Mask() == Bit(SIGUSR2));
    VERIFY(temporary == (Bit(SIGTERM) | Bit(SIGSYS)) && pack.pointer == &temporary && pack.size == 8);
    if (operation < 2) VERIFY(timeout.tv_sec >= 0 && timeout.tv_sec < 5
            && timeout.tv_nsec >= 0 && timeout.tv_nsec < 1000000000);
    else VERIFY(timeout.tv_sec == 5 && timeout.tv_nsec == 0);
    VERIFY(close(epoll) == 0 && mybox::GetIoStackStats().leased == 0);
    _exit(0);
}

struct Result { long result; int error; std::uint64_t mask; timespec timeout; };
struct Memory {
    char* mapping;
    long page;
    const std::uint64_t* mask;
    const Pack* pack;
    const timespec* timeout;
};
Result ErrorCase(int operation, int variant, int timeout_variant, int invalid_arguments,
        int epoll, const Memory& memory) {
    const std::uint64_t requested = Bit(SIGTERM) | Bit(SIGSYS);
    const std::uint64_t* mask = &requested;
    unsigned long size = 8;
    if (variant == 1 || variant == 3) mask = nullptr;
    if (variant == 2 || variant == 3) size = 7;
    if (variant == 4) mask = reinterpret_cast<const std::uint64_t*>(memory.mapping + memory.page * 2);
    if (variant == 5) mask = reinterpret_cast<const std::uint64_t*>(memory.mapping + memory.page * 2 - 4);
    if (variant == 6) mask = reinterpret_cast<const std::uint64_t*>(1);
    if (variant == 7) mask = memory.mask;
    if (variant == 8) mask = reinterpret_cast<const std::uint64_t*>(-1L);
    const Pack pack{mask, size};
    const void* packed = &pack;
    if (variant == 9) packed = memory.mapping + memory.page * 2;
    if (variant == 10) packed = memory.mapping + memory.page * 2 - 8;
    if (variant == 11) packed = memory.pack;
    if (variant == 12) packed = nullptr;
    timespec timeout{timeout_variant == 1 ? -1 : 0, timeout_variant == 2 ? 1000000000 : 0};
    const timespec* timeout_pointer = &timeout;
    if (timeout_variant == 3) timeout_pointer = reinterpret_cast<const timespec*>(1);
    if (timeout_variant == 4) timeout_pointer = memory.timeout;
    epoll_event event{};
    errno = 0;
    long result;
    switch (operation) {
        case 0: result = syscall(SYS_ppoll, nullptr, invalid_arguments ? -1L : 0L, timeout_pointer, mask, size); break;
        case 1: result = syscall(SYS_pselect6, invalid_arguments ? -1L : 0L,
                nullptr, nullptr, nullptr, timeout_pointer, packed); break;
        case 2: result = syscall(SYS_epoll_pwait, invalid_arguments == 1 ? -1L : static_cast<long>(epoll),
                &event, invalid_arguments == 2 ? 0L : 1L, 0L, mask, size); break;
        case 3: result = syscall(SYS_epoll_pwait2, invalid_arguments == 1 ? -1L : static_cast<long>(epoll),
                &event, invalid_arguments == 2 ? 0L : 1L, timeout_pointer, mask, size); break;
        default: result = syscall(SYS_rt_sigsuspend, mask, size); break;
    }
    const int error = errno;
    VERIFY(requested == (Bit(SIGTERM) | Bit(SIGSYS)) && pack.pointer == mask && pack.size == size);
    return {result, error, Mask(), timeout};
}
bool Included(int operation, int variant, int timeout_variant) {
    if (operation != 1 && variant >= 9) return false;
    if ((operation == 2 || operation == 4) && timeout_variant != 0) return false;
    // A valid sigsuspend has no timeout; its real EINTR path has a separate parent-driven test.
    return operation != 4 || (variant != 0 && variant != 7);
}
void ReadinessAndTimeouts(int epoll) {
    int pipefd[2];
    VERIFY(pipe(pipefd) == 0 && write(pipefd[1], "x", 1) == 1);
    epoll_event registered{};
    registered.events = EPOLLIN;
    registered.data.u64 = 0x1234;
    VERIFY(epoll_ctl(epoll, EPOLL_CTL_ADD, pipefd[0], &registered) == 0);
    const std::uint64_t temporary = Bit(SIGSYS) | Bit(SIGTERM);
    const Pack pack{&temporary, 8};
    for (int operation = 0; operation < 4; ++operation) {
        timespec timeout{0, 1000000};
        pollfd pollfd{pipefd[0], POLLIN, 0};
        fd_set descriptors;
        FD_ZERO(&descriptors); FD_SET(pipefd[0], &descriptors);
        epoll_event event{};
        long result;
        if (operation == 0) result = syscall(SYS_ppoll, &pollfd, 1L, &timeout, &temporary, 8L);
        else if (operation == 1) result = syscall(SYS_pselect6, static_cast<long>(pipefd[0] + 1),
                &descriptors, nullptr, nullptr, &timeout, &pack);
        else if (operation == 2) result = syscall(SYS_epoll_pwait, static_cast<long>(epoll), &event, 1L, 1L, &temporary, 8L);
        else result = syscall(SYS_epoll_pwait2, static_cast<long>(epoll), &event, 1L, &timeout, &temporary, 8L);
        VERIFY(result == 1 && Mask() == Bit(SIGUSR2));
        if (operation == 0) VERIFY((pollfd.revents & POLLIN) != 0);
        else if (operation == 1) VERIFY(FD_ISSET(pipefd[0], &descriptors));
        else VERIFY((event.events & EPOLLIN) != 0 && event.data.u64 == 0x1234);
        if (operation == 3) VERIFY(timeout.tv_sec == 0 && timeout.tv_nsec == 1000000);
    }
    VERIFY(epoll_ctl(epoll, EPOLL_CTL_DEL, pipefd[0], nullptr) == 0);
    VERIFY(close(pipefd[0]) == 0 && close(pipefd[1]) == 0);
    for (int operation = 0; operation < 4; ++operation) {
        timespec timeout{0, 1000000};
        epoll_event event{};
        long result;
        if (operation == 0) result = syscall(SYS_ppoll, nullptr, 0L, &timeout, &temporary, 8L);
        else if (operation == 1) result = syscall(SYS_pselect6, 0L, nullptr, nullptr, nullptr, &timeout, &pack);
        else if (operation == 2) result = syscall(SYS_epoll_pwait, static_cast<long>(epoll), &event, 1L, 1L, &temporary, 8L);
        else result = syscall(SYS_epoll_pwait2, static_cast<long>(epoll), &event, 1L, &timeout, &temporary, 8L);
        VERIFY(result == 0 && Mask() == Bit(SIGUSR2));
        if (operation < 2) VERIFY(timeout.tv_sec == 0 && timeout.tv_nsec == 0);
        if (operation == 3) VERIFY(timeout.tv_sec == 0 && timeout.tv_nsec == 1000000);
    }
    VERIFY(temporary == (Bit(SIGSYS) | Bit(SIGTERM)) && pack.pointer == &temporary && pack.size == 8);
}
void ErrorMatrix(const char* directory) {
    Prepare(directory);
    const long page = sysconf(_SC_PAGESIZE);
    VERIFY(page >= 4096);
    auto* mapping = static_cast<char*>(mmap(nullptr, 3 * page, PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    VERIFY(mapping != MAP_FAILED);
    auto* mask = reinterpret_cast<std::uint64_t*>(mapping);
    *mask = Bit(SIGTERM) | Bit(SIGSYS);
    auto* pack = reinterpret_cast<Pack*>(mapping + 16);
    *pack = {mask, 8};
    auto* timeout = reinterpret_cast<timespec*>(mapping + 32);
    *timeout = {0, 0};
    *reinterpret_cast<const std::uint64_t**>(mapping + 2 * page - 8) = mask;
    VERIFY(mprotect(mapping, page, PROT_READ) == 0 && mprotect(mapping + 2 * page, page, PROT_NONE) == 0);
    const Memory memory{mapping, page, mask, pack, timeout};
    const int epoll = epoll_create1(EPOLL_CLOEXEC);
    VERIFY(epoll >= 0);
    Result baseline[5][13][5][3]{};
    for (int filtered = 0; filtered < 2; ++filtered) {
        if (filtered) Install();
        unsigned calls = 0;
        for (int operation = 0; operation < 5; ++operation) {
            for (int variant = 0; variant < 13; ++variant) {
                for (int timeout_variant = 0; timeout_variant < 5; ++timeout_variant) {
                    if (!Included(operation, variant, timeout_variant)) continue;
                    const int argument_modes = operation == 4 ? 1 : operation >= 2 ? 3 : 2;
                    for (int invalid_arguments = 0; invalid_arguments < argument_modes; ++invalid_arguments) {
                        const Result actual = ErrorCase(operation, variant, timeout_variant,
                                invalid_arguments, epoll, memory);
                        VERIFY(actual.error != ENOSYS && actual.mask == Bit(SIGUSR2));
                        if (!filtered) baseline[operation][variant][timeout_variant][invalid_arguments] = actual;
                        else {
                            const Result& expected = baseline[operation][variant][timeout_variant][invalid_arguments];
                            if (actual.result != expected.result || actual.error != expected.error
                                    || actual.timeout.tv_sec != expected.timeout.tv_sec
                                    || actual.timeout.tv_nsec != expected.timeout.tv_nsec) {
                                std::fprintf(stderr, "wait error mismatch operation=%s variant=%d timeout=%d "
                                        "invalid_args=%d baseline=%ld/%d actual=%ld/%d\n", names[operation], variant,
                                        timeout_variant, invalid_arguments, expected.result, expected.error,
                                        actual.result, actual.error);
                                _exit(93);
                            }
                        }
                        ++calls;
                    }
                }
            }
        }
        ReadinessAndTimeouts(epoll);
        std::fprintf(stderr, "PASS: wait masks filtered=%d error/null/readonly/partial-copy cases=%u, "
                "real readiness and timeout writeback\n", filtered, calls);
    }
    VERIFY(*mask == (Bit(SIGSYS) | Bit(SIGTERM)) && pack->pointer == mask && pack->size == 8);
    VERIFY(timeout->tv_sec == 0 && timeout->tv_nsec == 0 && mybox::GetIoStackStats().leased == 0);
    VERIFY(close(epoll) == 0 && munmap(mapping, 3 * page) == 0);
    _exit(0);
}
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
            if (parsed == 1 && number == numbers[operation]) return true;
        }
        timespec now{};
        if (clock_gettime(CLOCK_MONOTONIC, &now) != 0 || now.tv_sec - start.tv_sec >= 3) return false;
        usleep(1000);
    }
}
}

void WaitMaskTests() {
    char directory[] = "/tmp/mybox-mylite-wait-XXXXXX", physical_file[256];
    VERIFY(mkdtemp(directory) != nullptr);
    std::snprintf(physical_file, sizeof(physical_file), "%s/value", directory);
    int fd = open(physical_file, O_CREAT | O_EXCL | O_WRONLY, 0600);
    VERIFY(fd >= 0 && write(fd, "data", 4) == 4 && close(fd) == 0);
    bool passed = true;
    for (int operation = -1; operation < 5; ++operation) {
        std::fprintf(stderr, "STAGE: fork wait-mask %s\n", operation < 0 ? "error matrix" : names[operation]);
        pid_t child = fork();
        VERIFY(child >= 0);
        if (child == 0) {
            if (operation < 0) ErrorMatrix(directory);
            else InterruptedWait(operation, directory);
            _exit(94);
        }
        bool observed = operation < 0 || ObserveWait(child, operation);
        if (operation >= 0 && observed) observed = syscall(SYS_tgkill, static_cast<long>(child),
                static_cast<long>(child), static_cast<long>(SIGUSR1)) == 0;
        if (!observed) kill(child, SIGKILL);
        int status = 0;
        bool completed = waitpid(child, &status, 0) == child;
        const bool success = observed && completed && WIFEXITED(status) && WEXITSTATUS(status) == 0;
        std::fprintf(stderr, "%s: wait-mask operation=%d observed=%d status=%d\n",
                success ? "PASS" : "FAILED", operation, observed, status);
        passed &= success;
    }
    VERIFY(unlink(physical_file) == 0 && rmdir(directory) == 0);
    VERIFY(passed);
}
