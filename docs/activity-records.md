# Mylite Activity records: checkpoints A / B1

The server reserves an opaque record for each standard Activity launch. It selects the component and committed package generation itself, acquires the target process, and binds the record to that process generation. The physical Intent identifies the guest component/user/generation and carries the opaque record rather than authoritative guest-supplied metadata.

The target claims the record using its real Android Activity token. The server queries Android for the actual task ID, checks the source task and process identity, and restores the approved original Intent. Actual Application lifecycle callbacks acknowledge creation/start/resume/pause/stop/destruction; completed records and dead processes are removed. Calling-Activity/package queries use the recorded source. Finish operations still reach Android.

The fixture checks same-slot distinct Activities, cross-process launches with real two-level Activity results, lifecycle/task acknowledgements and finished-record removal. It rejects unknown capabilities, wrong generations, wrong process owners and fake physical tokens. A separate host-owned AppTask test sends a real NewIntentItem and checks original-instance/payload/Binder restoration and acknowledgement.

Checkpoint B1 adds package-local standard/singleTop/singleTask and CLEAR_TOP handling. The server selects a guest record and a verified owned Android task, finishes exact victim tokens, waits for actual destruction, and uses framework NewIntentItem delivery. Bounded asynchronous task queues preserve accepted Intent order without blocking the guest main thread on its own lifecycle acknowledgements. Standard root replacement uses only an explicitly selected owned AppTask; guest-supplied task IDs remain rejected.

Four new Android tests exercise reuse and payloads, cross-process clearing, canonical root selection, rapid delivery, background tasks, recreation and rejection without side effects. B1 passed all three Android14/x86_64 rounds in [CI40070852561](https://github.com/xmy953538104/Mybox/actions/runs/40070852561), alongside JVM/lint and APK checks. Other Android versions and ARM64/OEM task behavior remain unverified.

Cross-package authentication UI grants, URI grants, singleInstance/documents/reorder, task-mode startActivityForResult, arbitrary displays and process-death Activity restoration remain unsupported. Google login still needs its actual authorization and business-flow acceptance.

See [STATUS](STATUS.md) for build/runtime acceptance. Original-artifact method evidence is kept privately; proprietary decompiled source and VLite binaries are not part of this repository.

## Android 12 records and singleTask aliases

Android 12/12L creates its launch record in `LaunchActivityItem.preExecute`; the Handler must update that exact token's cached record as well as the transaction item. Mylite authenticates the launch envelope, then verifies the cached token/filter before replacing its Intent, ActivityInfo, LoadedApk and referrer. Other API paths retain their existing adaptation. Primary source: [Android 12 LaunchActivityItem](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r34/core/java/android/app/servertransaction/LaunchActivityItem.java).

SingleTask aliases retain the original requested component and Intent, while task matching uses the real manifest target. Reuse through another alias or the direct target requires the same committed package, process and generation. Other planned alias modes and account UI aliases remain explicitly unsupported.

REA evidence for the original new APK (`6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257`): task matching `ev_f3c10ea496eed53fbd04a1115db1fcad6669a1178a17fe66181671e1d776a630`; launch-record adaptation `ev_2a7c914468f4196a6b69668b61019f2799c5bc6ac72007f6c22858f6147191da`. These are static method observations.

Local Android 12/x86_64 first failed all five task tests because the cached record was unchanged (116.195 seconds); two diagnostic launches reproduced it. After the cached-record fix, all five passed in 10.777 seconds, including alias payload/Binder preservation, clearing, background reuse, recreation and foreign-package rejection. JVM tests, host/test builds, lint and both APK audits passed. Android 14 CI and ARM64/OEM alias behavior still need verification.
