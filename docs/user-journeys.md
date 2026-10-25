# 可操作性与原版差距

本表取代“页面能显示就可以交给用户”的验收方式。它没有宣称所有差距已经解决；用户暂停 Y700 访问后，后续只在源码、REA 静态分析与一次性云端镜像中工作。

2026-10-25/26 恢复工作后的窗口/Toast、Activity PendingIntent和标准交互通知已在c7de65b的核心CI40207730922连续三轮通过，包含真实触摸及客体回执。下表其余未实现项不会自动改记为通过。Play85361540已真实导入并越过历史退出查询DUMP阻断，目前完整总装仍在Application启动任务屏障处超时；旧Play35则观测到反复的授权后null前缀查询及Application Future等待，两者分开分析。完整认证、下载和安装业务均未通过。完整范围包括表外正常功能：跨包分享/文件选择/URI授权/快捷方式、危险权限、各类通知与后台组件、Java/Native设备环境及一致性；不会因分阶段实现而从范围删除。

| 用户动作 | 原版证据 | Mylite 当前边界与下一验收 |
| --- | --- | --- |
| 首次安装 Google 组件 | 原包有单独的 Play 预置 APK；microG 本身不是商店 | 原按钮仅装 microG 是产品缺口。已补验真 Google 签名、导入本机 Play/splits、重复安装保留代次和明确缺包提示。正版镜像旧签名35与有真实PoR的新签名85361540均已实际导入，后者包含七个APK；这不等于启动或业务通过。公共源码/CI 不包含明文原版 Play 二进制。 |
| 点击 Google 登录并输入 | `h8.b.g` 改 EditorInfo.packageName 后调用真实 IME | 已补 EditorInfo 的独立拷贝与宿主归属转换，保留窗口 token、真实输入连接及原结果。必须观察实际输入法窗口和可编辑字段；Google 网页能显示不等于能输入。 |
| 长按、复制、粘贴、清空 | `r6.c` 注册 content/metadata/has/listener/clear 等整组 clipboard hooks | 已补内容读写及 clear 的校验和映射，前后台读取边界保留。listener 尚未扩展；首次 CI 的菜单文本 ACTION_CLICK 返回 false，故尚未接受为粘贴通过。改用真实触摸菜单并保存三阶段截图后，030279e 核心/总装及 d97cbc8 核心各三轮均实际通过，DOM 真收到合成文字且页面存活。 |
| 密码管理器/自动填充 | `w8.c.w` 映射 ComponentName 包名；原注册还涉及 UID/user 映射 | 新发现未适配的 startSession 导致真实 WebView 等 5000ms。API34 新实现将经过实际 Activity/token 核验的组件映射到物理 stub，真实系统服务决定结果，不关闭 autofill、不伪造回调。d97cbc8 的 API34 核心三轮均通过，真实交互未再出现 5000ms 超时。其他 API 尚未启用此适配。 |
| 弹窗、菜单、窗口焦点 | `i1.Z` 替换 WMS 多个缓存；`d.c` 拦截 IWindowSession.add/addToDisplay/relayout；`c.a` 改 LayoutParams.packageName 及部分旧 overlay type | 已重建普通窗口归属及Toast系统边界，保留真实token/参数/回调和OS结果；API34三轮实际触摸Popup/Dialog、Toast显示/隐藏回执与截图通过。分屏、旋转、覆盖窗及其他API仍需分别验收。 |
| 通知点击和按钮 | 原版按sender类型包装真实Activity/Service/Receiver入口，通知边界调整系统身份 | 标准通知正文及Activity按钮已有真实SystemUI点击和客体回执。新[Broadcast动作/删除候选](notification-broadcast-actions.md)复用真实OS广播capability，补点击/滑除、冷恢复与撤销测试，尚待云端运行；Service/FGS按钮、可变Broadcast RemoteInput、RemoteViews等仍未完成。 |
| 返回、键盘遮挡、系统手势 | `u2.g` 保留 IME insets，结合导航栏底部计算边距；其他分支涉及 edge-to-edge 和 softInputMode | 系统真实 token 仍在，但原版兼容补丁未等量覆盖。新增正版 Play 镜像的边缘滑动：先关闭实际键盘，再取消账号登录并回到商店。OEM/Android15、分屏及屏幕旋转仍需补验。 |
| 权限授权与系统设置页 | 原版有独立 AppOps、设备/用户和相关系统服务代理 | 现有普通权限读取、签名权限和宿主真实权限边界通过部分用例；危险权限交互、通知授权、位置、悬浮窗及跨用户不能用自检假勾选替代。需逐项连接到真实宿主授权与客体授权记录。 |
| 机型与设备信息 | DeviceEnvInfo 有 buildFields/systemProperties/androidId/deviceId/serial/MAC/UA 等字段；客户端应用 Build 字段并调用 Native.addNativeProp，WebView 另有 UA hook | Mylite 尚无对应的完整设备配置层。应区分兼容型号、稳定容器标识与真实硬件能力，先恢复配置来源/作用对象/生命周期，再实现一致的 Java/属性/WebView 视图；不伪报未支持的 SDK 或 ABI。设备信息替换也不能等同于系统级硬件认证。 |
| 商店浏览、下载、安装、更新 | 原版商店是独立 Google APK；容器负责账户、网络、下载进程、任务和安装会话 | 包/签名、真实 Installer 会话、OS Job/Alarm 及状态回调已有分层证据；实际账号认证后的目录、真实下载字节、安装成功、打开目标应用、应用更新和取消仍没有完整业务闭环证据。 |
| 后台、进程死亡与恢复 | 原版有独立进程/组件、Job、Alarm 及 native 边界 | 已有真实 OS 调度、重启恢复和死亡回收检查；新增过期物理 Activity 丢弃 guest Fragment 保存状态。须验证用户登录态、下载任务和安装状态的恢复，而不只比较 Binder 是否存活。 |
| 性能与稳定性 | 需要原版同机同操作对照，静态 hook 数量不能说明性能 | 原生基准和几轮 fixture 只覆盖局部。需要启动到可点击、打开键盘、菜单响应、滚动、下载吞吐、安装耗时、内存、持续前后台及恢复成功率；目前不宣称与 GBox 等同。 |

## 本轮 REA 证据

输入样本 SHA-256：`6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257`。调用方提供的 JADX headless 0.7.1，Java 静态解析；原始 JSON 保留在仓库外的私有分析目录。

- IME：`ev_e9338bcebf96a29e4369e0d6a0d7a9aa97f757f5f4b6e54ea01fa08fc1a8a9d5`。
- 完整 clipboard 注册：`ev_0df4dc34a262ef3951c9349c01c2d2031836d0d6215e95c06c081537f7df270d`；通用映射前置调用：`ev_a90877dcf095a1db3cff818b638c5a598dbf4bccebb18b8cc7b944874dcdee9f`。
- WMS 缓存：`ev_0ba1a3aa8c843dbddf1965b546c3967e7fa031fe1bc9575f667e0313b3a4c767`。
- WindowSession 注册：`ev_7412956e4aa0874732464ec7989495ce74bae1db2d9f3af6b3d9d6a4d2fa0592`；LayoutParams：`ev_35961548666b595d59d906f968a6e90f04fa5976e817027e05920ea8760aa530`。含混淆分支，需结合调用点及 AIDL 确认，不能机械照抄所有反编译分支。
- Autofill ComponentName 转换：`ev_ae184742bfb15db4f32947597fd899953e73401d4a1ee9fd05e448f59bcc9fe2`。
- IME/navigation insets：`ev_913546c22282d1c43128996d583d933c65bfd25ddfe0b9761f2b452664c83d56`。
- DeviceEnvInfo 字段：`ev_f66a2ed889d1f80230bc2ac7c523b15de79861b6945ec4f11eeca627e13d379e`；Build 字段应用：`ev_abb050780c1584724df600b8933acb550de88e74e3662c2cd86c379b7bc97747`；native 属性调用边界：`ev_793c4f02f8581a8b403a8972c769aabc93b16a8d16429fe8846c5026b5787897`。后者不是 native 属性 hook 的完整函数级实现证据。
- 本轮 PendingIntent 包装入口：`ev_ad819b57d1aeee87e09dcb6011ba2da92071cda60651aea43057c1d0f2df21a8`；原版按sender类型选择Activity/Service/Receiver物理入口，并保留ClipData、URI授权flag、原Intent和原flags等。当前Mylite不支持的URI/ClipData不能因此默默丢弃或声称对等。
- 本轮通知channel调整：`ev_85021d2b666a44ed3a5337478ec0f3819e0962d230713ee178b67f508f783892`，仅证明该方法的条件和channel映射；不是完整通知功能证据。Toast参数对照AOSP Android14/15官方AIDL，enqueue返回由void变为boolean，实际OS返回/拒绝、token、callback与display均须保留。

AOSP Android14 `IAutoFillManager`、`AutofillManager` 和 `AutofillManagerService` 对照确认：startSession 是 oneway，传入的真实用户与组件包必须通过系统校验后才有结果回调；调用方对未返回结果等待 5000ms。新实现保留原 token、callback、字段与 result receiver，由系统真实执行。[AOSP 服务端](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/autofill/java/com/android/server/autofill/AutofillManagerService.java)

## 交付门槛

先补上述实际阻断，运行真实 Store/Google 网页路径与独立交互回归；再在用户重新允许时做 Y700 ARM64/OEM 验证。只有已实际完成的步骤才能记为通过。真实认证、目录、下载、安装、目标应用使用及恢复均通过前，不把构建出的 APK 称为完整可用版，也不要求用户替我们发现基础点击/输入缺陷。

设备环境的 Java/Native 函数链及一致性验收另见[设备环境记录](device-environment.md)。

真实 Play 冷启动新暴露的绑定差异、现场与回归要求见[服务绑定重建记录](service-binding-reconstruction.md)。

原版microG的Photos/Meet/Classroom等配置分支与当前开源版本并不等量，已补完整Smali证据和范围记录，见[应用配置差异](microg-reference-configuration.md)；不能仅有容器与microG设置页面就称这些应用兼容。
