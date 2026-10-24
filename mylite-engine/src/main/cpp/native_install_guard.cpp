// SPDX-License-Identifier: Apache-2.0
#include "native_install_guard.h"
#include <atomic>
#include <cerrno>
#include <climits>
#include <cstddef>
#include <cstdint>
#include <fcntl.h>
#include <linux/futex.h>
#include <signal.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <time.h>
#include <unistd.h>

extern "C" long mybox_raw_syscall(long, long, long, long, long, long, long);

namespace {
constexpr unsigned kMaxThreads = 256;
constexpr std::uint64_t kTrapBit = std::uint64_t{1} << (SIGSYS - 1);
constexpr int kCookie = 0x4d590000;
constexpr long long kWaitNanos = 250000000;
enum Phase : int { Idle, Preparing, Waiting, Aborted, Committed };
struct KernelAction { std::uintptr_t handler, flags, restorer; std::uint64_t mask; };
struct Target { int tid = 0; std::atomic<int> entered{0}; };
static_assert(std::atomic<int>::is_always_lock_free && sizeof(std::atomic<int>) == 4
        && alignof(std::atomic<int>) >= 4, "Signal rendezvous requires lock-free futex words");

// A kernel-selected handler can enter after its disposition has been restored. Never reuse this state.
std::atomic<int> phase{Idle}, acknowledgements{0}, handler_error{0};
Target targets[kMaxThreads];
int initial_threads[kMaxThreads], verify_threads[kMaxThreads];
unsigned initial_count, target_count;
int installer_tid, process_pid, process_uid, selected_signal;
int task_directory = -1;
KernelAction original_action{}, installed_action{};
bool action_installed, installer_mask_saved;
std::uint64_t installer_mask;
mybox::InstallGuardDiagnostics diagnostics;
#if defined(MYLITE_NATIVE_TESTING)
mybox::InstallGuardPreparedHook prepared_hook;
void* prepared_hook_argument;
#endif

long Raw(long n, long a = 0, long b = 0, long c = 0, long d = 0, long e = 0, long f = 0) {
    return mybox_raw_syscall(n, a, b, c, d, e, f);
}
long Futex(std::atomic<int>& word, int operation, int value, const timespec* timeout = nullptr) {
    return Raw(__NR_futex, reinterpret_cast<long>(&word), operation | FUTEX_PRIVATE_FLAG, value,
            reinterpret_cast<long>(timeout));
}
bool SameAction(const KernelAction& a, const KernelAction& b) {
    return a.handler == b.handler && a.flags == b.flags && a.restorer == b.restorer && a.mask == b.mask;
}
int GetAction(int signal, KernelAction& action) {
    long result = Raw(__NR_rt_sigaction, signal, 0, reinterpret_cast<long>(&action), 8);
    return result < 0 ? static_cast<int>(-result) : 0;
}
int RestoreAction() {
    if (!action_installed) return 0;
    KernelAction current;
    if (GetAction(selected_signal, current) != 0 || !SameAction(current, installed_action))
        return EOWNERDEAD; // Never overwrite a concurrently installed owner.
    KernelAction replaced;
    long result = Raw(__NR_rt_sigaction, selected_signal, reinterpret_cast<long>(&original_action),
            reinterpret_cast<long>(&replaced), 8);
    if (result != 0) return EOWNERDEAD;
    action_installed = false;
    if (!SameAction(replaced, installed_action)) return EOWNERDEAD;
    if (GetAction(selected_signal, current) != 0 || !SameAction(current, original_action)) return EOWNERDEAD;
    return 0;
}
int RestoreInstallerMask(bool committed) {
    if (!installer_mask_saved) return 0;
    auto mask = committed ? installer_mask & ~kTrapBit : installer_mask;
    long result = Raw(__NR_rt_sigprocmask, SIG_SETMASK, reinterpret_cast<long>(&mask), 0, 8);
    if (result != 0) return EOWNERDEAD;
    installer_mask_saved = false;
    return 0;
}
int CloseTaskDirectory() {
    if (task_directory < 0) return 0;
    long result = Raw(__NR_close, task_directory);
    task_directory = -1;
    return result < 0 ? static_cast<int>(-result) : 0;
}
int Abort(int error) {
    diagnostics.cause_errno = error;
    phase.store(Aborted, std::memory_order_release);
    const long wake = Futex(phase, FUTEX_WAKE, INT_MAX);
    // SIGURG/WINCH default to ignore. Restoring DFL/IGN also discards blocked pending requests.
    const int restored_action = RestoreAction();
    const int restored_mask = RestoreInstallerMask(false);
    const int closed = CloseTaskDirectory();
    if (error == EOWNERDEAD || wake < 0 || restored_action != 0 || restored_mask != 0) {
        diagnostics.cleanup_errno = EOWNERDEAD;
        return EOWNERDEAD;
    }
    return closed != 0 ? closed : error;
}

void ParkThread(int signal, siginfo_t* info, void* context) {
    if (phase.load(std::memory_order_acquire) != Waiting) return;
    if (signal != selected_signal || info->si_code != SI_QUEUE || info->si_pid != process_pid
            || static_cast<int>(info->si_uid) != process_uid) return;
    const unsigned cookie = static_cast<unsigned>(info->si_value.sival_int);
    const unsigned index = (cookie & 0xffffu) - 1;
    if ((cookie & 0xffff0000u) != static_cast<unsigned>(kCookie) || index >= target_count
            || Raw(__NR_gettid) != targets[index].tid) return;
    if (targets[index].entered.exchange(1, std::memory_order_acq_rel) != 0) return;

    const std::uint64_t trap = kTrapBit;
    long result = Raw(__NR_rt_sigprocmask, SIG_UNBLOCK, reinterpret_cast<long>(&trap), 0, 8);
    if (result != 0) {
        int expected = 0;
        handler_error.compare_exchange_strong(expected, static_cast<int>(-result), std::memory_order_relaxed);
        Futex(acknowledgements, FUTEX_WAKE, 1);
        return;
    }
    acknowledgements.fetch_add(1, std::memory_order_release);
    Futex(acknowledgements, FUTEX_WAKE, 1);
    while (phase.load(std::memory_order_acquire) == Waiting) {
        result = Futex(phase, FUTEX_WAIT, Waiting);
        if (result < 0 && result != -EINTR && result != -EAGAIN) {
            int expected = 0;
            handler_error.compare_exchange_strong(expected, static_cast<int>(-result), std::memory_order_relaxed);
            Futex(acknowledgements, FUTEX_WAKE, 1);
            // Remain parked until the installer publishes a final phase, even if futex is unavailable.
        }
    }
    if (phase.load(std::memory_order_acquire) == Committed) {
        auto* saved = static_cast<ucontext_t*>(context);
        auto* mask = reinterpret_cast<std::uint64_t*>(&saved->uc_sigmask);
        *mask &= ~kTrapBit;
    }
    // ABORT leaves the interrupted frame unchanged, including its original SIGSYS bit.
}

struct Dirent64 {
    std::uint64_t inode;
    std::int64_t offset;
    unsigned short length;
    unsigned char type;
    char name[1];
};
int ReadThreads(int* output, unsigned& count) {
    count = 0;
    long result = Raw(__NR_lseek, task_directory, 0, SEEK_SET);
    if (result < 0) return static_cast<int>(-result);
    alignas(8) char buffer[4096];
    for (;;) {
        result = Raw(__NR_getdents64, task_directory, reinterpret_cast<long>(buffer), sizeof(buffer));
        if (result < 0) return static_cast<int>(-result);
        if (result == 0) break;
        for (long offset = 0; offset < result;) {
            if (result - offset < static_cast<long>(offsetof(Dirent64, name) + 1)) return EPROTO;
            auto* entry = reinterpret_cast<Dirent64*>(buffer + offset);
            if (entry->length < offsetof(Dirent64, name) + 1 || entry->length > result - offset) return EPROTO;
            if (entry->name[0] >= '0' && entry->name[0] <= '9') {
                unsigned value = 0, i = 0;
                const unsigned capacity = entry->length - offsetof(Dirent64, name);
                while (i < capacity && entry->name[i] >= '0' && entry->name[i] <= '9') {
                    if (value > static_cast<unsigned>(INT_MAX) / 10) return EOVERFLOW;
                    value = value * 10 + static_cast<unsigned>(entry->name[i++] - '0');
                    if (value > INT_MAX) return EOVERFLOW;
                }
                if (i == capacity || entry->name[i] != 0 || value == 0) return EPROTO;
                if (count == kMaxThreads) return E2BIG;
                output[count++] = static_cast<int>(value);
            }
            offset += entry->length;
        }
    }
    for (unsigned i = 1; i < count; ++i) {
        int value = output[i];
        unsigned j = i;
        while (j != 0 && output[j - 1] > value) { output[j] = output[j - 1]; --j; }
        output[j] = value;
    }
    return count == 0 ? EAGAIN : 0;
}
int ReadStatus(int directory, const char* path, char* buffer, unsigned capacity, unsigned& size) {
    long fd = Raw(__NR_openat, directory, reinterpret_cast<long>(path), O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return fd == -ENOENT ? EAGAIN : static_cast<int>(-fd);
    size = 0;
    int error = 0;
    for (;;) {
        if (size == capacity) { error = EOVERFLOW; break; }
        long result = Raw(__NR_read, fd, reinterpret_cast<long>(buffer + size), capacity - size);
        if (result < 0) { error = static_cast<int>(-result); break; }
        if (result == 0) break;
        size += static_cast<unsigned>(result);
    }
    long closed = Raw(__NR_close, fd);
    return error != 0 ? error : closed < 0 ? static_cast<int>(-closed) : 0;
}
int StatusNumber(const char* text, unsigned size, const char* key, bool hex, std::uint64_t& value) {
    for (unsigned line = 0; line < size;) {
        unsigned cursor = line, k = 0;
        while (cursor < size && key[k] != 0 && text[cursor] == key[k]) { ++cursor; ++k; }
        if (key[k] == 0) {
            while (cursor < size && (text[cursor] == ' ' || text[cursor] == '\t')) ++cursor;
            value = 0;
            unsigned digits = 0;
            while (cursor < size && text[cursor] != '\n') {
                char character = text[cursor++];
                unsigned digit;
                if (character >= '0' && character <= '9') digit = character - '0';
                else if (hex && character >= 'a' && character <= 'f') digit = character - 'a' + 10;
                else if (hex && character >= 'A' && character <= 'F') digit = character - 'A' + 10;
                else return EPROTO;
                const unsigned base = hex ? 16 : 10;
                if (value > (UINT64_MAX - digit) / base) return EOVERFLOW;
                value = value * base + digit;
                ++digits;
            }
            return digits != 0 ? 0 : EPROTO;
        }
        while (line < size && text[line] != '\n') ++line;
        ++line;
    }
    return EPROTO;
}
int TaskMasks(int tid, std::uint64_t& blocked, std::uint64_t& pending) {
    char reversed[16], path[32];
    unsigned length = 0;
    do { reversed[length++] = static_cast<char>('0' + tid % 10); tid /= 10; } while (tid != 0);
    for (unsigned i = 0; i < length; ++i) path[i] = reversed[length - i - 1];
    constexpr char suffix[] = "/status";
    for (unsigned i = 0; i < sizeof(suffix); ++i) path[length + i] = suffix[i];
    char buffer[8192];
    unsigned size;
    int error = ReadStatus(task_directory, path, buffer, sizeof(buffer), size);
    if (error != 0) return error;
    std::uint64_t shared;
    if ((error = StatusNumber(buffer, size, "SigBlk:", true, blocked)) != 0
            || (error = StatusNumber(buffer, size, "SigPnd:", true, pending)) != 0
            || (error = StatusNumber(buffer, size, "ShdPnd:", true, shared)) != 0) return error;
    pending |= shared;
    return 0;
}
int ConfirmThreadSet() {
    for (int pass = 0; pass < 2; ++pass) {
        unsigned count;
        int error = ReadThreads(verify_threads, count);
        if (error != 0) return error;
        if (count != initial_count) return EAGAIN;
        for (unsigned i = 0; i < count; ++i) if (verify_threads[i] != initial_threads[i]) return EAGAIN;
    }
    char buffer[8192];
    unsigned size;
    int error = ReadStatus(AT_FDCWD, "/proc/self/status", buffer, sizeof(buffer), size);
    if (error != 0) return error;
    std::uint64_t count;
    error = StatusNumber(buffer, size, "Threads:", false, count);
    // Parked threads cannot create or exit. Equality excludes an unobserved creator between scans.
    return error != 0 ? error : count == target_count + 1 ? 0 : EAGAIN;
}
long long MonotonicNanos() {
    timespec now;
    long result = Raw(__NR_clock_gettime, CLOCK_MONOTONIC, reinterpret_cast<long>(&now));
    return result < 0 ? result : static_cast<long long>(now.tv_sec) * 1000000000 + now.tv_nsec;
}
int WaitForThreads() {
    long long start = MonotonicNanos();
    if (start < 0) return static_cast<int>(-start);
    for (;;) {
        int error = handler_error.load(std::memory_order_acquire);
        if (error != 0) return error;
        int seen = acknowledgements.load(std::memory_order_acquire);
        if (seen == static_cast<int>(target_count)) return 0;
        long long now = MonotonicNanos();
        if (now < 0) return static_cast<int>(-now);
        long long left = kWaitNanos - (now - start);
        if (left <= 0) {
            for (unsigned i = 0; i < target_count; ++i) {
                if (targets[i].entered.load(std::memory_order_acquire) != 0) continue;
                diagnostics.tid = targets[i].tid;
                diagnostics.mask_errno = TaskMasks(diagnostics.tid, diagnostics.blocked, diagnostics.pending);
                break;
            }
            return ETIMEDOUT;
        }
        timespec timeout{static_cast<time_t>(left / 1000000000), static_cast<long>(left % 1000000000)};
        long result = Futex(acknowledgements, FUTEX_WAIT, seen, &timeout);
        if (result < 0 && result != -EAGAIN && result != -EINTR && result != -ETIMEDOUT)
            return static_cast<int>(-result);
    }
}
}

namespace mybox {
InstallGuardDiagnostics GetInstallGuardDiagnostics() {
    diagnostics.acknowledged = static_cast<unsigned>(acknowledgements.load(std::memory_order_acquire));
    return diagnostics;
}
#if defined(MYLITE_NATIVE_TESTING)
void SetInstallGuardPreparedHookForTest(InstallGuardPreparedHook hook, void* argument) {
    prepared_hook = hook;
    prepared_hook_argument = argument;
}
unsigned InstallGuardAcknowledgementsForTest() {
    return static_cast<unsigned>(acknowledgements.load(std::memory_order_acquire));
}
#endif

int BeginInstallGuard() {
    int idle = Idle;
    if (!phase.compare_exchange_strong(idle, Preparing, std::memory_order_acq_rel)) return EALREADY;
    diagnostics.stage = "open-task-directory";
    installer_tid = static_cast<int>(Raw(__NR_gettid));
    process_pid = static_cast<int>(Raw(__NR_getpid));
    process_uid = static_cast<int>(Raw(__NR_getuid));
    long directory = Raw(__NR_openat, AT_FDCWD, reinterpret_cast<long>("/proc/self/task"),
            O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
    if (directory < 0) return Abort(static_cast<int>(-directory));
    task_directory = static_cast<int>(directory);
    diagnostics.stage = "initial-cohort";
    int error = ReadThreads(initial_threads, initial_count);
    if (error != 0) return Abort(error);
    bool found_self = false;
    for (unsigned i = 0; i < initial_count; ++i) {
        if (initial_threads[i] == installer_tid) found_self = true;
        else targets[target_count++].tid = initial_threads[i];
    }
    if (!found_self) return Abort(EAGAIN);
    diagnostics.threads = initial_count;
    diagnostics.targets = target_count;

    if (target_count != 0) {
        constexpr int candidates[]{SIGURG, SIGWINCH};
        diagnostics.stage = "candidate-preflight";
        for (unsigned candidate_index = 0; candidate_index < 2; ++candidate_index) {
            const int candidate = candidates[candidate_index];
            auto& note = diagnostics.candidates[candidate_index];
            note.signal = candidate;
            note.reason = "read-action";
            KernelAction action;
            if ((error = GetAction(candidate, action)) != 0) return Abort(error);
            if (action.handler != reinterpret_cast<std::uintptr_t>(SIG_DFL)
                    && action.handler != reinterpret_cast<std::uintptr_t>(SIG_IGN)) {
                note.reason = "owned-handler";
                continue;
            }
            note.reason = "available";
            const auto bit = std::uint64_t{1} << (candidate - 1);
            bool unused = true;
            for (unsigned i = 0; i < initial_count; ++i) {
                std::uint64_t blocked, pending;
                if ((error = TaskMasks(initial_threads[i], blocked, pending)) != 0) {
                    note.reason = "read-task-masks";
                    note.tid = initial_threads[i];
                    return Abort(error);
                }
                if ((pending & bit) != 0 || (initial_threads[i] != installer_tid && (blocked & bit) != 0)) {
                    if (unused) {
                        note.reason = (pending & bit) != 0 ? "pending-signal" : "blocked-signal";
                        note.tid = initial_threads[i];
                        note.blocked = blocked;
                        note.pending = pending;
                    }
                    unused = false;
                }
            }
            if (unused) { selected_signal = candidate; original_action = action; break; }
        }
        if (selected_signal == 0) return Abort(EBUSY);
    }

    diagnostics.signal = selected_signal;
    diagnostics.stage = "installer-mask";
    const std::uint64_t temporary_mask = ~kTrapBit;
    long result = Raw(__NR_rt_sigprocmask, SIG_SETMASK, reinterpret_cast<long>(&temporary_mask),
            reinterpret_cast<long>(&installer_mask), 8);
    if (result != 0) return Abort(static_cast<int>(-result));
    installer_mask_saved = true;
    if (target_count != 0) {
        diagnostics.stage = "install-carrier-action";
        // The libc wrapper supplies the ABI's restorer before any thread is parked. Raw reads reject sigchain owners.
        struct sigaction action{}, previous{};
        action.sa_sigaction = ParkThread;
        action.sa_flags = SA_SIGINFO | SA_RESTART;
        sigfillset(&action.sa_mask);
        sigdelset(&action.sa_mask, SIGSYS);
        if (sigaction(selected_signal, &action, &previous) != 0) return Abort(errno);
        if (GetAction(selected_signal, installed_action) != 0
                || installed_action.handler != reinterpret_cast<std::uintptr_t>(&ParkThread)) return Abort(EOWNERDEAD);
        action_installed = true;
        KernelAction replaced{reinterpret_cast<std::uintptr_t>(previous.sa_handler),
                static_cast<std::uintptr_t>(static_cast<unsigned>(previous.sa_flags)),
                reinterpret_cast<std::uintptr_t>(previous.sa_restorer),
                *reinterpret_cast<const std::uint64_t*>(&previous.sa_mask)};
        if (!SameAction(replaced, original_action)) {
            // The wrapper exchanged an owner that changed after our first read. Return it, then retire.
            original_action = replaced;
            return Abort(EOWNERDEAD);
        }
    }
#if defined(MYLITE_NATIVE_TESTING)
    if (prepared_hook) prepared_hook(selected_signal, prepared_hook_argument);
#endif
    // Construct before the first park: debug compilers may emit an external memset for this object.
    siginfo_t info{};
    info.si_signo = selected_signal;
    info.si_code = SI_QUEUE;
    info.si_pid = process_pid;
    info.si_uid = static_cast<uid_t>(process_uid);
    phase.store(Waiting, std::memory_order_release);
    if (target_count != 0) {
        diagnostics.stage = "verify-carrier-action";
        KernelAction current;
        if (GetAction(selected_signal, current) != 0 || !SameAction(current, installed_action)) return Abort(EOWNERDEAD);
        for (unsigned i = 0; i < target_count; ++i) {
            diagnostics.stage = "queue-carrier";
            diagnostics.tid = targets[i].tid;
            info.si_value.sival_int = kCookie | static_cast<int>(i + 1);
            result = Raw(__NR_rt_tgsigqueueinfo, process_pid, targets[i].tid, selected_signal,
                    reinterpret_cast<long>(&info));
            if (result != 0) return Abort(result == -ESRCH ? EAGAIN : static_cast<int>(-result));
        }
        diagnostics.tid = 0;
        diagnostics.stage = "wait-acknowledgements";
        if ((error = WaitForThreads()) != 0) return Abort(error);
    }
    diagnostics.stage = "confirm-cohort";
    if ((error = ConfirmThreadSet()) != 0) return Abort(error);
    if (handler_error.load(std::memory_order_acquire) != 0) return Abort(handler_error.load());
    // Current handlers stay parked after this restoration. No borrowed disposition remains at TSYNC.
    diagnostics.stage = "restore-carrier-action";
    if ((error = RestoreAction()) != 0) return Abort(error);
    diagnostics.stage = "close-task-directory";
    if ((error = CloseTaskDirectory()) != 0) return Abort(error);
    diagnostics.stage = "parked";
    return 0;
}

int FinishInstallGuard(bool filter_installed) {
    if (phase.load(std::memory_order_acquire) != Waiting) return EOWNERDEAD;
    phase.store(filter_installed ? Committed : Aborted, std::memory_order_release);
    long result = Futex(phase, FUTEX_WAKE, INT_MAX);
    int restored = RestoreInstallerMask(filter_installed);
    const int error = result < 0 || restored != 0 || handler_error.load(std::memory_order_acquire) != 0
            ? EOWNERDEAD : 0;
    if (error != 0) diagnostics.cleanup_errno = error;
    return error;
}
}
