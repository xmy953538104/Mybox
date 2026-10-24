# VLite 重建验证状态

2026-10-24。用户要求改为依据原版 VLite 的实际行为重建核心，并在其使用平板期间仅在电脑及 CI 工作。

活动构建为独立 vlite-engine；Bcore 已从 settings 和宿主依赖中移除。源码没有引入原版 VLite 库或反编译代码。

本地第六次构建已通过宿主/测试 APK、ARM64/x86_64 原生库、28 项 JVM 单元测试和 lint。APK 审计未发现旧引擎、原版VLite、HMS、Pine或Xposed的DEX/原生库。CI 40051288612 已通过 Linux seccomp 真实系统调用及 Android 14 核心运行测试：客体 Application/Provider/Activity、SQLite、硬编码原生路径、跨进程 Service/Provider、真实 Binder 回执、服务生命周期和第二 Activity 跳转。整轮 CI 仍失败于独立 AttributionSource 复制测试；此缺陷已修复，并增加 token/PID/权限链保留断言，等待下一轮 Android 回归。

原生规格已从原版 ARM64 ELF 的 JNI 注册、初始化和 I/O 调度函数恢复：核心使用 seccomp BPF/TSYNC、SIGSYS 和指令地址放行。REA 的 Windows ELF 深度接口不支持此目标，因此Java使用REA，原生函数级证据来自官方Ghidra headless，保留各自工具来源，未伪造REA证据。

当前实现范围：

- APK真实验签、base/config splits、原子提交包记录、CE/DE数据保留、并发安装和失败清理。
- 服务端Provider握手、四个独立客体进程槽、PREPARED/BINDING/BOUND身份状态、LoadedApk与客体Application/Activity加载。
- 原生路径规则及seccomp/SIGSYS文件系统调用重定向，直接系统调用入口、readlink反向映射、错误码和信号mask处理。
- 显式同包跨进程 Service 生命周期、真实 Service Binder 和 Provider 路由，新增自有客体运行测试。
- 虚拟 appId 的包查询、从真实 manifest 恢复的隐式 Intent 匹配；后一项等待 Android 运行测试。
- 精确 microG APK/真实证书双 pin 的身份桥和兼容签名视图，APK验签及账户授权仍使用真实证书。
- 账户数据核心、消费者/认证器证书ACL、可见性和token授权分离、AES-GCM持久化；Android账户服务尚未接入。
- 自有客体测试 Application/Provider/Activity、SQLite及硬编码路径的实际执行；这些运行测试当前尚未完成。

尚未完成完整组件路由、虚拟账户、安装会话、后台任务、真实Google业务和推送。Google核心包的启动在相关隔离和服务实现完成前明确拒绝。账户和组件的下一阶段实现继续独立进行，不能拿此前BlackBox原型的通过记录当作新内核的验收。

兼容边界：首版API26+，ARM64/x86_64；当前仅 x86_64 Android14 核心流程已实测，ARM64和16KB页运行未验收。组件共用宿主真实Linux UID，容器身份不是系统权限。开发签名为公开测试身份。
