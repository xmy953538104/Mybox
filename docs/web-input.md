# WebView input and clipboard boundary

The Y700 report on 2026-10-25 exposed two missing Android service boundaries. Android rejected the guest EditorInfo package because the physical UID belongs to Mybox. Chromium also called IClipboard.hasPrimaryClip during long-press selection, which previously forwarded the virtual package unchanged and raised SecurityException. Process death then exposed a second failure: an expired physical stub tried to restore guest Fragment classes with the host class loader.

## Original artifact evidence

REA inspected the new APK with SHA-256 `6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257`, using caller-supplied JADX headless 0.7.1. Evidence `ev_25b211d4b0f7e78b2daa37aeaefa38f74365e7dc57b200693ab10875f0df599a` identified the actual Unicode nested class behind the display alias `com.vlite.sdk.p000.h8`. Evidence `ev_e9338bcebf96a29e4369e0d6a0d7a9aa97f757f5f4b6e54ea01fa08fc1a8a9d5` recovered its `b.g(Object, Method, Object[])` hook: it locates EditorInfo, changes its packageName to the host and invokes the original service. REA evidence `ev_0df4dc34a262ef3951c9349c01c2d2031836d0d6215e95c06c081537f7df270d` additionally identifies the original clipboard hook registrations for content read/write, metadata, presence checks, listeners and clear operations. This is static Java evidence, not proof of every native hook or Android release. Original decompiled code and binaries remain outside this source repository.

## Independent implementation

GuestInputMethodCalls adapts the local Java IInputMethodManager start call. It clones EditorInfo through Parcel and maps only the package; real window tokens, input connections, selection, input type and the OS result remain intact. Foreign editor packages and incoming remote callers fail before physical forwarding. Android 14 uses IInputMethodManagerGlobalInvoker.sServiceCache; older releases use the actual InputMethodManager service cache.

Clipboard metadata, content queries, content writes and clear operations share the same owning-guest, virtual-user, attribution and device checks. Reads require that guest's own focused Activity. Unfocused reads return no content; unfocused writes fail. Real OS permission/focus checks still apply. Listener handling is unchanged. No clipboard contents are logged by the probe.

Expired physical GuestActivity instances discard saved guest Fragment state before Activity.onCreate. Live guest Activity state is unaffected.

## Checks and limits

JVM tests cover clipboard identity mapping, focus refusal and actual exception propagation. InputMethodBoundaryTest checks copied EditorInfo state and rejection before physical invocation. The isolated clipboard group also runs WebInputRuntimeTest: an offline Chromium email field must show a real input-method window, expose the actual long-press Paste action, receive synthetic clipboard text and survive. This test does not submit a Google account or assert online login success. Runtime results must be read from the matching CI run; compilation alone is insufficient.

Y700 access was paused by the user before deploying this change. No Y700 retest is claimed.

## Google components

The former install button installed microG only, so its launcher correctly opened microG settings. The new flow additionally imports authentic Google Play from an optional local asset or the device's installed Store, including its split APKs. Every input must verify with Google's real APK signing certificate; PackageStore verifies the expected package and split set before publishing. An existing valid virtual Store is preserved. If no source exists, the app explicitly requests an original signed Store APK through the existing import flow. Public CI does not redistribute an original GBox or Google Play binary.

microG self-check includes real Android runtime permissions, overlay/cross-profile permissions and battery policy. Unchecked entries are not converted to success or treated as evidence that Play login works. Those permissions need feature-specific validation.
