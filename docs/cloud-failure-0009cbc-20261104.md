# 0009cbc 完整云端：别名回归通过，整批仍拒绝

包源为 `0009cbcf9e282ee06df3e042a8f6ec833798e024`。两组完整原始工件均已取得，不重跑覆盖：

| 环境 | 三轮140项用时（秒） | 结果 | 分组/实际空crash |
| --- | --- | --- | --- |
| [核心41811508660](https://github.com/xmy953538104/Mybox/actions/runs/41811508660) | 230.211 / 222.948 / 219.322 | 首轮1失败，后两轮通过 | 19 / 20 |
| [干净Google41811513338](https://github.com/xmy953538104/Mybox/actions/runs/41811513338) | 223.177 / 232.261 / 234.114 | 三轮通过 | 24 / 25 |

新singleTop别名用例六轮均真实执行并通过：根创建、保留原请求别名、两个别名指向同一真实类的token/task/onNewIntent复用，以及非栈顶另建实例。原standard安装别名、实际安装更新和打开、权限负例仍保留。这里没有真实Play打开成功的结论。两次跨boot Job与全部四份进程预算在各组核验通过；Native ARM64 Linux三轮/12组通过，仍不是Android或GBox性能基线。

核心首轮失败为AccountRuntimeTest.testRealVirtualAuthenticatorAndAccountCache。真实报告中添加、监听、缓存、认证结果/错误、交互UI及删除检查全部完成，finally注销监听时出现IllegalArgumentException。系统日志在此之前记录FixtureActivity泄漏AccountManager的接收器；该Activity的注册接收器已被框架清理。具体销毁起因及是否伴随配置重建尚未确定。首轮导出没有unregisterAccountListener记录，但dropped为2，不能仅凭缺行认定该IPC从未发生。

第一项诊断复核原版认证/监听/广播关联链；另补四段监听委派及引用计数主体448字节REA，与同SHA原DEX核验。原版监听器引用计数与Android本地Context/接收器生命周期是不同边界，不通过放宽服务端注销、吞掉异常或伪造认证结果修复。

随后核实[AOSP14 AccountManager](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/accounts/AccountManager.java)：管理器保留传入的Context，监听器需要匹配其生命周期；最后一个监听器移除时，先用该Context注销接收器，再调用账号服务的注销接口。校验程序的后台worker可跨越账号交互Activity生命周期，原先把它的长监听绑在发起页面上，范围不合适。

下一改动仅让AccountProbe从Application Context取得AccountManager，账号交互仍明确传入原Activity，全部真实认证/权限/监听/删除断言保持。补记清理时Activity是否已销毁，并保留原失败与独立清理失败，不让finally覆盖最先的错误。这是校验程序生命周期修正，不能称为生产Google认证或HTTP400修复；新改动尚未执行。

原始核心ZIP工件13313768528，SHA256 `732353b8b89bc3a20ed70f1951f5172473449c3842498f0c5d83431b3fed9a30`；干净ZIP13314138556，SHA256 `bae7d83c29c7d7283020449fd4bb035c04094116b88cca54c93a96dd0b1aca91`。完整日志、失败、导出和后续成功轮次均保留。交互截图与全部辅助导出尚未逐张/逐项完成额外复核，不声称视觉验收通过；整体已有失败，不下载并交付总装APK。

原版同步设置六方法898字节、实际同步初始化/账号刷新/排队/开始/完成/取消/心跳/死亡/服务绑定15方法3468字节已补核，包含分支与switch原数据纠正。Store40附加账号数据的两个静态消费入口涉及firstName及CredentialsState；未取得现场具体键或HTTP400拒绝原因，账号权限没有放宽。接下来需把真实同步选择、设置、服务执行和恢复接入Mylite；Keep同步、翻译BlobStore、首次搜索与完整Google业务仍未通过。

本轮后续没有访问已于09:50:17 UTC交还的Y700，没有本地模拟器/Java构建或系统配置变更。Git receive-pack返回服务器500后，使用仓库REST上传五个自有文件，远端树与提交SHA均与本地0009cbc完全一致；未上传原版私有代码或设备诊断。
