# Mylite native I/O

The active `mylite-engine` has an independently written, source-built native I/O backend. It uses the Android/Linux kernel already on the device. It does not include the original VLite library or the inactive Bcore engine. Original ARM64 function analysis established the seccomp/SIGSYS design; x86_64 is a new port of that design.

## Startup and behavior

`NativeBridge.initializeGuest(Context, ApplicationInfo)` runs before the guest Application and providers are constructed. It accepts only a dedicated `hostPackage:engine_pN` process, validates the guest package and requires physical destinations beneath the host's storage. A process binds to one guest. The result exposes actual initialization status and errno; a failed backend prevents guest startup.

The Java layer supplies immutable mappings for the guest's `/data/data`, `/data/user/0`, device-protected data and native-library roots. The C++ table selects the longest directory prefix, respects path-component boundaries and leaves an already mapped destination unchanged. Relative paths retain normal kernel `dirfd` behavior.

The backend installs a seccomp filter with thread synchronization. Selected path calls trap to a SIGSYS handler, which rewrites pathname arguments and executes the real syscall through a narrow, linked assembly gateway. ARM64 and x86_64 use their own UAPI syscall numbers and saved register layouts. Results and errors come from the real syscall. The signal handler leases one of eight independent 64KiB stacks, with the original temporary mmap path retained for exhaustion/allocation failure. It does not allocate C++ objects or take locks. Kernel-mediated copying checks pathname pointers without dereferencing arbitrary guest pointers.

Covered call families include open/openat, stat/statx, access, directory creation/removal, rename, link/symlink, ownership/mode/time updates, truncate and chdir. Both paths are mapped for two-path operations. Readlink output is mapped back to a guest-visible root, preserving the caller's truncation size. Supported syscall variants depend on the ABI's headers and running kernel.

SIGSYS must remain available for dispatch. Later signal-mask and handler updates cannot block or replace it; saved interrupted-thread masks are updated so `rt_sigreturn` restores the requested mask. Calls that replace the SIGSYS handler fail with `EBUSY`. `execve` and `execveat` fail with `EOPNOTSUPP`: an exec would retain the filter while discarding its in-process handler.

The mechanism follows the public [Linux seccomp filter contract](https://docs.kernel.org/userspace-api/seccomp_filter.html). ABI layouts come from the Android NDK's kernel UAPI and bionic headers. It does not grant root, change Linux/Binder UID, or depend on HMS, Xposed or a third-party inline-hook library.

## Verification

As of 2026-10-24, NDK 27.2.12479018 compiles and links both `arm64-v8a` and `x86_64` with Android API 26. `9b20b25` [CI40067549050](https://github.com/xmy953538104/Mybox/actions/runs/40067549050) passed all three Linux correctness rounds on both architectures and three rounds of 32 Android14/x86_64 core tests, plus JVM/lint/artifact checks. The pool's blocking/nesting/ENOMEM paths and same-runner performance results are recorded in [performance](performance.md). A previous pre-pool AArch64 Linux SIGSYS failure remains unresolved; later passes do not establish a fix. Compilation/Linux success alone does not demonstrate ARM64 Android compatibility, complete Google app support or 16 KiB-page runtime support.

On a Linux x86_64 machine that permits seccomp:

```sh
cmake -S mylite-engine/src/main/cpp -B build/native-test -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-test --parallel
ctest --test-dir build/native-test --output-on-failure
```

The native test performs real syscalls after filter installation. It checks direct openat, stat, rename, unlink, relative directory FDs, symlink/readlink and truncation, invalid-pointer `EFAULT`, an existing thread and a newly created thread, signal-mask changes, and a signal handler performing file I/O. Path-table tests also check boundary matching, longest-prefix selection and destination idempotence. The Android fixture additionally exercises the guest Context and SQLite through the actual Application/Provider/Activity bootstrap.

## Current limits

- This is a compatibility mechanism under the shared host UID, not an isolation boundary for hostile apps. It does not promise canonical containment across arbitrary `..`, symbolic-link or `/proc` aliases.
- Only the bound guest's directory roots are mapped. Exact-file exclusions, the original full normalization policy, external storage, `/proc` filtering and getcwd virtualization are not implemented.
- Virtual Binder UID/ART entry replacement is a separately tested debug experiment, not automatically enabled for guest applications. System-property, network and media compatibility hooks observed in the original remain separate work.
- Startup removes SIGSYS from preinstalled action masks while retaining handlers, flags and other mask bits, and allows reentrant dispatch. The accepted Linux tests cover ordinary/realtime handlers and nested I/O on both the main thread and a TSYNC worker.
- The pre-guest installation guard parks existing threads with one verified unused default-ignored signal, unblocks their SIGSYS masks, rechecks the actual thread set and restores the borrowed disposition before TSYNC. It makes one attempt; thread races, unavailable signals and missing acknowledgements fail explicitly. Uncertain signal/mask rollback returns Linux EOWNERDEAD (130), causing only the dedicated guest process to retire. Eight deterministic Linux cases passed three rounds on both architectures in [CI40070852561](https://github.com/xmy953538104/Mybox/actions/runs/40070852561), as did three Android14/x86_64 core rounds. The Java fatal-retirement branch was not deliberately triggered on Android, and these passes do not resolve the historical failures.
- The next native batch sanitizes nonempty ppoll, pselect6, epoll_pwait, epoll_pwait2 and rt_sigsuspend masks with safe local copies, clearing only SIGSYS. Empty mask pointers take a BPF fast path. Actual waiting, timeout buffers, readiness and error priority remain with the kernel. Dual-ABI compilation passes; five interrupted-wait cases and 389 parameter/error comparisons await runtime CI. The stack-pool observer now uses raw syscalls to avoid borrowing the pool it measures, without weakening the eight-slot assertion.
- Older interrupted signal frames can still restore a blocked SIGSYS mask after installation. This distinct gap is reproduced by the pinned mechanism probe and remains uncorrected; neither installation coordination nor temporary wait-mask handling establishes its safety.
- Self `process_vm_readv`/`writev`, seccomp TSYNC and signal installation must work under the device policy. Preflight and installation failures return an error. The filter cannot be removed after successful installation; process exit ends the binding.
- ARM64 Android runtime validation and wider OS/OEM coverage are still required. AArch64 Linux and x86_64 emulator success do not validate Android bionic/ART on an ARM64 device.

Original static decompilations and analysis artifacts remain outside this repository. REA's Windows provider rejected this ARM64 ELF, so native function evidence came from official Ghidra headless; the private ledger distinguishes its local evidence labels from REA-issued evidence. The source here implements the recorded behavior with public interfaces, not copied decompiled code.

The new sources are Apache-2.0. The NDK statically links LLVM C++ runtime components; their notices are preserved in `docs/licenses/android-ndk-llvm-runtime.txt`. No proprietary or prebuilt VLite implementation is distributed.
