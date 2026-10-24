# Original GBox runtime comparison

2026-10-24. This records the original artifacts on a dedicated local emulator. It is separate from Mylite acceptance and from ARM64 device results. Original APKs, libraries and decompiled source are retained privately, outside this repository.

## Exact inputs

| Sample | APK SHA256 | Version | Native ABI |
| --- | --- | --- | --- |
| New | `6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257` | 1.8.3.32 / 1080332 | ARM64 and ARM32 |
| Old | `ffc8f12795c643c96155de9bdc20360f5323a0068dd9f0ebb131bf6ca434f964` | 9.9.99 / 9999 | ARM64 |

Both use `com.gbox.android` and target API28. The new sample was uninstalled before the old sample was installed; this is not a downgrade over shared app data. No Google account or HMS package was added.

The independent owned probe is `io.github.xmy953538104.pathprobe`, APK SHA256 `07dac4c226624bbf3e3f8d4993434951a455695637ae832d154a0a82422f7549`. It compares Java file I/O, ARM64 JNI libc I/O and ARM64 inline raw system calls, using an independent per-run marker at its own hardcoded `/data/user/0/<package>/files` path. It has no network or engine dependency. A probe installation or JNI library load alone is not a passing result.

## Actual environment

Windows host, official emulator37.2.12, Google APIs API30/x86_64 revision16, 600×960, density240, 2GiB RAM, two virtual cores, SwiftShader and Vulkan disabled. Official AEHD2.2 was installed with the user's explicit permission and its Google LLC Authenticode signature checked; hardware acceleration is usable. No Windows feature, BIOS setting or reboot was changed.

Android fingerprint: `google/sdk_gphone_x86_64/generic_x86_64_arm64:11/RSR1.240422.006/12134477:userdebug/dev-keys`. SELinux is Enforcing. `libndk_translation.so` supplies ARM64 NativeBridge translation; ART itself is x86_64. No root shell, CPU information spoofing, property override or original-APK patch was used. All ADB commands named the dedicated emulator; the user's tablet was not used.

## Observed results

1. **Probe installed directly in Android: PASS.** Actual Java/JNI/raw ARM64 reads and writes completed with matching markers, PID5657 / UID10167. This establishes that the image can execute this independent ARM64 probe through NativeBridge. It does not establish that VLite's ART interception supports NativeBridge.
2. **New GBox: early Java crash.** At09:48:53–54 UTC, the main process and `:server` both failed in their HTTP header builder before usable UI. The crash is a null value passed to `ConcurrentSkipListMap.put`, not an observed HMS/network/Google login failure.
3. **Old GBox: home and virtual import succeeded.** After accepting the original terms and battery prompt, its own Clone interface imported that exact probe and displayed it on the home screen. Optional launcher shortcuts were declined.
4. **Old GBox guest: native crash before probe completion.** At09:58:10 UTC, guest PID7581 / physical GBox UID10169 failed with SIGSEGV in x86_64 ART `Class::FindClassMethod → FindMethodJNI → JNI.GetMethodID`, called through `libndk_translation` and translated native code. The host launch view remained black. No guest Java/JNI/raw-I/O result was obtained. This is a mixed-ISA observation, not evidence of the same failure on native ARM64 hardware.

The old host Splash launch reported925ms TotalTime; that is neither a guest cold-start measurement nor a performance comparison with Mylite.

## REA explanation of the new-sample crash

Focused REA method evidence ties the actual stack's DEX line11 to the `soc_model` map insertion. The hardware reader searches `/proc/cpuinfo` for `Hardware`; the real API30 image has no such line. Below API31 the fallback invokes a property helper with a null default. That helper constructs a String from the default before reading the property, catches the resulting exception and returns null. The map then rejects that null. Changing a hardware property or installing HMS cannot repair that String construction.

| Method evidence | REA Evidence ID |
| --- | --- |
| Header builder | `ev_43344797efaaf27e0e04e8a3f22c05ecc75e47587d0bd51d120e0debfc83d6b7` |
| Hardware accessor | `ev_5b89c7407df7c1ff46e7692e1751030d096e96c548f362c6adbbc36ce926927d` |
| CPU information/fallback | `ev_4ca92eb08bfc9be1257f01ed572ee9eaed471815e8189be9fe626b4e6c26a76a` |
| Property helper | `ev_c51de90a622afa7e3473310a2bccc4b4b2f436ed5daec18bab529d73bc45b35f` |

Each evidence subject is the new APK SHA256 above. The separate `engine.artifact_sha256` identifies the analysis JAR, not the APK.

## Next environment boundary

A separate API27 native-ARM64 image has now booted under emulator34.2.16's software AArch64 backend. Local launcher disassembly and the [fixed AOSP34.2 source](https://android.googlesource.com/platform/external/qemu/+/d6203d016f5067089215c2caf3448412de961c15/android/emulator/main-emulator.cpp) show the x86-host ARM64 rejection applies at API28 and above in that version. The default ranchu board failed on its generated HDA device because it exposes no PCI bus. The same official backend's Android-adapted virt board includes PCI and goldfish devices; selecting `-qemu -machine virt` allowed this image to boot, without modifying an executable, APK or system image. Its library directory also had to be present in the launch PATH. Failed attempts remain retained.

Actual fingerprint is `Android/sdk_phone_arm64/generic_arm64:8.1.0/OSM1.180201.044.D2/10042287:userdebug/test-keys`, ABI `arm64-v8a`, native bridge `0`, SELinux Enforcing, boot completed. The exact independent probe passed Java/JNI libc/raw-svc round trips on this image, PID2713 / UID10059, native uname `aarch64`. Device REA evidence is `ev_20ba5a8ac757992839190ef737962b276c8217046ab3ca6cce01a6fcbe7e553f`. The exact old GBox APK has been installed (`ev_e75ee396afc3330eb9ce2bbaad9fc82264847b7a216bbdf18844f6d31f5b3938`); its guest result on this environment remains pending. Software-emulated execution cannot establish OEM performance.

Native ARM64 alone does not guarantee the new sample's header crash disappears: the inspected official ARM64 goldfish3.18 CPU-information implementation also omits `Hardware`. Actual image CPU information must be read. An API31+ image is an alternative because that original code path uses `Build.SOC_MODEL`; its runtime result remains unverified.

No reference guest I/O redirection, Google login, Play installation, push delivery or performance parity has passed this comparison yet. Those missing results remain required.
