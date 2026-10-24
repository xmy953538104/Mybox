# Mybox / Mylite

- Mybox is the app; Mylite is the independent core in `mylite-engine`. VLite names refer to original-artifact evidence, not our product.
- Read `docs/OBJECTIVES.md` and `docs/STATUS.md` before changing runtime behavior. The user requires GBox core compatibility, performance and stability, excluding VIP and HMS; a settings screen or fixture pass is not full acceptance.
- Use REA evidence to guide reconstruction. Keep original APKs, private binaries and decompiled original code out of this repository. Do not reintroduce inactive Bcore as the implementation.
- Preserve real signature/caller checks and actual Android completion/error semantics. Do not fake Google accounts/tokens, installation results, task completion or foreground status.
- Keep app identity, existing data and account-key compatibility during renames. Unknown unsupported behavior must remain visible until implemented and tested.
- Current verification uses PC/static tools and disposable CI emulators. Coordinate with the user before Y700/physical-device work; notify them when cloud results are stable enough to need real hardware.
- Build/check: `:mylite-engine:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`; audit both host and test APK with `scripts/audit-reconstructed-apk.py`.
- Runtime evidence must distinguish Linux, Android emulator and ARM64/OEM hardware. Keep every stability round and raw benchmark environment/results; never retry away a failure or claim unmeasured parity.
