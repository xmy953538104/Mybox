# 调试与保留会话修复（00f5f8d 云端核验版）

2026-10-31候选补齐已观测的导出缺口：翻译的纯框架SecurityException此前engine_stack为空，Apple只留下外层引擎包装栈。保持同一JSON字段、最多8帧和容量上限，最多为4层原因中最深的已知Android应用、网络安全及TLS调用位置预留4帧，其余保留自有代码位置；不记录栈文件名或异常正文。新增JVM深层/纯框架异常、数量及敏感值检查，并在Android上用独立测试证书触发真实平台拒绝，核对实际导出ZIP。候选尚待编译和完整云端，旧导出不能补出未保留的框架位置。

本轮不访问 Y700。先修复已证实的会话可见性差异，并提供应用内的排查手段；搜索 HTTP400、旧 OPEN 会话与商店 INSTALLING 的对应恢复尚未证明解决，不把这次修改当成完整业务修复。

## 原版依据与修复边界

原 GBox SHA256 `6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257`，classes4 DEX `ac17de00d90982e036853c804fd9ae304cd28309f4ea1f3862df887060d16d09`。

- 服务构造 `f.<init>` 130字节，REA `ev_1809f1956d32add5c1b4d8c1874b360fb490f7d685c689f6d789064dd36c70c2`：创建空 SparseArray 和 Handler/HandlerThread，本方法没有从磁盘恢复会话。不能据此推断原版全局没有别的恢复入口，也不能因此抹掉 Mylite 的暂存下载。
- `f.p0` 创建208字节，`ev_ffdbaba0d5dc49faa1d1c33e17c550a0d0cf6fe7557e1057fb95b6b9088e2c6a`：按调用者限制数量，分配目录，注册会话，再派发 created。
- `getMySessions`118字节 `ev_6a3860c969cbdb545a908a0a38796cac2184f1e888d0065a7834cc52336ad6c5`、`getAllSessions`110字节 `ev_524022c3d3738041ac153c53b48a6d5e4946563e4579930eac4cab4ff4a50b85`、`getSessionInfo`48字节 `ev_4a0dea8d500a7348e33a94027beabf855be78641ad467671a95b712b41b0afe7`：从同一当前 map 取值，前者还按 installer/user 过滤。
- 完成入队 `f$b.e`150字节 `ev_2cf64e11a23a47035b33126cd2100ae9ed63c07885b866639c4a47d4e8130d2a` → Handler → `f$b.g`104字节 `ev_2197e4eb2d5bcd05a927e89afafb5ecdb40ac5e9e84180d6d6b15ab97b8bab4e`：已销毁或失败会话移出当前 map，成功与失败回调的先后不同。关联实际安装、销毁/缓存配置、回调分派证据见 install-flow-parity 与既有 original-installer-chain 报告。

Mylite 保留内部历史日志、归属/签名检查及有效 OPEN/SEALED/COMMITTING 会话，但不再向商店的公开 SessionInfo/枚举返回 SUCCEEDED/FAILED/ABANDONED 历史。安装 fixture 需要从真实平台接口确认成功及 abandon 后不可见，不能继续断言历史会话一直 sealed 可查。这个修复只解决已知差异；不自动提交、放弃或删除现场旧 OPEN 会话，不修改 Play 数据库。

进一步核到未完成会话的目标包名差异：Mylite 原 snapshot 返回只在成功后赋值的 `record.packageName`，丢掉请求里已有的 `expectedPackage`；原 `d.generateInfo`196字节 `ev_b703e112159bbd00b4427945555e4c1a846bcb7e18ab3076b88424441940316b` 则返回 SessionParams 的 appPackageName。原 Store40 `xoz.v`1206字节 `ev_3b7675db2657500815b7770d5bbc61ecfc8608d5f185eb7aa68f9ae0a2be35cf` 在创建前调用 setAppPackageName，构造函数196字节 `ev_e839fa15d35f198aa9f8339fa3bedec3535e3b9659aa4d2b1662bcddce03dd9f` 把 xor case4 放入 Handler；其恢复分支通过 getMySessions → SDK/instant/staged/远程开关过滤 → (getAppPackageName,getSessionId) → map。`ev_411240bc483b79a6580da95f10657eac473951cf35beeb9346843ca5ab4ee22d` 的1724字节指令流及 packed-switch 原始目标另行核验，case4确实指向0x1001e31fe；完整方法1812字节中尾部是数据而非可执行指令。第二候选在未发布时返回已声明的目标；未声明则保持 null，不制造已安装结果。既存会话的 target/id/文件不迁移、不重写。JVM 核 OPEN/SEALED 冷恢复且目标尚未发布；真实 fixture 核创建后的 SessionInfo 与枚举键。它是确定的契约修复，但尚无 Y700 上这段恢复分支的动态证据，不能宣称已经解决所有转圈。

## 调试入口与实际覆盖

首页“调试日志”默认关闭；打开后复现，再点“导出诊断日志”，由 Android 系统文件保存器选择 ZIP 位置。关闭立即停止各进程新的事件写入，已有日志保留；“清除诊断日志”只清理自己的四个事件文件，不触及应用、账号或下载。无需 root 或存储权限。

`no_backup/diagnostics` 中四段事件 JSONL 各256KiB，另两段异常各64KiB，总上限1152KiB（约1.2MiB），另有空开关/锁文件。异常副本避免大量普通调用覆盖错误；分析时按进程epoch/时间/事件去重，不能算两次失败。多个进程检查同一磁盘开关，文件锁保护轮转和快照；事件写入遇竞争跳过并累计 dropped，磁盘失败不改变业务结果。导出只在取得最多1152KiB快照时持锁，写入系统文档/云盘前释放，避免慢提供器拖住业务。记录 wall/monotonic 时间、PID、线程、进程 epoch/角色、已校验绑定包与代次、调用编号、会话/Job数值、阶段、异常类型与最多8个栈帧。`62e1c06`为最多4层cause中的已知框架边界预留最多4帧，其余保留自有引擎帧；框架只允许android.app、android.security.net.config、javax.net.ssl与com.android.org.conscrypt前缀，每层最多扫描64帧。固定字段engine_stack保持兼容，只写类/方法/行号，不写文件名。异常消息、Intent/Bundle 内容、账号、密码、令牌、APK、数据库、原始 logcat/URL/HTTP 正文均不导出。

`62e1c06`两组完整云端六份额外ZIP已检查真实平台证书拒绝：保留Conscrypt/NetworkSecurityTrustManager的底层栈，没有异常消息、测试域名或敏感canary；源SHA、固定schema、8帧/存储上限与六份实际安装导出均核验。该改动针对既有实机记录中翻译空栈、Apple外层包装栈不足的问题；尚未在新的Y700现场捕获它们，不能据此称业务故障解决。

覆盖引擎 RPC、启动/绑定异常、安装客户端入口与参数拒绝、服务端会话操作/暂存文件/持久化/提交/通知、描述符错误和关闭、Job 虚拟/物理编号/调度结果/实际执行/回执、原生初始化 errno、包管理与连接管理异常。导出前由仅宿主可调用的服务端接口记录全部会话状态、暂存字节、writer 数与状态码；另外记录实际待调度 OS Job 编号及宿主 VPN/validated/系统代理是否存在的位图，不采集代理地址或凭据。

第二候选还记录真实账号 API 的调用种类及返回账号数组的数量，不记录账号或参数；补后端验证/发布/落盘异常类型及进程死亡记录。Android30+导出最多32条本应用 OS 退出记录，仅 PID/原因/状态/时间，不读取描述或 tombstone。退出历史可能来自旧构建，不将其猜配给某个客体或本轮操作。ZIP manifest 含当前构建的 Git SHA（CI从GITHUB_SHA写入 APK manifest），本地未知构建明确标记 local-unidentified。

网络补充是按需、限时的自有 UID 日志快照：最多读取最后1000行、3秒读取预算，只保留 Volley/Finsky 中已知 Play 路由标签和 HTTP 数值。原 Store40 `jfa.a` 的 REA `ev_0f34a1be83f6c49c76b9abbac16f1435259fa182d8e7d4dde99dd14bf33f8a98` 及原始 DEX 字符串核到 `Unexpected response code %d for %s` 和带 `[rc=%d]` 的日志格式，解析器据此提取固定标签，丢弃 URL 查询参数和正文。日志不可读、未输出或被覆盖均不代表网络正常；这不是 TLS/抓包/FDFe 请求体分析，也不能将 HTTP400 一概归因于代理。

## 验证要求

第一候选e89f872两组原始结果已核：核心40695419938三轮134项228.756/223.149/224.613秒，19组/20份空crash；干净AOSP40695434273三轮134项186.489/186.043/185.689秒，24组/25份空crash，所有预算和真实跨boot Job通过。新增宿主/独立server开关、持久事件与ZIP导出通过，真实安装回归通过；这不覆盖后续目标包名/慢导出修正，不作为最终交付。

第二候选增加慢输出不持锁/错误独立留存检查，并在真实首页切换开关、通过Activity结果回调写ZIP（不等于人工操作系统文件选择器），核构建SHA。真实安装fixture在调试开启时验证提交、文件关闭、失败类型及状态投递日志，并导出每轮ZIP供核查。

现已完成：核心40701201595三轮135项184.638/199.376/212.698秒，干净AOSP40701227455三轮135项182.813/185.173/170.167秒。19/24组完成、45份crash为空、全部进程预算与两个跨boot Job通过；六份实际安装诊断ZIP各自CRC/逐条JSON/固定字段/大小/源码SHA/角色/关键阶段/敏感标记核验通过，六份ZIP摘要均不同。竞争记录了10/23/18/10/8/13条dropped，不保证全量；所需安装阶段齐全，异常副本去重，未把空日志当成功。

四图确认键盘、Paste菜单与返回商店入口；粘贴后截图为空，不把无障碍树文本断言扩张为独立视觉证明。真实Google认证、HTTP400原因、旧INSTALLING业务恢复和真实下载安装/打开仍未确认。主包SHA256 `5ac35178765a7dbc44c8a2dc7ca52572a3c4f340af7c9bc1f88cff28e1b31075`，对应`dist/mylite-00f5f8d`，仅称会话修复与诊断测试包。本轮不占用Y700。

## 原版下载日志关联配置补查

原GBox classes.dex SHA256 `07870558708f1c4d5ebd88af28e95b6964d9b56127a436485ce400c6e94b217f`。新增11个日志关联方法1958字节对原DEX校验：l.o初始化→l.s的no-backup目录；l.q入口→l.v单行/8192字符截断后入队；l$g.j构造单线程、256队列、DiscardOldestPolicy与30秒核心超时（REA `ev_f04fcbad9d7d564d9ddb23dd84a2db405a806380dbb6b67cecc3515a346d8773`）。

实际写入l.w→l.A的512KiB轮转/5备份（`ev_49fc8c70ed7af1218809678012a358d3f6ac29346058e0708f2489027957653d`）；l.d快照入口先由l.j限时刷新队列（`ev_fbac9390a33e7522215948c43f072c78ceae806bcc3bb686cd1432389f0b2d17`），再收集、排序/合并和写快照l.e（`ev_db275564ed66af549698bbef414fb09cdd3474dc7f55cd2d1028cadb6bd6da63`），由l.f清过期轮转文件（`ev_f0bc0f3cb3f547efe0ef86277a6995f7b566baaa6f7fa8dce1ba66d1e0a8b138`）。本轮共38个关联方法8546字节、4处有符号分支已核，不把Rizin误读的switch数据当代码。此证据不证明原版全局隐私过滤或零丢失。Mylite使用自有固定schema/快照与隐私排除，不装入原私有logger代码，也未宣称调试性能等同。
