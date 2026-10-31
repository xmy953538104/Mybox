# Y700 商店安装与标准别名启动

更新：`8ebc8b3`六轮真实合成alias安装/打开及任务快照已通过，但两组整批因独立作业失败和云端模拟器丢失而拒绝，未交付/装回Y700。下文“候选待核验”保留为实施时记录，当前以[整批失败记录](cloud-failures-8ebc8b3-20261031.md)为准。

本轮使用已核验的`5fc7eb3`，授权11:43:11–12:13:11 UTC，实际11:57:48 UTC提前完成调试关闭、诊断导出、HOME和观察器停止。没有清数据、卸载或主动重登；覆盖主APK前后账号文件摘要/大小及全部32个旧会话一致。业务操作后账号文件摘要有变化、大小仍7110字节，不能宣称整轮账号文件未变化。

## 实际结果

- 首次Google Translate搜索显示DF-DFERH-01，独立own-UID记录为getItems HTTP400；用户手动重试三次后出现结果。原失败保留，不将重试后的结果算首次成功。
- 同一账号随后Google Keep搜索出现结果，没有代理重试或账号重登。商店实际下载安装，新增会话1721739779返回SUCCEEDED/status0；实际包记录为版本220681616、arm64-v8a、base加3个splits、installer为com.android.vending，商店按钮变为“打开”。旧未完成队列恢复未在本轮验证。
- 点击“打开”后，Play进程发生EngineException。持久化记录指向ServerActivities.requireSupported，读取自有固定异常格式得到：launchMode0、aliasPresent=true、flags0x10000000、hostCall=true。完整Google业务仍未通过。
- Apple Music进程完成绑定和实际媒体接口往返，但仍显示Mybox首页，未取得应用界面。此轮没有观察到旧registerRouter2相同调用，不能据此宣称它已修复。一次设备任务dump解析未匹配到自己的记录，不能把它解释为空任务集。
- 新诊断实际取得getAuthToken.result_received及result_forwarded，位图37表示Bundle、token键和账号元数据键存在；不读取对应值，也不代表Google后端已接受凭据。日志有dropped计数，不能称无损。

真实导出ZIP为61602字节，SHA256 `01f508d66f40a0fff59ef1c5550f56d38455ef6109e895b0247aefdd8a06e988`，manifest源SHA为5fc7eb3。完整脱敏日志、截图、会话/升级对照及失败均保存在本地私有证据目录。

## 原版及系统契约

首次异常先复用原版launcher解析/虚拟AM分发、任务路由及实际任务清理证据。原任务路由完整2322字节`ev_1587176e9084572cf7d9f42bd02ae703979afa8ee769f2f6513a0ceea32dcc90`在检查launchMode前，已按ActivityInfo.targetActivity构造真实组件；相关原launcher解析574字节`ev_9939e129f0236f7d8f1d4678594c6464b93fe76f30f7a6d7a8a6d9039d3a6caf`、分发254字节`ev_8c515e6f417d619d8466f1866a84278ed20f2fbbc092333c1f472efa751362eb`及任务清理544字节`ev_06635a05060056da21be7067fd9c1bb9a53730630fdc1fb8d06051f68ee30fc4`一起核对。相关静态证据不等于已穷尽原版所有flags、持久化和OEM分支。

[Android activity-alias契约](https://developer.android.com/guide/topics/manifest/activity-alias-element)允许由别名提供MAIN/LAUNCHER入口，名称不需要是真实类名；启动必须使用targetActivity的类，同时保留请求别名及它的权限/导出约束。

## 当前修复候选

Mylite此前将跨包独立根启动作为planned任务处理，因而将原本普通模式的launcher别名也一并拒绝。候选允许这种standard模式的新根别名，任务的真实组件统一经targetActivity解析；原请求组件仍留在Intent/envelope。账号UI票据规则、跨包导出/权限、进程和安装代次复核、真实任务所有权及结果/URI边界不变。其他未实现的planned alias模式仍明确拒绝。

实际安装回归的目标APK改为standard launcher alias，Activity和Application两种真实消费者继续从PackageManager取得NEW_TASK且无MULTIPLE_TASK的Intent；要求真实代码版本、焦点、nonce、原始别名组件和实际实例类都正确。原非导出/未请求权限/共享任务/非法options负例继续执行。此前只有直接Activity入口的正例，漏掉了这个真实商店路径。

为定位尚未解释的Apple界面问题，加入任务入队、实际start请求/返回、token认领、生命周期、完成和失败阶段记录。start返回不等于创建或显示。导出补当前record状态和最近128个operation快照，含数值ID、PID、真实task ID和内部阶段；省略数有明确计数。快照在任务锁内复制有限元数据，日志I/O在锁外，不记录Intent、Bundle、任意错误正文或凭据。日志仍有界、尽力记录，缺行不是成功。

本候选尚待编译及两组完整云端核验；没有装回Y700。首次搜索不稳定、Apple界面、翻译BlobStore及完整Google兼容/性能仍未验收。
