# Mylite performance and repeatability

Performance is an acceptance requirement, separate from function coverage. No same-device GBox/Mylite comparison exists yet; Linux/x86_64 figures must not be presented as Y700/ARM64 results.

The reconstruction CI runs the complete Android core suite three times, force-stopping only its own host between rounds while retaining installed package/account data. Every round has its own results and diagnostics; one failure fails the workflow even if later rounds pass. This covers repeat startup and persistence boundaries, not arbitrary OEM background policy or a long-running soak test.

The Linux-only `mybox-mylite-benchmark` target compares fresh child processes without a filter, with an unmatched rule, and with actual path redirection, at one and four workers. The workload validates file contents, inode, size and readlink target while measuring repeated open/read/close/stat/readlink batches. It reports warmup, samples, wall/CPU time, iteration count, bytes and page faults with compiler/kernel/build/page-size metadata. Any incorrect result or timeout fails rather than producing a successful sample.

CI builds that benchmark with Release optimization, retains raw JSON and machine information, and keeps the separate Debug correctness tests. The numbers describe synthetic warm-cache aggregate overhead including validation and synchronization; they are not individual-call latency, storage throughput or Android application startup times. No arbitrary pass threshold substitutes for a measured baseline.

The independent `native-arm64` CI job runs the same Debug syscall checks and Release workload on a GitHub `ubuntu-24.04-arm` runner. This exercises AArch64 Linux assembly, signal and kernel boundaries; it does not validate Android bionic, ART, OEM behavior or a 16KB-page device. Runner/page-size metadata and results stay separate from x86_64.

## First measured baseline

Commit `5a25cf9`, [CI40065754354](https://github.com/xmy953538104/Mybox/actions/runs/40065754354): Linux x86_64, Azure kernel 6.17.0-1022, 4 online CPUs, 4096-byte pages, Release build, no inherited host seccomp filter. Median wall time divided by completed iterations across three samples:

| Workers | No Mylite filter | Unmatched filter path | Redirected path |
|---|---:|---:|---:|
| 1 | 2.45 µs | 40.39 µs | 40.68 µs |
| 4 | 1.29 µs | 66.22 µs | 66.04 µs |

An iteration includes five public API operations plus correctness checks; four-worker values are aggregate time per completed iteration, not per-thread latency. Filtered cases recorded roughly 18 minor faults per iteration, while the unfiltered samples had zero or one total. Repeated signal-stack mmap/munmap and first-touch faults are a concrete optimization lead; this measurement alone does not attribute all overhead to them. Raw samples and machine metadata remain in the CI diagnostic artifact.

The pooled implementation reserves eight independent 64KiB signal-dispatch stacks per guest process, leases them with lock-free atomics and releases a lease only after the assembly bridge has restored the original stack. Slots are not prefaulted; resident memory follows actual use. Exhaustion or pool allocation failure retains the original temporary mmap path. Correctness tests occupy all slots in real blocked syscalls, exercise nested signal I/O and reuse, and force/recover allocation failure. This adds 512KiB of virtual address reservation per guest process; measured acceptance is recorded below.

`adb0f25` passed the x86_64 Android three-round suite and both Linux architectures once, including pool exhaustion/ENOMEM. Warm filtered samples no longer showed approximately 18 faults per iteration. The x86_64 unfiltered time also moved materially between runners, so a direct cross-run speedup factor would be misleading. CI now builds pinned pre-pool commit `5a25cf951b7f3e39090f89277a6f722a1803ada9` and the current benchmark on the same runner and retains both JSON results. The earlier `2a24b09` AArch64 SIGSYS failure remains unresolved; subsequent passes are not proof the stack optimization fixed it. Native acceptance now retains three independent rounds and diagnostic tracing after any failure.

## Same-runner stack-pool comparison

`9b20b25`, [CI40067549050](https://github.com/xmy953538104/Mybox/actions/runs/40067549050), Linux 6.17.0-1022-azure, 4 CPUs/4KiB pages, GCC13.3 Release. Each architecture job builds the exact pre-pool commit above and current sources, then runs the unchanged benchmark sequentially on that same runner. Median µs/completed five-operation iteration:

| Architecture | Workers | Unfiltered old/current | Redirected old | Redirected pooled |
|---|---:|---:|---:|---:|
| x86_64 | 1 | 7.24 / 7.26 | 82.09 | 23.21 |
| x86_64 | 4 | 2.44 / 2.45 | 91.71 | 8.05 |
| AArch64 | 1 | 2.85 / 2.84 | 30.30 | 11.52 |
| AArch64 | 4 | 1.07 / 1.11 | 37.14 | 3.94 |

Pooled warm samples recorded zero to two minor faults total, versus tens of thousands per old filtered sample. Both architecture jobs also passed all three correctness rounds, including full-pool blocking/nesting and allocation failure/recovery. This supports the bounded optimization for this synthetic workload, not device/UI/Google latency, overall power consumption, a long soak or parity with GBox. There is no pre-pool Android/Y700 same-device comparison, and the earlier intermittent SIGSYS remains open.

## Android initialization observations

`1ce7b5a`, [CI40070852561](https://github.com/xmy953538104/Mybox/actions/runs/40070852561), Android14/x86_64 emulator: the logged native I/O installation intervals across all three core rounds contain 12 distinct guest processes, each returning errno 0. At 1ms log timestamp resolution, minimum/median/maximum were 1/1/7ms. This includes scheduling, logging and initialization work between those two messages. Cumulative log snapshots were deduplicated by timestamp/event/PID. This is a diagnostic interval, not total app cold/warm startup, a percentile study or an ARM64/GBox comparison.

Next acceptance before broader performance claims: Android cold/warm launch and Binder timing, SQLite WAL/concurrent workload, process/file-descriptor/memory growth during repeated use, background wakeup, and a controlled same-environment GBox comparison on Y700 when the user makes it available. Profile first; never remove signature validation, result acknowledgements or error handling merely to improve a benchmark.

## Archive metadata reuse

In the `83181b3` [bundled registration diagnostic](https://github.com/xmy953538104/Mybox/actions/runs/40073850240), one microG process emitted 384 parse warnings for the same committed APK between 07:30:28.551 and 07:32:03.188, including 293 on its main thread. The source confirmed `PackageStore.getPackageInfo` reparsed that archive for every query. These counts establish repeated work, not its duration or the cause of the registration timeout, which separately had a real missing network permission.

The implementation retains up to eight raw archive views per store, keyed by the immutable committed APK path and exact flags. Each result is deep-copied with Android Parcel before applying current installed paths, identity or signature compatibility. Every query still reads and validates the committed package record; permissions, authenticator authorization and caller decisions are not cached. Another process's same-version reinstall or update selects a new generation automatically. Actual Android checks cover concurrent real parsing, mutable arrays/Bundle isolation, flags, external updates, eviction and corrupt/missing records. These checks passed in all three rounds of both the otherwise-failed [cache CI40075898279](https://github.com/xmy953538104/Mybox/actions/runs/40075898279) and the fully-passed [readiness CI40076711072](https://github.com/xmy953538104/Mybox/actions/runs/40076711072). Fifteen concurrent queries required one actual archive parse per stable key. This is work-reuse evidence; measured application latency, memory cost and GBox performance parity remain separate acceptance requirements.
