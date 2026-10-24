# Mylite native I/O

The active `mylite-engine` has an independently written, source-built native I/O backend. It uses the Android/Linux kernel already on the device. It does not include the original VLite library or the inactive Bcore engine. Original ARM64 function analysis established the seccomp/SIGSYS design; x86_64 is a new port of that design.

## Startup and behavior

`NativeBridge.initializeGuest(Context, ApplicationInfo)` runs before the guest Application and providers are constructed. It accepts only a dedicated `hostPackage:engine_pN` process, validates the guest package and requires physical destinations beneath the host's storage. A process binds to one guest. The result exposes actual initialization status and errno; a failed backend prevents guest startup.

The Java layer supplies immutable mappings for the guest's `/data/data`, `/data/user/0`, device-protected data and native-library roots. The C++ table selects the longest directory prefix, respects path-component boundaries and leaves an already mapped destination unchanged. Relative paths retain normal kernel `dirfd` behavior.

The backend installs a seccomp filter with thread synchronization. Selected path calls trap to a SIGSYS handler, which rewrites pathname arguments and executes the real syscall through a narrow, linked assembly gateway. ARM64 and x86_64 use their own UAPI syscall numbers and saved register layouts. Results and errors come from the real syscall. The signal handler uses bounded storage and a temporary RW stack; it does not allocate C++ objects or take locks. Kernel-mediated copying checks pathname pointers without dereferencing arbitrary guest pointers.

Covered call families include open/openat, stat/statx, access, directory creation/removal, rename, link/symlink, ownership/mode/time updates, truncate and chdir. Both paths are mapped for two-path operations. Readlink output is mapped back to a guest-visible root, preserving the caller's truncation size. Supported syscall variants depend on the ABI's headers and running kernel.

SIGSYS must remain available for dispatch. Later signal-mask and handler updates cannot block or replace it; saved interrupted-thread masks are updated so `rt_sigreturn` restores the requested mask. Calls that replace the SIGSYS handler fail with `EBUSY`. `execve` and `execveat` fail with `EOPNOTSUPP`: an exec would retain the filter while discarding its in-process handler.

The mechanism follows the public [Linux seccomp filter contract](https://docs.kernel.org/userspace-api/seccomp_filter.html). ABI layouts come from the Android NDK's kernel UAPI and bionic headers. It does not grant root, change Linux/Binder UID, or depend on HMS, Xposed or a third-party inline-hook library.

## Verification

As of 2026-10-24, NDK 27.2.12479018 compiled and linked both `arm64-v8a` and `x86_64` with Android API 26. The integrated build at `bd00b561511bca80570f8d4f823b6562e7a6f472` also passed. Linux CTest passed in CI run 40050197532 at commit `449d62a`; Android 14 emulator execution was still pending at this documentation checkpoint. Compilation alone does not demonstrate device compatibility, complete Google app support or 16 KiB-page runtime support.

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
- ART native-entry replacement, virtual Binder UID, system-property, network and media compatibility hooks observed in the original are not implemented by this milestone.
- Startup now removes SIGSYS from preinstalled action masks while retaining the handlers, flags and other mask bits, and allows reentrant SIGSYS dispatch. The new Linux tests cover ordinary/realtime handlers and a nested signal doing I/O on both the main thread and an existing TSYNC worker. Other threads' own pre-existing blocked SIGSYS masks and temporary ppoll/pselect/epoll wait masks still need separate handling and validation.
- Self `process_vm_readv`/`writev`, seccomp TSYNC and signal installation must work under the device policy. Preflight and installation failures return an error. The filter cannot be removed after successful installation; process exit ends the binding.
- ARM64 runtime validation and wider OS/OEM coverage are still required. x86_64 emulator success would not by itself validate ARM64.

Original static decompilations and analysis artifacts remain outside this repository. REA's Windows provider rejected this ARM64 ELF, so native function evidence came from official Ghidra headless; the private ledger distinguishes its local evidence labels from REA-issued evidence. The source here implements the recorded behavior with public interfaces, not copied decompiled code.

The new sources are Apache-2.0. The NDK statically links LLVM C++ runtime components; their notices are preserved in `docs/licenses/android-ndk-llvm-runtime.txt`. No proprietary or prebuilt VLite implementation is distributed.
