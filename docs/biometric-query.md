# 生物识别能力查询

2026-10-27 再次开放 Y700 后，只读补核完整自有 crash buffer：`715859a` 在13:43—13:48有八次通知私有载荷 fatal，随后13:48:53出现独立的 `BiometricManager.canAuthenticate → IAuthService → AuthService.checkPermission` fatal。之前只记录到第一条通知崩溃的短观察不代表后续没有其他崩溃。未重新打开商店、清除账号或缓存。

首项诊断查原版注册、SDK门槛、方法适配、基础分派和权限清单：原APK SHA256 `6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257`，classes4 SHA256 `ac17de00d90982e036853c804fd9ae304cd28309f4ea1f3862df887060d16d09`。`i1.W` 在 Android30门槛注册 IAuthService/m6；m6配置的 canAuthenticate 使用 d7.i/d7.m，将包名映射为真实宿主，再经公共 m 分派到真实服务。原宿主和内置 Play40 都声明 USE_BIOMETRIC、USE_FINGERPRINT，Mybox此前均缺失。

REA关联证据：注册 `ev_1c38164c57476e4ff33df6d63b2adba5a9500fba5451d4452507330defd910d0`，配置 `ev_8d3585ef5df0a46413894e2831fe2d53112f503954f4f2e7fcc227326ed25ca6`，基础分派 `ev_2f4434ed24cfb9c2e5ffa3fc7f3b601c5d1bd37de8ce7dc8961a2afd68266c13`、`ev_477101c11f237ff1ce3050619dbf7531d505890cfd60697e7ec7d062490cf5cc`，参数映射 `ev_dca36e4a908038ea7a2ec7ec08c089c75c3a635e3389fc7555c6e25515bdb27b`、`ev_0d44e7e7a0eead9c377fe2fd725db550c66634f9fef8154df63c0504735ddca8`，版本门槛 `ev_88c60814ad4bc0371e59ced33aeee8c47c5c03a032e3cb66718e71c3c9912675`。原始字节10944已匹配；m6配置的 fill-array 在0x1004d9c68有Rizin报告尺寸不一致，完整指令解释不接受为全部已验证。canAuthenticate 常量另经保留的解码语义与精确DEX数组校核，未执行原程序；辅助函数未全部另作REA反汇编，此限制保留。

[Android14 AuthService](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/services/core/java/com/android/server/biometrics/AuthService.java)先检查真实调用者所属用户及普通生物识别权限；跨物理用户要求内部权限。其 getAuthenticatorIds 特意允许 Keystore 在未声明生物识别权限时查询，不能用“只开放canAuthenticate”的总开关破坏客体密钥生成。[USE_BIOMETRIC](https://developer.android.com/reference/android/Manifest.permission#USE_BIOMETRIC)是普通权限，新增它不等于授予内部权限或认证成功。

候选在客体 Application 初始化前适配 auth 服务及已缓存 BiometricManager，后续新 Context 同样从缓存取得本地适配器。仅普通能力查询 canAuthenticate 和三个公开字符串查询映射包名、虚拟用户0到实际宿主用户；必须是当前客体/宿主归属及客体清单声明的普通权限。authenticator mask、真实系统返回和异常保持，不把无硬件/未录入等状态改为成功。Keystore 的实际 authenticator-ID 查询保留，不记录真实 ID。未知接口不猜测改参数，特权拒绝由真实 Android 执行。

真实生物识别 Prompt、token和取消归属仍未实现，显式拒绝，不把能力查询当作认证成功。API29及旧 FingerprintManager 路径也未由此验收；代码中的 shortcut 标明扩展边界。

验证包括真实宿主/客体及新 Context 的系统能力结果、DEVICE_CREDENTIAL mask 对照；参数不变、异主/跨用户/未知参数形状、未声明权限、真实后台拒绝不吞、Keystore例外及认证不伪造。接下来须收齐两组完整云端，再保留账号到 Y700 复验商店、下载、安装和打开。该用例未检查真实录入、指纹/人脸交互或完整原版性能。
