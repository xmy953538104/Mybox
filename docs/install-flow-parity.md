# 下载、安装、部署与启用差距

2026-10-28。参考为用户提供的新版 GBox、其内置 Store40 及 Android 14；排除 GBox 自有账号、会员、HMS 和使用限制。以下不是完整兼容性或性能承诺。

**最新实机结论：f112fa9仍未通过真实业务。** 搜索getItems HTTP400，新请求停留SCHEDULED，旧Chrome/翻译仍INSTALLING且引擎会话OPEN；既有AppMetadata修复没有完成旧业务恢复。已停止Y700访问，详[保留队列现场](play-retained-queue-20261028.md)。以下云端夹具通过不代替此项。

## 本次实际阻断与修复范围

最新`f112fa9`原始两组已核并交付测试包：核心40680329869、干净AOSP40680351139均三轮各133项全部通过，19/24组及20/25份空crash完成。旧OS快照误排除刚登记task的竞态已修复，连续Intent真实复用及原有安装、焦点、数据、负例均通过；六轮安装/更新后分别从Activity和Application上下文实际打开到窗口焦点。`dist/mylite-f112fa9`有精确工件审计与逐轮证据。真实Play下载安装仍需另行安排实机，不能用这些结果代替；下文历史失败保持原记录。

用户手动复现 Google 翻译下载安装。下载已到进度 1.0，APK 和 `.dm` 均已暂存，但商店在 `Session.setAppMetadata()` 调用隐藏的 `openWriteAppMetadata` 时遇到本引擎的 UnsupportedOperationException，尚未提交发布。设备现场、原始脱敏日志和会话记录留在仓库外；不是把旧 Chrome 会话或历史崩溃当作本次原因。

原版的元数据写入复用正常 FileBridge，读取返回真实只读 PFD，空值删除文件；准备 APK 前通过 `P0 → C0/G0 → renameTo` 移出暂存目录，避免被当成 APK。该搬移受 Android 版本门控，原版还会吞掉搬移异常，不照抄其弱错误处理。对应 REA：写 `ev_e68564c7b38b8bbc6708c77b923f3f8e59b4dc1864c080535e1252f4e1758183`；读 `ev_c6f6f81565afd048589dad85b06e41b367991d484254e6640ebfa025642e8506`；搬移 `ev_02b1e6d6e413dfe331ec22b8e74873b9b74c7df43e8d4fa681818ac682f71ca2`。原始二进制/反编译代码不随源码发布。

本次候选接入元数据 PFD 读、写、删除，复用现有服务端真实身份/证书校验、代理描述符、FileBridge 和持久会话；不存在返回空成功以跳过 Store 流程的分支。替换会截断旧内容，读取/删除先等待已关闭桥完成释放。元数据最多 32000 字节，不能替代 APK、改变签名校验或在封存后变更。暂存恢复仍读同一日志格式；最终发布仅接收 APK，终态清理会话元数据。**安装后 PackageManager 的元数据持久查询尚未实现**，因此不能声称这一整套 API 已全部复刻。

另修复待验收的安装列表回归：将 `MATCH_SYSTEM_ONLY` 直接传给系统 archive parser 会使普通用户 APK 返回 null，被误判为元数据损坏。现在先解析元数据，再按实际 ApplicationInfo.FLAG_SYSTEM 返回查询未命中；普通查询、组件和签名标志不被丢弃。夹具保留根因栈，并检查列表以及直接 system-only 查询。

## 全流程核查

| 环节 | 已有实现/证据 | 尚未做到或尚未验收 |
|---|---|---|
| 下载与网络 | 真机本次翻译 APK 和 `.dm` 已落盘；已有网络身份映射和 MCS 恢复回归 | 未证明全部 Google CDN、按应用代理及断网续传行为与原版相同；MCS 连接不等于 FCM 投递 |
| 首次组件部署 | microG内置源码构建；已有可信Play可保留，支持物理安装/可信APK集合/原包固定条目导入 | 无Google设备仍需用户提供商店来源；尚非原版预置与远端更新体验；不把只装microG当完整准备 |
| 创建会话与暂存 | 真实调用者授权、文件配额、分段写入、fsync、读回、持久日志和死亡撤销 | DataLoader 流式安装、多包父子会话、继承安装/移除 split 尚明确拒绝；原版有对应父子和排序链 |
| 安装元数据 | 本次补 AppMetadata；`.dm` 可暂存且不作 APK 解析；校验和提示接受有界参数 | `.dm` 未用于 ART 优化；可选校验和未缓存，requestChecksums 未实现；预审批回调未实现，不能照抄原版空方法 |
| 验签与发布 | 真实 APK 验签、包名/版本/split 一致性、独立 generation 发布，更新保留 appId 和数据 | 当前明确拒绝 isolatedSplits 和 split 声明组件，已保存的 Chrome156 因此仍会失败；静态共享库/SDK 库联合发布、requiredSplitTypes/隔离类加载依赖尚未闭环 |
| 结果与恢复 | 实际安装结果、状态广播、SessionCallback、终态查询；中断时按签名/来源/精确 APK 字节恢复 | 缺少独立提交收据，完全相同来源/字节的其它提交仍可能被错误归为本会话；不伪造原子多包事务 |
| 商店发现已安装包 | 已枚举虚拟包，返回当前 generation 和请求的元数据；system-only 过滤已在最新六轮真实更新流程执行，整批通过 | 全量多用户/归档/静态库查询未实现；公开 ApplicationInfo UID 仍为真实宿主，而 getPackageUid 返回虚拟 appId，身份视图尚未统一；真实Play刷新仍需实机 |
| 安装后启用与入口 | 解析 MAIN/LAUNCHER、持久组件状态；受认证跨包NEW_TASK、真实导出/声明权限校验，传递已核的splash样式；最新六轮Activity/Application两种上下文均实际获得焦点，核版本/代次/数据及不同task/nonce | 同任务跨包/结果、全应用enabled override、Provider启停和真实Play“打开”仍未验收；夹具成功不等于任意应用兼容 |
| 身份和设备资料 | 进程/证书/代次授权使用真实身份；原版设备环境的缓存、配置来源、Build、native属性、UA及覆盖链已有REA | 完整客体UID视图与自动Binder边界未接入生产；完整[机型/设备快照](device-environment.md)仍待实现，不能随机更换资料或冒称已复刻机型伪装 |
| 部署与后台 | 已有 native 库选择、路径映射、服务/Job 恢复回归 | ARM64/OEM、16 KiB 页、真实系统手势与最近任务一致性未全部验收；没有同环境原版性能基线 |
| 取消、卸载与空间 | 放弃会话撤销PFD；普通失败清理当次未发布目录，更新保留数据与旧generation | 已安装包卸载、依赖引用清理、旧代码安全回收和骤死暂存对账尚未完整实现，不能把abandon当卸载；不盲删运行代码/账号 |
| 进程容量与恢复 | 当前固定8客体槽，活跃进程溢出真实拒绝；最新六轮满槽死亡回收通过，只有已确认死亡且已持有租约关闭才复用 | 所有空槽发布/引导租约并发窗口尚未全量证明；原版选择器搜索1..99并排除待启动/保留项，不代表已证明同时支持99个；当前无完整淘汰/扩容策略 |

## 本轮验收顺序

1. JVM 仓库边界和实际客体公共 Session API：嵌套 bundle、长值换短值的精确字节、空值删除、会话重开、大小/身份/并发/封存约束，带元数据的真实验签更新和回调。
2. 核心 Android 与干净 AOSP 两组完整回归，保留每轮原始结果、crash、Google 输入与 MCS 证据。新异常先查原版关联链，再选择修改。
3. 稳定候选产物核对摘要、签名和依赖后，等用户另行安排 Y700；保留现有账号，实际下载 → 安装 → 商店显示已安装 → 打开应用。云端 fixture 不替代此项。

`6544f95` 两组完整结果已收齐：安装元数据和更新用例六轮均通过，但干净AOSP第三轮的native路径探针超时，整批失败，不能交付为稳定包。下一候选补超时阶段/线程栈证据，保留失败条件，并给原本无Activity的安装目标增加真实启动、编译版本/新generation/nonce/窗口焦点和自身launcher禁用恢复检查；尚待云端执行。

包变更广播的原版观察者、action映射和豁免链另有REA证据 `ev_f160785a7cfd8c4c01248b463439019fee6039d59f41c93cb84845d63dfe1048`、`ev_ebf8490c8fa93ef943ca001a9bdf9f25f2ee5c669ac7f5a35277a0c4a1cd62ec`；不能直接发物理受保护广播，也不能把Session状态回调当成这条链已完成。

当前未访问 Y700、未本地启动模拟器或 Gradle/JVM。Google 翻译真实安装成功、首次启动及性能对齐仍未验收。

`b90e8ef`完整核心40665972389和干净AOSP40665974941均三轮133项各1失败，19/24组全部结束、20/25份crash为空。新目标代码已完成编译版本2、独立nonce和窗口焦点后写入报告，但宿主断言错误地把Context的真实UID与内部appId等同。生产PackageStore和GuestRuntime均明确使用真实UID，公开getPackageUid则是appId；本次只修夹具，分别实际读取并精确断言四种视图，保留全部焦点/版本/代次/组件状态/数据断言，不通过单改LoadedApk UID冒险影响AppOps、资源缓存或账号。

这一修正不代表原版身份语义已经复刻：原版fixApplicationInfo写虚拟appId（REA `ev_8f2fa7352b05c442bce649993afb13f6d3303590e8269954095307868a08bb4c`），bootstrap从虚拟PM取得ApplicationInfo及当前虚拟UID的Provider（`ev_00e6b5234b19dd920b52cc784bb12a01e1c9a3df63b41e49756562da9976f71a`）；另一个物理解析回退的o2.b会写常量7800（`ev_42d3ad78f99584811d4d286a9e81aeacafd9520a73cf23c7422e0f7088bda304`），它不是宿主UID。原版完整Context/Binder/系统调用的身份视图仍须闭环，不能用单个字段推断。

`877a3c7`第一种打开已验证，第二种五轮失败指向真实失效task复用。原版启动入口先执行任务对账（REA `ev_1587176e9084572cf7d9f42bd02ae703979afa8ee769f2f6513a0ceea32dcc90`、`ev_06635a05060056da21be7067fd9c1bb9a53730630fdc1fb8d06051f68ee30fc4`）；下一候选排除系统已不存在和全部结束中的根，仍保留后续真实OS归属校验，不放宽AppTask权限。槽位回收依据固定[AOSP14 BpBinder](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-14.0.0_r1/libs/binder/BpBinder.cpp)区分缓存存活、实际死亡和租约关闭，失败ping不等于死亡。两项均待完整新批次。

卸载及旧代码回收另补原版REA：安装器实际状态发送 `ev_af170f83c9eb36209b30d1d06774995db584e6dc92f48c401e27cf882889b6c3`、旧环境代码目录比较清理 `ev_f406955ecdafca46f2bd375d5c89280b318f31216aeda6c673ce7a7b41645e82`、卸载协调 `ev_6e72760783d42c221edc9c603aae98c3e8ce36ce3e8af5bcc886db27d74961c4`；完整依赖/权限/删除工具的事务与崩溃边界仍待解析，不照抄宽松异常处理。组件状态读写、按用户持久化另核五方法306字节；它不自动证明全应用停用/Provider切换已实现。

当前候选ARM64 Linux合成暖缓存I/O约为未启用拦截的4倍聚合耗时；不是原GBox或Android实际业务基线。性能与长期存储回收均继续列为差距，不能按无crash或测试通过宣称等同性。

`8e5c553`加入真实启动后的两批再次失败，完整结果与旧失败均保留：安装已提交，但普通跨包启动在GuestActivityCalls/ServerActivities被硬拒绝；不能把“宿主可从列表打开”代替“商店客体可打开另一个应用”。下一候选复用现有真实组件权限检查，限制为明确的新根启动，禁止调用者指定物理task、跨包结果/共享task及未实现授权标志。原版launcher入口REA `ev_9939e129f0236f7d8f1d4678594c6464b93fe76f30f7a6d7a8a6d9039d3a6caf` / `ev_8c515e6f417d619d8466f1866a84278ed20f2fbbc092333c1f472efa751362eb`展示查询launcher、exported检查再交虚拟AM；这不证明原版所有普通Activity权限分支均已解析。

实际消费端另核Store40 builder/executor八方法746字节：`svz.c/f → swd.e`保留目标component、设VIEW/NEW_TASK，`swe.r/G → aiaw.s`经Context.startActivity(Intent,Bundle)执行，SDK/远端开关可能添加splashScreenStyle=1。REA `ev_a2781b9f287ad78e0ea5fcf41d4ab4823b1053d992712c1dcf5dc0429de3dbee`、`ev_e587a884818502153d4ff050807bfa815afd779487889c6e2ea15734aa19d8bc`、`ev_d8ce15d7d228e1e0dc79a996c1b98d754de5ace0c1f953a653b5966b43db5e9a`。并未确定具体Y700“打开”点击采用哪一个分支或远端开关是否激活；候选同时覆盖Activity、Application上下文及这个已观察的可选样式，传给真实OS，不只接受参数后丢弃。
