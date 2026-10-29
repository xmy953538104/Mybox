# 7467a05 实机整批后的关联修复候选

本轮平板使用已于11:48结束，调试关闭。7467a05仍不是业务验收通过版：Tasks虽然真实发布并回传成功，商店仍退回安装；Tasks和翻译分别在周期闹钟、快捷方式初始化处失败。首候选0101e29已编译但两组云端整批失败，后续源码修正尚待下一批，不能据此宣称商店已修好。

## 0101e29完整失败与后续修正

后续`f051b68`完成两组[核心40823327561](https://github.com/xmy953538104/Mybox/actions/runs/40823327561)/[干净Google40823335535](https://github.com/xmy953538104/Mybox/actions/runs/40823335535)整批：六轮135项、全部预算、两次跨boot Job、MCS和六份当前诊断ZIP通过；Google输入/Paste/值及返回商店画面已核。仍不接受整版：核心首轮桌面长按没有菜单，其余五轮真实桌面点击和焦点通过；干净组虽24分组全绿，另有一次主线程Settings/ANGLE读取在服务端死亡时抛出DeadObjectException，随后13份快照重复同一崩溃。原日志显示杀服务端05:38:56.116，FATAL.127，guest死亡回调.131。

新REA原版`client.f.k`436字节、`f.n`86字节及`client.i.binderDied`16字节（`ev_7cc25a94629513d596778fffae7c82cad1455e2b1a8f8b01b88d97ca3a97babc`、`ev_155f339d5399a7248b04ff47ae6331fcec1bb0ad96daaa3796d97c9baf051f7a`、`ev_ab080e0663374ef97d06a41325a75d4ea215944c97cf7ac46d0474ec3b8f6c4b`）确认有角色flags门控的死亡订阅和应用生命周期回调；原GBox回调清准备标志，不能据此声称原版也按Mylite策略杀guest。关联原进程移除/Provider启动及原Settings代理资源初始化已复核。我们的缺陷是Settings适配器绕过已有绑定请求路径；候选只改该共享读写入口复用requestBoundServer，不返回假设置、不吞权限/协议/活端点错误，也不把旧guest接到新身份。新增受控端点只在可丢弃fixture中替换句柄，实际读Settings并验证普通错误存续、确切死端点SIGKILL；原真实服务端死亡/重连用例仍保留。

首轮Pixel桌面触摸根因仍未知。原快捷方式转换/转发和Manifest已重读；AOSP14的长按在View队列延迟执行，需窗口焦点，UP会取消尚未触发的回调，但这不是Pixel本轮具体根因证明。候选用真实可长按节点、空闲观察和一次有界DOWN等待实际菜单后UP，保留失败及输入/window状态，不重试或直接启动目标。后续需要重新编译和两组完整136项回归；目前无新设备访问或可用版交付。

后续de9bd9e两组构建40822984812/40823120705均在javac失败，未执行Android：Intent.removeFlags返回void，不能参与赋值链。原REA同样标注`removeFlags(I)V`；已改独立语句重新构建。这不是功能回归结果，也不用于覆盖此前失败。

核心[40818897359](https://github.com/xmy953538104/Mybox/actions/runs/40818897359)、干净Google[40818906501](https://github.com/xmy953538104/Mybox/actions/runs/40818906501)已收齐43分组和45份crash文件。核心三轮各135项184.369/188.742/197.223秒，干净组三轮201.677/209.006/209.473秒；六轮独立OS能力各7项约190秒完成，实际包事件/安装/打开断言通过，预算及两次跨boot Job、MCS通过。不是整版通过：剪贴板/WebView组各三轮失败，另有Google菜单登录及Play输入组失败，共8个独立FATAL；其它后续crash文件多次保留相同崩溃，不计为几十个独立问题。

根因是新增的notifyPackageUse校验误把使用目标限制成自己或虚拟包。实际栈为LoadedApk创建WebView类加载器时调用notifyPackageUse，随后WebViewFactory失败、Activity崩溃，最后表现为输入就绪/RESUMED超时。原PM注册、SDK、固定结果处理器和调用模板已先复核：SDK大于26或26 preview会登记固定结果处理器；关联REA已有11方法8588字节，关键Evidence `ev_f2a5634d7bdc9e5708be5bdd31e5ffbe0aa9494408e0a7b1a26fd058b3593c39`、`ev_16e8538772c5ae3110bad3bcf52021928acbebe927e5a44b67485711adbafb94`。AOSP说明这是被使用依赖的提示，不是opPackage身份声明。两批全部收齐后才改共享入口：只把虚拟目标映射宿主，物理依赖及reason原样交真实OS；测试比较宿主与客体对Settings提示的真实结果，并保留实际WebView输入回归。独立Dex加载归属、安装和账号权限校验保持原有语义。

另外补查原快捷方式转发完整194字节，确认在容器启动前去掉RESET_TASK_IF_NEEDED；现在仅在已校验能力的启动副本移除此标志，查询仍保留原Intent。诊断改名launch_queued，避免把排队当实际打开。回归改为真实HOME/桌面或抽屉长按Mybox/点击快捷方式，由系统LauncherApps启动非导出入口，再检查客体window focus、fresh nonce、死亡前后PID和真实OS记录；逐轮保存当前PNG、结构和摘要，先清掉本组旧fixture文件。它尚未云端验证，不能提前宣称桌面已可用。

## 原版、客体与平台证据

- 原版`r4.c/r4$d.a/g`注册、SDK分支、宿主包名和WorkSource处理已核；正重复间隔继续交给真实AlarmManager。Tasks现场栈明确为`setRepeating → gjf.h → TasksApplication.e/onCreate`；REA补查`gjf.h`完整228字节，实际为本地时间9点和86400000毫秒周期。对应Evidence `ev_a96bc7cc6fd403d408f4147c7a6a80d0df8b3c8a86f43287888ee5b768285797`，不是仅凭方法名推断。
- 原版发布回调`z$a.a`498字节，在真实发布后发送包ADDED/更新REMOVED及REPLACED，Evidence `ev_d14071f1bff61d3e7d58fafc23cdb639130609c09081984c6f337040d3badc92`。Store40 `xpx`构造器、去重/分派/监听集合、`xpv.onReceive → xpx.e`和登记桥一起核查：SDK26以上监听ADDED/REMOVED及`package` scheme；SDK33以上登记使用flags2。补查处理链1116字节、登记桥30字节及两个20字节SDK门槛，Evidence `ev_a99f3accd5ff63f2f7a910696e1f3a84942a836b8d5c9bc79e02a0e2d0218129`、`ev_8be15d0e50727dbe21c2054575cac93d9acd9f16e70348fc892e47abbb558bdb`。此为缺失通知的依据，尚不能认定为商店确认失败的唯一原因。
- 翻译的完整Activity初始化、enable/remove/list/disable桥和`ebo.a`关联工厂查询已核；另证实其初始化会调用`setDynamicShortcuts`，Evidence `ev_e2c1bfda80d749c1e16305700641de2edfe331958cc45701f837b6f45b8319b8`。原版`x5`注册/名字解码、`t(ShortcutInfo)`258字节和`ShortcutForwardActivity.onCreate`194字节显示宿主身份/图标重写、Intent封装及容器启动转发，Evidence `ev_05b52bf0b7f311c6d9487b9a7c92631a78232899ab2302ba82170be86929b425`、`ev_9fabec2c82da156ad42ec98231874516d0af846184f5529563a603dd72c6003e`。原x5数组payload曾有Rizin地址偏移，继续使用原DEX边界修正；不把数组/开关payload称为有效指令。
- 沿用已解出的PM通知注册、SDK及固定处理器证据，补`notifyPackageUse`宿主映射。原`getNames/r0`在封存状态也拒绝，不放宽COMMITTING/终态，不改Play数据库或手工提交旧会话。

原版APK与DEX、方法边界、完整REA返回和设备原始现场仅保存在私有analysis目录；没有把原版反编译代码或私有二进制放进项目。

## 独立实现及相应验证

1. 闹钟共享入口保留type/window/flags、当前进程、PendingIntent所有者和签名校验，允许非负重复间隔，并传给真实OS。增加实际60秒重复投递及杀服务端后的下一次投递检查，不重新set，不调整系统最小间隔。
2. PackageStore在验签及原子发布、释放安装锁后通知；通知失败留痕，不能把已发布APK伪报为失败。私有发布端点检查真实host/server进程及当前APK代次；客体不能伪造包事件。动态和冷manifest接收均走容器，携带真实虚拟UID、替换标志及正确data scope，代次不符不投递。新增首次安装、更新事件顺序/实际版本、冷接收及客体伪造拒绝断言；每轮记录须匹配当前源码APK路径，旧文件不能充当本轮成功。
3. 快捷方式使用真实ShortcutManager及其持久记录，不另造成功数据库；每个客体独立ID，set/remove不能清掉其它客体或宿主记录。图标/标签在客体资源上下文解析，列表沿用平台ParceledListSlice。创建、查询、部分更新、enable/disable/remove及使用通知接入真实OS，限流/错误继续来自系统；额度仍共享真实宿主，不能称独立每客体额度。
4. 点击数据复用既有OS能力密钥，但使用独立HMAC用途域和有界Intent编解码。校验当前appId/真实证书/包、OS记录的当前签名及启用状态，再经非导出的OsPendingActivity和现有Activity代次校验启动。删除、替换、篡改或其它客体的记录不能直接打开。回归包含真实OS创建/读回/部分更新、宿主记录保留、错误owner/user、伪造发布端点、错误签名/URI、原始OS Intent在服务端死亡后激活及删除后的拒绝。
5. `notifyPackageUse`对已绑定客体或已安装虚拟目标映射到真实宿主；其它物理使用目标保留原参数，交真实OS。保留reason及OS异常。最初“其它物理包拒绝”的实现与负向断言有误，已由完整失败批次揭示，按上述语义修正并补实际系统结果比较。

周期和快捷方式冷恢复增加了真实等待：独立OS能力组外层预算240秒改360秒，工作流35分钟改40分钟；核心135项的360秒预算不变。每批仍完整收齐三轮、崩溃和Google链路后再决定下一次修复。

## 尚未验收与明确边界

修正候选尚未编译或云端通过，未装回平板。静态XML快捷方式、push/pin/backup/system恢复、跨包及多Intent任务栈、证书轮换和实际桌面长按点击尚未闭环；目前单Intent同应用入口，超出范围明确报错。已有快捷方式按实际证书一致性保护，不悄悄放宽身份。通知/包事件队列尚无跨服务端死亡的持久日志保证，不能宣称精确一次投递。Tasks/翻译是否进入业务页、商店是否稳定显示打开，以及性能等同，都要后续真实结果。

平台契约参考[AOSP14 IShortcutService](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/content/pm/IShortcutService.aidl)、[ShortcutInfo持久Intent及资源行为](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/content/pm/ShortcutInfo.java)、[LauncherApps启动权限](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/services/core/java/com/android/server/pm/LauncherAppsService.java)、[LoadedApk跨包依赖提示](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/app/LoadedApk.java)、[PMS使用提示的真实策略](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/services/core/java/com/android/server/pm/PackageManagerService.java)。它们提供平台依据，不替代本机/OEM运行证据。
