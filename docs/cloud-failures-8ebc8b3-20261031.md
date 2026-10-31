# 8ebc8b3 整批失败与后续取证

后续六轮定向已结束，取得一条独立的carrier清理FATAL并据关联REA修复候选，原首次回执缺失仍未重现/解释。见[后续真实竞态及边界](bound-group-retirement-20261031.md)。

两组原始结果均已收齐并拒绝，没有交付此版本，也没有在11:57:48 UTC结束访问后再次操作Y700。首次搜索失败和用户三次手动重试仍保留。

核心[41199737810](https://github.com/xmy953538104/Mybox/actions/runs/41199737810)完成19个分组、三轮各138项终止状态和20份实际空owned-crash。第二轮唯一失败是`testProxyDeathBeforeConnectionReleasesTheOriginalBindingWithoutStartingJob`未收到A的首次启动文件；此时尚未进行本用例计划的代理进程死亡。第一/三轮及跨boot任务通过，不能覆盖第二轮失败。

干净Google组[41199741121](https://github.com/xmy953538104/Mybox/actions/runs/41199741121)三轮138项均通过，已取得19个分组及19份实际空owned-crash；重启后采集阶段的logcat超时124，随后五项健康探测均失败，transport报告ADB目标不存在。日志先出现图形Context错误，但它与ADB失联的因果关系尚未确定，Linux模拟器进程的退出状态也未取得。跨boot结果和后续Google/MCS阶段缺失，不能按完整24分组/25份crash接受，也不通过加长超时或忽略错误来接受。

两环境的标准launcher alias回归共六轮、12个不同nonce真实打开通过：请求组件是`.Launcher`，实例类是`.LaunchActivity`，取得窗口焦点，真实任务及代码版本、安装/更新和负向权限检查继续满足原要求。六份实际安装诊断ZIP均含当前源码标识和任务快照。但这是合成APK证据，真实Keep打开、Apple界面和完整Google业务仍未验收。ARM64 Linux三轮及12个基准case仅属于Linux合成证据。

## 首项REA及未知

作业失败首先复核原版bind、connected/ACK、代理准入、断连/清理及JobInfo转换/实际调度配置。匹配证据包括`ev_c6041a2cce4a320c7696381a5d041ffc0ebbb2d40a3f2a9117e6418a06fb1242`、`ev_b5c8021080a16149a9806a803770aa7b4208f4e2c0826398ae165831524a97b5`、`ev_eec9272c4800b2b62e782ace92dfd4738d439ca57c95ed04a0dc018b0ef12ef7`及[job历史关联分析](jobs.md)。原版有准入失败后结束/取消的分支，不能复制成虚假的作业成功。原版静态证据不能说明这次Android是否已经实际派发。

当前race夹具A保留minimumLatency100/deadline1000毫秒，首次回执预算20秒。其他恢复用例此前已有失败时OS快照，本race用例却在finally取消之后才统一收日志，缺少此次原始OS队列、pendingReason和绑定阶段。不能直接认定为系统闹钟合并，也不能据此修改生产调度/死亡协议、放宽断言或加长预算。

后续只补两项race用例在finally前的失败取证，复用已有JobScheduler/Alarm/Power有界快照，记录当前真实物理job ID、关联匹配布尔值、时序和pendingReason；保留原失败及诊断自身异常。原恢复用例文件名和nonce字段保持兼容。失败文件每轮只清本合成诊断，完整dump有完成标记；缺失/部分dump仍未知。云端新增六轮定向诊断入口，复用原真实安装准备及两个race用例，不产APK，不替代整批。原100/1000、20秒、4秒门闩、生产8秒期限和真实OS回调均不变。

## Activity关联配置补查

已另补24段原版方法、4260字节逐指令匹配，覆盖新根/旧任务分发、envelope、宿主Context及初始化、包名映射、代理选择、方向/PIP配置、全局缓存/恢复/default和Parcel传递。独立aapt2对同SHA原APK核得slot01的八类Activity声明，包括三个PIP变体、方向/对话框主题和configChanges。Mylite尚无全部对应物理声明，保留为差距；未证明它造成Apple缺界面。原版反射混淆、全部配置写入方/主题缓存及实际原版运行状态仍未穷尽；不把完整字节取证当完整语义或性能复刻。

本机仅进行静态工具、Python和Git检查；未运行JVM、Gradle、模拟器或任何新增设备命令。C盘已检查，仅保留两份项目transform，没有删除其他项目数据。
