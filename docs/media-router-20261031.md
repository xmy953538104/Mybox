# Y700 失败整批与媒体路由候选

2026-10-31 对 `62e1c06` 保数据覆盖后进行了两段经授权实机观察。升级瞬间账号文件摘要/大小、31个安装会话及翻译安装记录一致；运行中账号摘要发生变化，没有解密、编辑或重置账号，不能称运行后逐字节不变。

## 实际结果

- Apple Music 越过先前 Application 网络安全配置异常并完成 guest_bound，随后在 `IMediaRouterService.registerRouter2 → MediaRouter2.registerRouteCallback` 出现真实 SecurityException。没有实际播放成功。
- 翻译在 `IBlobStoreManager.getLeasedBlobs → BlobStoreManager.getLeasedBlobs` 出现真实 SecurityException，返回 Mybox。现有诊断已经保留这两个框架帧；没有重试掩盖失败。
- 既有账号的 Play 可进入首页和搜索框；合成画面确认真实输入法可见，查询实际进入输入框，提交后显示 DF-DFERH-01。同进程、同查询时段有 getItems HTTP 400；这只是关联，不证明唯一原因。本轮未进入下载、安装或打开新应用。
- 后一窗口实际于 06:33:18 UTC 开始，06:39:46 UTC 已提前完成独立收尾：debug OFF、HOME、观察器停止、五份固定结构日志导出及 ZIP CRC 核验。自动收尾实际执行成功。前一窗口的超时/未收尾记录继续保留，没有倒填为成功。
- 最终日志 1,019,647 字节，按 pid/epoch/mono_ns/area/operation/id 去重 3,171 条，其中后一窗口新增 3,047 条、reported dropped 319。保留的新增 account_api 含真实枚举、异步 token 和用户数据操作；没有新增 account_api 错误，但不等于服务器接受认证。历史错误仍在错误文件中；收尾时有限 logcat 未命中不推翻已经保留的崩溃。

## 原版 REA 与关联配置

原版 classes4.dex SHA256 `ac17de00d90982e036853c804fd9ae304cd28309f4ea1f3862df887060d16d09`。媒体链保留14段完整方法返回，共11,200字节：服务注册表 i1.W、carrier/解码器、w0 注册/解码器/初始化、d7$f/d7$i 与 d7.m/p/s、servicehook.h.i 和 m.invoke/r。证据包括 registry `ev_b25d6171f8719311f120360f54e624fc83cc4e00b939516fc2b5f71d4f355c0f`、注册 `ev_a2da3b253928d88df69cdd5370e8e15dc8ba557e72c00f1352d0b71880b6b6ed`、包映射 `ev_6636c3aea0676247c23811f88db0148d23d3b04a45ffefdd76b08f1a3976532d`。

原始DEX字符串/字节核对解出服务键 media_router 及 registerClientAsUser/registerRouter2/registerManager/getSystemRoutes；关联处理器映射物理宿主包和物理用户，然后委派实际服务。初始化/配置决定是否走处理器，没有证据支持伪造路由或授予系统权限。数组 payload 的工具显示地址存在8字节偏差，原字节相对偏移已另行核实，不把该数据区误称可执行指令。

对照 [AOSP Android 14 AIDL](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/media/java/android/media/IMediaRouterService.aidl)、[服务权限检查](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/media/MediaRouterService.java)、[MediaRouter2](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/media/java/android/media/MediaRouter2.java) 及 8/11/15 版本缓存布局。API35 的 getSystemRoutes 有调用包和 proxy 布尔参数，不能只照搬 API34 的零参数模板。现代系统路由器还会引用共享 MediaRouter2Manager；现有缓存和相应框架锁一并检查。

## 独立实现及验证范围

候选在客体 Application 创建前安装进程本地 media_router 适配：只映射已核实接口的本客体/宿主调用包和当前用户，保持回调、路由、标志及真实 OS 异常；不全局替换 String，也不替换目标包、groupId 或路由 ID。缓存对象保留原身份与回调，仅替换服务引用。对未知版本/签名保留明确错误。

该本地 Java 边界以实际 Process UID/PID 固定进程身份；合法 incoming callback 内的 Binder callingUid 不等于当前进程，不能在此误拒。原始 Binder transact 明确拒绝请求归属转换，跨进程不能通过这张门面获得权限。诊断只增加 android.media 的已知框架帧，仍最多4个框架帧、合计8帧，没有异常消息、路线名或任意参数。

新增 JVM 正负例及真实 Android legacy/modern 注册、系统路由、初始回调、取消注册、外包拒绝、原始事务拒绝和每轮 nonce 留证。源候选尚待云端编译及两组完整运行；这些检查通过也不等于 Apple Music 播放或完整投屏兼容通过。

BlobStore 仍未修复：盲目让全部客体共用宿主租约/枚举/releaseAll 会丢失客体隔离，先前原型已拒绝，未合入产品。Play HTTP400 也仍未修复；已按首项诊断规则复核匹配原版请求工厂及头部初始化/配置，不能据状态码猜测清缓存、重登或更换设备身份。
