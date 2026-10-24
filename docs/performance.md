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

The next implementation reserves eight independent 64KiB signal-dispatch stacks per guest process, leases them with lock-free atomics and releases a lease only after the assembly bridge has restored the original stack. Slots are not prefaulted; resident memory follows actual use. Exhaustion or pool allocation failure retains the original temporary mmap path. Correctness tests occupy all slots in real blocked syscalls, exercise nested signal I/O and reuse, and force/recover allocation failure. This adds 512KiB of virtual address reservation per guest process; benefit and runtime acceptance remain pending CI until measured.

Next acceptance before performance claims: Android cold/warm launch and Binder timing, SQLite WAL/concurrent workload, process/file-descriptor/memory growth during repeated use, background wakeup, and a controlled same-environment GBox comparison on Y700 when the user makes it available. Profile first; never remove signature validation, result acknowledgements or error handling merely to improve a benchmark.
