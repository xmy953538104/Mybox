# Mylite Activity records: checkpoints A / B1

The server reserves an opaque record for each standard Activity launch. It selects the component and committed package generation itself, acquires the target process, and binds the record to that process generation. The physical Intent identifies the guest component/user/generation and carries the opaque record rather than authoritative guest-supplied metadata.

The target claims the record using its real Android Activity token. The server queries Android for the actual task ID, checks the source task and process identity, and restores the approved original Intent. Actual Application lifecycle callbacks acknowledge creation/start/resume/pause/stop/destruction; completed records and dead processes are removed. Calling-Activity/package queries use the recorded source. Finish operations still reach Android.

The fixture checks same-slot distinct Activities, cross-process launches with real two-level Activity results, lifecycle/task acknowledgements and finished-record removal. It rejects unknown capabilities, wrong generations, wrong process owners and fake physical tokens. A separate host-owned AppTask test sends a real NewIntentItem and checks original-instance/payload/Binder restoration and acknowledgement.

Checkpoint B1 adds package-local standard/singleTop/singleTask and CLEAR_TOP handling. The server selects a guest record and a verified owned Android task, finishes exact victim tokens, waits for actual destruction, and uses framework NewIntentItem delivery. Bounded asynchronous task queues preserve accepted Intent order without blocking the guest main thread on its own lifecycle acknowledgements. Standard root replacement uses only an explicitly selected owned AppTask; guest-supplied task IDs remain rejected.

Four new Android tests exercise reuse and payloads, cross-process clearing, canonical root selection, rapid delivery, background tasks, recreation and rejection without side effects. B1 has passed local compilation/JVM/lint and APK checks; its actual emulator results are pending. Checkpoint A's prior accepted runtime results do not certify B1.

Cross-package authentication UI grants, URI grants, singleInstance/documents/reorder, task-mode startActivityForResult, arbitrary displays and process-death Activity restoration remain unsupported. Google login still needs its actual authorization and business-flow acceptance.

See [STATUS](STATUS.md) for build/runtime acceptance. Original-artifact method evidence is kept privately; proprietary decompiled source and VLite binaries are not part of this repository.
