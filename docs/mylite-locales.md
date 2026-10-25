# Mylite guest language compatibility

Y700/API34 running authentic Play40.0.13 first crashed in `LocaleManager.setApplicationLocales`: the physical host UID does not own `com.android.vending`. Granting CHANGE_CONFIGURATION or forwarding a different installed package would change the wrong application's settings.

Focused REA of the supplied original APK confirms a separate locale hook and client resource update: `q7.b.g` selects the guest package and LocaleList; `h7.e` updates guest Resources, restores system languages for an empty request, and persists the choice. Its server setter alone does not describe that complete path. Original code remains private analysis material; Mylite implements its own adapter.

Evidence: `ev_3d48a786b86f529121d79e934a6a4497ebc73995be1b86e064d6332ba61054fc` (hook), `ev_de66ad423dca4ec67a8f59d22fd30e9d49aa36293c445da0f23bda18550887f0` (client update). Provider: JADX headless 0.7.1, original APK SHA-256 `6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257`; complete private JSON retained.

`GuestLocaleCalls` is enabled only on API34, whose AIDL was checked. It accepts only a local call for the bound guest and user zero, rejects raw Binder calls and foreign identities, and retains app-language and supported-language overrides separately. Preferences use an AtomicFile and a cross-process file lock, outside the guest APK generation. The actual system-language query remains delegated. No setter reaches the physical LocaleManager.

Initial resources are configured after sealing LoadedApk identity and before constructing Application. Subsequent resource updates are posted to the real main looper, rather than blocking a worker that Application initialization might be waiting for. The update reads the latest committed preference. Tracked activities receive a configuration callback if both locale and layout-direction changes are handled, otherwise recreation. New activities restore the preference before onCreate.

Y700 boundary checks passed (four tests including IME and autofill, 0.086 seconds). The independent public-API locale probe passed in 2.332 seconds: actual French Application/Activity resources and configuration callback, exact owned guest process termination, saved preference/resources before the next Activity.onCreate, clearing back to real system languages, and unchanged host resources. This does not validate all Android locale behavior.

Limits: other Android API versions, already-running sibling-process notification, installer/backup locale broadcasts, custom configuration contexts and resource fallback ordering are not complete. Preferences currently live under host `files/mylite-locales`; guest uninstall cleanup must be connected when virtual uninstall is implemented. No parity claim is made for those paths.

The older [guest-locales.md](guest-locales.md) documents the former Bcore prototype. That module is excluded from the Mylite product and its former tests are not Mylite evidence.
