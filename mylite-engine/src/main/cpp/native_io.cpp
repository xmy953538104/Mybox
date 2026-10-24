// SPDX-License-Identifier: Apache-2.0
#include "native_io.h"
#include "native_install_guard.h"
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <sys/uio.h>
#include <unistd.h>

extern "C" long mybox_raw_syscall(long, long, long, long, long, long, long);
extern "C" char mybox_raw_syscall_end[];
struct Request { long number; long args[6]; std::uint64_t* interrupted_mask; };
extern "C" long mybox_dispatch_on_stack(Request*, void*);

namespace {
mybox::PathRules paths;
std::atomic<bool> enabled{false};
std::atomic<bool> attempted{false};
struct sigaction previous_sigsys{};
std::size_t page_size;
constexpr unsigned kTrapTag = 0x4d42;
constexpr std::size_t kSignalStackSize = 64 * 1024;
constexpr unsigned kSignalStackSlots = 8;
constexpr std::uint32_t kSignalStackMask = (1u << kSignalStackSlots) - 1;
std::uintptr_t signal_stack_pool;
bool signal_stack_pool_available;
int signal_stack_pool_error;
std::atomic<std::uint32_t> signal_stack_leases{0};
std::atomic<std::uint64_t> fallback_requests{0}, fallback_failures{0};
static_assert(std::atomic<std::uint32_t>::is_always_lock_free
        && std::atomic<std::uint64_t>::is_always_lock_free, "Signal stack leases must not take locks");
#if defined(__aarch64__)
constexpr unsigned kArchitecture = AUDIT_ARCH_AARCH64;
#elif defined(__x86_64__)
constexpr unsigned kArchitecture = AUDIT_ARCH_X86_64;
#else
#error Unsupported native I/O ABI
#endif

long Raw(long n, long a = 0, long b = 0, long c = 0, long d = 0, long e = 0, long f = 0) {
    return mybox_raw_syscall(n, a, b, c, d, e, f);
}
int AcquireSignalStack() {
    if (!signal_stack_pool_available) return -1;
    auto occupied = signal_stack_leases.load(std::memory_order_relaxed);
    for (;;) {
        auto available = ~occupied & kSignalStackMask;
        if (available == 0) return -1;
        auto bit = available & (0u - available);
        if (signal_stack_leases.compare_exchange_weak(occupied, occupied | bit,
                std::memory_order_acquire, std::memory_order_relaxed)) return __builtin_ctz(bit);
    }
}
long CopyMemory(void* local, void* remote, std::size_t length, bool write) {
    iovec here{local, length}, there{remote, length};
    return Raw(write ? __NR_process_vm_writev : __NR_process_vm_readv, Raw(__NR_getpid),
            reinterpret_cast<long>(&here), 1, reinterpret_cast<long>(&there), 1, 0);
}
int ReadPath(long pointer, char* target) {
    if (!pointer) return -EFAULT;
    std::size_t done = 0;
    while (done < mybox::kPathSize) {
        std::uintptr_t address = static_cast<std::uintptr_t>(pointer) + done;
        std::size_t count = page_size - (address % page_size);
        if (count > mybox::kPathSize - done) count = mybox::kPathSize - done;
        long copied = CopyMemory(target + done, reinterpret_cast<void*>(address), count, false);
        if (copied <= 0) return copied < 0 ? static_cast<int>(copied) : -EFAULT;
        for (long i = 0; i < copied; ++i) if (target[done + i] == 0) return 0;
        done += static_cast<std::size_t>(copied);
    }
    return -ENAMETOOLONG;
}

struct PathCall { int number; int first; int second; };
// All numbers come from this ABI's kernel UAPI headers, never from the other ABI.
constexpr PathCall path_calls[] = {
    {__NR_openat, 1, -1},
    {__NR_newfstatat, 1, -1},
    {__NR_mkdirat, 1, -1}, {__NR_mknodat, 1, -1}, {__NR_unlinkat, 1, -1},
    {__NR_renameat, 1, 3}, {__NR_renameat2, 1, 3}, {__NR_linkat, 1, 3},
    {__NR_symlinkat, 0, 2}, {__NR_readlinkat, 1, -1},
    {__NR_faccessat, 1, -1}, {__NR_fchmodat, 1, -1}, {__NR_fchownat, 1, -1},
    {__NR_utimensat, 1, -1}, {__NR_truncate, 0, -1}, {__NR_chdir, 0, -1},
    {__NR_statfs, 0, -1},
#ifdef __NR_openat2
    {__NR_openat2, 1, -1},
#endif
#ifdef __NR_faccessat2
    {__NR_faccessat2, 1, -1},
#endif
#ifdef __NR_fchmodat2
    {__NR_fchmodat2, 1, -1},
#endif
#ifdef __NR_statx
    {__NR_statx, 1, -1},
#endif
#if defined(__x86_64__)
    {__NR_open, 0, -1}, {__NR_creat, 0, -1}, {__NR_stat, 0, -1}, {__NR_lstat, 0, -1},
    {__NR_access, 0, -1}, {__NR_mkdir, 0, -1}, {__NR_mknod, 0, -1},
    {__NR_unlink, 0, -1}, {__NR_rmdir, 0, -1}, {__NR_rename, 0, 1},
    {__NR_link, 0, 1}, {__NR_symlink, 0, 1}, {__NR_readlink, 0, -1},
    {__NR_chmod, 0, -1}, {__NR_chown, 0, -1}, {__NR_lchown, 0, -1},
    {__NR_utime, 0, -1}, {__NR_utimes, 0, -1},
#endif
};

bool IsReadlink(long n) {
    return n == __NR_readlinkat
#if defined(__x86_64__)
            || n == __NR_readlink
#endif
            ;
}

long Readlink(Request& r) {
    int output_index = r.number == __NR_readlinkat ? 2 : 1;
    void* user = reinterpret_cast<void*>(r.args[output_index]);
    auto capacity = static_cast<std::size_t>(r.args[output_index + 1]);
    if (capacity == 0) return -EINVAL;
    char actual[mybox::kPathSize], logical[mybox::kPathSize];
    r.args[output_index] = reinterpret_cast<long>(actual);
    r.args[output_index + 1] = sizeof(actual) - 1;
    long n = Raw(r.number, r.args[0], r.args[1], r.args[2], r.args[3], r.args[4], r.args[5]);
    if (n < 0) return n;
    actual[n] = 0;
    int mapped = paths.Rewrite(actual, logical, true);
    if (mapped < 0) return mapped;
    std::size_t length = static_cast<std::size_t>(mapped);
    if (length > capacity) length = capacity;
    long written = CopyMemory(logical, user, length, true);
    return written == static_cast<long>(length) ? written : -EFAULT;
}

// The 64-bit kernel rt_sigaction layout differs from bionic/glibc's public wrapper layout.
struct KernelAction { std::uintptr_t handler, flags, restorer; std::uint64_t mask; };
bool SameKernelAction(const KernelAction& a, const KernelAction& b) {
    return a.handler == b.handler && a.flags == b.flags && a.restorer == b.restorer && a.mask == b.mask;
}
KernelAction KernelActionFrom(const struct sigaction& action) {
    return {reinterpret_cast<std::uintptr_t>(action.sa_handler),
            static_cast<std::uintptr_t>(static_cast<unsigned>(action.sa_flags)),
            reinterpret_cast<std::uintptr_t>(action.sa_restorer),
            *reinterpret_cast<const std::uint64_t*>(&action.sa_mask)};
}
struct SavedSignalActions {
    KernelAction actions[NSIG]{};
    std::uint64_t changed = 0;

    int UnblockTrapInHandlers() {
        static_assert(NSIG <= 65, "Signal mask must fit the kernel's 64-bit sigset");
        for (int signal = 1; signal < NSIG; ++signal) {
            if (signal == SIGKILL || signal == SIGSTOP || signal == SIGSYS) continue;
            // Use the kernel layout to preserve restorer and reserved libc signal actions too.
            long status = Raw(__NR_rt_sigaction, signal, 0,
                    reinterpret_cast<long>(&actions[signal]), sizeof(std::uint64_t));
            if (status < 0) return static_cast<int>(-status);
            KernelAction action = actions[signal];
            const auto trap = std::uint64_t{1} << (SIGSYS - 1);
            if ((action.mask & trap) == 0) continue;
            action.mask &= ~trap;
            KernelAction replaced{};
            status = Raw(__NR_rt_sigaction, signal, reinterpret_cast<long>(&action), reinterpret_cast<long>(&replaced),
                    sizeof(std::uint64_t));
            if (status < 0) return static_cast<int>(-status);
            if (!SameKernelAction(replaced, actions[signal])) return EOWNERDEAD;
            changed |= std::uint64_t{1} << (signal - 1);
        }
        return 0;
    }

    int Restore() const {
        int error = 0;
        for (int signal = 1; signal < NSIG; ++signal) {
            if ((changed & (std::uint64_t{1} << (signal - 1))) == 0) continue;
            KernelAction expected = actions[signal], current{};
            expected.mask &= ~(std::uint64_t{1} << (SIGSYS - 1));
            long status = Raw(__NR_rt_sigaction, signal, 0, reinterpret_cast<long>(&current), sizeof(std::uint64_t));
            if (status != 0 || !SameKernelAction(current, expected)) { error = EOWNERDEAD; continue; }
            status = Raw(__NR_rt_sigaction, signal,
                    reinterpret_cast<long>(&actions[signal]), reinterpret_cast<long>(&current), sizeof(std::uint64_t));
            if (status != 0 || !SameKernelAction(current, expected)) error = EOWNERDEAD;
        }
        return error;
    }
};

long SignalCall(Request& r) {
    if (r.number == __NR_rt_sigaction && r.args[1] != 0 && r.args[3] == 8) {
        if (r.args[0] == SIGSYS) return -EBUSY;
        KernelAction action{};
        if (CopyMemory(&action, reinterpret_cast<void*>(r.args[1]), sizeof(action), false)
                != sizeof(action)) return -EFAULT;
        action.mask &= ~(std::uint64_t{1} << (SIGSYS - 1));
        return Raw(r.number, r.args[0], reinterpret_cast<long>(&action), r.args[2], r.args[3]);
    }
    if (r.number == __NR_rt_sigprocmask) {
        if (r.args[3] != 8) return -EINVAL;
        // rt_sigreturn restores this saved mask. The live mask belongs to our SIGSYS handler.
        std::uint64_t previous = *r.interrupted_mask;
        std::uint64_t next = previous;
        if (r.args[1] != 0) {
            std::uint64_t requested;
            if (CopyMemory(&requested, reinterpret_cast<void*>(r.args[1]), sizeof(requested), false)
                    != sizeof(requested)) return -EFAULT;
            if (r.args[0] == SIG_BLOCK) next |= requested;
            else if (r.args[0] == SIG_UNBLOCK) next &= ~requested;
            else if (r.args[0] == SIG_SETMASK) next = requested;
            else return -EINVAL;
            next &= ~((std::uint64_t{1} << (SIGSYS - 1)) | (std::uint64_t{1} << (SIGKILL - 1))
                    | (std::uint64_t{1} << (SIGSTOP - 1)));
        }
        if (r.args[2] != 0 && CopyMemory(&previous, reinterpret_cast<void*>(r.args[2]),
                sizeof(previous), true) != sizeof(previous)) return -EFAULT;
        *r.interrupted_mask = next;
        return 0;
    }
    return Raw(r.number, r.args[0], r.args[1], r.args[2], r.args[3], r.args[4], r.args[5]);
}

void HandleSignal(int signal, siginfo_t* information, void* context) {
    if (information->si_code != 1 || information->si_errno != kTrapTag
            || information->si_arch != kArchitecture) {
        if (previous_sigsys.sa_handler != SIG_DFL && previous_sigsys.sa_handler != SIG_IGN) {
            if (previous_sigsys.sa_flags & SA_SIGINFO) previous_sigsys.sa_sigaction(signal, information, context);
            else previous_sigsys.sa_handler(signal);
            return;
        }
        Raw(__NR_exit_group, 128 + SIGSYS);
        __builtin_unreachable();
    }
    auto* saved = static_cast<ucontext_t*>(context);
    Request request{};
    request.number = information->si_syscall;
    request.interrupted_mask = reinterpret_cast<std::uint64_t*>(&saved->uc_sigmask);
#if defined(__aarch64__)
    for (int i = 0; i < 6; ++i) request.args[i] = saved->uc_mcontext.regs[i];
#else
    constexpr int argument_registers[]{REG_RDI, REG_RSI, REG_RDX, REG_R10, REG_R8, REG_R9};
    for (int i = 0; i < 6; ++i) request.args[i] = saved->uc_mcontext.gregs[argument_registers[i]];
#endif
    // A nested trap must lease a different stack; exhaustion retains the original mmap path.
    int slot = AcquireSignalStack();
    long result;
    if (slot >= 0) {
        auto top = signal_stack_pool + (static_cast<unsigned>(slot) + 1) * kSignalStackSize - 16;
        result = mybox_dispatch_on_stack(&request, reinterpret_cast<void*>(top));
        // The assembly bridge has restored the original stack before this slot becomes reusable.
        signal_stack_leases.fetch_and(~(1u << slot), std::memory_order_release);
    } else {
        fallback_requests.fetch_add(1, std::memory_order_relaxed);
        long allocation = Raw(__NR_mmap, 0, kSignalStackSize, PROT_READ | PROT_WRITE,
                MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        result = allocation;
        if (allocation < -4095 || allocation >= 0) {
            result = mybox_dispatch_on_stack(&request, reinterpret_cast<void*>(allocation + kSignalStackSize - 16));
            Raw(__NR_munmap, allocation, kSignalStackSize);
        } else {
            fallback_failures.fetch_add(1, std::memory_order_relaxed);
        }
    }
#if defined(__aarch64__)
    saved->uc_mcontext.regs[0] = result;
#else
    saved->uc_mcontext.gregs[REG_RAX] = result;
#endif
}
}

extern "C" __attribute__((visibility("hidden"))) long mybox_dispatch(Request* request) {
    Request r = *request;
    if (r.number == __NR_rt_sigaction || r.number == __NR_rt_sigprocmask) return SignalCall(r);
    // An exec would retain the filter but discard its handler. Do not pretend that it can work.
    if (r.number == __NR_execve || r.number == __NR_execveat) return -EOPNOTSUPP;
    char original[mybox::kPathSize], first[mybox::kPathSize], second[mybox::kPathSize];
    for (const PathCall& call : path_calls) {
        if (call.number != r.number) continue;
        // utimensat(fd, nullptr, ...) is a descriptor operation; preserve that platform contract.
        if (r.number == __NR_utimensat && r.args[1] == 0) break;
        int error = ReadPath(r.args[call.first], original);
        if (error < 0) return error;
        error = paths.Rewrite(original, first);
        if (error < 0) return error;
        r.args[call.first] = reinterpret_cast<long>(first);
        if (call.second >= 0) {
            error = ReadPath(r.args[call.second], original);
            if (error < 0) return error;
            error = paths.Rewrite(original, second);
            if (error < 0) return error;
            r.args[call.second] = reinterpret_cast<long>(second);
        }
        if (IsReadlink(r.number)) return Readlink(r);
        break;
    }
    return Raw(r.number, r.args[0], r.args[1], r.args[2], r.args[3], r.args[4], r.args[5]);
}

namespace mybox {
PathRules& ConfiguredPaths() { return paths; }
bool IoEnabled() { return enabled.load(std::memory_order_acquire); }
IoStackStats GetIoStackStats() {
    return {signal_stack_pool_available ? kSignalStackSlots : 0,
            static_cast<unsigned>(__builtin_popcount(signal_stack_leases.load(std::memory_order_relaxed))),
            signal_stack_pool_error, fallback_requests.load(std::memory_order_relaxed),
            fallback_failures.load(std::memory_order_relaxed)};
}

int InstallIo() {
    if (enabled.load(std::memory_order_acquire)) return 0;
    if (attempted.exchange(true)) return EALREADY;
    if (paths.size() == 0) return EINVAL;
    page_size = static_cast<std::size_t>(sysconf(_SC_PAGESIZE));
    if (page_size < 4096 || page_size > 65536) return ENOSYS;
    // Verify the safe pointer-copy mechanism before making any irreversible filter change.
    char from = 'v', to = 0;
    long copied = CopyMemory(&to, &from, 1, false);
    if (copied != 1 || to != from) return copied < 0 ? static_cast<int>(-copied) : EFAULT;
    to = 'm';
    copied = CopyMemory(&to, &from, 1, true);
    if (copied != 1 || from != to) return copied < 0 ? static_cast<int>(-copied) : EFAULT;
    auto begin = reinterpret_cast<std::uintptr_t>(&mybox_raw_syscall);
    auto end = reinterpret_cast<std::uintptr_t>(mybox_raw_syscall_end);
    if (end <= begin || (begin >> 32) != (end >> 32)) return ENOSYS;
    sock_filter filter[160]{};
    unsigned count = 0;
    auto statement = [&](unsigned short code, unsigned value) { filter[count++] = {code, 0, 0, value}; };
    auto jump = [&](unsigned short code, unsigned value, unsigned char yes, unsigned char no) {
        filter[count++] = {code, yes, no, value};
    };
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, arch));
    jump(BPF_JMP | BPF_JEQ | BPF_K, kArchitecture, 1, 0);
    statement(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, instruction_pointer) + 4);
    jump(BPF_JMP | BPF_JEQ | BPF_K, begin >> 32, 0, 4);
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, instruction_pointer));
    jump(BPF_JMP | BPF_JGE | BPF_K, static_cast<unsigned>(begin), 0, 2);
    jump(BPF_JMP | BPF_JGE | BPF_K, static_cast<unsigned>(end), 1, 0);
    statement(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr));
    auto trap = [&](unsigned nr) {
        jump(BPF_JMP | BPF_JEQ | BPF_K, nr, 0, 1);
        statement(BPF_RET | BPF_K, SECCOMP_RET_TRAP | kTrapTag);
    };
    for (const auto& call : path_calls) trap(call.number);
    trap(__NR_rt_sigaction); trap(__NR_rt_sigprocmask); trap(__NR_execve); trap(__NR_execveat);
    statement(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    sock_fprog program{static_cast<unsigned short>(count), filter};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return errno;
    SavedSignalActions old_actions;
    int action_error = old_actions.UnblockTrapInHandlers();
    auto restore_actions = [&](int error) {
        int restore_error = old_actions.Restore();
        return restore_error != 0 ? EOWNERDEAD : error;
    };
    if (action_error != 0) return restore_actions(action_error);
    struct sigaction action{};
    action.sa_sigaction = HandleSignal;
    // Another signal can interrupt the raw syscall and itself call an intercepted syscall.
    // Leaving SIGSYS blocked would make the kernel reset its handler to SIG_DFL and kill us.
    action.sa_flags = SA_SIGINFO | SA_RESTART | SA_NODEFER;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGSYS, &action, &previous_sigsys) != 0) return restore_actions(errno);
    KernelAction installed_sigsys{};
    if (Raw(__NR_rt_sigaction, SIGSYS, 0, reinterpret_cast<long>(&installed_sigsys), 8) != 0
            || installed_sigsys.handler != reinterpret_cast<std::uintptr_t>(&HandleSignal)) return EOWNERDEAD;
    sigset_t unblocked, previous_mask;
    sigemptyset(&unblocked); sigaddset(&unblocked, SIGSYS);
    int mask_error = pthread_sigmask(SIG_UNBLOCK, &unblocked, &previous_mask);
    if (mask_error != 0) {
        if (sigaction(SIGSYS, &previous_sigsys, nullptr) != 0) mask_error = EOWNERDEAD;
        return restore_actions(mask_error);
    }
    // Reserve once without prefaulting: only pages reached by actual dispatches become resident.
    long pool = Raw(__NR_mmap, 0, kSignalStackSlots * kSignalStackSize, PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    signal_stack_pool_available = pool < -4095 || pool >= 0;
    signal_stack_pool_error = signal_stack_pool_available ? 0 : static_cast<int>(-pool);
    if (signal_stack_pool_available) signal_stack_pool = static_cast<std::uintptr_t>(pool);
    int guard_error = BeginInstallGuard();
    // EOWNERDEAD means cleanup could not establish a reusable process. The guest bootstrap must retire it.
    if (guard_error == EOWNERDEAD) return EOWNERDEAD;
    if (guard_error == 0) {
        KernelAction current;
        if (Raw(__NR_rt_sigaction, SIGSYS, 0, reinterpret_cast<long>(&current), 8) != 0
                || !SameKernelAction(current, installed_sigsys)) {
            FinishInstallGuard(false);
            return EOWNERDEAD;
        }
    }
    long status = guard_error == 0
            ? Raw(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC,
                    reinterpret_cast<long>(&program))
            : -guard_error;
    if (guard_error == 0) {
        if (status == 0) enabled.store(true, std::memory_order_release);
        if (FinishInstallGuard(status == 0) != 0) return EOWNERDEAD;
    }
    if (status != 0) {
        if (signal_stack_pool_available) {
            Raw(__NR_munmap, pool, kSignalStackSlots * kSignalStackSize);
            signal_stack_pool_available = false;
        }
        // A positive result is the TID that prevented TSYNC; it is not success or errno.
        int error = status < 0 ? static_cast<int>(-status) : EBUSY;
        KernelAction current{};
        struct sigaction replaced{};
        if (Raw(__NR_rt_sigaction, SIGSYS, 0, reinterpret_cast<long>(&current), 8) != 0
                || !SameKernelAction(current, installed_sigsys)) error = EOWNERDEAD;
        else if (sigaction(SIGSYS, &previous_sigsys, &replaced) != 0
                || !SameKernelAction(KernelActionFrom(replaced), installed_sigsys)) error = EOWNERDEAD;
        mask_error = pthread_sigmask(SIG_SETMASK, &previous_mask, nullptr);
        if (mask_error != 0) error = EOWNERDEAD;
        return restore_actions(error);
    }
    return 0;
}
}
