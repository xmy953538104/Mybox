# microG service build

Mybox builds `com.google.android.gms` from [microg/GmsCore v0.3.17.252432](https://github.com/microg/GmsCore/tree/32bc8954ff872d0e1a05ddfae81561b6dbecef69), commit `32bc8954ff872d0e1a05ddfae81561b6dbecef69`. Upstream sources and this patch retain their Apache-2.0 notices. No GBox/VLite binary or decompiled application source is used in this build.

On Linux with Java 17, Android SDK platform 35 and Build Tools 35.0.0:

```sh
export ANDROID_HOME=/path/to/android-sdk
bash scripts/build-microg.sh
```

Gradle 8.13, AGP 8.13.2 and the upstream dependency versions are preserved. Kotlin is explicitly pinned to 2.2.21, the version already selected transitively by Wire 6.4.6 in the original build. The patch removes reliance on that implicit upgrade; the original `tasks` configuration itself was verified to succeed.

The script checks the upstream commit, applies the versioned patches, records the actual tracked diff, sets `modules.hms=false`, and builds only `:play-services-core:assembleMapboxDefaultDebug`. CI starts from a fresh upstream checkout. `default` selects the normal Google signature compatibility metadata and Android location provider. `mapbox` is upstream's historical flavor name for MapLibre Native 10.2.0. Neither the HMS flavor nor Companion is built or installed. The real Play Store occupies `com.android.vending` in the container.

Repeated builds accept only the pinned pristine source or the exact complete current patch series. `prepare-microg-source.py` applies the patches sequentially in a temporary Git index and compares the full Git-visible source tree, including added files. It leaves the main index unchanged and records the complete verified diff. Staged changes (apart from existing intent-to-add entries), partial/older patch sets and additional source edits are rejected without resetting or discarding them; use a separate fresh checkout for those cases. Normal ignored Gradle outputs and generated configuration remain allowed. Java self-test classes go under the upstream ignored `build/` directory. The build runs `python3 scripts/test-prepare-microg-source.py`; these real-Git regression tests can also run separately without an Android build or network access.

Outputs are in `dist/microg/`. The reusable workflow uploads this directory as `microg-nohms`. The host workflow must take `microg-nohms.apk` and `trusted-microg.properties` from that same run before building its assets; never trust a separately fetched "latest" APK. The properties pin both the entire APK SHA-256 and its real signing certificate SHA-256. `artifact.json` records the source, patch hash, package version and certificate. `apksigner verify` must succeed before these files are generated.

`signing/development.keystore` is the deliberately public, stable development key. Its passwords are public too. This supports repeatable developer updates, not production publisher identity. A production build needs a private, stable key and a separate release process; changing the microG signer prevents in-place updates of already installed service data.

## Keyless maps

When upstream's Mapbox and Stadia keys are both absent, the small patch loads the [OpenFreeMap Liberty style](https://openfreemap.org/quick_start/) over HTTPS. The provider explicitly supports MapLibre Native mobile applications and [allows its public instance without an API key](https://github.com/hyperknot/openfreemap#openfreemap). Source attribution from the style/TileJSON is retained in MapLibre, including snapshots; it must remain visible. See the [provider terms](https://openfreemap.org/tos/) and [attribution requirements](https://github.com/hyperknot/openfreemap#attribution).

This is an online vector basemap, not a routing/geocoding backend or satellite service. Keyless satellite/terrain requests currently display that same vector map. `MAP_TYPE_NONE` remains blank. Custom Google JSON styles return `false` in keyless mode because the provider style lacks upstream's Google feature metadata. Offline region downloading is not implemented; the public service has separate offline limits. Network reachability and a rendered map still require device testing.

Supplying upstream's existing `MAPBOX_VECTOR_TILES_KEY` or `STADIA_API_KEY` build environment variables selects upstream's original styles instead. These keys become part of the APK, so they must be appropriate for client-side distribution.

## Audit and limits

```sh
python3 microg-patches/audit_apk.py --self-test
```

The audit rejects unresolved runtime dependencies, HMS/AGConnect/HMF dependencies, HMS DEX descriptors/references, HMS manifest components, wrong package identity, missing MapLibre implementation, and missing ARM64 native libraries. It emits trust pins only after those checks and SDK signature verification.

The audit proves selected build contents. It does not prove container signature compatibility, Google server authentication, Play downloads, GPS/FCM delivery or application compatibility; those use the Mybox device acceptance tests. Upstream's native MapLibre binary and all other transitive native libraries still need page-size/device compatibility testing.

`0003-private-service-logs.patch` removes raw check-in/form bodies, MCS payloads, registration tokens, authenticated URLs, consent data and credentials from the service's diagnostic logs. Status codes, message tags, operation results and network errors remain available. HTTP error bodies remain accessible to upstream authentication recovery through `getMessage()`; `NotOkayException.toString()` omits them so nested diagnostic stack traces cannot expose those bodies. Check-in errors contain only the HTTP status because that caller does not parse the response body.

The build runs `audit_source.py` over the selected authentication, check-in and FCM source paths, plus `PrivacySelfTest.java` for the exception's privacy/recovery behavior. This is a regression guard for the identified logging sinks, not a claim that arbitrary third-party code or exceptions can never log sensitive information. Do not publish unreviewed device logs. The `audit_source.py --self-test` cases retain useful status/type messages and reject raw payload or token logging.

`0005-private-gservices-settings.patch` also removes the provider's raw settings name/value log after a check-in response. The source audit includes that provider and its multi-line logging regression. CI drops the entire `GmsServicesProvider` tag before retaining diagnostic streams, including when inspecting an older approved artifact. `0006-checkin-cleanup-diagnostic.patch` adds one constant message after the existing `schedule(this); stopSelf();` calls return; it changes no control flow and cannot appear when either call throws. This diagnostic is not proof of OS alarm registration or completed Service destruction. `0007-local-self-identity.patch` additionally logs the constant `Check-in started` immediately before calling CheckinManager, allowing observation of the actual service PID during response processing. It does not change the registration test's 90-second success/cleanup requirements. A changed patch series requires a fresh microG build and new exact APK provenance; older artifacts cannot satisfy the current patch pin.

`0009-gservices-cursor-ownership.patch` closes the provider's temporary `MatrixCursor` when an empty result returns null or query processing throws. Nonempty results remain open for the caller to close; empty-result, authentication and exception semantics are preserved. `test_gservices_cursor.py` executes the patched production query method in a JVM with explicit cursor doubles, covering repeated misses, prefixes, returned ownership, rejection and failures. It is run before the Android build; it does not establish that Play startup, login or downloads work. This fixes an independently confirmed resource leak, not a demonstrated cause of the Play startup timeout.

## Check-in receiver alarm

At the pinned upstream commit, `CheckinService.schedule` creates a Service PendingIntent targeting `org.microg.gms.checkin.TriggerReceiver`, which is declared as a manifest receiver and extends `WakefulBroadcastReceiver`. `0008-checkin-receiver-alarm.patch` corrects only `PendingIntentCompat.getService` to `getBroadcast`. The request code, explicit target, one-shot/update flags, immutable setting, alarm type/time, exception behavior and `schedule(this); stopSelf();` order are preserved. The engine must not reinterpret arbitrary Service PendingIntents as broadcasts to compensate for this source error.

The existing exact patch-tree and APK provenance checks cover this source change. Real OS PendingIntent records, host UID/package attribution, alarm permission checks, delivery, cancellation and restart behavior still require separate engine implementation and Android acceptance. This correction alone does not make MCS reconnect or heartbeat scheduling work.

## Container caller identity

`0002-container-caller-identity.patch` adds one service-side bridge in `play-services-base:core`. It captures the original kernel Binder UID/PID at the entry, then asks `content://io.github.xmy953538104.mybox.identity` with method `resolveCallingIdentity`, extras `originalUid` and `originalPid`. The host verifies that the querying PID is the exact pinned microG, resolves the original PID from its registered guest processes in the same virtual user, and returns `uid`, `userId`, `packageName`, and `packages`. The microG side validates the shape and claimed caller package/UID/PID; it does not grant permissions or change certificate checks.

If the provider is absent, upstream system identity remains in use. An existing provider with a wrong owner, denied call, empty reply, inconsistent UID/user or malformed package list fails closed. The bridge also recognizes the host's authenticated account server as virtual system UID 1000; arbitrary guest processes cannot claim that PID through microG's entry point.

The patch covers common package validation, token-consent caller identity, account/GSF providers, advertisement-ID ownership, location client identities and FCM registration. Internal Messenger self checks happen before the Binder caller identity is lost to a handler queue. FCM registration carries the verified UID per message, avoiding the upstream shared-handler UID race. Deprecated `EXTENDED_ACCESS` fallback is disabled inside Mybox; upstream Google certificate and specific permission checks remain. Direct token/account/options logs on the authentication path are removed.

`0004-virtual-self-identity.patch` compares self identity using the package returned by the authenticated bridge. The guest's Context retains the host's real Linux UID for framework calls; it cannot be compared directly with a virtual application ID. Outside Mybox the upstream real-UID self check remains in use. This change does not grant another package or an arbitrary UID the account-server role.

`0007-local-self-identity.patch` reads the original Binder UID/PID before any IPC or identity clearing. When both identify the current process, `isCallingSelf` returns true without querying the container; a local check-in settings update need not ask the remote identity provider to hash the service APK again. A shared UID with a different PID, PID zero, or a different UID still follows the full bridge with that original snapshot. No permission, signature, pin or dynamic caller identity is cached. `resolve`, account-server forwarding and account-response authorization keep their existing checks. Removing this redundant local boundary call does not by itself establish why any particular check-in observation timed out.

[Android Binder does not provide a calling PID for oneway calls](https://developer.android.com/reference/android/os/Binder#getCallingPid()). PID 0 remains rejected. The host must therefore deliver authenticator and approved microG Messenger requests synchronously from the actual calling process. Account result, error and continue callbacks also use synchronous transactions from the authenticated microG process. The helper extracts the existing real response binder through its public Parcelable format; it does not trust caller-supplied identity fields or serialize a Java `IBinder` proxy. Framework `AccountAuthenticatorActivity` gets a real local Binder delegate that forwards only after the same self/system identity check. Raw one-way callbacks from an unknown caller are never accepted as a fallback. These transport paths require real-device interop tests.

Production identity/account-response helpers are compiled against Android SDK 35 before the build. Separately, `test_identity_self.py --source /path/to/MyboxIdentity.java` compiles the actual identity source and `IdentitySelfTest.java` against explicit, temporary JVM doubles for Android Binder/Process/Context. This tests local zero-IPC behavior, shared-UID remote package checks, captured/cleared/restored identity, PID zero, provider ownership, transport failures, malformed replies and cross-user rejection without a test dependency. These are simulated boundary checks, not Android Binder evidence; the doubles never enter an APK.

The bundled-service `GoogleRuntimeTest` also uses a debug-only `MicrogIdentityProbeService` in a separate process. It verifies the installed APK and signer against the host assets, loads the actual helper with PathClassLoader, and checks a true local worker call plus a same-host-UID remote Binder call. The remote test process must be rejected by the real engine process registry; there is no test identity exemption or native UID hook. Android execution of this test remains a separate CI gate. Client-side compatibility libraries (`play-services-iid` MessengerCompat and wearable callbacks), unbuilt Companion and unbuilt Huawei signature flavor retain upstream identity handling; they are not the service-side P1–P3 path.
