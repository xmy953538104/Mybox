# 实施与验证状态

2026-10-23：已从源码构建并安装开发 APK；完整 Google 业务流程尚未验收。

| 阶段 | 范围 | 当前状态 |
| --- | --- | --- |
| P0 | 开源宿主/ARM64 引擎构建、移除 Pine/Xposed、Android 14 启动 | 本地构建、单元测试和 lint 通过；客体启动、SQLite、跨进程 Provider 及进程边界通过；原生 openat/stat/rename/相对目录读取/unlink、WebView、Android 14 跨进程服务绑定、任务清除与语言状态恢复均实测通过 |
| P1 | microG 无 HMS 构建、受控兼容签名、账户/服务路由 | 实际安装及两种签名查询、设置/签名自检、Google 网络检查通过。原版 Play 已调起 microG 认证器；Google 设备注册响应成功解码，真实 Google 账号网页已加载。用户完成网页交互后 Play 仍显示登录入口，正在检查账号写入与返回链；账号持久化和商店内容尚未验收 |
| P2 | 真正 PackageInstaller.Session、base/split、更新保留数据 | 自有 fixture 的完整会话流程已通过：FileBridge fsync/close、split 代码资源、组件状态、会话所有权、坏签名/缺 split/损坏 APK 拒绝、更新数据保留、同进程查询新版及取消会话。真实 Play 下载与更新仍待验收 |
| P3 | Google 应用、地图与推送 | 未开始完整业务验收；依赖 P1 真实账号及 Play 安装链完成 |

已接入测试设备：联想 TB321FU，Android 14/API 34；设备有 Root，验收按普通应用身份运行，不使用 Root 安装或修改系统。

设备未安装 HMS Core。系统原有 GMS 和 Store 保留；容器测试必须证明使用容器内服务，不能将系统兜底计为通过。无虚拟 GMS 时客体没有读取系统 Google 认证器；非认证器读取密码、读取及写入 token 的负面测试通过。

开发 APK 内置自编译 microG；Google Play 是用户提供的外部 APK，验收版本 40.0.13-23，实际 Google 证书与 APK SHA-256 均验证。它未提交到源码仓库或宿主资产。当前开发签名密钥公开，仅适用于开发验证。

提交 `2539602` 的完整 CI（宿主、microG、合并 APK）已通过；其后原生 I/O、生命周期、隐私日志及兼容修复已通过本地第十一次构建和以下设备回归，完整 CI 产物以对应提交的 Actions 状态为准。构建成功不代表 P1–P3 的业务验收通过。小米、16 KB 页设备和其他 Android 版本尚未验证。

设备回归已覆盖 Binder 服务连接复用、死亡进程任务清理、物理进程槽保留、损坏 APK 状态码、Provider AttributionSource 和平台 Apache HTTP 库。第十一次本地构建通过编译、单元测试和 lint；13 项设备回归全部通过（46.795 秒）：真实跨进程 int/long 服务绑定、语言设置/冷启动恢复/清除后新 Activity、35 秒空闲后的服务依赖、PM 通信错误语义、5 项生命周期用例、P0 I/O/WebView、完整 P2 fixture、microG 组件解析。原生路径规则的并发、边界与长度测试也已在设备运行通过。

此前 microG 登录查询失败由服务进程被系统冻结并终止引起。现在每个客户端保留真实 stable provider 连接；平台 dumpsys 已显示各客体的稳定依赖，前台登录页面空闲后原服务进程仍可响应。Android 14 的 `bindServiceInstance` 已纳入服务代理，真实 WebView 绑定保留实例名及 64 位 flags。网络曾出现三个 Google 域同时 TLS 握手失败，用户调整网络后同一宿主检查全部通过（1.892 秒）；随后完成设备注册并加载真实 Google 登录页。没有关闭 TLS 校验、绕过注册或生成假账号/token。

Google 账号凭据由用户在 Google 登录页输入。测试日志不得提交 token、cookie 或账户隐私。
