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

总装包首页的“安装 Google 组件（microG + Play 商店）”会安装精确 pin 的 microG，并尝试从本机已安装的原签名 Play 导入容器；可选本地构建也可提供 `mybox/google-play.apk` asset。公共 CI 不附带 Google Play 安装包，设备无可用来源时需要通过“导入 APK”选择原签名商店包。microG 设置不等于 Play 商店；自检权限结果与登录、下载是否可用分别验收。输入法和长按粘贴的修复与实际交互检查见[输入记录](docs/web-input.md)。

包管理目前支持虚拟用户 0、完整 base/config split 集合、真实 APK 验签、ABI 库提取及更新保留数据。签名轮换、isolated split 和 split 新增组件暂不支持。未实现能力必须明确失败，不能生成假账号、假 token 或伪造完成状态。

通用依赖：apksig 8.7.3 用于真实 APK 验签；HiddenApiBypass 6.1 用于当前进程的 Android 内部接口访问，无需安装 Xposed 框架。详见 NOTICE。源码中的开发签名是公开测试身份，不作为生产发布身份。
