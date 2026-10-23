# Virtual package installer

Host imports call `BlackBoxCore.getBPackageManager().installPackageSetAsUser(files, option, 0, hostPackage)`.
`files` contains the base APK and every required split; with `FLAG_URI_FILE`, every entry is a content URI.
Direct import, clear, stop and uninstall endpoints reject registered guest callers. Guest installers use
the standard Android `PackageInstaller` API instead.

Both paths parse a complete cluster and verify actual APK signatures before publishing code. Package,
version, split dependencies, ABI, provider authorities and existing signer checks run before replacing
the installed package. A stage contains base/splits, extracted native libraries and package settings.
The installer then swaps that directory with a recoverable backup. App data is in a separate directory
and is retained on updates and validation failures. A failed component-index update rolls code and
settings back too. On a successful commit, the installer emits internal virtual package broadcasts;
it does not send Android's protected package broadcasts as the physical host.

`com.google.android.gms` is accepted only when its exact base APK hash and actual signing certificate
match `assets/mybox/trusted-microg.properties`. Only that approved package receives a Google-compatible
signature query view. Update checks retain the actual APK signer. Initial `com.android.vending` imports
also require one of the fixed upstream microG privileged Google signing keys.

Virtual sessions support create/open/query, callback registration, progress, write/read/fsync/close,
full and inherit-existing split sets, split removal, commit and abandon. The write endpoint implements
both the Android FileBridge protocol and the platform's raw descriptor mode. A commit reports the
actual install result through `IntentSender` and `SessionCallback`. Session metadata and staged APKs
survive installer restarts; the owner can reopen and recommit them. The implementation does not
invent an authentication token or a successful installation result after interruption.

Current explicit limits:

- Multi-package parent/child sessions, staged system/APEX installation, incremental DataLoader
  installation and session transfer are unsupported.
- The Java framework interface is proxied locally. Raw hidden Binder interface transfer between
  guest processes requires an additional versioned transaction bridge.
- APK signing-key rotation is rejected until verified signing-lineage support is implemented.
- Serialized engine settings are a development schema, not a stable upgrade format across Android
  framework versions. Unreadable settings are retained for recovery rather than deleted.

Run `python tools/test-installer.py` for the Android-independent signature, split, session state,
native extraction and directory rollback checks. `Bcore:testDebugUnitTest` also covers component-state
copy/override behavior. These checks are not proof that Play downloads work.

The `probe` APK embeds real `session-target` v1/v2/bad-signer base APKs plus `session_feature` feature
splits. `SessionInstallerProbe` uses standard guest APIs to check real result callbacks, split dex and
resources, update data preservation, incorrect signatures, missing splits, corrupt files, session
ownership and abandon. Run the host `ContainerSmokeTest` instrumentation on a device to execute it;
the test cleans the fixture before and after each run. Google account, Play downloads and Google app
functionality require their separate real-device checks.
