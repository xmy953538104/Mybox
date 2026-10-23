# Guest cross-profile boundary

Mybox has no mapping from a guest to other Android profiles. Android 9+ `CrossProfileApps` is therefore scoped to the currently bound guest: `getTargetUserProfiles` returns an empty list, and interaction/consent/configuration capability queries return `false`. A query claiming another package or Android user is rejected. Android 14 and 15 expose ten Binder methods; every one is handled explicitly.

Both activity-start entry points and all app-op administration writes throw `SecurityException`. The container does not delegate these operations to the host package or grant access to a real work/private profile. Unknown methods and raw Binder transactions are rejected. The framework's cached manager and subsequent service lookups receive the same guest proxy. The host process keeps the real service.

The trigger was Play's download UI calling `getTargetUserProfiles` with `com.android.vending` while its real UID belonged to Mybox. The real Android service rejected that package/UID pair. Returning the actual empty virtual profile scope avoids that invalid RPC without claiming cross-profile support.

`CrossProfileCompatibilityTest` launches a separate guest fixture. It checks the framework query and consent APIs, caller-package/user boundaries, both launch paths, all administration writes, raw Binder rejection, and unchanged host queries. Actual Play downloading still requires a separate device acceptance test.

Limitation: cross-profile launching, including the same-user overload of the cross-profile intent API, is deliberately unavailable. Ordinary guest activity launches use the existing container activity path. Adding work-profile support requires an explicit mapping and authorization model before this boundary can change.

Primary sources: [Android 14 interface](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android14-release/core/java/android/content/pm/ICrossProfileApps.aidl), [Android 15 interface](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android15-release/core/java/android/content/pm/ICrossProfileApps.aidl), [framework API contract](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android14-release/core/java/android/content/pm/CrossProfileApps.java), and [Android 14 service checks](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android14-release/services/core/java/com/android/server/pm/CrossProfileAppsServiceImpl.java).
