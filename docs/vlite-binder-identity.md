# Optional Binder identity view

`NativeBinderIdentity` is an independent native-entry compatibility experiment. It is not enabled by `Engine.attach`, guest initialization, or the I/O backend. The only current caller is a debug-only synthetic service in `:identity_test`. Production guest identity mapping still requires integration and runtime acceptance.

It preserves original Binder UID and PID entries and exposes `rawCallingUid()` / `rawCallingPid()` for engine authorization. An explicit `enterVerifiedCall(rawUid, rawPid, virtualUid, generation)` scope can change the guest-visible `Binder.getCallingUid()` on its calling thread. The caller must first authenticate the physical PID and package generation using the engine registry. The native check verifies the current raw UID/PID match; it cannot independently establish ownership of a supplied virtual UID or generation.

The scope accepts only a same-host UID, nonzero remote PID and application virtual UID. Self/no-IPC, PID 0, genuine `clearCallingIdentity()`, changed caller identity and absent scopes retain the real UID. Nested raw scopes suppress mapping for engine/framework operations; closing restores the previous scope. Only a genuine platform identity token is used by the tests. WorkSource is never authentication and never affects this mapping.

## Runtime calibration

Two ordinary JNI markers locate one shared native-entry offset in their actual reflected ART methods. Reads are bounded and use `process_vm_readv`; an ambiguous or unreadable layout fails. Saved Binder entries must belong to `libandroid_runtime.so`. Replacement and rollback use JNI `RegisterNatives`, without changing access flags or writing an ART structure directly.

`CriticalNative` changes the C ABI to omit `JNIEnv*` and `jclass`, and its annotation has CLASS retention. Runtime annotation reflection cannot detect it. The implementation instead reads `Executable.accessFlags`, which ART populates from the actual method. The inspected AOSP native flag values are:

| Android | FastNative | CriticalNative |
| --- | --- | --- |
| 8 / 8.1 | `0x00080000` | absent in inspected ART headers |
| 9–11 | `0x00080000` | `0x00200000` |
| 12–15 | `0x00080000` | `0x00100000` |

Sources: [Android 9 ART flags](https://android.googlesource.com/platform/art/+/refs/heads/pie-release/libdexfile/dex/modifiers.h), [Android 12 ART flags](https://android.googlesource.com/platform/art/+/refs/heads/android12-release/libdexfile/dex/modifiers.h), [Android 14 reflected executable](https://android.googlesource.com/platform/art/+/refs/heads/android14-release/runtime/mirror/executable.cc), and [CriticalNative contract](https://android.googlesource.com/platform/libcore/+/refs/heads/android14-release/dalvik/src/main/java/dalvik/annotation/optimization/CriticalNative.java).

Normal/Fast JNI and CriticalNative callbacks use separate function signatures. The callback only reads saved getters and thread-local fixed frames; it makes no Java call, allocation, lock acquisition or server RPC. Scope setup uses ordinary JNI. A real Java call verifies that registration reached the replacement without changing unscoped identity. Unsupported metadata, version, ABI or registration is reported as failure. Versions outside API 26–35 are currently rejected; inspecting a version's metadata does not establish device compatibility.

## Validation and remaining work

Standalone NDK ARM64 and x86_64 builds and strict compiler diagnostics pass. The debug instrumentation covers actual remote sync/oneway IPC, no-IPC identity, raw getters, nested scopes, genuine clear/restore, WorkSource, thread isolation, exception cleanup, rejected callers and wrong close order. It also makes a real system AppOps check while using the host's unchanged attribution. The first Android 14 x86_64 CI attempt returned `installed=false` during calibration; its assertion did not expand the diagnostic Bundle. Diagnostics are now explicit, and runtime acceptance remains pending. The mapping must not be enabled in production on the strength of compilation alone.

Automatic scope entry has deliberately not been connected. Before enabling it, private server/identity/account ACLs must use the raw bridge, the live registry must authorize the caller tuple, and exact trusted microG processes must retain their raw identity view. Framework `ContentProvider.Transport` and system-bound attribution must also remain real. A process-wide virtual UID, unknown-PID-to-system fallback or forged identity token would violate those requirements.

The new source is Apache-2.0. It uses Android JNI/libdl/pthreads and the project's existing HiddenApiBypass dependency for metadata access. It contains no proprietary native library or decompiled implementation, and adds no Pine, Xposed, Dobby or old container engine dependency. As with the rest of the reconstruction, the shared host UID is a compatibility boundary, not isolation from hostile native code.
