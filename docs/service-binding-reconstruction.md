# 真实 Play 冷启动与服务绑定差异

## 当前现场

`9bd700b` 的独立云端业务运行 `40176806288` 失败，但已经越过上一轮的商店导入：物理 Play 版本号 83561110（35.6.11），当前与历史证书均为锁定的 Google SHA-256 `f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83`。安装及重复安装代次检查通过，随后在 Engine.launch 中失败。

08:35:04.968 Vending PID4747 的 native I/O 初始化成功；随后创建 microG PID4813，其 native 初始化也成功，日志经过 Provider 与 WorkManager 初始化。08:35:34.826 Vending 的主线程工作预算到期，实际自有进程被回收。尚未进入账号输入网页，边缘返回手势、真实认证及下载没有完成。

此现场未包含超时前的 Vending 主线程栈，不能仅凭两进程存活或超时顺序断言唯一死锁原因。上轮 `701294a` 的签名拒绝没有逐 APK 签名诊断，不能凭本轮的正常签名倒推上轮是证书轮换、镜像调试签名或同一故障。

## 原版已确认的路径

REA 对同一原版样本恢复了三段链：

- `server.virtualservice.am.u.bindService` 解析真实虚拟 ServiceInfo、初始化进程，构造代理 Intent，并包裹 IServiceConnection。
- `context.systemservice.e.l` 最终调用真实 IActivityManager 的 bindService/bindIsolatedService/bindServiceInstance，保留实际 Android 返回值和错误处理。
- `proxy.StubService.onBind` 核对虚拟进程、原 Intent、用户和连接信息，在物理 Service 生命周期内取得虚拟 Service.onBind 的 Binder；另有 Binder 包装、连接计数、onUnbind/rebind 和回收路径。

Evidence：`ev_98b58a25c999d08ce8520304d4fce60f9fed946cccdd6d03f48b83829c9c634b`、`ev_de978d63e0d826f3c79a6b8f635451c59558a22aa9ca82ad8f348438d4ab4914`、`ev_c85ada93a54888e33773242e2fa0374150bcbcbe433fc37c33ad866c123165a8`。均为静态 Java 证据；initProcess 的各阶段是否等待 Application 完成还需继续恢复，不能把整个链概括为完全无阻塞。

## 当前 Mylite 的已确认差异

GuestComponentCalls 的跨进程 bind 会同步发送 BIND_SERVICE。ComponentRouter 等待目标 ProcessRegistry.acquire 完成，再同步调用目标 GuestRuntime；GuestComponents 随后实际创建 Service 并执行 onBind，调用者直到这些操作完成后才得到返回和 connected 通知。GuestServiceStub 当前仅承接 started/foreground Service，onBind 返回 null。

这与原版经过真实 Android ServiceRecord/连接投递的路径不同，会让调用线程持有锁等待目标启动更久，并具有形成启动等待环的风险。这里确认的是实现差异，尚未证明本次超时的具体等待环。

本地已补 GuestRuntime 超时前的实际主线程状态/栈捕获；保留原30秒预算和原失败，不通过延长超时、预热 microG 或假返回绑定成功消除症状。日志只记录类、方法、状态及栈，不输出账号或输入内容。该增量尚未在云端运行；此前推送被自动审批拦截，2026-10-25 用户明确要求重试 GitHub 操作后恢复推送。

## 下一次修改的验收要求

先取得完整超时栈，核对原版进程初始化与实际 Service 绑定的阶段界限，再选择重建物理绑定路径或等价的异步投递。复用现有自有进程、签名/权限、代次与生命周期结构，不能因异步化丢失这些校验。

除真实 Play 冷启动外，至少覆盖：bind 返回与 Service.onBind 发布的顺序；主线程/工作线程各自持锁时的跨进程绑定；绑定完成前 unbind；调用者/目标死亡；onBind 返回 null；同 Intent 重用及 onRebind；同连接多个不同 Intent；无 AUTO_CREATE 时不启动；应用更新使旧代次失效。要同时检查真实 callback、Binder 和后续使用，不以 admission 的 true 代替连接完成。

用户提供样本内的 Play40.0.13 已再次验签并准备成本地完整候选包，便于与原版同组件版本对照；这不等于候选包已通过上述业务。2026-10-25 用户已重新授权使用空闲 Y700，开始恢复实机诊断。
