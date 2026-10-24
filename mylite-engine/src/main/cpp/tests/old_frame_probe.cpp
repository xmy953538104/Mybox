// SPDX-License-Identifier: Apache-2.0
// Independent experiment: no product I/O engine, Java, or original binary is linked.
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <signal.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <sys/uio.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

extern "C" {
long old_probe_raw(long, long, long, long, long, long, long);
long old_probe_getpid();
long old_probe_invoke(long, long, long, std::uintptr_t*);
void old_probe_restorer();
void old_probe_handoff();
void old_probe_neighbor_restorer();
void old_probe_readonly_restorer();
void old_probe_bad_restorer();
void old_probe_overflow_restorer();
void old_probe_wrong_nr();
extern char old_probe_restorer_pc[], old_probe_handoff_pc[], old_probe_getpid_pc[];
extern char old_probe_neighbor_pc[], old_probe_invoke_getpid_pc[];
__attribute__((visibility("hidden"))) std::uint64_t old_probe_page_mask;
__attribute__((visibility("hidden"))) std::uint64_t old_probe_page_size;
}

namespace {
static_assert(sizeof(void*) == 8 && sizeof(long) == 8 && SIGSYS == 31);
constexpr std::uint64_t kSys = std::uint64_t{1} << (SIGSYS - 1);
constexpr std::uint64_t kOther = std::uint64_t{1} << (SIGTERM - 1);
constexpr unsigned long kRestorer = 0x04000000;
constexpr unsigned kTag = 0x4f46;
constexpr int kUnavailable = 77, kFailure = 80, kOverflow = 84, kReadFailure = 85, kWriteFailure = 86;
#if defined(__aarch64__)
constexpr unsigned kArch = AUDIT_ARCH_AARCH64;
constexpr std::uintptr_t kMaskOffset = 168;
constexpr const char* kAbi = "aarch64";
static_assert(offsetof(ucontext_t, uc_sigmask) == 40);
static_assert(offsetof(ucontext_t, uc_mcontext.sp) == 432);
#elif defined(__x86_64__) && !defined(__ILP32__)
constexpr unsigned kArch = AUDIT_ARCH_X86_64;
constexpr std::uintptr_t kMaskOffset = 296;
constexpr const char* kAbi = "x86_64";
static_assert(offsetof(ucontext_t, uc_sigmask) == 296);
static_assert(offsetof(ucontext_t, uc_mcontext.gregs[REG_RSP]) == 160);
#else
#error Only native LP64 ARM64 and x86_64 are reviewed
#endif

enum Mode { Baseline, Calibration, WrongNr, Control, Candidate, Worker, Nested, Alt,
    NestedAlt, Neighbor, BadControl, BadRead, Overflow, ReadOnly };
enum Stage { Start, MemoryProbe, Actions, Filter, UserEntry, GetpidTrap, ReturnTrap,
    CopyRead, CopyWrite, Returned, Complete };
struct KernelAction { std::uintptr_t handler; unsigned long flags; std::uintptr_t restorer; std::uint64_t mask; };
static_assert(sizeof(KernelAction) == 32);
struct Report {
    int stage, error, filter_installed, entered, getpid_traps, other_getpid_traps, return_traps;
    int mask_checks, read_ok, write_ok, alt_entries, sp_checks, register_bits, neighbor_seen;
    long pid, tid;
    std::uintptr_t expected_sp, getpid_pc, getpid_sp, return_pc, return_sp, copy_address;
    std::uintptr_t restorer, outer_restorer, alt_begin, alt_end;
    unsigned long action_flags;
    std::uint64_t action_mask, final_mask, before[4], after[4];
    std::uintptr_t user_masks[4];
};
volatile Report* report;
Mode mode;
long process_id;

long Raw(long number, long a = 0, long b = 0, long c = 0, long d = 0, long e = 0, long f = 0) {
    return old_probe_raw(number, a, b, c, d, e, f);
}
[[noreturn]] void Exit(int status) {
    Raw(__NR_exit_group, status);
    __builtin_unreachable();
}
[[noreturn]] void Fail(int error, int status = kFailure) {
    report->error = error;
    Exit(status);
}
void Require(bool condition, int error = EPROTO) { if (!condition) Fail(error); }
std::uintptr_t Sp(ucontext_t* context) {
#if defined(__aarch64__)
    return context->uc_mcontext.sp;
#else
    return static_cast<std::uintptr_t>(context->uc_mcontext.gregs[REG_RSP]);
#endif
}
std::uintptr_t Pc(ucontext_t* context) {
#if defined(__aarch64__)
    return context->uc_mcontext.pc;
#else
    return static_cast<std::uintptr_t>(context->uc_mcontext.gregs[REG_RIP]);
#endif
}
void Result(ucontext_t* context, long value) {
#if defined(__aarch64__)
    context->uc_mcontext.regs[0] = value;
#else
    context->uc_mcontext.gregs[REG_RAX] = value;
#endif
}
void Handoff(ucontext_t* context) {
#if defined(__aarch64__)
    context->uc_mcontext.pc = reinterpret_cast<std::uintptr_t>(&old_probe_handoff);
#else
    context->uc_mcontext.gregs[REG_RIP] = reinterpret_cast<std::uintptr_t>(&old_probe_handoff);
#endif
    // Only our immediate kernel-created frame is touched here. Preserve its original SP.
    *reinterpret_cast<std::uint64_t*>(&context->uc_sigmask) &= ~kSys;
}
int Copy(void* local, std::uintptr_t remote, bool write) {
    iovec here{local, sizeof(std::uint64_t)}, there{reinterpret_cast<void*>(remote), sizeof(std::uint64_t)};
    long count = Raw(write ? __NR_process_vm_writev : __NR_process_vm_readv, process_id,
            reinterpret_cast<long>(&here), 1, reinterpret_cast<long>(&there), 1, 0);
    return count == 8 ? 0 : count < 0 ? static_cast<int>(-count) : EFAULT;
}
void Trap(int signal, siginfo_t* information, void* opaque) {
    auto* context = static_cast<ucontext_t*>(opaque);
    Require(signal == SIGSYS && information->si_code == 1 && information->si_errno == kTag
            && information->si_arch == kArch);
    Require(reinterpret_cast<std::uintptr_t>(information->si_call_addr) == Pc(context));
    if (information->si_syscall == __NR_getpid) {
        report->stage = GetpidTrap;
        report->getpid_pc = Pc(context); report->getpid_sp = Sp(context);
        if (mode == WrongNr) {
            Require(Pc(context) == reinterpret_cast<std::uintptr_t>(old_probe_restorer_pc));
            ++report->getpid_traps;
            report->stage = Complete;
            Exit(0);
        }
        if (Pc(context) == reinterpret_cast<std::uintptr_t>(old_probe_getpid_pc)
                || Pc(context) == reinterpret_cast<std::uintptr_t>(old_probe_invoke_getpid_pc)) {
            ++report->getpid_traps;
        } else {
            ++report->other_getpid_traps;
        }
        Result(context, process_id);
        return;
    }
    Require(information->si_syscall == __NR_rt_sigreturn);
    report->stage = ReturnTrap;
    const auto pointer = Sp(context);
    report->return_pc = Pc(context); report->return_sp = pointer;
    const int index = report->return_traps++;
    Require(index < 4);
    // If either private return site reaches this handler the filter calibration failed.
    Require(Pc(context) != reinterpret_cast<std::uintptr_t>(old_probe_restorer_pc)
            && Pc(context) != reinterpret_cast<std::uintptr_t>(old_probe_handoff_pc));
    if (Pc(context) == reinterpret_cast<std::uintptr_t>(old_probe_neighbor_pc)) ++report->neighbor_seen;
    if (pointer > std::numeric_limits<std::uintptr_t>::max() - kMaskOffset) Fail(EOVERFLOW, kOverflow);
#if defined(__aarch64__)
    if ((pointer & 15) != 0) Fail(EINVAL, kOverflow);
#endif
    const auto address = pointer + kMaskOffset;
    report->copy_address = address;
    std::uint64_t mask;
    report->stage = CopyRead;
    int error = Copy(&mask, address, false);
    if (error) Fail(error, kReadFailure);
    ++report->read_ok;
    report->before[index] = mask;
    const std::uint64_t original = mask;
    mask &= ~kSys;
    report->stage = CopyWrite;
    error = Copy(&mask, address, true);
    if (error) Fail(error, kWriteFailure);
    ++report->write_ok;
    std::uint64_t checked;
    error = Copy(&checked, address, false);
    if (error) Fail(error);
    report->after[index] = checked;
    Require(checked == mask && (original & ~kSys) == checked);
    ++report->mask_checks;
    const int frame = report->entered - 1 - index;
    Require(frame >= 0 && address == report->user_masks[frame]);
    Handoff(context);
}

void InstallFilter(bool returns) {
    report->stage = Filter;
    sock_filter instructions[24];
    unsigned count = 0;
    auto statement = [&](unsigned short code, unsigned value) { instructions[count++] = {code, 0, 0, value}; };
    auto jump = [&](unsigned short code, unsigned value, unsigned char yes, unsigned char no) {
        instructions[count++] = {code, yes, no, value};
    };
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, arch));
    jump(BPF_JMP | BPF_JEQ | BPF_K, kArch, 1, 0);
    statement(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS);
    statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr));
    jump(BPF_JMP | BPF_JEQ | BPF_K, __NR_getpid, 0, 1);
    statement(BPF_RET | BPF_K, SECCOMP_RET_TRAP | kTag);
    if (returns) {
        jump(BPF_JMP | BPF_JEQ | BPF_K, __NR_rt_sigreturn, 1, 0);
        statement(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
        const std::uintptr_t sites[]{reinterpret_cast<std::uintptr_t>(old_probe_restorer_pc),
                reinterpret_cast<std::uintptr_t>(old_probe_handoff_pc)};
        for (const auto site : sites) {
            statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, instruction_pointer) + 4);
            jump(BPF_JMP | BPF_JEQ | BPF_K, static_cast<unsigned>(site >> 32), 0, 3);
            statement(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, instruction_pointer));
            jump(BPF_JMP | BPF_JEQ | BPF_K, static_cast<unsigned>(site), 0, 1);
            statement(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
        }
        statement(BPF_RET | BPF_K, SECCOMP_RET_TRAP | kTag);
    } else {
        statement(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    }
    sock_fprog program{static_cast<unsigned short>(count), instructions};
    long result = Raw(__NR_prctl, PR_SET_NO_NEW_PRIVS, 1);
    if (result < 0) Fail(static_cast<int>(-result), kUnavailable);
    // Per-thread installation deliberately excludes the production TSYNC/guard problem.
    result = Raw(__NR_seccomp, SECCOMP_SET_MODE_FILTER, 0, reinterpret_cast<long>(&program));
    if (result < 0) Fail(static_cast<int>(-result), kUnavailable);
    report->filter_installed = 1;
}

bool IsNested() { return mode == Nested || mode == NestedAlt; }
bool OnAlt() { return mode == Alt || mode == NestedAlt; }
void UserSignal(int signal, siginfo_t*, void* opaque) {
    report->stage = UserEntry;
    auto* context = static_cast<ucontext_t*>(opaque);
    const int index = report->entered++;
    Require(index < 4);
    report->user_masks[index] = reinterpret_cast<std::uintptr_t>(&context->uc_sigmask);
    if (OnAlt()) {
        const auto location = reinterpret_cast<std::uintptr_t>(context);
        Require(location >= report->alt_begin && location < report->alt_end);
        ++report->alt_entries;
    }
    if (signal == SIGUSR1 && mode != Calibration) {
        Require(Sp(context) == report->expected_sp);
        ++report->sp_checks;
    }
    if (mode == Calibration) return;
    Require((*reinterpret_cast<std::uint64_t*>(&context->uc_sigmask) & (kSys | kOther)) == (kSys | kOther));
    if (IsNested() && signal == SIGUSR1) {
        Require(Raw(__NR_tgkill, process_id, report->tid, SIGUSR2) == 0);
        Require(report->entered == 2);
        return;
    }
    const std::uint64_t unblock = kSys;
    Require(Raw(__NR_rt_sigprocmask, SIG_UNBLOCK, reinterpret_cast<long>(&unblock), 0, 8) == 0);
    if (mode != Baseline) InstallFilter(mode != Control && mode != BadControl);
}

void SetRawAction(int signal, const KernelAction& action) {
    long status = Raw(__NR_rt_sigaction, signal, reinterpret_cast<long>(&action), 0, 8);
    if (status != 0) Fail(static_cast<int>(-status), kUnavailable);
    KernelAction actual;
    status = Raw(__NR_rt_sigaction, signal, 0, reinterpret_cast<long>(&actual), 8);
    if (status != 0) Fail(static_cast<int>(-status), kUnavailable);
    Require(actual.handler == action.handler && actual.flags == action.flags
            && actual.restorer == action.restorer && actual.mask == action.mask);
    if (signal == SIGSYS) {
        report->restorer = actual.restorer;
        report->action_flags = actual.flags;
        report->action_mask = actual.mask;
    }
}
void Setup() {
    report->stage = MemoryProbe;
    process_id = Raw(__NR_getpid);
    report->pid = process_id;
    std::uint64_t local = 0x1234, copied = 0;
    int error = Copy(&copied, reinterpret_cast<std::uintptr_t>(&local), false);
    if (error) Fail(error, kUnavailable);
    Require(copied == local);
    copied = 0x5678;
    error = Copy(&copied, reinterpret_cast<std::uintptr_t>(&local), true);
    if (error) Fail(error, kUnavailable);
    Require(local == copied);
    const bool trap_alt = mode == BadControl || mode == BadRead || mode == Overflow || mode == ReadOnly;
    if (OnAlt() || trap_alt) {
        void* memory = mmap(nullptr, 1024 * 1024, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (memory == MAP_FAILED) Fail(errno, kUnavailable);
        stack_t stack{}; stack.ss_sp = memory; stack.ss_size = 1024 * 1024;
        if (sigaltstack(&stack, nullptr) != 0) Fail(errno, kUnavailable);
        report->alt_begin = reinterpret_cast<std::uintptr_t>(memory);
        report->alt_end = report->alt_begin + stack.ss_size;
    }
    report->stage = Actions;
    KernelAction sys{reinterpret_cast<std::uintptr_t>(&Trap),
            SA_SIGINFO | SA_NODEFER | kRestorer | (trap_alt ? SA_ONSTACK : 0ul),
            reinterpret_cast<std::uintptr_t>(&old_probe_restorer), 0};
    SetRawAction(SIGSYS, sys);
    struct sigaction user{};
    user.sa_sigaction = UserSignal;
    user.sa_flags = SA_SIGINFO | (OnAlt() ? SA_ONSTACK : 0);
    sigemptyset(&user.sa_mask); sigaddset(&user.sa_mask, SIGWINCH);
    if (sigaction(SIGUSR1, &user, nullptr) != 0 || sigaction(SIGUSR2, &user, nullptr) != 0) Fail(errno, kUnavailable);
    KernelAction outer;
    long result = Raw(__NR_rt_sigaction, SIGUSR1, 0, reinterpret_cast<long>(&outer), 8);
    if (result != 0) Fail(static_cast<int>(-result), kUnavailable);
    void (*restorer)() = nullptr;
    if (mode == Neighbor) restorer = old_probe_neighbor_restorer;
    if (mode == BadRead || mode == BadControl) restorer = old_probe_bad_restorer;
    if (mode == Overflow) restorer = old_probe_overflow_restorer;
    if (mode == ReadOnly) restorer = old_probe_readonly_restorer;
    if (restorer) {
        outer.flags |= kRestorer;
        outer.restorer = reinterpret_cast<std::uintptr_t>(restorer);
        SetRawAction(SIGUSR1, outer);
    }
    report->outer_restorer = outer.restorer;
}
void Flow() {
    report->tid = Raw(__NR_gettid);
    const std::uint64_t initial = kOther | ((mode == Calibration || mode == WrongNr) ? 0 : kSys);
    Require(Raw(__NR_rt_sigprocmask, SIG_SETMASK, reinterpret_cast<long>(&initial), 0, 8) == 0);
    if (mode == Calibration || mode == WrongNr) {
        InstallFilter(true);
        if (mode == WrongNr) { old_probe_wrong_nr(); Fail(EPROTO); }
        Require(old_probe_getpid() == process_id);
        Require(Raw(__NR_tgkill, process_id, report->tid, SIGUSR1) == 0);
        Require(old_probe_getpid() == process_id);
        Require(report->getpid_traps == 2 && report->return_traps == 1);
    } else {
        report->register_bits = static_cast<int>(old_probe_invoke(process_id, report->tid, SIGUSR1,
                const_cast<std::uintptr_t*>(&report->expected_sp)));
        Require(report->register_bits == 0);
        Require(mode != Control && mode != BadControl && mode != BadRead && mode != Overflow && mode != ReadOnly);
        Require(report->sp_checks == 1);
        Require(report->return_traps == (mode == Baseline ? 0 : IsNested() ? 2 : 1));
        Require(report->getpid_traps == (mode == Baseline ? 0 : 1));
        if (mode == Neighbor) Require(report->neighbor_seen == 1);
    }
    report->stage = Returned;
    std::uint64_t actual;
    Require(Raw(__NR_rt_sigprocmask, SIG_SETMASK, 0, reinterpret_cast<long>(&actual), 8) == 0);
    report->final_mask = actual;
    Require(actual == (kOther | (mode == Baseline ? kSys : 0)));
    Require(report->read_ok == report->return_traps && report->write_ok == report->return_traps
            && report->mask_checks == report->return_traps);
    if (OnAlt()) {
        stack_t actual_stack;
        Require(sigaltstack(nullptr, &actual_stack) == 0 && !(actual_stack.ss_flags & SS_ONSTACK));
        Require(reinterpret_cast<std::uintptr_t>(actual_stack.ss_sp) == report->alt_begin);
        Require(report->alt_entries == (IsNested() ? 2 : 1));
    }
    report->stage = Complete;
}
void* Thread(void*) { Flow(); return nullptr; }
[[noreturn]] void Run(Mode next) {
    alarm(3); // Independent process deadline even if the supervising wait fails.
    mode = next;
    Setup();
    if (mode == Worker) {
        pthread_t worker;
        int error = pthread_create(&worker, nullptr, Thread, nullptr);
        if (error) Fail(error, kUnavailable);
        error = pthread_join(worker, nullptr);
        if (error) Fail(error);
        Require(report->tid != process_id);
    } else Flow();
    Exit(0);
}

long long Nanos() {
    timespec time{};
    if (clock_gettime(CLOCK_MONOTONIC, &time) != 0) return -1;
    return static_cast<long long>(time.tv_sec) * 1000000000 + time.tv_nsec;
}
struct Case { const char* name; Mode mode; int exit, signal; };
const Case cases[]{
    {"unfiltered-old-frame-control", Baseline, 0, 0},
    {"raw-action-pc-sp-calibration", Calibration, 0, 0},
    {"restorer-address-wrong-number-traps", WrongNr, 0, 0},
    {"filtered-old-frame-control-sigsys", Control, -1, SIGSYS},
    {"two-return-main-register-fp-mask", Candidate, 0, 0},
    {"two-return-ready-worker", Worker, 0, 0},
    {"two-return-two-older-frames", Nested, 0, 0},
    {"two-return-altstack", Alt, 0, 0},
    {"two-return-already-on-altstack", NestedAlt, 0, 0},
    {"adjacent-nonexempt-restorer-traps", Neighbor, 0, 0},
    {"bad-frame-kernel-control-sigsegv", BadControl, -1, SIGSEGV},
    {"bad-frame-safe-read-failure", BadRead, kReadFailure, 0},
    {"frame-address-overflow-failure", Overflow, kOverflow, 0},
    {"readonly-old-mask-safe-write-failure", ReadOnly, kWriteFailure, 0},
};
const char* StageName(int stage) {
    const char* names[]{"start", "memory-probe", "raw-actions", "filter", "user-entry", "getpid-trap",
        "return-trap", "copy-read", "copy-write", "returned", "complete"};
    return stage >= 0 && stage <= Complete ? names[stage] : "invalid";
}
void JsonString(const char* string) {
    std::putchar('"');
    for (const auto* p = reinterpret_cast<const unsigned char*>(string); *p; ++p) {
        if (*p == '"' || *p == '\\') { std::putchar('\\'); std::putchar(*p); }
        else if (*p < 32) std::printf("\\u%04x", *p);
        else std::putchar(*p);
    }
    std::putchar('"');
}
}

int main() {
    setvbuf(stdout, nullptr, _IOLBF, 0);
    rlimit no_core{0, 0};
    if (setrlimit(RLIMIT_CORE, &no_core) != 0) return 2;
    struct sigaction default_action{};
    default_action.sa_handler = SIG_DFL;
    sigemptyset(&default_action.sa_mask);
    if (sigaction(SIGALRM, &default_action, nullptr) != 0
            || sigaction(SIGCHLD, &default_action, nullptr) != 0) return 2;
    const long page = sysconf(_SC_PAGESIZE);
    if (page <= 0 || (page & (page - 1)) != 0) return 2;
    old_probe_page_size = static_cast<std::uint64_t>(page);
    old_probe_page_mask = ~(old_probe_page_size - 1);
    utsname system{};
    if (uname(&system) != 0) return 2;
    unsigned long shstk = 0;
    long shstk_result = -ENOSYS;
#if defined(__x86_64__) && defined(__NR_arch_prctl)
    shstk_result = Raw(__NR_arch_prctl, 0x5005, reinterpret_cast<long>(&shstk)); // ARCH_SHSTK_STATUS
#endif
    std::printf("{\"type\":\"environment\",\"abi\":\"%s\",\"kernel\":", kAbi);
    JsonString(system.release);
    std::printf(",\"uid\":%ld,\"euid\":%ld,\"page_size\":%ld,\"hwcap\":%lu,\"hwcap2\":%lu,"
            "\"seccomp_before\":%ld,\"shstk_status_rc\":%ld,\"shstk_status\":%lu,"
            "\"protection_profiles_verified\":false,\"production_engine_linked\":false,\"tsync_used\":false}\n",
            static_cast<long>(getuid()), static_cast<long>(geteuid()), page, getauxval(AT_HWCAP), getauxval(AT_HWCAP2),
            Raw(__NR_prctl, PR_GET_SECCOMP), shstk_result, shstk);
    void* memory = mmap(nullptr, sizeof(Report), PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (memory == MAP_FAILED) return 2;
    report = static_cast<Report*>(memory);
    unsigned failed = 0;
    bool cleanup_lost = false;
    for (const auto& test : cases) {
        if (cleanup_lost) {
            ++failed;
            std::printf("{\"type\":\"case\",\"name\":\"%s\",\"matched\":false,\"not_run\":true,"
                    "\"reason\":\"previous-child-cleanup-unconfirmed\"}\n", test.name);
            continue;
        }
        std::memset(memory, 0, sizeof(Report));
        const long long started = Nanos();
        if (started < 0) return 2;
        pid_t child = fork();
        if (child == 0) Run(test.mode);
        int status = 0, wait_error = 0;
        bool timeout = false, reaped = false;
        if (child < 0) wait_error = errno;
        while (child > 0 && !reaped) {
            pid_t result = waitpid(child, &status, WNOHANG);
            if (result == child) { reaped = true; break; }
            if (result < 0 && errno != EINTR) { wait_error = errno; break; }
            const long long now = Nanos();
            if (now < 0 || now - started >= 3000000000LL) {
                timeout = true;
                if (kill(child, SIGKILL) != 0 && errno != ESRCH) wait_error = errno;
                const long long reap_started = Nanos();
                while (reap_started >= 0) {
                    result = waitpid(child, &status, WNOHANG);
                    if (result == child) { reaped = true; break; }
                    if (result < 0 && errno != EINTR) { wait_error = errno; break; }
                    const long long reap_now = Nanos();
                    if (reap_now < 0 || reap_now - reap_started >= 1000000000LL) break;
                    timespec pause{0, 1000000};
                    nanosleep(&pause, nullptr);
                }
                if (!reaped && !wait_error) wait_error = ETIMEDOUT;
                break;
            }
            timespec pause{0, 1000000};
            nanosleep(&pause, nullptr);
        }
        const int exited = reaped && WIFEXITED(status) ? WEXITSTATUS(status) : -1;
        const int signal = reaped && WIFSIGNALED(status) ? WTERMSIG(status) : 0;
        if (signal == SIGALRM) timeout = true;
        bool matched = reaped && !timeout && !wait_error && exited == test.exit && signal == test.signal;
        if (test.mode != Baseline) matched = matched && report->filter_installed;
        if (test.mode == BadRead) matched = matched && report->stage == CopyRead && report->error == EFAULT;
        if (test.mode == Overflow) matched = matched && report->stage == ReturnTrap && report->error == EOVERFLOW;
        if (test.mode == ReadOnly) matched = matched && report->stage == CopyWrite && report->error == EFAULT
                && report->read_ok == 1 && report->write_ok == 0 && (report->before[0] & kSys);
        if (test.exit == 0) matched = matched && report->stage == Complete;
        if (!matched) ++failed;
        std::printf("{\"type\":\"case\",\"name\":\"%s\",\"expected_exit\":%d,\"expected_signal\":%d,"
                "\"exit\":%d,\"signal\":%d,\"wait_status\":%d,\"timeout\":%s,\"reaped\":%s,\"wait_error\":%d,"
                "\"cleanup_confirmed\":%s,"
                "\"unavailable\":%s,\"matched\":%s,\"elapsed_ns\":%lld,\"stage\":\"%s\",\"errno\":%d,"
                "\"pid\":%ld,\"tid\":%ld,\"filter\":%d,\"getpid_traps\":%d,\"other_getpid_traps\":%d,"
                "\"return_traps\":%d,\"entered\":%d,\"mask_checks\":%d,\"read_ok\":%d,\"write_ok\":%d,"
                "\"register_bits\":%d,\"sp_checks\":%d,\"alt_entries\":%d,\"neighbor_seen\":%d,"
                "\"restorer\":\"0x%lx\",\"outer_restorer\":\"0x%lx\",\"action_flags\":\"0x%lx\","
                "\"action_mask\":\"0x%lx\",\"getpid_pc\":\"0x%lx\",\"getpid_sp\":\"0x%lx\","
                "\"return_pc\":\"0x%lx\",\"return_sp\":\"0x%lx\",\"copy_address\":\"0x%lx\","
                "\"final_mask\":\"0x%lx\",\"before\":[\"0x%lx\",\"0x%lx\"],\"after\":[\"0x%lx\",\"0x%lx\"]}\n",
                test.name, test.exit, test.signal, exited, signal, status, timeout ? "true" : "false",
                reaped ? "true" : "false", wait_error, child < 0 || reaped ? "true" : "false",
                exited == kUnavailable ? "true" : "false",
                matched ? "true" : "false", Nanos() - started, StageName(report->stage), report->error,
                report->pid, report->tid, report->filter_installed, report->getpid_traps, report->other_getpid_traps,
                report->return_traps, report->entered, report->mask_checks, report->read_ok, report->write_ok,
                report->register_bits, report->sp_checks, report->alt_entries, report->neighbor_seen,
                static_cast<unsigned long>(report->restorer), static_cast<unsigned long>(report->outer_restorer),
                report->action_flags, static_cast<unsigned long>(report->action_mask),
                static_cast<unsigned long>(report->getpid_pc), static_cast<unsigned long>(report->getpid_sp),
                static_cast<unsigned long>(report->return_pc), static_cast<unsigned long>(report->return_sp),
                static_cast<unsigned long>(report->copy_address), static_cast<unsigned long>(report->final_mask),
                static_cast<unsigned long>(report->before[0]), static_cast<unsigned long>(report->before[1]),
                static_cast<unsigned long>(report->after[0]), static_cast<unsigned long>(report->after[1]));
        if (child > 0 && !reaped) cleanup_lost = true;
    }
    std::printf("{\"type\":\"summary\",\"cases\":%zu,\"failed\":%u,\"success\":%s,\"cleanup_confirmed\":%s,"
            "\"uncovered\":[\"inter-return-signal-rendezvous\",\"SS_AUTODISARM\",\"unwinder\","
            "\"CET-PAC-BTI-GCS-enabled-profiles\",\"SVE-SME-AVX-extended-state\",\"ART-sigchain\",\"engine-pool-leases\"]}\n",
            sizeof(cases) / sizeof(cases[0]), failed, failed == 0 ? "true" : "false", cleanup_lost ? "false" : "true");
    munmap(memory, sizeof(Report));
    return failed == 0 ? 0 : 1;
}
