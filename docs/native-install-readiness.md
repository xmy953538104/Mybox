# Native installation readiness

The installation guard makes one attempt in a dedicated guest process. Before changing signal actions or parking any thread, it allows up to **250 ms total** for a temporarily blocked candidate signal to become deliverable. It reads real thread masks and sleeps through raw `nanosleep` in intervals of at most 1 ms. The deadline is shared by both candidates and is not reset by a signal interruption or another observation. There is no automatic installation retry.

This addresses a measured rejection in [CI40073849217](https://github.com/xmy953538104/Mybox/actions/runs/40073849217): both SIGURG and SIGWINCH were blocked in one existing thread, and the guard returned EBUSY before TSYNC. Its `fffffffffffbfeff` mask blocked every blockable signal. [Android 14 bionic](https://android.googlesource.com/platform/bionic/+/android-14.0.0_r1/libc/bionic/pthread_create.cpp) uses that raw full mask around thread creation. The creating thread restores its mask before unlocking the child's startup handshake; the child restores its inherited start mask later. The observed mask matches that mechanism, but no stack for the rejected thread was captured, so its exact function is unproven.

Readiness leaves every peer running. Queueing the parking signal immediately could instead park a creator before it releases its still-blocked child. The guard waits before borrowing the signal, then re-reads the complete fixed thread cohort, disposition, blocked masks and pending signals. After the I/O handler setup it revalidates again. Only then does it queue one authenticated request per original peer and run the existing acknowledgement/TSYNC protocol. That acknowledgement phase retains its separate 250 ms deadline.

The two candidates must have the original default-ignore/ignore action and no pending signal. An owned or already-pending candidate is not borrowed, and a candidate whose action changes during readiness is excluded for this attempt. The original thread cohort is never silently replaced or expanded.

| Result | Meaning |
| --- | --- |
| `0` | Every original peer acknowledged; the fixed cohort and real TSYNC checks succeeded. |
| `ETIMEDOUT` | A candidate remains blocked through the readiness deadline, or the later acknowledgement deadline expires. Permanent preflight blocking previously returned immediate `EBUSY`; it now returns bounded `ETIMEDOUT`. |
| `EBUSY` | Neither candidate can be borrowed because of ownership/pending state, a selected action/pending state changed after preparation, or the kernel rejects TSYNC with a positive TID. Diagnostics distinguish these stages. |
| `EAGAIN` | A recorded thread disappears, the observed thread set changes, or a candidate becomes blocked again after preparation. No filter success is claimed. |
| `EOWNERDEAD` (130) | Signal ownership, mask restoration or cleanup is uncertain. The dedicated guest process must retire. |

Diagnostics retain the first blocked TID/mask, readiness observation and sleep counts, elapsed nanoseconds, final candidate state and TSYNC result. Logging runs after installation returns and does not replace its error.

The native test now has eleven isolated guard cases. The added success case gives two real threads full kernel masks, then requires the creator to restore its mask before releasing the child. A readiness-only test hook checks every kernel signal action, including reserved realtime masks/restorers, and the installer's original mask. The test then requires actual redirected I/O from both threads. Other cases require exact failure codes and zero wrong installation: permanent blocking and late unmasking, a real new thread during readiness, preexisting pending signals preserved until the test explicitly unmasks them, action takeover and late acknowledgement timeout. Existing pool and temporary-wait tests remain separate.

NDK API26 compilation/linking has passed for ARM64 and x86_64 at O0 and O2. Runtime acceptance of this readiness change is pending Linux and Android CI; earlier successful runs do not prove this new code. The guard can still reject ongoing thread creation or later mask changes. It does not fix old signal frames restoring SIGSYS through `rt_sigreturn`, establish ARM64 Android/16 KiB runtime support, or explain earlier unexplained process deaths.
