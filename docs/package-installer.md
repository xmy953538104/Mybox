# Mylite package installer

The active engine is `mylite-engine`. Host imports use `Engine.install(List<File>)`, which delegates to `PackageStore`; guest installers use Android's `PackageInstaller` API through `GuestInstaller` and the private versioned `VirtualInstallerService`. The frozen BlackBox prototype and its tests are not this implementation.

`PackageStore` verifies actual APK signatures and a complete base/config-split set before publishing an immutable package generation and atomically replacing its package record. Package, version, split dependencies, supported ABI, authority conflicts and the installed signer are checked. Updates retain the appId, CE/DE data and previous code generation. Failed validation does not replace the committed record. Signing-key rotation and downgrades are rejected.

The guest installer accepts virtual user 0 and complete, non-staged APK sessions. It supports create/open/query, progress and callbacks, names, PFD-backed write/read/fsync/close, seal, commit and abandon. FileBridge and the platform's raw-descriptor mode both use real staging files. Session records survive service restart; interrupted publication is reconciled against the actual published APK hashes rather than assumed successful. Commit status uses a registered mutable virtual broadcast `IntentSender`; callbacks and status reflect the actual result.

Caller authorization uses the registered process, virtual package identity, current APK generation and real signing certificates. Production installer authority is limited to the actual Google-signed `com.android.vending`. A same-build debug fixture is separately pinned by complete APK SHA-256 and real certificate; its pin asset is absent from release builds. The Google-compatible signature query view never grants installation authority. Separately, bundled microG identity requires its exact APK/certificate pins.

Explicit current limits:

- `MODE_INHERIT_EXISTING`, split removal, parent/child multi-package sessions, staged/APEX/DataLoader installation, transfer and interactive installation approval are unsupported.
- Only the flags accepted by `InstallerWire.params` are supported. Installer requests cannot grant runtime permissions or arbitrary placement/enabled-state changes.
- The guest uses a local framework proxy backed by our private versioned Binder protocol. Transferring the hidden framework installer/session Binder directly to another process is unsupported.
- Package-event delivery, every Play-selected installation mode and Play's real download/installation flow are not accepted merely because fixture installation succeeds.

Validation commands:

```sh
bash gradlew :mylite-engine:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
python3 scripts/audit-reconstructed-apk.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

`PackageStoreTest` and `SessionRepositoryTest` cover signed package sets, publication/data preservation, session ownership, interruption and rejection paths. The guest fixture uses the standard Android installer API to exercise real file descriptors, partial-write resume, actual commit callbacks, signer rejection and update data preservation. The complete Android suite runs through `scripts/test-reconstruction-runtime.sh` on disposable CI emulators only. See [STATUS](STATUS.md) for the exact accepted runs; Google login, downloads, installed-app startup and physical ARM64/OEM behavior require their own evidence.
