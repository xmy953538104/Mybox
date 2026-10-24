# Mylite 验证状态

2026-10-24。用户要求改为依据原版 VLite 的实际行为重建核心，并在其使用平板期间仅在电脑及 CI 工作。

活动构建为独立 mylite-engine；Bcore 已从 settings 和宿主依赖中移除。源码没有引入原版 VLite 库或反编译代码。

用户已恢复工作并将独立核心正式命名为 **Mylite**，产品仍为 Mybox。目标约定见 [OBJECTIVES](OBJECTIVES.md)。`8f44ca2` 的[总装40063143267](https://github.com/xmy953538104/Mybox/actions/runs/40063143267)已通过45项JVM/核心23项Android及microG2项：认证服务端代理确认真实MainSettingsActivity已恢复，并保留运行截图。相同提交的[核心40063012269](https://github.com/xmy953538104/Mybox/actions/runs/40063012269)出现第二次Activity启动未执行的偶发失败；仍在追根因，不能仅凭另一轮通过宣布稳定。

同进程Provider依赖初始化（按需安装、循环/失败缓存）、普通manifest/ordered广播结果链，以及原生嵌套信号I/O安全已通过下述CI。Google登录singleTask/跨包界面、权限广播、前台服务、真实Alarm/Job和Service型PendingIntent等前置缺口继续保留，不能把设置UI通过写成Google业务完成。

`5a25cf9` 的[核心40065754354](https://github.com/xmy953538104/Mybox/actions/runs/40065754354)已通过45项JVM、Linux信号/I/O用例、连续三轮各29项Android及lint，并产出首份Release原生微基准，见[性能记录](performance.md)。Provider依赖/循环、广播结果与abort、安装、账户、两次启动及Native身份实验均保留严格断言；fixture现按探针模式隔离无关自动导航。此前`5f1bfca`的总装40064597816仍出现一次worker进程启动中断，缺少原日志的系统退出原因；新增exit-info/Zygote/native阶段证据继续追查，不能由三轮通过抹去。`84b24d5`又补充广播失败后队列推进、真实超时与迟到finish隔离，运行验收待新CI。

`84b24d5` 的[核心40066024744](https://github.com/xmy953538104/Mybox/actions/runs/40066024744)与增加原生启动阶段诊断的`a5781fc` [核心40066103182](https://github.com/xmy953538104/Mybox/actions/runs/40066103182)均已通过全部三轮29项Android测试，包含广播超时/迟到回执隔离。`5a25cf9` 的[总装40065777580](https://github.com/xmy953538104/Mybox/actions/runs/40065777580)亦已通过三轮29项核心与两项microG测试；这仍只验证了microG真实设置界面，未验收登录/商店/推送。新增AArch64 Linux job等待首次运行，不能提前写为ARM64 Android通过。

当前新增[Activity record A](activity-records.md)与[真实前台Service/通知](foreground-services.md)已通过本地JVM、双ABI/宿主/测试APK构建、lint与工件审计；新增3项Android测试尚待云端运行。AArch64 Linux首轮`2a24b09` [40066690632](https://github.com/xmy953538104/Mybox/actions/runs/40066690632)编译成功但真实CTest发生SIGSYS，x86_64三轮核心通过。栈池版`adb0f25`的ARM job单次通过并保留栈耗尽/ENOMEM用例与基准；这不能证明首次失败已修好。增加分阶段诊断和三轮原生运行，保留每轮结果，任何一轮失败仍判失败。

`9b20b25` [核心40067549050](https://github.com/xmy953538104/Mybox/actions/runs/40067549050)已通过45项JVM、三轮各32项Android（38.706/24.895/20.038秒）、双Linux架构各三轮原生测试、lint和工件审计。[总装40067576738](https://github.com/xmy953538104/Mybox/actions/runs/40067576738)亦成功，含同批核心与两项microG验证。Activity A及FGS新增用例已实际通过；singleTask/权限跨包UI、系统Alarm/Job/Service型PendingIntent、冷恢复和真实Google业务仍未验收。早期ARM64 Linux SIGSYS继续保留为未解决项，后续通过不等于根因已修复。

`54deb56` 已通过完整核心 CI [40052964162](https://github.com/xmy953538104/Mybox/actions/runs/40052964162)：28 项 JVM 测试、Linux seccomp 真实系统调用、9 项 Android14/x86_64 测试及 lint。覆盖客体 Application/Provider/Activity、SQLite、硬编码原生路径、跨进程 Service/Provider、真实 Binder 回执、生命周期、Activity 跳转、账户缓存/监听、认证器 token/features/error 与跨进程认证界面回传。AttributionSource 复制缺陷已在这轮修复并验证；身份桥拒绝未登记调用者。

总装 CI [40052965141](https://github.com/xmy953538104/Mybox/actions/runs/40052965141) 同时从源码构建无HMS microG，使用同轮APK/真实证书双pin打包，通过核心测试和真实microG APK兼容签名视图测试。该结果尚不包含microG实际启动或Google联网业务。

`3d82178` 的核心 CI [40054630814](https://github.com/xmy953538104/Mybox/actions/runs/40054630814) 通过32项JVM与10项Android测试。总装 [40054632140](https://github.com/xmy953538104/Mybox/actions/runs/40054632140) 核心及microG签名测试通过；实际启动测试发现 MultiDexApplication 的 `notifyDexLoad` 被未实现门禁阻止。当前修改将该优化通知限定为本进程绑定包，并在真实 Android PM 边界使用宿主身份，保留实际DEX路径/加载器/ISA；不修改客体包查询或认证身份。修复后的UI启动仍待CI确认。Store/GSF继续受门禁。

`6772668` 的核心 CI [40056002466](https://github.com/xmy953538104/Mybox/actions/runs/40056002466) 通过43项JVM与11项Android测试。新增广播型PendingIntent已验证manifest进程唤起、goAsync和真实OnFinished完成、一次性/取消/不可变语义、creator查询及越界请求拒绝。

当前开发checkpoint的45项JVM、双ABI原生编译、宿主/测试APK编译及lint通过。独立安装会话已接入客体PackageInstaller；真实PFD/FileBridge、分段恢复、验签更新和结果广播/监听正在等待Android运行验收。仅真实Google签名Store或同批debug fixture全APK/真实证书pin可执行安装，兼容签名不能授权安装。

新增原生Binder身份入口会自行校准ART结构并保存原始UID/PID，只有显式验证作用域才映射虚拟UID。当前只在debug独立测试进程启用，真实跨进程、oneway、clear/restore和系统身份边界待CI验证；尚未对客体自动启用。不能将源码/ABI编译通过写成已经恢复完整VLite身份能力。

最近的实际失败：[9f472d7 核心 CI 40056599363](https://github.com/xmy953538104/Mybox/actions/runs/40056599363) 的14项Android测试中4项失败，分别为安装PFD的IOException、重复启动探针的文件竞争，以及原生身份初始化未成功的两项测试。重复探针已改为串行执行并使用每次独立的action/文件/请求标识，其他两处先补充精确异常和初始化阶段，尚未认定根因。同期[6772668总装40056041341](https://github.com/xmy953538104/Mybox/actions/runs/40056041341)的microG应用绑定已越过DEX通知错误，但Settings可见性断言未通过；增加失败截图、Activity状态和完整宿主UID日志后继续定位。失败的工作流不会上传可交付APK。

`b297728` 的[核心40057455005](https://github.com/xmy953538104/Mybox/actions/runs/40057455005)和[总装40057456665](https://github.com/xmy953538104/Mybox/actions/runs/40057456665)已给出完整证据：安装失败发生在createSession读取缺失的debug fixture授权资产，尚未到PFD；原生读取使用变参syscall的x86_64参数宽度有误；组件调用收到BR_FROZEN_REPLY，缺少真实Android Provider进程依赖。当前分别改为AGP生成资产任务、固定long参数原生入口，以及宿主→服务端→客体的稳定Provider连接，并保留死亡释放竞态测试。两次启动仍保持独立请求和实际受理断言，等待新CI判定。45项JVM、双ABI编译、APK/测试APK、lint及最终安装fixture资产hash审计已通过本地检查。

同轮microG界面日志已显示真实“microG Settings”及设置内容；旧测试错误地要求无障碍窗口显示虚拟包名，而Android实际按宿主UID显示宿主包名。新测试同时要求可见的准确标题、认证进程登记与真实恢复的`org.microg.gms.ui.MainSettingsActivity`，并在测试过程中保存截图。该修正尚待CI；可见设置界面也不代表Google登录、下载或推送通过。

`5017e21` 的[核心40058983558](https://github.com/xmy953538104/Mybox/actions/runs/40058983558)已全部通过：45项JVM、Linux真实系统调用、23项Android及lint。安装器的PFD读写/续传、验签更新与数据保留、结果广播/监听和撤销拒绝均通过；两次独立启动在不同Activity实例实际受理并完成。原生身份实验成功校准CriticalNative入口，真实同步/oneway及clear/restore等边界均通过；它仍只在debug实验进程启用。

同版本[总装40058990727](https://github.com/xmy953538104/Mybox/actions/runs/40058990727)核心23项及microG真实/兼容证书分离通过，准确设置标题和运行中截图已取得。余下失败来自新增测试直接向客体发送DESCRIBE，被现有“客体命令仅接受服务端”ACL正确拒绝；当前改为宿主经既有进程代理查询状态，保留客体ACL，并把这一代理检查加入核心测试。最终总装仍待重跑，未把失败工作流的APK作为交付物。

原生规格已从原版 ARM64 ELF 的 JNI 注册、初始化和 I/O 调度函数恢复：核心使用 seccomp BPF/TSYNC、SIGSYS 和指令地址放行。REA 的 Windows ELF 深度接口不支持此目标，因此Java使用REA，原生函数级证据来自官方Ghidra headless，保留各自工具来源，未伪造REA证据。

当前实现范围：

- APK真实验签、base/config splits、原子提交包记录、CE/DE数据保留、并发安装和失败清理。
- 服务端Provider握手、四个独立客体进程槽、PREPARED/BINDING/BOUND身份状态、LoadedApk与客体Application/Activity加载。
- 原生路径规则及seccomp/SIGSYS文件系统调用重定向，直接系统调用入口、readlink反向映射、错误码和信号mask处理。
- 显式同包跨进程 Service 生命周期、真实 Service Binder 和 Provider 路由，新增自有客体运行测试。
- 虚拟 appId 的包查询、从真实 manifest 恢复的隐式 Intent 匹配，已有Android测试；新增跨包Service权限策略等待下一轮回归。
- 精确 microG APK/真实证书双 pin 的身份桥和兼容签名视图，APK验签及账户授权仍使用真实证书。
- 虚拟用户0账户服务、真实认证器Binder和界面回传、缓存与账户变更通知；消费者/认证器真实证书ACL、可见性和token授权分离、AES-GCM持久化。

尚未完成完整组件覆盖、安装会话的Android适配、后台任务、真实Google业务和推送。账户两阶段会话/授权选择UI、多用户操作明确不支持；账户跨包秘密ACL有JVM负向测试，但Android跨包独立消费者仍待验收。不能拿此前BlackBox原型的通过记录当作新引擎的验收。

兼容边界：首版API26+，ARM64/x86_64；当前仅 x86_64 Android14 核心流程已实测，ARM64和16KB页运行未验收。组件共用宿主真实Linux UID，容器身份不是系统权限。开发签名为公开测试身份。
