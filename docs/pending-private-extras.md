# Play 下载通知与 PendingIntent 私有载荷

2026-10-27，`715859a` 两组完整云端已过，但 Y700 实际重新安装翻译仍失败。上一失败会话被 Play 正常 abandon 后清理；新的会话 `1704241692` 尚无下载字节。实际 FGS 栈经过 `GuestNotificationCalls.prepare`，服务端完整栈定位到 `ServerPendingIntents.materialize → OsPendingStore.validate/copyIntent → bundle.get`：持久化遍历会解析通知按钮原始 Intent 中的客体私有 Parcelable。之前的回归只有 Notification.Action 自身 extras 使用私有类，真实 FGS 动作还是 null，漏掉了此关联路径。修复通知正文边界不能代替原始 PendingIntent 载荷的验证。

首项诊断先复核原版完整通知/Service 关联证据，随后补原版 utils.f.d（460字节）与 f.j（108字节）：原始 Intent 保留在代理封装中，还原时设置客体 ClassLoader，并合并 base/fill-in Bundle。原 APK SHA256 `6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257`，classes4 SHA256 `ac17de00d90982e036853c804fd9ae304cd28309f4ea1f3862df887060d16d09`，REA `ev_a4ab045a3ae37a7352b3d2ac6a74824d31ebad1aed15e797357014e72f9202b9`、`ev_62bd956e11c93eca3fe053e8cd2f7f7a84e53d27da2249fa3d7783175d0876cf`；旧封装证据 `ev_ad819b57d1aeee87e09dcb6011ba2da92071cda60651aea43057c1d0f2df21a8` 保留。原版的 ClipData/URI grant 支持没有被本候选据此记作完成。

候选在客体进程打包 immutable PendingIntent 的 extras，宿主仅保存有界字节、schema 和 Android fingerprint，继续复用原有 primitive journal、完整 HMAC、boot、真实签名/包代次、权限与可变性检查。实际 Receiver、Service、Activity 和 OnFinished 均在客体 ClassLoader 下还原；服务端不加载客体类。OnFinished 使用真实 IIntentReceiver 协议转发，验证当前绑定服务端 PID/UID/Binder，保留 Handler、结果和完成时序。journal 格式及账号密钥没有修改。观察器改用原本已唯一的 action（包含 run/kind）定位通知广播，不在宿主解析客体 extras。

[Android Parcel 的格式不提供跨平台版本持久化兼容](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/os/Parcel.java)，因此此载荷仅用于现有 boot 内、同 Android build 的能力恢复；不同 fingerprint 的记录在 reconciliation 中取消原 OS 能力并退休，签名验证入口也拒绝其投递。它不是通用永久序列化。完整能力仍受32KiB限制；FD、Binder 对象及超界载荷保留原有路径和拒绝条件，不丢值或制造持久化成功。mutable 私有载荷、跨 build 恢复与完整 URI grant 仍未完成。

新增覆盖使用宿主类路径不存在的 NotificationMetadata，放进原始 PendingIntent 的嵌套 Bundle：普通通知 Activity 点击、广播按钮/删除及冷服务端恢复、真实 FGS 的非空广播动作、注册接收器及真实 OnFinished 都检查实际值。另有源码对象不变、跨平台拒绝、尾随数据拒绝、Binder 不被静默序列化的回归。所有原撤销/旧代次/可变性与真实 OS 检查保留，不能只接受 fixture 发出一个 PASS 标记。

同批另有安装前 `updateSessionAppIcon` 未实现阻断，及 `getDataLoaderParams` 可选查询报错。原版 installer.f.updateSessionAppIcon（258字节，`ev_5599a966cb2683f7a4ae5651d36c2c1beae3b80fdca8bc2d59ffa0042ed64b14`）核归属、非销毁状态，更新真实 Bitmap 并触发 badging；installer.d.getDataLoaderParams（174字节，`ev_5369c0e684c000fe7ad22af15d21446bdeff5dc7ec6de0d5f1ce066eb149b617`）根据实际 SessionParams 返回数据或 null。

候选支持创建图标、更新/清除、SessionInfo 实际图像及 badging；图标按比例缩至96px并限制48KiB PNG，服务端验证真实格式/边界/解码后原子存入原 session journal，旧无 icon 字段仍可读。当前身份与归属先核，终态不再变更/发假 badging，SEALED/COMMITTING 可更新图标元数据；签名、APK 和发布状态不受影响。普通会话真实返回 null DataLoader，创建 DataLoader 安装的拒绝保持。回归覆盖初始/变更像素与尺寸、清除、label 不丢 icon、重开与引用隔离、异主/终态/过界/坏 PNG，以及真实非流式会话查询。

本候选尚待完整云端验证，不宣称真机下载、安装、打开或整体稳定性已通过。重复 auth-provider 的非 fatal 日志仍未定因，未修改账户/token；真实 socket 路由、历史最近任务、虚拟包枚举和完整原版性能对照仍是独立未完成项。
