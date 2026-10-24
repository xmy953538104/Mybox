# Mylite Activity records: checkpoint A

The server reserves an opaque record for each standard Activity launch. It selects the component and committed package generation itself, acquires the target process, and binds the record to that process generation. The physical Intent identifies the guest component/user/generation and carries the opaque record rather than authoritative guest-supplied metadata.

The target claims the record using its real Android Activity token. The server queries Android for the actual task ID, checks the source task and process identity, and restores the approved original Intent. Actual Application lifecycle callbacks acknowledge creation/start/resume/pause/stop/destruction; completed records and dead processes are removed. Calling-Activity/package queries use the recorded source. Finish operations still reach Android.

The fixture checks same-slot distinct Activities, cross-process launches with real two-level Activity results, lifecycle/task acknowledgements and finished-record removal. It rejects unknown capabilities, wrong generations, wrong process owners and fake physical tokens. A separate host-owned AppTask test sends a real NewIntentItem and checks original-instance/payload/Binder restoration and acknowledgement.

This checkpoint does not implement guest singleTask/singleTop, CLEAR_TOP/task reuse, cross-package authentication UI grants, URI grants, arbitrary task operations or process-death Activity restoration. Those gates remain explicit. A host-driven NewIntentItem test is not acceptance of guest singleTask. Google login remains blocked on its actual task and authorization requirements.

See [STATUS](STATUS.md) for build/runtime acceptance. Original-artifact method evidence is kept privately; proprietary decompiled source and VLite binaries are not part of this repository.
