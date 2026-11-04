# Completed full cohort: rejected, with two linked observation failures

The normal paired acceptance is incomplete when only one workflow passes. `7f4b1cf3a5f3701f6603d28b737e2c5968ac6f34` is not staged or delivered.

| Actual evidence | Core41836674687 | Clean Google41836680267 |
|---|---|---|
| Three complete core rounds |143 tests each, failures1/1/0, errors0|143 tests each, failures0/0/0, errors0|
| JUnit elapsed seconds |247.013 /224.294 /235.698|215.709 /234.655 /236.099|
| Actual retained groups |19, two assertion-failed groups|24, all primary groups passed|
| Actual owned-crash snapshots |20: one empty, nineteen identical copies of one real FATAL|25 empty|
| Real sync reports |Three completed init/manual+auto Provider mutation/finished/cancel reports; one exported terminal receipt missing|Three corresponding real reports passed|
| Budgets and persisted Job reboot |All four process budgets and actual cross-boot Job checks passed|Same primary checks passed|

All twelve actual sync/observer diagnostic ZIPs passed CRC, schema and exact build checks. Six distinct real sync reports were read, including unchanged physical UID, scoped system entry and actual received spoof refusal. Historical events are not counted as new events. The separate AArch64 Linux artifact passed three native I/O rounds and twelve benchmark cases on4KiB pages; this is not Android ARM64, GBox performance or hardware acceptance. Clean Google also passed its real input/back and anonymous MCS recovery checks; no actual account login or Play download acceptance is inferred.

## Broadcast worker ownership

Core round1's BroadcastProbe registers dynamic/ordered receivers against its launcher Activity. At13:21:41 UTC the framework logs that Activity's leaked receivers and reclaims them. At13:21:50 the worker unregisters an already reclaimed receiver, throws IllegalArgumentException outside its report-producing catch and dies. The test later times out without its result file. The nineteen later crash snapshots retain the same event, not nineteen separate crashes. The cause of the Activity's destruction is not established; it is not labeled recreation or an engine retirement without evidence.

First-diagnostic reference review includes the retained original virtual broadcast entry78 bytes and manifest routing880 bytes, plus the linked Context identity/lifetime boundary and existing lifecycle reconstruction. The actual platform contract in [Android14 LoadedApk](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/app/LoadedApk.java) removes receivers registered to a destroyed Context; forgetReceiverDispatcher then rejects a second unregister. That contract must remain real.

The probe now registers/sends/cleans up through its real Application Context because it is background work independent of its launcher. Main and nested cleanup failures are saved separately and remain asserted as failures, so a cleanup exception cannot erase the primary result or become an uncaught worker exception. Real ordered results, cross-process manifest execution, abort/no-abort, timeout and late-finish checks are retained. No production receiver guard is relaxed.

## Missing cancellation receipt

Core round2 work10 actually dispatches cancellation at monotonic632.359s. The server receives the control reply at632.35952s, but its `canceled` event is absent from the exported ZIP. Its next BoundLease event at632.37784s reports one dropped record. The fixture's real adapter interruption, inactive/pending checks and final report already completed. Other rounds have the expected terminal receipt.

The original start/cancel/finish/cleanup and bound-service chain was reviewed again before choosing a change. Production completion remains based on the real adapter and current session ledger. The own diagnostic journal uses an immediate cross-process tryLock for ordinary events; only account callback completion previously had a bounded100ms contention window. Fixed sync control/lifecycle receipts now share that existing bounded window. Ordinary high-volume records remain nonblocking, storage/retention limits stay bounded, disabled debug stays disabled, and an expired wait still records loss. A real file-lock contention unit regression covers the new critical paths alongside the existing expiry/drop and slow-export checks. Neither missing receipts nor real failures are silently treated as success.

## Further REA and next action

While this exact build was frozen, twenty original periodic/config methods7586 bytes and nine application sync policy methods572 bytes were verified, including signed constant/branch corrections. They show separate default periodic records, configuration and last-enqueue persistence, a Gmail auto-setting exception and an optional account bridge whose checked allowlist is WhatsApp. These are evidence for later work, not new implemented features.

The original native UID body651 instructions2604 bytes and its19-instruction PID lookup were linked to imports/relocations. A bounded analyst replay explored1152 explicit input/stub combinations and reached every instruction address; it did not execute original code, prove every path, recover all bootstrap/string configuration or establish original runtime behavior. No global UID fallback was added.

Rerun the normal full core and clean Google pair after the ownership/logging corrections. Real Keep/Google business, BlobStore, periodic/readiness, Android ARM64/OEM and performance parity remain open. No device access is authorized by this work. Per the user's latest instruction, the next normally accepted and audited deliverable APK ends development/CI/device work for a pause, followed by a detailed whole-project retrospective and progress table, awaiting further instructions.
