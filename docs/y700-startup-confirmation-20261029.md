# Y700 启动与安装确认核查

北京时间2026-10-29 09:34开始，按约20分钟窗口安排，09:49完成实机采集并停止访问。76079c0覆盖由Android接受；升级瞬间账号摘要/大小、27个会话和翻译目录记录一致。没有清账号/缓存、卸载、重写Play队列或手动提交/放弃会话。

## 整批实际结果

商店恢复到原有账号的目录，新的Google Clock查询返回Google LLC时钟。安装尝试中，我们刷新控件时目标已进入等待状态，自动化没有再次点击“安装”；请求的触发者无法由此确认。随后新会话89570731实际写入约16.6MB、验签发布并送出status0；目录中com.google.android.deskclock版本76112330、一个split、安装来源com.android.vending。但商店弹“无法安装时钟”，不能算用户可用的安装成功。

翻译实际启动在TranslateActivity.onCreate内调用ShortcutManager.getIconMaxWidth时崩溃：IShortcutService.getIconMaxDimensions的身份被真实系统拒绝。相同批次还出现ManifestIntents.query第42行的服务查询空指针、notifyPackageUse/setApplicationCategoryHint未实现，以及提交期间更新会话标签的SecurityException。时钟从首页打开被ProcessRegistry.acquire第136行拒绝，8个真实客体进程均存活；microG与Play等占满容量，不能通过强行回收存活VM掩盖。

结束账号文件仍5624字节，翻译目录保留，旧空会话874245388从OPEN变为ABANDONED（前后进度均0且没有暂存文件），新增时钟会话SUCCEEDED。没有手动放弃或清理本轮下载；旧会话、实际成功和界面失败分开保存。没有要求本人重新登录。

系统文件保存器成功导出70388字节ZIP，构建SHA及CRC已核；固定schema事件可定位启动异常、完整安装结果和进程容量。本窗导出中3235条去重事件、reported dropped188，并非无损。先前点保存时悬浮输入法遮挡了按钮，这是自动化的操作问题；收起键盘并查看实际合成屏幕后保存成功。调试已关闭并核开关为false，已发送HOME，外部采集已停止。

## 原版与平台核查及修复范围

- 启动访问/Intent生成、服务查询/解析/flags关联方法已通过REA补查。原查询未命中返回空；Mylite现有PackageStore会按MATCH_SYSTEM_ONLY返回null，ManifestIntents却继续解引用。共享查询循环补跳过未命中，真实查询测试覆盖服务、Activity、Receiver的过滤，不改安装解析或组件权限。
- 原shortcut注册x5.c、名字解码u/x、共同self参数处理d7$l与物理服务q.l已补查。独立静态解码确认getIconMaxDimensions注册到d7$l，实际参数处理将虚拟包名映射为物理宿主；SDK>=24的安装注册也已复核。Rizin将数组payload误当指令且fill-array目标多8字节，使用原DEX相对偏移和payload头纠正，不声称整个clinit都是指令。Mylite仅对自己的图标尺寸和每Activity数量上限查询做物理身份映射，检查包名/真实用户/接口形状，保留真实OS结果；其它账号/包、shortcut内容、写入、备份和原始Binder事务不受此授权。
- 原updateSessionAppLabel/updateSessionAppIcon及s0所有者检查补查，关联此前commit/finish链。标签和图标都允许在非销毁的封存/提交期更新；Mylite标签错误地要求OPEN，而图标已允许。标签改与图标一致，先验证owner，终态不修改也不发送badging；不改变APK、验签、会话提交结果或调用者授权。JVM锁定SEALED/COMMITTING/终态/外部调用者，真实PFD fixture增加封存后的标签更新和完成后的调用。
- 原空槽选择、保留项和死亡退休链已有完整REA：搜索1..99，并不等于证明能同时稳定运行99个。当前8槽被真实业务占满，本候选同步将协议、7种系统入口及对应类扩为16槽，并更新MCS身份范围。现有实际满槽、最后一槽Activity/Service、溢出拒绝、真实死亡后回收测试跟随16槽执行。没有驱逐存活进程；16仍是资源边界，尚不能宣称原版容量或性能相同。

平台对照：[AOSP14 Shortcut接口](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/content/pm/IShortcutService.aidl)、[AOSP14安装器服务](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/services/core/java/com/android/server/pm/PackageInstallerService.java)。原私有源码/二进制不进入仓库或发布包。

## 第一候选及后续分类提示

fc0923a完成两组整批核验：核心40803595464三轮135项163.163/212.761/213.304秒，干净Google40803596624三轮135项202.634/209.890/212.823秒；19/24组、45份空crash、预算及实际跨boot Job通过。16槽满载三轮均有真实engine_p15，检查最后槽Activity/Service、溢出保留和死亡回收；实际ShortcutManager配置读、标签边界及六轮真实合成安装/12次焦点打开通过。四张当前Google输入/粘贴/返回图已查看，测试文字前缀横向裁切，完整编辑框值另核。六份诊断ZIP核源版本/schema/阶段及敏感canary。主包SHA256 11a287708e2be326bba69ac5786c0e77b9509782a1dd0887bb81af590edb3368，13份microG补丁及来源审计通过，没有装回Y700。

继续依据实机堆栈REA确认Store40 aafo.C调用PackageManager.setApplicationCategoryHint，仅捕获IllegalArgumentException；本次EngineException穿出sbk.apply的后续确认路径。原VLite通用分派、方法/default handler和应用前后回调已补查，但加密PM注册的该具体方法尚未完全恢复，不能声称恢复了原分类实现。AOSP以真实caller package和目标installer关联限制写入，并在ApplicationInfo未声明category时使用持久hint。

第二候选独立补分类提示：沿现有component_states服务端入口校验实际Binder进程及当前APK代次，仅允许目标记录的安装者写入，原子持久化后由当前ApplicationInfo读回；自声明包名、外部安装目标和旧代次均不能获得授权。没有修改包签名、账号、安装成功状态或物理Android分类。普通客体在真实提交成功后调用分类API，主测试从另一PackageStore读取确认，JVM覆盖更新/重开、错误安装者/缺包/旧代次和损坏/符号链接记录。

a334e8a两组完整失败保留：核心40806523740三轮135项158.477/199.039/192.945秒；干净Google40806529678三轮135项204.030/212.659/213.251秒。每轮仅安装回归失败一次，135个终止状态均齐；其余16/21组、45份空crash、全部预算和两个实际跨boot Job通过，Google输入与MCS通过。成功提交、分类写入/读回已执行，但测试在后续“非安装者应收到IllegalArgumentException”处中止，后续fixture任务没有执行，不能称这一轮安装全流程通过。

根因是这版新增分类入口沿用了内部Bundle错误回传，普通EngineProtocol.request把服务端IllegalArgumentException包装为EngineException。首项诊断复用已核的原商店捕获表、hook异常分派和[AOSP Parcel异常传输](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/os/Parcel.java)。在两组收齐后，仅为分类setter的SecurityException/IllegalArgumentException使用Parcel.writeException保留类型，继续记录诊断；IOException与其他失败仍保持显式失败，没有放宽权限、伪造成功或删除负向断言。后续候选待两组完整云端，原商店确认尚未通过。

标签竞态与分类异常都是有证据的差异，尚未证明是时钟界面报错的全部原因。完整shortcut数据/写入、Google应用实际打开、网络截断和隔离split仍需后续查证，fixture通过不等于真实商店闭环。
