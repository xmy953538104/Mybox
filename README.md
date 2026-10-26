# Mybox · Mylite 应用容器

Mybox 是应用，Mylite 是独立核心。项目范围、兼容性和性能/稳定性验收要求见[目标约定](docs/OBJECTIVES.md)。

本分支依据用户提供 GBox 样本中的 VLite Java/JNI/原生行为，独立实现应用容器。活动构建包含 app、mylite-engine，以及自有 engine-fixture/engine-install-target 测试包；旧 Bcore 保留在工作树中，不参与本分支的 Gradle 构建。

原版样本仅作为分析输入。产品不包含原版 VLite、GBox 的二进制或反编译源码。原生实现规格必须区分 REA 的 Java 证据、Ghidra 函数级静态分析，以及实际运行验证。

构建使用 JDK 17、Gradle 8.9、SDK 35，首版最低 Android 8.0/API 26。客体绑定、APK事务、组件启动、原生I/O及基础虚拟账户链已通过Android14/x86_64测试；本地真实Play可经microG进入Google账号输入网页，但完整账号认证、Play安装和推送尚未验收。源码microG须匹配精确pin，Play须通过实际Google证书检查，GSF仍受门禁。实际运行范围与未解决问题见[验证状态](docs/STATUS.md)。

```sh
bash gradlew :app:assembleDebug :app:assembleDebugAndroidTest :mylite-engine:testDebugUnitTest :app:lintDebug
python3 scripts/audit-reconstructed-apk.py app/build/outputs/apk/debug/app-debug.apk
```

CI 的 reconstruction 工作流在一次性Linux KVM模拟器运行自有客体，检查真实Application/Provider/Activity、SQLite、硬编码路径、跨进程Binder、账户监听与认证界面回传；测试不会选择物理设备。总装工作流默认使用同轮源码microG打包，也可指定本仓库先前构建的run ID；复用时强制核对源码版本、当前全部patch哈希、APK/真实证书pin和无HMS构建清单。构建检查和运行结果必须分别查看。

总装包首页的“准备 Google 服务与商店”会安装精确 pin 的 microG、请求正常 Google 服务初始化，并从完整可信来源导入 Play。源码 microG 的 checkin/GCM 默认开启，已有手动关闭保持有效，后台恢复状态在首页显示；这仍需真实连接与业务验收。没有内置或本机来源时，首页提供系统文件选择器导入原签名完整 Play APK 集合，或从固定 hash 的已有 GBox 安装包只提取 Google 原签名 Play 入口；不加载 VLite/HMS。普通无 GMS 设备也可由本地私有构建提供 `mylite-engine/src/main/assets/mybox/google-play/base.apk` 和全部 `split_*.apk`；旧 `mybox/google-play.apk` 仍兼容，两种内置来源不能同时存在，也不会与设备上的 splits 拼接。完整集合必须经过真实 Google 发布者及所有 split 验签后才安装，最多 256 个 APK、实际复制总量不超过 512 MiB；不同 ABI 的集合不能互相替代。

只在本地准备自有来源：`python scripts/stage-google-play.py /private/play-set`（目录须平面包含 `base.apk`、`split_*.apk`，也可传单 APK）。脚本不下载、不改签名，不覆盖已有来源；来源文件不进仓库。私有构建审核须显式使用 `python scripts/audit-reconstructed-apk.py app/build/outputs/apk/debug/app-debug.apk --allow-private-google-assets`。该开关只允许本地封装审核，不代替运行时验签；公共 CI 默认拒绝包含 Google Play 原包的构建并阻止上传。公共 CI 包不内置 Play，microG 设置也不等于 Play 商店；完整登录、下载和推送仍需各自实际验收。输入法和长按粘贴的修复与实际交互检查见[输入记录](docs/web-input.md)。

包管理目前支持虚拟用户 0、完整 base/config split 集合、真实 APK 验签、ABI 库提取及更新保留数据。同一 Android SDK 上基于真实 v3/v3.1 proof-of-rotation 的签名轮换已实现，并已取得新 Play 集合的真实验签及导入证据；跨系统 SDK 导致有效签名变化的记录迁移、isolated split 和 split 新增组件仍未支持。未实现能力必须明确失败，不能生成假账号、假 token 或伪造完成状态。

通用依赖：apksig 8.7.3 用于真实 APK 验签；HiddenApiBypass 6.1 用于当前进程的 Android 内部接口访问，无需安装 Xposed 框架。详见 NOTICE。源码中的开发签名是公开测试身份，不作为生产发布身份。
