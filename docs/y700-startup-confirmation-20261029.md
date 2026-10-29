# Y700 启动与安装确认核查

北京时间2026-10-29 09:34开始，按约20分钟窗口安排，09:49完成实机采集并停止访问。76079c0覆盖由Android接受；升级瞬间账号摘要/大小、27个会话和翻译目录记录一致。没有清账号/缓存、卸载、重写Play队列或手动提交/放弃会话。

## 整批实际结果

商店恢复到原有账号的目录，新的Google Clock查询返回Google LLC时钟。安装尝试中，我们刷新控件时目标已进入等待状态，自动化没有再次点击“安装”；请求的触发者无法由此确认。随后新会话89570731实际写入约16.6MB、验签发布并送出status0；目录中com.google.android.deskclock版本76112330、一个split、安装来源com.android.vending。但商店弹“无法安装时钟”，不能算用户可用的安装成功。

翻译实际启动在TranslateActivity.onCreate内调用ShortcutManager.getIconMaxWidth时崩溃：IShortcutService.getIconMaxDimensions的身份被真实系统拒绝。相同批次还出现ManifestIntents.query第42行的服务查询空指针、notifyPackageUse/setApplicationCategoryHint未实现，以及提交期间更新会话标签的SecurityException。时钟从首页打开被ProcessRegistry.acquire第136行拒绝，8个真实客体进程均存活；microG与Play等占满容量，不能通过强行回收存活VM掩盖。

结束账号文件仍5624字节，翻译目录保留，旧空会话874245388的进度从0变化，新增时钟会话SUCCEEDED。没有本轮已下载暂存被手动清理；旧会话、实际成功和界面失败分开保存。没有要求本人重新登录。

系统文件保存器成功导出70388字节ZIP，构建SHA及CRC已核；固定schema事件可定位启动异常、完整安装结果和进程容量。本窗导出中3235条去重事件、reported dropped188，并非无损。先前点保存时悬浮输入法遮挡了按钮，这是自动化的操作问题；收起键盘并查看实际合成屏幕后保存成功。调试已关闭并核开关为false，已发送HOME，外部采集已停止。

## 原版与平台核查及修复范围

- 启动访问/Intent生成、服务查询/解析/flags关联方法已通过REA补查。原查询未命中返回空；Mylite现有PackageStore会按MATCH_SYSTEM_ONLY返回null，ManifestIntents却继续解引用。共享查询循环补跳过未命中，真实查询测试覆盖服务、Activity、Receiver的过滤，不改安装解析或组件权限。
- 原shortcut注册x5.c、名字解码u/x、共同self参数处理d7$l与物理服务q.l已补查。独立静态解码确认getIconMaxDimensions注册到d7$l，实际参数处理将虚拟包名映射为物理宿主；SDK>=24的安装注册也已复核。Rizin将数组payload误当指令且fill-array目标多8字节，使用原DEX相对偏移和payload头纠正，不声称整个clinit都是指令。Mylite仅对自己的图标尺寸和每Activity数量上限查询做物理身份映射，检查包名/真实用户/接口形状，保留真实OS结果；其它账号/包、shortcut内容、写入、备份和原始Binder事务不受此授权。
- 原updateSessionAppLabel/updateSessionAppIcon及s0所有者检查补查，关联此前commit/finish链。标签和图标都允许在非销毁的封存/提交期更新；Mylite标签错误地要求OPEN，而图标已允许。标签改与图标一致，先验证owner，终态不修改也不发送badging；不改变APK、验签、会话提交结果或调用者授权。JVM锁定SEALED/COMMITTING/终态/外部调用者，真实PFD fixture增加封存后的标签更新和完成后的调用。
- 原空槽选择、保留项和死亡退休链已有完整REA：搜索1..99，并不等于证明能同时稳定运行99个。当前8槽被真实业务占满，本候选同步将协议、7种系统入口及对应类扩为16槽，并更新MCS身份范围。现有实际满槽、最后一槽Activity/Service、溢出拒绝、真实死亡后回收测试跟随16槽执行。没有驱逐存活进程；16仍是资源边界，尚不能宣称原版容量或性能相同。

平台对照：[AOSP14 Shortcut接口](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/content/pm/IShortcutService.aidl)、[AOSP14安装器服务](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/services/core/java/com/android/server/pm/PackageInstallerService.java)。原私有源码/二进制不进入仓库或发布包。

以上候选待完整云端核验，尚未安装到Y700。标签竞态是已证明差异，尚未证明它是时钟界面报错的唯一原因；安装后元数据/分类提示、完整shortcut数据/写入、Google应用实际打开、网络截断和隔离split仍需后续查证。不能将fixture通过当成真实商店闭环。
