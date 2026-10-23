# microG service build

Mybox builds `com.google.android.gms` from [microg/GmsCore v0.3.17.252432](https://github.com/microg/GmsCore/tree/32bc8954ff872d0e1a05ddfae81561b6dbecef69), commit `32bc8954ff872d0e1a05ddfae81561b6dbecef69`. Upstream sources and this patch retain their Apache-2.0 notices. No GBox/VLite binary or decompiled application source is used in this build.

On Linux with Java 17, Android SDK platform 35 and Build Tools 35.0.0:

```sh
export ANDROID_HOME=/path/to/android-sdk
bash scripts/build-microg.sh
```

Gradle 8.13, AGP 8.13.2 and the upstream dependency versions are preserved. Kotlin is explicitly pinned to 2.2.21, the version already selected transitively by Wire 6.4.6 in the original build. The patch removes reliance on that implicit upgrade; the original `tasks` configuration itself was verified to succeed.

The script checks the upstream commit and exact tracked patch, sets `modules.hms=false`, and builds only `:play-services-core:assembleMapboxDefaultDebug`. `default` selects the normal Google signature compatibility metadata and Android location provider. `mapbox` is upstream's historical flavor name for MapLibre Native 10.2.0. Neither the HMS flavor nor Companion is built or installed. The real Play Store occupies `com.android.vending` in the container.

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
