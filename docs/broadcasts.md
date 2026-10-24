# Mylite broadcast delivery

Ordinary same-package broadcasts and broadcast PendingIntents use the installed manifest and live process registry. Explicit receivers may have no action or intent filter. Registered-only broadcasts never start manifest receivers. Cross-package permission/exclusion lists, sticky broadcasts, AppOps and option-bearing broadcasts remain explicit errors.

Ordered delivery combines manifest and registered receiver priority. Each real `BroadcastReceiver.PendingResult` receives the prior code/data/extras; `finishReceiver` returns its actual result and abort flag. `goAsync()` advances only on the real completion, death or timeout. Abort skips remaining receivers but preserves the sender's final callback; `FLAG_RECEIVER_NO_ABORT` prevents abort from stopping the chain. Tokens are per-delivery and completions authenticate the live receiving PID/package. Registry locks are not held while executing guest code.

A receiver execution/transport failure is logged and contributes no new result; the queue continues to remaining receivers and the final callback with the preceding result. That callback is not a success acknowledgement for every receiver. A deliberate unfinished `goAsync` fixture checks the actual timeout, subsequent delivery and isolation from a late finish.

Original-artifact REA observations guided component lookup, receiver construction and preserving the original PendingResult. Original compatibility branches that discard permissions, downgrade ordered delivery or swallow errors are not copied. The Android public result/abort/goAsync contract defines acceptance; static observations are not runtime proof.

`BroadcastRuntimeTest` uses public Context APIs in a separately signed fixture APK and an actual worker-process manifest receiver. It checks mixed priority, asynchronous result propagation, abort/final callback, NO_ABORT, registered-only, explicit no-action delivery, no-target final callback, ordinary manifest delivery and rejection of unsupported permission lists. Compilation is separate from Android runtime acceptance.
