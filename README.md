# Mybox

面向普通 Android 的免 Root 应用容器原型，使用源码构建的 microG，排除 HMS、Pine 和 Xposed。Google Play、YouTube、Gmail、Google Maps 是外部客体，不随开源仓库分发。

项目目前处于实现和设备验证阶段。构建成功、可以导入 APK、可以显示界面，均不代表已完成 Google 登录、Play 下载与更新、地图或熄屏推送验收。实际验证记录见 [docs/STATUS.md](docs/STATUS.md)。

## 构建

宿主使用 JDK 17、Gradle 8.9、AGP 8.7.3、SDK/Build Tools 35、NDK 27.2.12479018、CMake 3.22.1。

```sh
bash gradlew :app:assembleDebug
```

普通宿主构建不含 microG。完整 `Mybox APK` GitHub Actions 会从固定源码构建无 HMS 的 microG，生成 APK/证书哈希信任清单，将同一次构建的 APK 与清单加入宿主，然后输出 `Mybox-dev`。不会导入 GBox 的私有内核、Google cookie 通道或原 APK 中的签名密钥。

## 使用

安装 Mybox，点击“安装内置 microG”，然后通过系统文件选择器导入自己取得的原版 Google Play APK。分包应用必须一次选择完整的 base 与 splits。全部客体位于容器虚拟用户 0。

Google Play 必须实际完成登录、下载、会话安装、打开与更新，才能认为核心链路通过。Mybox 的 APK 导入入口只负责引导和排错。microG Companion 默认占用 `com.android.vending`，不与原版 Play 同装。

首次设备验证不使用 Root；已有系统 GMS/HMS 的设备须记录环境，避免把系统服务能力误认为容器能力。项目不会修改真机系统分区。

## 签名和当前限制

`signing/development.keystore` 是**公开的开发签名**，密码均为 `android`。它使本项目的开发 APK 可以持续更新，不用于建立生产身份，也不适合正式分发。生产版本需要自己的私有签名、更新渠道与迁移安排。

只有信任清单精确批准的 microG 文件和真实证书，才能取得容器内部的 Google 兼容签名视图；安装来源和更新仍校验真实签名。该机制不修改 ROM，也不向容器外应用提供系统 GMS。

当前宿主 targetSdk 28 是历史引擎原型的兼容条件，不代表已适配所有新 Android 限制。支持范围由具体系统上的实测决定。容器中的客体共用宿主系统 UID，并不具备系统多用户的安全隔离强度。只导入可信 APK。

## 来源

容器基线为 FBlackBox/BlackBox 的 Apache-2.0 历史提交 `ffe950f7d15dae671cd8e95b58c83b35aab8f141`，保留原版权。microG 固定 `v0.3.17.252432` / `32bc8954ff872d0e1a05ddfae81561b6dbecef69`，其修改和构建脚本单独保存。详见 [NOTICE](NOTICE)。
