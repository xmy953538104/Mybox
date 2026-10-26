# 真实 Play 冷启动与服务绑定差异

2026-10-27 `4240b45`干净AOSP总装在Instrumentation.finish停止包时发现另一条实际清理竞态：Play收到onBindingDied后正常unbindService，Mylite关闭服务端lease的事务抛出DeadObject并逃逸到主线程。原版五个方法已通过REA和原DEX指令校核：guest解绑hook先从原连接Binder取走代理再委托真实方法（`ev_083af24d81521c308ba7a43951b005165bcedc5a832b764dd22689c3534c3d1b`），代理表同步remove（`ev_0585af40abfbbf2e4d904ed986988916067e90259dd501663176634bba326ca7`），AMS适配器、客户端及宿主解绑包装分别有Exception处理（`ev_9009a35183085d7c589fe5d169360cdc9f7538ee40b68d49395eff36c2cf8236`、`ev_d1bafcb08f8c469968cbd51b6891cf951d181f7112c8c9e0458549db92e3967e`、`ev_67d238a7222661a750bf455741214af78e319fd0d3ac03639d4b2f0f12b1196a`；typed catch另按DEX解码）。原版guest hook本身没有通用Exception吞错边界，不能由此复制成“所有解绑都成功”。自主候选只对关闭固定旧lease时确认死亡结束释放，真实OS解绑仍执行，其他错误不吞。新矩阵为受控Binder/解绑回调边界，不能称为真实进程死亡；已有真实AMS/服务端死亡组及完整Google批次另行验收。

## 当前现场

2026-10-26完整批次d2ff的核心/总装均失败，不能沿用下面“待CI”的历史候选表述。关联原始栈确认同进程AMS回调继承system身份、真实owner死亡后清理竞态，以及boot后台startService保活造成实际FATAL。新候选在认证SYSTEM_UID后仅对本地dispatch clear/restore身份，死亡清理核固定owner与源transport错误；保活改为固定owner的server真实AUTO ServiceConnection，私有keeper control不调用guest.onBind、不等待guest main，只有已存在物理carrier或真实virtual started且仍有logical leases才创建。首个inactive noAUTO不自动建立；最后lease/owner death按原ServiceConnection解绑，不按slot组件停止。keeper实际拒绝会退休该owner连接并通知真实失败，不能伪前台或静默返回成功。keeper使用WAIVE_PRIORITY/NOT_FOREGROUND，但共享carrier的实际OOM/性能仍待测；这不是原版相同实现或通用跨VM自动恢复证明。

新增真实OS回归检查carrier.started=false，原pending→explicit start、另一filter最后AUTO撤销、真实kill/重用slot/旧group重放继续保留；另核首Intent Parcel隔离。旧token机制已移到debug-only物理平台回归，不进入生产引擎。此候选尚未构建/Android验收，统一在云端收齐完整批次后再判断。

2026-10-26整合候选已重建实际OS绑定：PREPARED与BOUND分离、实际AMS返回与异步客体Binder/null、filter共享/重绑、无AUTO pending、提前解绑和代次/权限复核，账户及Job已接入。carrier使用捕获的真实AMS Binder与旧物理mToken同步停止，不按slot组件名停止、不排队到guest main；新lease加入同owner OS mutation串行边界。旧owner start不能清新实例started状态，同slot退休旧group的真实OS重放返回inactive control。新增真实ServiceRecord token与group重放回归已编译；round22完整构建34秒、91项JVM/lint/双APK审计通过，但Android新运行尚待CI。跨进程自动重连、URI授权/ClipData、孤立迟到start/OOM及不同SDK版本仍未完成；不宣称完整等价。

2026-10-26 `6a264b4` 的完整40222582953已越过原先的Play冷启动/首次RESUMED等待：相同85361540七APK集合完成商店登录点击，microG LoginActivity实际恢复。后续intro探针对全大写SIGN IN识别失败；独立后台VpaService随后在重复stopForeground时触发Mylite错误门禁崩溃，不能将此轮标为登录通过。新REA `VpaService.h`（`ev_e510e9d82aed056565132399a3154185985307b8a158e7a96a69625938070310`）明确正常清理会stopForeground后stopSelf；原版`g.i`（`ev_77086ce214c4ca047c9aed4e369355024d9b86a1e1932e892ea483505bcc6734`）无proxy直接返回。候选保留真实token/参数检查，对停止清理允许无started physical或stopping；开始前台的校验不因此放宽。缓存/Job测试通过不能证明其他绑定时序已等价。

2026-10-26新现场：完整40220181123（Play53同七包hash）首次实际取得Activity等待诊断。主PID5503的Application.onCreate于18:29:25.758返回，后续LAUNCH_REWRITTEN中LoadedApk/Application/双缓存均相同，只有一次Process created。18:29:40.892主线程处在真实bindServiceInstance→GuestComponentCalls.send IPC；server线程45/44正ComponentRouter→ProcessRegistry.acquire，background6144主线程账户枚举，而server线程36处在AccountDirectory.registrations→PackageStore元数据扫描。这支持同步冷bind阻塞实际UI的现场，仍非完整等待环证明。期间391条queries解析警告另揭示八视图cache在多包/多flags扫描时抖动，按容量缓存候选保持真实授权与视图校验，不靠增加Activity等待时限记成功。此前旧Play35的prefix-null链和较早53的Application屏障超时继续分别保留。

`29b7f76` / `40181232795` 的真实 oneway 诊断已通过票据验证并取得9条microG栈。GMS PID4794，状态BOUND；其Binder线程37阻塞在 `ContentProviderProxy.call → PhysicalProviderCalls.invoke → MyboxIdentity.resolve → isCallingSelf → GServicesProvider.query`。因此当前等待链为 Play初始化Future→GServices查询→Mybox身份解析服务；尚缺engine_server的阻塞点。失败快照继续增加服务端自身的有限线程栈。此时不能把归因改称SQLite死锁或验签性能问题，二者仍只是待核对假设。

`f55456d` 的运行 `40179785062` 再次失败，但新增关联栈显示工作线程42停在 `ContentProviderProxy.query → ContentResolver.query → aivx.k/l/e → ... → jpr.b`，主线程仍等初始化 Future。该证据把现场缩小到跨进程 Provider 查询，尚不支持归因为同步 Service bind。下一轮在获取时记录静态 authority/目标组件，并由已认证引擎服务通过 oneway 请求其余自有客体的有限线程栈；失败清理不等待诊断回复，不增加启动预算。

Oneway Binder 不能依赖调用者PID，因此异步诊断使用每次进程预留单独生成的Binder票据，并继续检查真实宿主UID。同步业务命令仍要求实际引擎服务PID；诊断不返回应用数据。Y700票据负例已与其他边界/实际交互组成10项检查通过：缺失、其他预留、未准备进程和同步请求均拒绝。

2026-10-25 `2a20ef6` 的运行 `40178732974` 已取得 Play35 主线程栈：`Unsafe.park→LockSupport.park→aond.get→oq.i→jqe.i→jqe.onCreate→Instrumentation.callApplicationOnCreate`，状态 WAITING。它证实 Application 初始化在等待 Future，仍不足以定位关联工作线程。后续诊断在同一失败点最多记录64条客体线程栈，每条最多40帧；不记录线程名称、局部变量或账号数据。Y700 Play40 的启动已通过，另复现并修复语言权限/Receiver状态边界；两个版本的不同现场应分开分析。

`9bd700b` 的独立云端业务运行 `40176806288` 失败，但已经越过上一轮的商店导入：物理 Play 版本号 83561110（35.6.11），当前与历史证书均为锁定的 Google SHA-256 `f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83`。安装及重复安装代次检查通过，随后在 Engine.launch 中失败。

08:35:04.968 Vending PID4747 的 native I/O 初始化成功；随后创建 microG PID4813，其 native 初始化也成功，日志经过 Provider 与 WorkManager 初始化。08:35:34.826 Vending 的主线程工作预算到期，实际自有进程被回收。尚未进入账号输入网页，边缘返回手势、真实认证及下载没有完成。

此现场未包含超时前的 Vending 主线程栈，不能仅凭两进程存活或超时顺序断言唯一死锁原因。上轮 `701294a` 的签名拒绝没有逐 APK 签名诊断，不能凭本轮的正常签名倒推上轮是证书轮换、镜像调试签名或同一故障。

## 原版已确认的路径

REA 对同一原版样本恢复了三段链：

- `server.virtualservice.am.u.bindService` 解析真实虚拟 ServiceInfo、初始化进程，构造代理 Intent，并包裹 IServiceConnection。
- `context.systemservice.e.l` 最终调用真实 IActivityManager 的 bindService/bindIsolatedService/bindServiceInstance，保留实际 Android 返回值和错误处理。
- `proxy.StubService.onBind` 核对虚拟进程、原 Intent、用户和连接信息，在物理 Service 生命周期内取得虚拟 Service.onBind 的 Binder；另有 Binder 包装、连接计数、onUnbind/rebind 和回收路径。

Evidence：`ev_98b58a25c999d08ce8520304d4fce60f9fed946cccdd6d03f48b83829c9c634b`、`ev_de978d63e0d826f3c79a6b8f635451c59558a22aa9ca82ad8f348438d4ab4914`、`ev_c85ada93a54888e33773242e2fa0374150bcbcbe433fc37c33ad866c123165a8`。均为静态 Java 证据。

后续已进一步恢复initProcess边界：h.y（`ev_8c6bcb0c4c988f115f0fa53fd9ed1afb150c9a202b4cbe731ade2a85524fbdea`）→h.u（`ev_0d8401d89ad60028767a9b45106abb2bcde4f1329540586c294ef826df4d99bf`）→StubContentProvider.call（`ev_ad79ea9178d12c29d77fe492f5a646b43e8649dec291aba6a3b1b692671cabb0`）→k.initClient（`ev_90f0df4f8835bb2561fddcac41b07c5a7591d0ccfa79020bc3d46b5fa4caf551`）。已追到的同步回执是pid/client/ApplicationThread等准备，随后才真实OS bind；guest Application绑定/Service创建在物理StubService生命周期内。准备阶段仍有包解析、Provider IPC、并发pending/权限准备等等待；extension/native及所有版本尚未穷尽，不能概括全程无阻塞。自主实现据此区分PREPARED与BOUND，保留现有Activity/Provider等调用者的BOUND契约；完整绑定还必须处理真实AMS返回、null/断连、filter共享和虚拟对象重建、无AUTO_CREATE、提前解绑、安装代次和内部账户/Job调用者。

## 当前 Mylite 的已确认差异

GuestComponentCalls 的跨进程 bind 会同步发送 BIND_SERVICE。ComponentRouter 等待目标 ProcessRegistry.acquire 完成，再同步调用目标 GuestRuntime；GuestComponents 随后实际创建 Service 并执行 onBind，调用者直到这些操作完成后才得到返回和 connected 通知。GuestServiceStub 当前仅承接 started/foreground Service，onBind 返回 null。

这与原版经过真实 Android ServiceRecord/连接投递的路径不同，会让调用线程持有锁等待目标启动更久，并具有形成启动等待环的风险。这里确认的是实现差异，尚未证明本次超时的具体等待环。

本地已补 GuestRuntime 超时前的实际主线程状态/栈捕获；保留原30秒预算和原失败，不通过延长超时、预热 microG 或假返回绑定成功消除症状。日志只记录类、方法、状态及栈，不输出账号或输入内容。该增量尚未在云端运行；此前推送被自动审批拦截，2026-10-25 用户明确要求重试 GitHub 操作后恢复推送。

## 下一次修改的验收要求

最新 `fde5796` 诊断已补齐服务端线程栈：Play 查询 gservices，microG 的 Provider 等待自有身份解析，而 engine_server 正在 `IdentityProvider.call → TrustedMicrog.require → verify → NativeCrypto.EVP_DigestUpdate`。这将完整 APK SHA-256 重算定位到实际等待路径，尚不能据一张快照量化单次散列耗时。先消除对同一已验证、未变化只读文件的重复散列，再用真实 Play 冷启动确认是否推进。此证据不证明上述物理 Service 绑定差异不存在，也不把该差异作为本次故障已证实的根因；校验边界见 [sealed-service-trust](sealed-service-trust.md)。本轮按用户最新要求只用云端和模拟器。

先取得完整超时栈，核对原版进程初始化与实际 Service 绑定的阶段界限，再选择重建物理绑定路径或等价的异步投递。复用现有自有进程、签名/权限、代次与生命周期结构，不能因异步化丢失这些校验。

除真实 Play 冷启动外，至少覆盖：bind 返回与 Service.onBind 发布的顺序；主线程/工作线程各自持锁时的跨进程绑定；绑定完成前 unbind；调用者/目标死亡；onBind 返回 null；同 Intent 重用及 onRebind；同连接多个不同 Intent；无 AUTO_CREATE 时不启动；应用更新使旧代次失效。要同时检查真实 callback、Binder 和后续使用，不以 admission 的 true 代替连接完成。

用户提供样本内的 Play40.0.13 已再次验签并准备成本地完整候选包，便于与原版同组件版本对照；这不等于候选包已通过上述业务。2026-10-25 用户已重新授权使用空闲 Y700，开始恢复实机诊断。
