# Mylite Activity records: checkpoints A / B1

The server reserves an opaque record for each standard Activity launch. It selects the component and committed package generation itself, acquires the target process, and binds the record to that process generation. The physical Intent identifies the guest component/user/generation and carries the opaque record rather than authoritative guest-supplied metadata.

The target claims the record using its real Android Activity token. The server queries Android for the actual task ID, checks the source task and process identity, and restores the approved original Intent. Actual Application lifecycle callbacks acknowledge creation/start/resume/pause/stop/destruction; completed records and dead processes are removed. Calling-Activity/package queries use the recorded source. Finish operations still reach Android.

The fixture checks same-slot distinct Activities, cross-process launches with real two-level Activity results, lifecycle/task acknowledgements and finished-record removal. It rejects unknown capabilities, wrong generations, wrong process owners and fake physical tokens. A separate host-owned AppTask test sends a real NewIntentItem and checks original-instance/payload/Binder restoration and acknowledgement.

Checkpoint B1 adds package-local standard/singleTop/singleTask and CLEAR_TOP handling. The server selects a guest record and a verified owned Android task, finishes exact victim tokens, waits for actual destruction, and uses framework NewIntentItem delivery. Bounded asynchronous task queues preserve accepted Intent order without blocking the guest main thread on its own lifecycle acknowledgements. Standard root replacement uses only an explicitly selected owned AppTask; guest-supplied task IDs remain rejected.

Four new Android tests exercise reuse and payloads, cross-process clearing, canonical root selection, rapid delivery, background tasks, recreation and rejection without side effects. B1 passed all three Android14/x86_64 rounds in [CI40070852561](https://github.com/xmy953538104/Mybox/actions/runs/40070852561), alongside JVM/lint and APK checks. Other Android versions and ARM64/OEM task behavior remain unverified.

URI grants, documents/reorder, task-mode result reuse/aliases, arbitrary displays and full process-death Activity restoration remain unsupported. Fresh singleTask result targets and standard result targets from a singleInstance caller are covered below. Google login still needs its actual authorization and business-flow acceptance.

## Results from singleInstance and fresh singleTask targets

Real stock Android12 probes showed that startActivityForResult from a singleInstance caller to a standard or fresh singleTask target retains the source task and actual result link. Android adds NEW_TASK to the guest-visible Intent after its early result-cancellation decision. Mylite therefore preserves the original physical flags and lets Android carry the result, adding that automatic flag only to the approved guest Intent. Setting it on the physical IPC would prematurely cancel the result. Server-derived launch modes distinguish the singleInstance record from its result children. Existing task-mode result reuse remains rejected before any launch side effect.

The fixture checks cross-process result payload42, calling package, task identity, no premature result, an intervening child and actual finish. Seven task and two record tests passed locally in 13.859 seconds and subsequently in repeated Android14 core/bundled runs through 02e955a. The original Play singleInstance entry now opens the pinned microG fresh singleTask LoginActivity and actual Google WebView locally. Returning without credentials restores Play. Real authenticated account completion remains unaccepted.

See [STATUS](STATUS.md) for build/runtime acceptance. Original-artifact method evidence is kept privately; proprietary decompiled source and VLite binaries are not part of this repository.

## Android 12 records and singleTask aliases

Android 12/12L creates its launch record in `LaunchActivityItem.preExecute`; the Handler must update that exact token's cached record as well as the transaction item. Mylite authenticates the launch envelope, then verifies the cached token/filter before replacing its Intent, ActivityInfo, LoadedApk and referrer. Other API paths retain their existing adaptation. Primary source: [Android 12 LaunchActivityItem](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r34/core/java/android/app/servertransaction/LaunchActivityItem.java).

SingleTask aliases retain the original requested component and Intent, while task matching uses the real manifest target. Reuse through another alias or the direct target requires the same committed package, process and generation. Other planned alias modes and account UI aliases remain explicitly unsupported.

REA evidence for the original new APK (`6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257`): task matching `ev_f3c10ea496eed53fbd04a1115db1fcad6669a1178a17fe66181671e1d776a630`; launch-record adaptation `ev_2a7c914468f4196a6b69668b61019f2799c5bc6ac72007f6c22858f6147191da`. These are static method observations.

Local Android 12/x86_64 first failed all five task tests because the cached record was unchanged (116.195 seconds); two diagnostic launches reproduced it. After the cached-record fix, all five passed in 10.777 seconds, including alias payload/Binder preservation, clearing, background reuse, recreation and foreign-package rejection. JVM tests, host/test builds, lint and both APK audits passed. Android 14 CI and ARM64/OEM alias behavior still need verification.

## Late physical entries and singleInstance

Core CI40106624564 exposed an old OS placeholder arriving after its slot was already rebound. Server generation numbers alone are not unique across server restarts. Physical envelopes now also carry the server-assigned guest-owner Binder. A matching current owner retains full claim validation; otherwise a well-formed owned placeholder can close without guest code only before binding or after its original record or original guest-owner Binder throws actual DeadObjectException during a read-only transaction. The latter also covers executor-only death while the server-owned record Binder still exists. A failed ping or unsupported transaction is not proof of death. Closing uses a thread-local scope for that exact physical token; ordinary guest finish/source queries retain their record checks.

The first expanded test falsely passed before the delayed finish crashed. Its strengthened check waits beyond the discard counter, requires the original replacement PID to survive and verifies the new stale task disappears without mistaking the healthy task's matching base Intent for the stale one. Five local content-observer/recovery tests then passed in 8.129 seconds with no new owned crash. This does not restore old guest state after death.

Real Play's unauthenticated redirect requires launchMode3. Package-local singleInstance tasks now match exact canonical components, never affinity-merge, and send outgoing ordinary launches to regular tasks. Successful operations remember the actual caller task; after an explicit finish and actual destruction, a still-live owned caller task can return to front. Local fixture first exposed the missing return; the same physically installed fixture on stock Android12 confirmed Remote should return after Back. That temporary physical fixture was uninstalled. All six task tests passed in 11.505 seconds after the fix. Original task lookup REA: `ev_f4390b11e1a5ec8517247893add38991ba7caa01a1c66a9b63d6844e7e53d06c`. Complete Store recovery flags/foreground services and OEM behavior remain unaccepted.
