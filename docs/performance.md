# Mylite performance and repeatability

Performance is an acceptance requirement, separate from function coverage. No same-device GBox/Mylite comparison exists yet; Linux/x86_64 figures must not be presented as Y700/ARM64 results.

The reconstruction CI runs the complete Android core suite three times, force-stopping only its own host between rounds while retaining installed package/account data. Every round has its own results and diagnostics; one failure fails the workflow even if later rounds pass. This covers repeat startup and persistence boundaries, not arbitrary OEM background policy or a long-running soak test.

The Linux-only `mybox-mylite-benchmark` target compares fresh child processes without a filter, with an unmatched rule, and with actual path redirection, at one and four workers. The workload validates file contents, inode, size and readlink target while measuring repeated open/read/close/stat/readlink batches. It reports warmup, samples, wall/CPU time, iteration count, bytes and page faults with compiler/kernel/build/page-size metadata. Any incorrect result or timeout fails rather than producing a successful sample.

CI builds that benchmark with Release optimization, retains raw JSON and machine information, and keeps the separate Debug correctness tests. The numbers describe synthetic warm-cache aggregate overhead including validation and synchronization; they are not individual-call latency, storage throughput or Android application startup times. No arbitrary pass threshold substitutes for a measured baseline.

Next acceptance before performance claims: Android cold/warm launch and Binder timing, SQLite WAL/concurrent workload, process/file-descriptor/memory growth during repeated use, background wakeup, and a controlled same-environment GBox comparison on Y700 when the user makes it available. Profile first; never remove signature validation, result acknowledgements or error handling merely to improve a benchmark.
