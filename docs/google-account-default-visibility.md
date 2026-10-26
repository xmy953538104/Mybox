# Google default account enumeration

Y700's retained-data Store40 stayed at Sign in although microG's own settings displayed an account. The eab5cc3 diagnostic captured actual `jtp.q` in the Store main process and `jtr.a` in its background process calling ordinary `AccountManager.getAccounts`. Main-process initial enumeration and listener refresh both returned empty; the selected microG snapshots had no account-provider or access-request calls. These are bounded snapshots, not proof of invalid credentials or of a provider never running.

Original-artifact REA had to cover initialization as well as the account API. Original package installation calls `i.J -> i.E -> i.K -> i.r` before publication. When the parsed application requests signature compatibility and supplies `fake-signature` metadata, the original modifies parsed `mSignatures` or `mSigningDetails`. The metadata key was statically decoded from verified DEX instructions/raw array data; the embedded microG resource's certificate matches Store40's actual Google publisher. Original `checkUidSignatures -> checkSignatures` reads that parsed signing state. Its account policy considers an explicit visibility entry first, then uses `legacy_visible` (default 2) for the matching-signature branch, otherwise `legacy_not_visible` (default 4).

Mylite previously supplied the signature through outward `getPackageInfo` metadata only. Its account repository deliberately kept actual owner/caller certificates and always selected the hidden fallback for a nonowner without an explicit entry. Thus inspecting only microG's provider would miss the actual Store path and this internal difference.

The candidate adds a narrowly verified enumeration default:

- Only a currently approved Google authenticator and Google account type may request it. The exact source-built microG APK/hash/signature pins still apply.
- The compatibility certificate is validated through the existing signature-view logic; the source generation must remain current during metadata lookup. The consumer's actual current APK signing identity is independently verified. Its current certificate must equal that compatibility certificate.
- Existing explicit visibility entries, including hidden 3/4, take precedence. A qualifying caller uses the actual stored `legacy_visible` value or default 2; every other caller keeps the existing hidden fallback.
- No real package/owner certificate, account record format, password, user data, raw token or token grant is replaced. Enumeration is not credential authority. Reading the default does not persist account data or grants.

Past signing keys alone do not qualify. Rotation requires a separate policy for preserving explicit denials and is intentionally not inferred from publisher history. This candidate also does not implement every Android pre-O/contacts/device-owner visibility branch.

The [Android 14 account-service source](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/services/core/java/com/android/server/accounts/AccountManagerService.java) is the fixed platform comparison for default visibility and separate authenticator sessions. Original signature substitution is broader than this independent enumeration policy; no general permission or credential bypass is being reproduced.

Two repository tests cover the approved default, unchanged persisted bytes, separate password/data/raw-token/token-grant denials, explicit 3/4, legacy override, reopen, policy withdrawal, consumer-key replacement and retired authenticator. The real Google journey checks the actual pinned microG and cryptographically verified Store publisher qualify while the development signer and consumer-as-authenticator do not. Existing signature-view tests continue checking the real signer remains intact. The prior complete eab5cc3 core and journey-only diagnostic were collected before selecting this change.

The candidate is unbuilt at commit time. Genuine retained-account discovery, authenticated catalog, download/install/open and non-instrumented background behavior still require runtime evidence. The independent cd08c5c modern-image notification swipe failure remains retained and unresolved; a later green run does not erase it.
