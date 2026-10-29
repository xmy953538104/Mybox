# 7467a05 实机整批后的关联修复候选

本轮平板使用已于11:48结束，调试关闭。7467a05仍不是业务验收通过版：Tasks虽然真实发布并回传成功，商店仍退回安装；Tasks和翻译分别在周期闹钟、快捷方式初始化处失败。以下是源码修复范围，构建和完整云端结果尚待核验，不能据此宣称商店已修好。

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
5. `notifyPackageUse`仅当前绑定客体、已安装虚拟目标或真实宿主映射到实际宿主，保留原reason和OS异常；其它物理包拒绝。新增实际IPackageManager调用及Settings负向检查。

周期和快捷方式冷恢复增加了真实等待：独立OS能力组外层预算240秒改360秒，工作流35分钟改40分钟；核心135项的360秒预算不变。每批仍完整收齐三轮、崩溃和Google链路后再决定下一次修复。

## 尚未验收与明确边界

源码候选尚未编译或云端通过，未装回平板。静态XML快捷方式、pin/backup/system恢复、跨包及多Intent任务栈、证书轮换和实际桌面长按点击尚未闭环；目前单Intent同应用入口，超出范围明确报错。已有快捷方式按实际证书一致性保护，不悄悄放宽身份。通知/包事件队列尚无跨服务端死亡的持久日志保证，不能宣称精确一次投递。Tasks/翻译是否进入业务页、商店是否稳定显示打开，以及性能等同，都要后续真实结果。

平台契约参考[AOSP14 IShortcutService](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/content/pm/IShortcutService.aidl)、[ShortcutInfo持久Intent及资源行为](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/content/pm/ShortcutInfo.java)、[LauncherApps启动权限](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/services/core/java/com/android/server/pm/LauncherAppsService.java)。它们提供平台依据，不替代本机/OEM运行证据。
