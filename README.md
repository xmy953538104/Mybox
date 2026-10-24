# Mybox · VLite 行为重建分支

本分支依据用户提供 GBox 样本中的 VLite Java/JNI/原生行为，独立实现应用容器。活动构建仅包含 app、vlite-engine 和自有 engine-fixture；旧 Bcore 保留在 Git 历史工作树中，不参与本分支的 Gradle 构建。

原版样本仅作为分析输入。产品不包含原版 VLite、GBox 的二进制或反编译源码。原生实现规格必须区分 REA 的 Java 证据、Ghidra 函数级静态分析，以及实际运行验证。

构建使用 JDK 17、Gradle 8.9、SDK 35，首版最低 Android 8.0/API 26。当前目标是客体绑定、APK 事务、组件启动和原生 I/O 的基础行为；完整 Google 登录、Play 安装、账户与推送尚未在新引擎上验收。

```sh
bash gradlew :app:assembleDebug :app:assembleDebugAndroidTest :vlite-engine:testDebugUnitTest :app:lintDebug
python3 scripts/audit-reconstructed-apk.py app/build/outputs/apk/debug/app-debug.apk
```

CI 的 reconstruction 工作流在一次性 Linux KVM 模拟器运行自有客体，检查真实 Application/Provider/Activity 启动、SQLite 与硬编码文件路径重定向；测试不会选择物理设备。构建检查和测试结果必须分别查看。

包管理目前支持虚拟用户 0、完整 base/config split 集合、真实 APK 验签、ABI 库提取及更新保留数据。签名轮换、isolated split 和 split 新增组件暂不支持。未实现能力必须明确失败，不能生成假账号、假 token 或伪造完成状态。

通用依赖：apksig 8.7.3 用于真实 APK 验签；HiddenApiBypass 6.1 用于当前进程的 Android 内部接口访问，无需安装 Xposed 框架。详见 NOTICE。源码中的开发签名是公开测试身份，不作为生产发布身份。
