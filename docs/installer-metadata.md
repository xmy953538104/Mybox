# Play 安装会话的校验和与 DEX 元数据

2026-10-27，Y700 保留账号及已下载的 Google 翻译 APK/DM，升级 `7ebd909` 后真实商店恢复安装，在 `PackageInstaller.Session.setChecksums` 被未实现分支阻断；随后 Play 对已经 close 的 Session 调用 abandon，又被客体本地 closed 标记拒绝。初始观察没有新的 fatal crash，但安装仍失败，不能作为业务验收。

首项诊断先用 REA 对照原版安装链，补齐完整 setChecksums、close、abandon、staging filter 及两个 DM helper。原 APK SHA256 `6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257`，classes4 SHA256 `ac17de00d90982e036853c804fd9ae304cd28309f4ea1f3862df887060d16d09`。六方法720字节逐指令与 DEX 校核。此前实际安装、参数、提交/校验/结果及进程恢复链证据保留，未将一个孤立空方法当作全部实现。

- setChecksums 52字节，`ev_7dc3678517353d00f5359f67e586492e457ae7b1bd50dd819804a871f3b2d294`：只有计数/混淆分支，没有消费 checksum 参数。
- 过滤器及 DM helper，`ev_b46fe898f28b9ce8c5e051be04210340e618f73900221a7fe96c51968a7eadc2`、`ev_29ba8f2220fc85a0c0cc1349c3b273d7a71809e1d0a12f7506c8dbef6aaf03fb`、`ev_1d03e7026b2f37062cbf61b5c8cd2d6520b19a31049c6e3adf6eef3855e9797b`：APK 校验枚举排除目录、`.removed` 和 `.dm`。本轮只实现相关 `.dm` 边界；removeSplit 仍明确不支持。
- close/abandon，`ev_d0129da554c01b72d5bc1890b1725cf60056341bf65bacdbe563baf3e63e6bb3`、`ev_c36c0f84a3802e608b1bdb99256f3c4443c1fd1ab46ff8110fd86dbe36885aa5`：close 仅减少活跃引用；abandon 独立判断终态并销毁、发送失败结果，不依赖 handle 保持 active。

[AOSP14 服务端](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/services/core/java/com/android/server/pm/PackageInstallerSession.java) 的 installer-provided checksums 是可选缓存，独立于 APK 签名验证；close 释放活跃引用，不销毁会话。Mylite 不照抄原版弱身份检查：checksum 请求仍核当前虚拟身份、会话归属、OPEN/SEALED 状态、文件名和有界类型/摘要长度。可选 hints 被丢弃，签名字节也不被当成可信证据；没有保存/验证 checksum 或 PKCS7 的声明，requestChecksums 仍明确不支持。APK 内容/签名、base/split 一致性、真实发布及结果回调继续走原有检查。

DM 保留在 staging 中，继续路径、配额、读写和恢复检查，但不交给 APK 解析/发布；只有 DM 的会话不能提交。不宣称已支持 ART profile 优化。close 后仅允许 abandon 清理，其他本地关闭限制保持，服务端归属及提交中不可销毁规则保持。无需改 journal、账号、签名或包名。

回归覆盖：真实 Android 客体通过公开 API 设置 SHA256 hints、拒绝越界文件名，写入/回读 DM 后完成真实版本更新和回调、保持旧数据，close 后 abandon；JVM 覆盖 staging 恢复、DM-only 拒绝、异主/终态/类型/长度/过大签名拒绝，以及 hints 不能绕过真实 APK signer 检查。候选尚待完整云端及 Y700 实际安装/打开验证。
