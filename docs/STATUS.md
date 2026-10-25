# Mylite 验证状态

`dd4539d` 修复 Y700 反馈的输入法/长按粘贴故障，当前[核心40173692424](https://github.com/xmy953538104/Mybox/actions/runs/40173692424)及[总装40173697553](https://github.com/xmy953538104/Mybox/actions/runs/40173697553)未通过本轮 WebView 交互门禁：核心三轮62项及剪贴板实际读写/负向检查通过，输入法窗口和长按菜单可见，但测试对菜单文字节点发送 ACTION_CLICK 返回 false，尚未证明点击粘贴完成。下一版改用实际触摸该菜单并在键盘、菜单和粘贴后三个阶段留图，仍要求 DOM 接收合成文字及页面存活。原失败保留，不能称为交互通过。设备日志明确显示 IME 因虚拟包名与宿主 UID 不匹配拒绝输入，Chromium 的 hasPrimaryClip 又抛出同类 SecurityException；死亡后的物理 stub 恢复 guest Fragment 导致第二次崩溃。已补 IME EditorInfo 身份映射、剪贴板内容查询/写入边界、过期 stub 丢弃保存状态；新增离线 WebView 实际键盘与长按 Paste 回归。REA 原版 hook 证据及实现边界见[输入记录](web-input.md)。本地64项 JVM、编译/lint/APK 审计通过，不能代替运行结果。

此前按钮只安装 microG，打开它本来就是设置界面，并未安装 Play。新流程尝试导入设备上的 Google 原签名 Play（含 splits）或可选本地 asset；无来源时明确提示通过现有入口导入原签名商店 APK。公共 CI 不附带原版 GBox 或 Google Play 二进制。自检空白项仍按真实权限结果显示，没有伪造授予。

用户已明确要求暂停 Y700 访问；本次尚未将修复版安装到设备，也不声称 Y700 故障已经验收解决。等待用户新的设备使用通知。Google 完整认证、实际 Play 下载/安装闭环及地图/FCM仍未验收。

`02e955a` 的[核心40117742662](https://github.com/xmy953538104/Mybox/actions/runs/40117742662)与[总装40117743721](https://github.com/xmy953538104/Mybox/actions/runs/40117743721)均成功。核心三轮60项70.802/47.669/53.181秒，八VM容量三轮3.477/3.444/3.812秒；总装20组均通过，含三轮60项93.942/59.523/60.787秒、容量三轮4.550/3.601/3.686秒、Google引导5项41.180秒及匿名注册10.564秒。两次云端真实重启通过；总装boot1→2、原job7010/nonce匹配、新PID2328、原OS记录删除且宿主界面未启动。日志未见先前的原生初始化或unlink异常。64项JVM、lint、双ABI构建及APK审计通过。Android14完整原生专项沿用a727f16成功证据：两个提交之间C++源码无改动。

当前可以安排Y700第一轮ARM64/OEM验证：先在设备上完成真实Google登录，再检查Play目录、实际下载字节、安装完成回调和目标应用启动。电脑模拟器已进入Google官方账号表单，并在Play/microG进程存活时启动另一应用，但没有输入账号。**真实账号认证、Play下载/安装闭环、地图/FCM及原版性能等同仍未验收**；八VM仍是有限上限，没有自动空闲VM驱逐。云端基础稳定不等于这些业务已通过。

`a727f16` 的[核心40116617925](https://github.com/xmy953538104/Mybox/actions/runs/40116617925)、[总装40116620058](https://github.com/xmy953538104/Mybox/actions/runs/40116620058)与[Android bionic40116623299](https://github.com/xmy953538104/Mybox/actions/runs/40116623299)均成功。核心三轮60项89.015/56.911/57.455秒；总装三轮60项66.138/45.380/45.601秒、Google5项20.010秒及匿名注册7.147秒通过。两个工作流均完成boot1→2的原持久化Job，原OS记录删除且宿主界面未启动。Android14原生完整套件三轮2.974/2.300/2.242秒均成功，case9各两个真实ACK，Linux AArch64与x86_64也通过。修复后本地重启boot5→6也成功；仍非完整Google业务或原版性能等同。

随后实际Play+microG登录场景占满四个VM，打开第五个应用明确失败，已保存现场。当前进程池补齐为八个，连同全部Provider/Activity/Service声明；不启动未使用的VM，也不重分配活VM。独立容量测试本地3.529秒通过八个实际BOUND客体、末槽Activity/Service、超额拒绝及真实死亡后槽位再用。再次保留真实Play和microG的四个进程后，第五个应用在p4/PID5773真正启动Activity和Service，原四进程仍存活。此容量增量仍待新云端，不能把a727f16的通过替代它。

`8aa78f0` 的[核心40113641950](https://github.com/xmy953538104/Mybox/actions/runs/40113641950)成功，包含真实Android14重启后自动完成持久化Job；[总装40113643844](https://github.com/xmy953538104/Mybox/actions/runs/40113643844)失败，不能称云端稳定。总装三轮56项及Google引导/匿名注册虽通过，重启后客体原生初始化在预检查期间遇到线程集合变化而返回EAGAIN，随后未建立死亡监听的Job清理又抛出NoSuchElementException，掩盖原始错误。当前修复仅在发送信号、修改handler/mask之前、同一个250ms预算内刷新线程集合；开始协调之后仍严格拒绝变化。失败的已验证自有客体保留槽位/连接至真实死亡。Job清理允许尚未建立的死亡监听，并保持仅释放一次。新云端尚待。

真实Play登录现已从商店Sign in，经microG确认页进入Google官方账号输入网页；人工返回也回到商店，未输入账号、密码或验证码，**尚未完成认证或下载**。本地实测解决了singleInstance来源/全新singleTask目标的真正Activity结果链接，以及WebView网络查询携带虚拟包名导致物理UID校验失败的问题。网络适配保留真实系统网络及回调；包名/UID仅在物理服务调用边界转换。网络与任务记录共10项本地14.615秒通过，早期Job绑定失败清理回归0.042秒通过。此前一次网络用例因操作过早终止fixture导入而运行旧fixture，出现Unknown fixture mode，失败记录保留；完成真实导入后通过。

本地Android12原生线程协调11项三轮均通过，新增线程的预检查用例每轮实际收到两个ACK并安装真实TSYNC filter；开始协调后的集合变化仍返回EAGAIN。完整原生套件在该旧模拟器内核因epoll_pwait2=ENOSYS失败，因此只接受上述协调用例的局部证据，不计作完整原生验收；完整Linux x86_64/AArch64和Android14 bionic仍需新云端。

本批完整59项本地199.343秒有1错误：网络fixture新增INTERNET声明后，旧权限负向测试仍期待它未申请，触发观察用PM拒绝。该负向项现改为确实未申请的NFC，保留危险/未知权限拒绝及真实normal获授/拒绝检查。新增真实Application故意失败、旧PID实际消失、同虚拟进程新PID重新绑定和清理；连同Provider连接及权限两组8项1.032秒通过。64项JVM、构建/lint及双APK审计通过；未将前述完整失败写成通过，完整60项及真实重启由下一轮云端继续验收。

持久化Job及Google VpaService适配的本地完整56项137.033秒通过，无新增崩溃；两个实际Android重启（3→4、4→5）均验证原nonce/作业ID、自行冷启动、真实完成删除且无宿主界面进程。可复现的模拟器限定重启脚本已加入核心/总装流程，尚待这批云端。权限查询又补齐“客体已申请、Android定义为normal或normal|instant、宿主实际已获授”的普通系统权限读数，真实拒绝结果原样保留；未申请、dangerous/signature及跨用户仍拒绝。最初遗漏instant附加标志的断言失败保留，修正后两项身份测试0.239秒通过；三项中的真实Play启动本身通过，仍待手动账号流程。

此前`66edaf0`的[核心40111209856](https://github.com/xmy953538104/Mybox/actions/runs/40111209856)和[总装40111213766](https://github.com/xmy953538104/Mybox/actions/runs/40111213766)均成功：核心三轮56项65.857/44.525/44.893秒；总装三轮56项105.857/62.461/63.158秒，五项Google引导25.183秒及匿名注册12.592秒通过。它们不包含随后新增的重启持久化及Vpa适配，不能替代下一批验收。

空广播身份凭据及普通广播两项本地13.437秒通过，包含真实OnFinished、不可变填充拒绝、无接收器误投、一次性失效和无目标Service拒绝。真实Play当前另外暴露VpaService前台类型适配及InstantAppHygieneService持久化Job缺口，后者已明确是isPersisted，不再是泛化flags错误。未将这些失败改成成功或关闭该后台模块，继续按原版/AOSP证据实现。

恢复修复`75dfb5b`的[总装40109739591](https://github.com/xmy953538104/Mybox/actions/runs/40109739591)也已成功：17组通过，含三轮55项、独立Alarm/账户UI/广播权限/剪贴板、五项Google引导及12.580秒匿名注册。之后本地Play实际显示“Sign in”页面；点击时暴露空广播PendingIntent身份凭据的创建校验缺口，REA已定位真实调用。当前在保留creator、不可变/一次性与Service显式目标限制的前提下补齐空广播能力，不伪造投递或登录成功。新加急任务/迟到回调/权限发现与该修复尚待下一轮云端。

`75dfb5b`的[核心40109730181](https://github.com/xmy953538104/Mybox/actions/runs/40109730181)成功：三轮55项82.311/52.938/55.163秒，独立账户UI、Alarm、权限广播与剪贴板各三轮通过，崩溃门禁通过。未再现先前Job90秒恢复失败，但不能据此宣称根因已消除；失败现场采集保留。同期[总装40109739591](https://github.com/xmy953538104/Mybox/actions/runs/40109739591)仍待结果。

其后本地真实Play继续暴露加急任务0x10及迟到完成回调；当前已保留系统加急配额与合法迟到回调，六项Job及Play启动共七项100.967秒通过。GMS检查使用的READ_GSERVICES权限查询已复用现有真实manifest/签名授权模型，两个身份检查及Play启动共三项40.889秒通过。全新本地microG实际UI单次启用注册15.941秒通过：三个注册字段非零、原PID存活且清理完成；这是本地匿名设备注册，不是账号登录。手动Play登录入口继续验证；新改动仍待云端，无需接入Y700。

手动Play白屏后出现实际输入ANR；现场主线程在Play的ServiceConnection回调等待GoogleApiHandler所持HashMap，后者当时在同步虚拟bindService请求内。原始ANR及随后server/microG线程快照已保留私下（后两者采集时已空闲，不能据此宣称永久死锁根因）。同轮Play后台调度器明确报告被未实现的Job形态拒绝；新增分项错误以辨明持久化、flags或URI形态，未绕过约束或假报成功。登录和下载安装仍是当前真实阻断。

本批本地API31完整54项172.452秒通过且无新增崩溃；随后加上仅客体死亡恢复的6项ContentObserver/恢复、2项包身份及私有Play30秒观察，共9项69.272秒通过。64项JVM、构建/lint和APK审计通过。真实手动再打开Play目前仍为白屏，登录入口未验收；进程存活不是业务完成。下一轮云端将验证新增55项核心及超时现场，当前仍不需要Y700。

当前继续修复恢复竞态并验证真实Play。`3f64813`的[核心40106624564](https://github.com/xmy953538104/Mybox/actions/runs/40106624564)失败：首轮Job服务端死亡恢复90秒超时，第三轮虽52项报告通过仍发生迟到旧Activity崩溃，被崩溃门禁拒绝；[总装40106627580](https://github.com/xmy953538104/Mybox/actions/runs/40106627580)成功不能抵消它。尚未替换`135c7cc`交付包。新增失败时现场采集真实JobScheduler状态，不延长超时或宿主重投任务。

本批修复：迟到物理入口在新客体已绑定时，通过原记录/原客体Binder的实际DeadObjectException识别过期代次；关闭时只绕行该物理token的finish校验，普通客体记录校验保留。已增加服务端死亡及仅执行器死亡两类恢复检查。Play所需同包singleInstance已实现任务独占、跨进程复用与实际调用任务返回，六项任务用例通过；仍未支持其账户UI/alias、完整恢复flags等。读取管理员限制保留真实宿主策略，安装来源API读取真实容器记录。详见[Activity记录](activity-records.md)、[Google启动记录](google-startup-compatibility.md)及[JobScheduler](jobs.md)。新一轮完整回归和云端尚待最终结果，账号认证、目录及下载安装未验收；未操作Y700。

真实[OS JobScheduler](jobs.md)首版已落地：实际OS约束/工作队列、签名任务信封、独立物理job代理、实际JobService及身份核验回调。本地API31五项92.390秒通过，含原任务在真实执行器死亡后重调度、server死亡后由OS冷启动恢复；均核对相同原nonce、不同执行PID与真实OS完成删除，没有宿主重投/force-run。整组52项166.288秒中1项新过滤测试错误依赖ServiceInfo默认enabled值；生产代码未因它放宽，显式设为false后两项身份/Direct Boot用例0.077秒通过。64项JVM、双APK/lint/审计通过，云端整组与Android14命名空间待验收。实际stop/network/进度回调仍需运行覆盖，不能把本地任务恢复当作完整后台兼容。

真实私有Play已越过先前enqueue阻断，并逐项暴露系统共享库、用户/owner发现、安装来源及Direct Boot辅助组件查询缺口，详细REA证据、失败轮次和边界见[Google启动兼容记录](google-startup-compatibility.md)。第16/17轮实际出现RESUMED，随后各自在后台安装来源读取/存储判断处死亡，严格存活检查拒绝；尚未验收Play主界面、账号或下载安装。Direct Boot误判已由真实调用追到MATCH_DISABLED_COMPONENTS被错误丢弃，修正查询并保留真实锁定用户过滤，未强制解锁。

`558c20d`的[总装40103789885](https://github.com/xmy953538104/Mybox/actions/runs/40103789885)成功，[核心40103787982](https://github.com/xmy953538104/Mybox/actions/runs/40103787982)仍失败：首轮45项的组件状态冷恢复在首次界面绘制读取已缓存Settings Provider时被真实包/UID检查拒绝，后两轮通过。已适配绑定前的System/Secure/Global Provider缓存，复用原身份/token保留逻辑，新增实际缓存和幂等测试。CI现在将自有UID未预期Java/native崩溃和无法收集崩溃证据一并记为失败，避免仅凭instrumentation成功忽略客体死亡。该新修正待CI，最新双工作流均接受的交付仍为135c7cc。

恢复修正已在API31/x86_64完整45项87.892秒通过，61项JVM、宿主/测试APK构建、lint与审计通过，待云端重复验证。`8789417`的核心40099781527和总装40099783863均失败于server死亡后的旧物理Activity恢复：Android先启动未绑定VM的占位Activity，其异常阻断后续主线程绑定。现在只关闭结构有效且仍处于EMPTY/PREPARED的本宿主旧入口；正常BOUND拦截错误仍可见。生命周期上报固定使用原先绑定的server，真实死亡即退休旧客体，不把旧记录送往新server。新增测试实际保存旧envelope、观察原客体Binder死亡、再次启动旧入口，再核对同一新VM的丢弃计数和真实正常重开。本地先前3轮测试分别暴露死亡观察缓存及历史recent-task记录误判，修正为实际死亡通知和本轮新task；先前97.393秒的45项中1失败仍保留。原失败和本地通过不等于云端稳定或Google业务通过。

当前组件状态批次已完成[Activity/Service自有状态持久化](component-states.md)：正常查询/解析、实际别名启动、禁用后已运行Service解绑销毁、server重启后状态保留，以及未知组件/跨包/终止型flags/无效批次/旧代次拒绝。API31完整45项91.333秒通过；独立扩展生命周期用例4.905秒通过，61项JVM、APK/lint/双工件审计通过。API33+真实批量setter分支仍待本批CI。实际Play已越过组件切换，27.456秒诊断仍未RESUMED，最新FATAL是JobScheduler.enqueue试图调度物理系统不存在的`com.google.android.finsky.instantapps.PhenotypeUpdateService`，真实Job桥接尚待实现。

`135c7cc` 的[核心40097666234](https://github.com/xmy953538104/Mybox/actions/runs/40097666234)与[总装40097668851](https://github.com/xmy953538104/Mybox/actions/runs/40097668851)均成功。总装17组全部通过，包括三轮44项核心、三轮死亡清理/服务重连、Alarm、五项Google引导及八补丁microG匿名注册健康。开发包位于`dist/mylite-135c7cc/Mybox-dev.apk`，119523422字节，SHA256 `0976f1513b8f9fef786526420c791859175fd7488a4c0e562ceb2f42309e3a59`；实际v2签名、唯一内嵌APK为八补丁microG、源码补丁全集和真实APK/证书pin均已核对，DELIVERY.json保存每组结果。仍非Google业务或日用验收。

`9f5ebc5` 的[总装40096413020](https://github.com/xmy953538104/Mybox/actions/runs/40096413020)已成功；同提交[核心40096410390](https://github.com/xmy953538104/Mybox/actions/runs/40096410390)仍失败：首轮43项48.443秒中1项在连续客体死亡后获取Provider断连，后两轮43项29.366/28.898秒通过，全部三轮Alarm/账户UI/权限广播/剪贴板组通过。该差异不能称为已稳定。新增恢复只在Provider获取实际DeadObjectException且原reservation的Binder死亡已确认后执行一次，并重新核对当前Provider权限；活进程/失败ping及Service副作用操作不重试，待新CI。

Play组件状态读取原型本地0.047秒通过，但进一步启动22.567秒仍未RESUMED；已明确下一处FATAL是动态setComponentEnabledSetting。REA商店方法`ev_4f3bd807f1388c1f031a60fe09c1fbd71156dc32ec58c7d9cf497e4adeadc6b3`确认使用状态1/2及DONT_KILL_APP=1。该原型仅保存为本机补丁，尚未进入发布核心；需要把持久化状态、所有组件查询/解析及已绑定进程的Activity元数据一起实现和验证。

新增容器[内容观察](content-observers.md)、无权限限制的已导出跨包Provider及[Provider故障隔离](virtual-server-lifetime.md)，本地API31/x86_64完整43项56.400秒通过，61项JVM、双APK构建/lint/审计通过。五代实际远程客体各64条订阅、超额拒绝、进程死亡清理和真实server死亡后宿主重连/客体重启均有运行断言。首个死亡组导致宿主连带退出，切换unstable依赖后仍有缓存死Provider竞态，补上仅bootstrap真实DeadObjectException的一次重取；四项死亡组4.946秒通过。完整43项首轮55.822秒仅失败于旧测试仍断言stable lease，改为核对真实保留的unstable lease并保留其他生命周期断言后通过。受保护跨包Provider、同步适配器及现代target回调兼容不在此批已验证范围。

`032b6b3` 的[核心40093682087](https://github.com/xmy953538104/Mybox/actions/runs/40093682087)与[总装40093684873](https://github.com/xmy953538104/Mybox/actions/runs/40093684873)均成功：总装核心三轮39项41.912/26.112/25.888秒、Alarm三轮4项54.022/48.758/48.937秒、Google5项22.357秒及匿名注册健康10.412秒通过。随后`e58421f` 的[核心40094372014](https://github.com/xmy953538104/Mybox/actions/runs/40094372014)与[总装40094374026](https://github.com/xmy953538104/Mybox/actions/runs/40094374026)失败，原因是复制AttributionSource时使用其Parcel构造器，在Android14普通代码上下文触发必须处于Binder transaction的安全检查；该回归由本次改动引入，未发布失败包。现改为复制平台生成的AttributionSourceState再经本地构造接口建立对象，原token、PID（支持时）、next及renounced permissions仍保留，真实远端Binder验证不变，待本批Android14 CI。

本地原版Play40.0.13目前能通过虚拟GServices监听与查询并完成Application绑定，但主界面仍未验收：最近一次客体FATAL是未实现的getComponentEnabledSetting；另有凭据保护存储/解锁状态相关日志。真实账户登录、商店下载、地图、FCM与性能等同均未通过，不能将这些底层进展当成已可日用。

Android12本地完整核心首轮39项52.270秒出现1失败1错误：归因测试调用不存在的setPid及安装fixture调用不存在的显式seal。兼容fixture后第二轮49.553秒出现归因token被重置，以及旧fixture与新宿主pin不匹配的2失败1错误，均保留。实际修复使用系统Parcelable完整复制归因状态；PID仅在API33+设置/校验；旧系统通过真实commit封存并验证后续写入拒绝，新系统继续验证显式seal。经正常Mybox文件导入更新本地fixture，完整39项第三轮73.859秒通过。此结果是API31/x86_64，非ARM64/OEM或完整Google业务验收。

本地新增 Android12 启动缓存记录修复及 singleTask Activity 别名复用，5项任务测试10.777秒通过；此前5项全失败116.195秒及两次诊断失败均保留，详见[Activity记录](activity-records.md)。60项JVM、宿主/测试APK、lint及双APK审计通过，尚待本批CI。Google启动门禁允许实际Google证书的已安装Play商店，并仍要求精确pin的microG；不为GSF放行，也不用兼容签名授权。私有诊断输入不进入测试资产或公共仓库。

原版内置Play40.0.13在本地API31已进入FinskyApp初始化，但尚未恢复主界面：首次因单Task别名门禁失败，补齐后两次在配置初始化失败，底层为`registerContentObserver`落入物理GSF Provider权限检查。下一步需实现容器内内容观察与跨包Provider权限，不能通过授予物理GSF权限或吞掉异常冒充通过。物理GMS/GSF/Play仅在这台独立模拟器user0被暂时禁用，无HMS、无真实账户。

2026-10-24。用户要求改为依据原版 VLite 的实际行为重建核心，并在其使用平板期间仅在电脑及 CI 工作。

活动构建为独立 mylite-engine；Bcore 已从 settings 和宿主依赖中移除。源码没有引入原版 VLite 库或反编译代码。

最新核心批次包含 [Activity B1](activity-records.md) 的同包 singleTask/singleTop/CLEAR_TOP、[Service PendingIntent](pending-intents.md)、认证界面普通 Intent/URI grant 负向检查，以及原生 TSYNC 前线程协调与失败回滚保护。原生历史SIGSYS与worker死亡仍保留为未定位根因，临时wait-mask、系统Alarm/网络回调、跨包认证UI和真实Google业务仍未完成。

上述批次 `1ce7b5a` 已通过[核心 CI40070852561](https://github.com/xmy953538104/Mybox/actions/runs/40070852561)：三轮各37项Android（52.957/31.505/24.449秒），Linux x86_64/AArch64各三轮、45项JVM、lint和工件审计全部成功。新的guard负向用例验证精确errno、原mask/action恢复、停驻线程释放及拒绝错误filter；Android各客体实际I/O安装返回0。它们仍不能证明历史偶发退出已定位或ARM64 Android/Google业务通过。

同提交[总装40070862438](https://github.com/xmy953538104/Mybox/actions/runs/40070862438)也成功：三轮37项核心（48.020/30.971/23.910秒）及两项microG验证（14.140秒），产出含同批精确pin无HMS microG的APK。下一检查为可手动选择的真实匿名设备注册探针，要求UI开启后持久化三项非零注册结果；探针本身不是已通过的Google业务。

新增探针的 `094bada` [总装40071596728](https://github.com/xmy953538104/Mybox/actions/runs/40071596728)失败：首轮slot0原生初始化返回EBUSY(16)，导致37项中14项错误；第二/三轮37项通过不抹去失败。EBUSY来自候选信号检查还是TSYNC拒绝尚无精确阶段，正在补诊断。原microG两项通过，但注册探针在首个设置页标题识别处超时，fresh=true/menuOpened=false/enableClicked=false，尚未触发注册；不能称为网络或Google服务端失败。同期核心40071593970成功也不能替代这一失败。独立[机制诊断40071950577](https://github.com/xmy953538104/Mybox/actions/runs/40071950577)另在两个Linux架构各复现18项预期证据，包括wait-mask/旧signal frame导致的真实SIGSYS；该诊断成功不是产品通过，也未将旧偶发死亡归因此机制。

诊断版 `ac4579d` [总装40072831153](https://github.com/xmy953538104/Mybox/actions/runs/40072831153)三轮37项Android及两项microG通过，但注册探针仍失败；结构证据明确显示标题存在而可见性为false，实际注册菜单可见、客体进程仍存活，因此下一版以可见菜单作为入口，保留单次点击及真实注册三字段断言。该轮所有客体native errno0，旧EBUSY尚未复现定位。[核心40072830125](https://github.com/xmy953538104/Mybox/actions/runs/40072830125)的x86 Linux第三轮栈池占用断言失败（前两轮及ARM三轮通过），实际计数未记录；已确认旧观察器的fopen也会占被测栈，正在隔离该观察路径并补原始计数，不放宽8槽断言、不用诊断重跑的成功覆盖失败。

`83181b3` 的 [B2a跨包账户UI](account-ui-tickets.md)已在[核心40073849217](https://github.com/xmy953538104/Mybox/actions/runs/40073849217)及[总装40073850240](https://github.com/xmy953538104/Mybox/actions/runs/40073850240)分别独立三轮通过（6.207/3.147/3.125秒；8.291/5.002/4.126秒），跨包singleTask仍门禁。50项JVM、双ABI构建、lint/工件审计及两个Linux架构各三轮也通过，包含五种临时wait-mask实际等待/中断与严格8槽栈池检查。但核心首轮37项中账户token失败，后两轮37项通过不抵消失败：新诊断定位到认证进程的candidate-preflight，23/28两个信号均被同一TID屏蔽（mask=fffffffffffbfeff），TSYNC尚未调用即返回EBUSY；请求未进入认证器Binder。这定位了本轮拒绝阶段，尚未证明该线程为何屏蔽信号，不能归因于账户授权冻结，也不能将旧SIGSYS/worker退出归因此原因。

同批总装三轮37项核心（57.840/31.655/27.336秒）及两项microG通过，真实注册探针首次成功导航、单次开启开关，但90秒内无注册字段，工作流判失败。实际TriggerReceiver在读取网络状态时被系统ConnectivityService拒绝，明确缺宿主真实ACCESS_NETWORK_STATE权限；尚未到Checkin网络请求。下一批补真实normal权限与实际系统边界检查，保持无重试、三项非零注册结果断言。旧signal frame、历史退出、系统Alarm/网络回调与真实Google业务仍未完成。

宿主ACCESS_NETWORK_STATE/WAKE_LOCK补丁 `effc74f` 已通过[核心40074958059](https://github.com/xmy953538104/Mybox/actions/runs/40074958059)三轮37项（48.709/29.244/24.417秒）、B2a三轮、50项JVM、双Linux架构三轮、lint及工件审计。[总装40074959085](https://github.com/xmy953538104/Mybox/actions/runs/40074959085)也成功：核心三轮37项（53.156/30.537/27.248秒）、B2a三轮、三项microG检查（13.556秒），包含真实Connectivity调用及真实UID/PID唤醒锁系统记录的创建/释放。没有增加电池优化豁免、修改microG pin或改变注册探针。

该总装的真实匿名注册探针首次通过（8.036秒）：fresh/menuOpened/enableClicked/switchEnabled/prefsParsed及三项非零注册结果全部为true，记录仅含布尔，无标识符/token原值。它证明这次全新UI单次触发的注册结果实际落盘；尚未验证CheckinService后续finally清理/Alarm、MCS、Google登录、Play或推送。探针在结果出现后即结束instrumentation，不能由其通过推断后台健康。原生EBUSY另行修复，后续成功不抹去前轮真实拒绝或证明冷启动已稳定。

元数据缓存 `2f2766d` 的[核心40075898279](https://github.com/xmy953538104/Mybox/actions/runs/40075898279)判失败：首/三轮38项通过（41.969/24.082秒），第二轮38项中5失败2错误（44.057秒）。新增缓存检查三轮均通过，包含真实并发解析、深复制、外部代次更新和实时record校验；B2a三轮也通过。第二轮slot3的candidate-preflight再次捕获同一TID的23/28全mask，TSYNC未调用即errno16，随后广播异步节点、Service和Activity远端进程发生级联失败。每个失败仍保留；不以首/三轮成功替换第二轮。下一[安装就绪修复](native-install-readiness.md)保持所有线程运行，先在单次有界deadline内等候信号可投递，再进行原ACK/TSYNC；双ABI O0/O2编译/链接通过，实际运行待新CI。

后续注册观察将延长到原checkin进程的安全完成标记和实际Service退出，原字段落盘记录独立保留。新版microG还将从源码移除GServices原始设置值日志，并加入一个仅在原finally调度/stopSelf返回后出现的常量诊断；六补丁必须全新构建及重新pin，不能复用四补丁APK。常量日志不是实际OS Alarm登记证明，也不改变异常路径。

`fe2f191` 的[核心40076711072](https://github.com/xmy953538104/Mybox/actions/runs/40076711072)已通过：三轮38项（54.507/31.498/24.371秒）、B2a三轮（10.187/5.147/3.868秒）、50项JVM、双Linux架构各三轮、lint/工件审计。新11项guard用例验证全mask依赖恢复、永久超时、真实cohort变化和pending保留；本轮Android客体没有记录实际readiness sleep，因此不把它当作已复现ART窗口并恢复的证据。历史失败继续保留。

跨包账户singleTask的B2b及延长注册完成观察已通过本地52项JVM、双ABI/宿主/测试APK构建、lint和双APK审计。`3112332` 的[核心40077565789](https://github.com/xmy953538104/Mybox/actions/runs/40077565789)三轮38项通过（37.643/24.342/19.342秒），扩展账户UI三轮通过（8.982/4.225/4.646秒），包括真实配置重建后新Future/newIntent、跨进程clear-above和部分清除后的受控拒绝；双Linux架构各三轮也通过。六补丁[microG源码构建40076712447](https://github.com/xmy953538104/Mybox/actions/runs/40076712447)成功，精确APK/真实证书及六补丁来源已核对。

使用该六补丁的[总装40077567522](https://github.com/xmy953538104/Mybox/actions/runs/40077567522)失败：核心三轮38项（36.535/21.362/20.389秒）、B2b三轮（5.962/4.666/5.418秒）及microG三项通过；注册三字段实际非零，但90秒内未见同一服务的返回/清理标记。PID7705在08:26:38.767已解码checkin响应；没有观察到GmsCheckinSvc标记或提前fatal/进程退出，直到08:28:08结束instrumentation才force-stop。该证据不能判定为Alarm异常、服务已健康退出或某个死锁；逐项GServices写入与身份核对的耗时/阻塞正在定位。没有上传该失败工作流的交付APK，最新已通过总装包仍为effc74f。完整服务/调度、Google登录和Play仍未验收。

`ff57ae8` 的[独立Android/bionic诊断40078462451](https://github.com/xmy953538104/Mybox/actions/runs/40078462451)三轮全部通过，包含389项wait错误矩阵、五种实际等待、11项guard、严格8槽及ENOMEM恢复。实际为Android14/x86_64、4KiB页、真实宿主UID的runas_app独立进程；安装前Seccomp=0且保留shell附加组，不代表ART/zygote的继承过滤与权限，更不代表ARM64/OEM或16KiB运行通过。原生旧frame和历史退出仍未解决。

账户修复`7947e40`将可信addAccount最终结果与枚举/token可见性分离，53项JVM、双ABI构建、lint和审计通过；[核心40078946863](https://github.com/xmy953538104/Mybox/actions/runs/40078946863)中的隐藏账户/完整账户UI三轮也通过（9.034/5.429/5.176秒），确认真实Future成功但枚举、密码、用户数据、raw token及token请求仍拒绝。它不修改账户可见性或token授权。

该核心工作流整体仍失败：第一轮38项中1项失败（44.572秒），后两轮38项通过（24.890/21.356秒）。组件fixture立即startService再bindService，却要求onStartCommand必在onBind之前；本轮真实记录为create/bind/start/unbind/destroy，两次回调均存在。AOSP14冷启动路径本就先requestServiceBindingsLocked再sendServiceArgsLocked，startService返回不是回调ACK。下一修正仅让fixture在bind前有界等待真实create/start事件，保留完整精确生命周期断言，并新增startBeforeBindObserved检查；不将该失败归因账户改动，也不放宽为任意序列。两处修改已通过独立SDK35编译，运行待CI。

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

`518ec3c` 的[核心40079698944](https://github.com/xmy953538104/Mybox/actions/runs/40079698944)整体成功；组件fixture现在在bind前等待实际start回调，旧失败仍保留。新增权限广播按真实APK签名、双向权限及安装代次筛选，另以独立APK覆盖不同签名、未请求权限、disabled/private、无匹配和排队更新；Google账户事件只开放精确pin microG到精确Google签名接收器的窄互操作路径，Store启动门禁仍保留，不能称为Play通过。第七个microG源码补丁只对实际同UID且同PID调用直接确认self，跨进程继续完整身份核验，并增加checkin开始常量诊断。两批统一通过本地55项JVM、宿主/测试APK构建、lint及双APK审计；新增Android运行与精确七补丁microG全新构建仍待CI，未将其作为已交付总装包。

`f46f003` 的[核心40080639585](https://github.com/xmy953538104/Mybox/actions/runs/40080639585)成功：核心三轮38项（46.468/26.177/24.970秒）、账户UI三轮（8.534/7.048/7.263秒）、新增权限广播三轮（2.728/2.365/2.231秒）、55项JVM及双Linux架构验证通过。精确[七补丁microG源码40080641101](https://github.com/xmy953538104/Mybox/actions/runs/40080641101)成功，APK/真实证书及完整补丁hash已独立复核。

同提交的[总装40080983836](https://github.com/xmy953538104/Mybox/actions/runs/40080983836)仍失败，不能作为交付包：三轮38项核心、账户UI、权限广播及microG四项（15.487秒，含真实跨进程身份负例）通过。真实注册PID8349于09:19:42.084开始、43.041解码响应、45.685成功，随后45.727在MCS广播PI的exact/idle Alarm处被真实系统包/UID检查拒绝；45.738又在finally CheckinService.schedule的Service PI闹钟调用处fatal，cleanup未到达，原PID退出，健康探针10.047秒失败。新路径已越过之前90秒未返回的观察点，但不能据此倒推旧轮唯一根因。下一步为真实OS Alarm/PI及冷恢复，不吞异常、不将Service PI假转成Receiver。新增菜单登录引导测试和独立旧signal-frame原型仅通过本地构建；前者尚未运行，后者默认关闭、不链接产品核心、尚非产品修复。

`5347177` 的[核心40081771524](https://github.com/xmy953538104/Mybox/actions/runs/40081771524)成功；独立[旧frame实验40081772963](https://github.com/xmy953538104/Mybox/actions/runs/40081772963)在Linux x86_64/AArch64各Debug/Release三轮、每轮14场景全部匹配，含受控SIGSYS/SIGSEGV负例。实际两架构为4KiB页、初始Seccomp=0；x86 shadow-stack状态0，未启用防护配置/扩展向量/ART/产品栈池尚未覆盖，原型未接入产品。

同批[总装40081776164](https://github.com/xmy953538104/Mybox/actions/runs/40081776164)失败：核心/账户UI/权限广播均三轮通过，新增真实菜单引导已完成Google Accounts和Add Google account点击，但LoginActivity.createWebView被尚未实现的PM.isInstantApp查询阻断并fatal（09:31:32.670，PID8183）；随后Activity观察失败是该进程退出的后果。引导页未验收，本轮未执行后续注册健康探针。下一补丁仅让已提交完整安装包的isInstantApp返回false，拒绝非0虚拟用户，Google缺包不查询物理系统，普通未知包仍委托原PM；扩展原身份路由测试验证这些边界。两文件已通过独立SDK35编译；因Alarm A未跟踪实现仍在编写，本地未运行这一补丁的全量Gradle，交由干净CI验证，未将编译称为Android通过。

`010daaf` 的[总装40082687600](https://github.com/xmy953538104/Mybox/actions/runs/40082687600)仍失败：真实LoginActivity已越过isInstantApp，随后09:46:15.461在WebView窗口焦点回调读取ClipboardManager.getPrimaryClipDescription时，被真实IClipboard的包名/UID检查拒绝（PID8248，物理UID10192）。登录引导未验收，后续注册健康探针未执行。下一修正仅适配这一已观测元数据查询，并要求本地已认证、当前恢复且真正持有窗口焦点的客体Activity；其余剪贴板调用保留原行为，不借服务端替后台客体读取，也不读取用户剪贴板内容。真实Alarm/冷恢复仍在开发，不能称为注册清理通过。

已在用户授权安装官方AEHD2.2后启动独立本地Android11模拟器，开始运行两份原版GBox，完整边界见[原版运行对照](gbox-reference-runtime.md)。独立ARM64探针系统直装实际读写通过；新版GBox因硬件信息空值在HTTP头构造处崩溃，REA已定位；旧版完成探针导入，但客体在x86 ART/ARM NativeBridge边界SIGSEGV，尚未完成客体I/O。继续准备同ISA的ARM64环境，不据此推断原版ARM64真机兼容性，也未操作用户平板。

[核心40082686280](https://github.com/xmy953538104/Mybox/actions/runs/40082686280)和[40082781557](https://github.com/xmy953538104/Mybox/actions/runs/40082781557)均失败，六轮各38项、0失败/1错误，根因相同：新增isInstantApp分支先将平台包`android`送入只接受客体安装名的PackageStore，触发IOException，尚未到预期的真实PM委托。修正统一四个只读查询入口对该平台包绕过虚拟存储；安装名/路径校验保持原样，扩展既有Android身份测试验证instant、包UID、组件与显式resolve都实际委托原PM。两文件独立SDK35编译通过，运行待CI，原失败继续保留。

这两轮CI的双Linux架构各三轮原生检查通过，Android共72条InstallIo均errno0/TSYNC0/cleanup0，crash buffer为空。40082686280的PID6295在09:41:36.420实际记录readiness waits14、elapsed20.329ms，先遇到TID6308全mask窗口再成功安装，首次取得有界准备等待在ART进程内恢复该窗口的正向证据。它不解释PM查询失败，也不证明历史SIGSYS/旧signal frame问题全部已解决。

平台查询修正`102953c`的[核心40085217719](https://github.com/xmy953538104/Mybox/actions/runs/40085217719)成功。新增[系统广播闹钟与冷恢复](os-alarm-broadcasts.md)、[焦点客体剪贴板元数据](clipboard-metadata.md)已通过本地60项JVM、双ABI/宿主/测试APK构建、lint和双APK审计，真实Android验收待CI。精确[八补丁microG源码40082782912](https://github.com/xmy953538104/Mybox/actions/runs/40082782912)已下载并独立验证APK摘要`ab6376742c764bf1166c9d08d84ed90cae419c282f833fe5af0d9d4322f1b42e`、真实签名证书和全部八补丁来源；第八补丁将Checkin的实际Receiver目标改用广播PI工厂，未把引擎Service PI假转成Receiver。尚未交付这批总装包，也未宣布注册清理、登录、Play或FCM通过。

`2308d1f` 的[核心40086917364](https://github.com/xmy953538104/Mybox/actions/runs/40086917364)和[总装40086918401](https://github.com/xmy953538104/Mybox/actions/runs/40086918401)整体均失败：三轮38项核心、账户UI、权限广播、独立剪贴板均通过；Alarm四项中三项通过，一项每轮停在OS记录观察断言。旧测试在AlarmManager dump中寻找capability URI，但实际只打印PendingIntentRecord编号，尚未释放fixture继续投递/冷恢复。修正从系统PendingIntent dump找精确URI的记录编号，再核对AlarmManager引用同一编号，保持真实OS断言；本地API30针对中断恢复场景实际通过，含该精确记录关联，未放宽为任意宿主闹钟。完整Alarm运行仍待新CI。

同批总装Google五项通过（24.994秒），包含真实Settings菜单→Google Accounts→Add account→LoginActivity的WebView引导及取消清理，尚未进入实际账号登录。真实匿名注册健康探针也首次完整通过（12.010秒）：PID9232于10:50:40.330开始checkin，44.198成功，44.284到达原finally调度/stopSelf之后的清理标记；原PID仍存活、无新fatal/退出、物理Service记录已消失，三项注册字段实际非零。此结果修复了此前注册落盘后崩溃/清理未返回的阻断，但不证明长期Alarm唤醒、MCS/FCM或Play通过。由于独立Alarm suite失败，未上传交付APK。

OS记录观察修正`3a61e93`的[核心40087781372](https://github.com/xmy953538104/Mybox/actions/runs/40087781372)成功，首次完成Android14三轮独立Alarm四项。继续本地Android11测试定位并修复两处真实版本差异：广播完成时OS会清除组件字段，旧CancelListener方法名为onCancelled；物理入口仍严格校验组件，仅来自真实OS的完成回调适配该字段，并保留HMAC/boot/creator检查及本次send快照。API31新增的测试查询按系统版本调用，实际immutable填充拒绝、真实完成及取消断言保留。四项完整API30 suite已通过49.058秒（此前单项49.108秒），包含原OS闹钟冷唤醒与组件丢失/外组件负例；最终补丁的重复运行和总装尚待验收。

`dc22e87` 的[核心40088534487](https://github.com/xmy953538104/Mybox/actions/runs/40088534487)与[总装40088535813](https://github.com/xmy953538104/Mybox/actions/runs/40088535813)均成功。总装三轮38项核心50.603/23.573/23.815秒，独立Alarm三轮四项49.445/48.573/48.479秒，剪贴板三轮2.837/1.583/1.603秒，账户UI/权限广播各三轮也通过；Google五项20.141秒，实际注册及原服务健康9.803秒通过。精确八补丁microG已核对嵌入APK字节与真实证书。新的开发包为`dist/mylite-dc22e87/Mybox-dev.apk`，119507038字节，SHA256 `36233630a83ad66f4ec0203128b5157288fd5330b70cbd38a618d80c903ea3df`，APK/签名/内置microG审计及独立DELIVERY.json已保存。它不代表真实账号登录、Play下载、Maps或FCM通过。

本地API30最终补丁又连续通过两轮Alarm四项48.197/47.726秒；追加第三轮被电脑非正常重启打断，保留为未完成，不计通过。Windows事件41记录BugcheckCode0，未定位原因，不归因于Mylite或AEHD。后续本地对照只同时运行一台模拟器。

新开发包在API27原生ARM64 ART环境首次完成同一独立探针的真实客体验证：经Mybox的SAF界面导入，三次独立冷启动PID3489/3631/3748均Java/JNI libc/直接AArch64 svc文件往返通过，UUID均不同，实际UID10061；第一轮TSYNC返回0、11/11线程ACK，SELinux Enforcing、4KiB页、内核3.18.94+，无NativeBridge。结果与系统直装PID2713/UID10059分离。这是软件模拟器的ARM64/ART证据，非OEM真机、16KiB或性能等同证明。旧GBox同环境则遇到系统明确的`timeout publishing content providers`终止，未到探针，不能判为同样的真机故障。详细对照继续见[gbox-reference-runtime](gbox-reference-runtime.md)。
