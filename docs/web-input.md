# WebView input and clipboard boundary

2026-10-26 consecutive9d33447/c8c3cb6 actual Google Paste failures triggered a full related REA review before the next candidate. The input was clipped to329..346px above the IME, while its pre-IME height was55px. The new scroll check still mistook freshly queried, already-clipped accessibility bounds for the full control, so it never actually scrolled. Fixed [Chromium113 AccessibilityNodeInfoBuilder](https://chromium.googlesource.com/chromium/src/+/refs/tags/113.0.5672.136/content/public/android/java/src/org/chromium/content/browser/accessibility/AccessibilityNodeInfoBuilder.java) clips screen bounds to the viewport and retains `AccessibilityNodeInfo.unclippedTop`/`unclippedBottom`. The exact source SHA256 is6afe1d75ea8291900c2b45e86bc4025c8f804bb44c509751d016078eedcf211c. The candidate reads these typed coordinates, rejects a non-superset and uses the real full rectangle to decide whether an actual content swipe is needed. It still requires one real long press, actual Paste, full synthetic text readback and the same login PID; no SET_TEXT, DOM injection or fake menu is used. New Android geometry boundaries are not business acceptance.

Original login onCreate/createWebView/WebSettings were rechecked through REA (`ev_79a330da24fb09b204d19b02f2cf4560738685a49189e534e60f7178887a77ac`, `ev_020d1d5b2a9246f93f60b8debda2dae3a48906b82581a1844d467527bb0cc376`, `ev_8d35072fefaadea62cb3d47aba0aa3592e5559dbc7ff3593bd1f7c138039bf58`), together with original Manifest/resources and13-node day/API34 app-theme inheritance. No explicit soft-input override appeared in those bounded locations; framework defaults, other runtime window mutations and original runtime parity remain separate. The Dalvik const/4 nibble0xf for LayoutParams is signed -1 MATCH_PARENT, not15px. No guessed resize policy is introduced. This candidate remains unbuilt/unaccepted.

The Y700 report on 2026-10-25 exposed two missing Android service boundaries. Android rejected the guest EditorInfo package because the physical UID belongs to Mybox. Chromium also called IClipboard.hasPrimaryClip during long-press selection, which previously forwarded the virtual package unchanged and raised SecurityException. Process death then exposed a second failure: an expired physical stub tried to restore guest Fragment classes with the host class loader.

## Original artifact evidence

REA inspected the new APK with SHA-256 `6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257`, using caller-supplied JADX headless 0.7.1. Evidence `ev_25b211d4b0f7e78b2daa37aeaefa38f74365e7dc57b200693ab10875f0df599a` identified the actual Unicode nested class behind the display alias `com.vlite.sdk.p000.h8`. Evidence `ev_e9338bcebf96a29e4369e0d6a0d7a9aa97f757f5f4b6e54ea01fa08fc1a8a9d5` recovered its `b.g(Object, Method, Object[])` hook: it locates EditorInfo, changes its packageName to the host and invokes the original service. REA evidence `ev_0df4dc34a262ef3951c9349c01c2d2031836d0d6215e95c06c081537f7df270d` additionally identifies the original clipboard hook registrations for content read/write, metadata, presence checks, listeners and clear operations. This is static Java evidence, not proof of every native hook or Android release. Original decompiled code and binaries remain outside this source repository.

## Independent implementation

GuestInputMethodCalls adapts the local Java IInputMethodManager start call. It clones EditorInfo through Parcel and maps only the package; real window tokens, input connections, selection, input type and the OS result remain intact. Foreign editor packages and incoming remote callers fail before physical forwarding. Android 14 uses IInputMethodManagerGlobalInvoker.sServiceCache; older releases use the actual InputMethodManager service cache.

Android15 adds startInputOrWindowGainedFocusAsync with EditorInfo at the same argument position. The local pending change applies the same mapping and preserves startInputSeq; its boundary test checks the real void return, window, sequence and foreign-editor refusal. This has passed local compilation, not an Android15 device run or a new CI run.

Clipboard metadata, content queries, content writes and clear operations share the same owning-guest, virtual-user, attribution and device checks. Reads require that guest's own focused Activity. Unfocused reads return no content; unfocused writes fail. Real OS permission/focus checks still apply. Listener handling is unchanged. No clipboard contents are logged by the probe.

Expired physical GuestActivity instances discard saved guest Fragment state before Activity.onCreate. Live guest Activity state is unaffected.

## Checks and limits

JVM tests cover clipboard identity mapping, focus refusal and actual exception propagation. InputMethodBoundaryTest checks copied EditorInfo state and rejection before physical invocation. The isolated clipboard group also runs WebInputRuntimeTest: an offline Chromium email field must show a real input-method window, expose the actual long-press Paste action, receive synthetic clipboard text and survive. This test does not submit a Google account or assert online login success. Runtime results must be read from the matching CI run; compilation alone is insufficient.

Y700 access was subsequently reauthorized on 2026-10-25. The focused clipboard plus WebView suite passed on the actual API34/ARM64 device in 6.221 seconds (two tests): real keyboard, long press, localized Paste control, actual synthetic content in the DOM and page survival. The clipboard contains synthetic test content only. This is separate from online Google authentication and Play download verification.

## Google components

The former install button installed microG only, so its launcher correctly opened microG settings. The new flow additionally imports authentic Google Play from an optional local asset or the device's installed Store, including its split APKs. Every input must verify with Google's real APK signing certificate; PackageStore verifies the expected package and split set before publishing. An existing valid virtual Store is preserved. If no source exists, the app explicitly requests an original signed Store APK through the existing import flow. Public CI does not redistribute an original GBox or Google Play binary.

microG self-check includes real Android runtime permissions, overlay/cross-profile permissions and battery policy. Unchecked entries are not converted to success or treated as evidence that Play login works. Those permissions need feature-specific validation.
