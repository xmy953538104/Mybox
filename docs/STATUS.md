# Mylite 验证状态

2026-10-27 `78c8e22`两批均失败，未升级Y700：核心40375709514三轮128项219.101/187.319/203.769秒、干净AOSP40375711001三轮128项200.225/202.474/209.935秒、各15个独立组均完整完成；新增两个待创建页面实际遮挡65秒后逐层点击通过，分别67.373/66.997秒。所有核心进程退出码0且有最终code=-1，均无旧聚合超时。两次持久Job实际跨重启通过，AOSP首装36.914秒、Google6项9.599秒、注册7.683秒、实际输入/Paste/手势31.288秒、MCS97.412秒完成。但核心场景收尾和AOSP microG设置页收尾分别出现一次onStop→DeadObject崩溃，不能计整批通过。

先核原版生命周期分发与activityResumed/activityDestroyed的真实Exception处理（REA `ev_fff176e7c220eab540da05c0fb5a085615af6628cf76b6516611e9f244865e5e`、`ev_4d5e721d0c75366320fb3439cecafc5e1cd18f3cd4127f660d659429b24db87f`、`ev_d498fca8cd8c0a13647d3411d12f97212e9f306fcd3feff2f0a1221ba77174c8`，指令及typed catch均按DEX校验），再核实际退出记录：Instrumentation.finish的包停止与客体onStop相撞。下一候选让已经确认死亡/DeadObject的精确绑定服务端触发客体退休，不再被缓存isBinderAlive阻挡；所有调用点已核，普通宿主/其他服务端仍不允许退休，不吞活服务端的身份或协议错误。同时独立场景等待真实销毁ACK，并新增真实服务端死亡与客体停止相撞的回归，核真实客体退出且无Java/native崩溃。候选尚未重新构建/运行；原始两批失败完整保留。

2026-10-27 `d9e95e8`整批复核：核心40362982326三轮128项154.452/171.526/191.730秒、干净AOSP40363048488三轮128项202.142/209.252/209.417秒及两批全部15个独立组通过，45份crash全空；两次真实持久Job跨重启通过。干净首装38.832秒、Google6项9.623秒、匿名注册8.155秒、真实输入/Paste/返回手势31.685秒、MCS恢复100.608秒通过。现代Play40367314330整批失败：首轮在第122/128项未完成即碰到外层240秒预算，并出现一次Instrumentation.finish中的UiAutomation断连崩溃，17份非空快照是同一历史崩溃；其余两轮128项205.588/226.700秒、全部独立组和Google/MCS交互组完成，不能抹去首轮失败。下一候选仅将聚合核心进程预算改为360秒并保存退出码、要求真实最终code=-1，不放宽单项或生产超时。

同一开发包已保数据升级到Y700；商店已越过原先的前台服务/Settings异常，进入真实语言更新提示，但点击“知道了”后仍崩溃。新的完整时序是singleTask入口→singleInstance未认证主页待创建→standard语言提示；提示关闭后，待创建页的记录已被撤销。先用REA核原版launch handler、pending record、真实activityCreated、OS任务及进程死亡清理，再定位本实现20秒任务撤销和60秒未创建记录清理两条相关路径。下一候选保留Android已接受的创建记录，直到真实创建/死亡/代次失效；身份、token、任务及账号UI票据校验保持。增加真实65秒前台遮挡后逐层按钮打开两个待创建页的独立回归；尚未构建/运行，不接受修复已成功。原版Store自更新标志已另行确认，但未证明语言提示由该标志造成，未改用户数据库或删除缓存。

用户已授权休息期间自行操作Y700；需要本人账号验证时只记录后续步骤，不索取凭据、不打扰。真实认证持久化、商店目录及下载安装仍未通过，不能交付为已完成产品。

2026-10-27 登录后闪退修复候选：用户已开放Y700并实际完成一次可见Google登录流程，但随后Play40反复崩溃；不接受真实认证持久化、下载或安装已通过。整批异常归为绑定型前台服务缺少真实承载、`pai_selection_page_complete`写入误达物理SettingsProvider，以及现代Play53另行观察的公开Job标志0x2拒绝。首失败即REA规则已落实；新候选补真实bound carrier/通知清理、受信Google的容器Secure/Global持久化及跨VM读取一致性，并保留公开Job标志。新增真实OS前台状态/通知、跨进程设置更新/删除/物理系统不变/伪造调用拒绝及磁盘提交失败回归。尚未构建和云端验收，未再次启动设备上的商店；账号及应用数据未清除。仅一条bound-only前台承载/VM的容量仍明确受限，不宣称完整核心兼容。

2026-10-26 `df82a33`整批已核：[核心40338339308](https://github.com/xmy953538104/Mybox/actions/runs/40338339308)三轮125项160.549/190.525/190.157秒、[干净AOSP40338394661](https://github.com/xmy953538104/Mybox/actions/runs/40338394661)三轮125项195.002/200.329/202.577秒及两批全部15个独立组通过，45份crash全空，两次真实持久Job跨重启通过。首装38.001秒、Google6项9.615秒、注册实际持久化8.355秒通过；实际Google输入/系统Paste/完整合成值回读/同PID/两次返回手势31.048秒通过，四张关键截图已核。MCS97.732秒完整通过真实服务器登录、明确在UI选择一分钟后的OS心跳与ACK、实际断网重连、无额外唤醒的死亡后新PID恢复、显式OFF落盘且正常准备尊重OFF、UI重新开启后连接，并从UI恢复Automatic。这个结果不接受默认五分钟长期稳定性，也不接受真实账号认证、Play下载安装或FCM消息投递。开发包和独立DELIVERY保存在`dist/mylite-df82a33/`；当前用户要求继续云端与源码，暂不访问Y700。

2026-10-26 `a1368e3`整批已核：[核心40331863431](https://github.com/xmy953538104/Mybox/actions/runs/40331863431)三轮125项188.955/194.212/214.334秒，[干净AOSP40331925689](https://github.com/xmy953538104/Mybox/actions/runs/40331925689)三轮125项196.935/203.188/203.935秒及两批全部15个独立组通过，45份crash全空，两次真实持久Job跨重启通过。普通跨包null接收方权限的实际有序/无序投递、发送方权限/签名/私有与disabled/旧代次负例均六轮通过，未扩大账号变更特例。

总装仍失败：首装38.041秒、Google6项10.041秒、注册UI及实际持久化8.338秒通过。MCS13.008秒已确认下个进程checkin=true、实际Play接收器授权、真实登录PID4195、开关enabled+checked、Connected UI及Play非空注册记录，但首次心跳选项检查在点击后立即查找，未等真实对话框创建，仍未执行手动心跳/恢复。Play29.095秒第一次真实滚动已将输入框完整露出151..204px，Chromium却保留旧unclippedBottom382，测试误判231px高度并停止；失败截图与精确113源码的缓存/只写不清extras吻合。下一候选仅修测试：根据当前真实WebView视口边缘识别过期extras，保留完整可见与实际Paste/全文/同PID/手势要求；单次点击后有界等待真实已选选项，不改生产心跳默认。待完整云端验证，未交Y700，认证/下载安装/FCM仍未验收。

2026-10-26 `179dc6f`整批已收齐：[核心40323432710](https://github.com/xmy953538104/Mybox/actions/runs/40323432710)成功，三轮124项155.974/183.664/189.249秒；[干净AOSP40323464975](https://github.com/xmy953538104/Mybox/actions/runs/40323464975)整体失败，核心三轮124项196.805/206.096/205.051秒和全部独立组通过。45份crash全空，两次真实持久Job同nonce跨重启、原OS任务移除且未启动首页。Job执行已获真实OS开始确认后的executor/server死亡恢复，确实使用原回调请求重试；未确认开始的失败仍可能退休代理进程，不宣称完全隔离。

AOSP首装40.258秒、Google6项9.618秒、注册开关6.84秒、真实Google IME/系统Paste/完整合成值回读/两次手势30.601秒通过，四张关键截图已核。MCS19.548秒在初始开关检查失败：存在真实服务器登录，但后续明确报Checkin disabled。心跳手动配置、OS心跳与ACK、断网/死亡恢复在这轮未执行。界面显示开启不再作为跨进程持久化的充分证据，不能将本批算Google业务通过。

下一候选依据原版REA补普通跨包广播：原microG实际消息投递有null接收方权限分支，当前引擎却一概拒绝，并把所有Google目标广播错误送入仅支持账号变更的策略。现保留真实签名/manifest权限、exported和代次检查，补正常有序/无序投递及独立APK负例；账号变更例外仍仅限原精确action。注册OFF/ON改为等待只读真实XML状态并重新打开界面，MCS另检查下个进程启动时的真实落盘布尔值和实际Play接收器授权。异步落盘被测试结束打断只是候选解释，未认定根因；不直接写设置、不清凭据。候选待完整云端验证，不交Y700。

2026-10-26 `c8c3cb6`整批已核：核心40315249904三轮123项160.24/117.906/120.568秒、干净AOSP40315250389三轮123项104.845/111.708/112.044秒均通过，两批全部15个独立组通过，45份crash全空，两次同nonce持久Job真实跨boot通过。首装43.09秒包含实际首次checkin/清理，Google6项10.659秒，注册开关7.153秒通过。Play43.95秒仍无Paste菜单；MCS369.658秒卡在定时心跳。实际已核服务器LoginResponse、当前物理Service/OS Service闹钟、连接UI及Play非空注册记录，但连接约275秒被重置，早于300秒心跳，实际重连闹钟约5秒后恢复同PID连接。不能把它归因于闹钟未投递，也不能把此批称为完整MCS恢复通过。

连续Paste失败已触发REA升级：重新串原版登录入口/createWebView/WebSettings、Manifest及13层主题、GCM高级设置/资源/每网络间隔，并核与实际WebView113对应的Chromium源码。屏幕边界已被viewport裁切到17px，旧探针把它又当完整高度；新候选改读该版本保留的未裁切扩展，再作真实滚动及一次长按。MCS候选通过真实高级界面选择原版同样支持的60秒间隔，区分正常准备的即时心跳与实际OS闹钟触发；生产自动五分钟默认不变，旧默认重置仍留为未解决观测。另补已真实ACK的Job失去执行器时用原始OS回调请求重调度，避免已知杀代理/旧进程重新绑定竞态；未确认启动仍保留真实死亡路径。新增边界与原nonce/新客体PID恢复验证，候选尚未构建，仍不交Y700。

2026-10-26 `9d33447`整批已核：核心40304997691首轮123项232.885秒1失败（Job执行器死亡后原OS任务重新绑定超时），后两轮110.641/116.055秒通过；干净AOSP40305020189三轮123项90.527/95.64/96.381秒均通过，两批独立组全过、45份crash全空、两次真实持久Job跨boot通过。首装32.345秒/Google6项7.947秒通过；注册95.631秒失败（已有成功注册，但观察器错误要求新的Service收尾），Play37.891秒无Paste菜单，MCS探针90.579秒未通过初始连接。实际早期日志已有MCS5228/TLS/server LoginResponse及ping/ACK，后续测试连续force-stop耗尽恢复预算，不能据此把完整MCS恢复记为通过。

13补丁microG已源码构建并核全部Git补丁哈希、真实APK/证书pin和无HMS清单；默认ON及显式OFF持久化已实际观察。下一候选仅修正验证场景：真实首装观察首次checkin及收尾；已有注册不强迫重新联网；独立Google旅程先执行真实正常准备动作，自动死亡恢复阶段继续只作被动观察；Google输入框被IME遮到仅17像素时先作真实内容滚动，必须完整露出再长按。原版REA已补登录入口/Manifest、checkin12小时间隔及Job断连/解绑链；Android14绑定超时明确不重调度，Job失败保留为未解决，未猜测修改生产调度。新验证候选未构建，仍不交Y700。

2026-10-26 `f577612`整批已核原始证据：核心40293882445三轮121项131.107/114.241/112.4秒、干净AOSP40293919527三轮121项106.901/110.957/113.802秒均通过；两批各三轮5项真实OS闹钟组及其余独立组通过，44份crash全空，两次实际持久Job同nonce跨boot1→2通过。真实Service闹钟类型/update/cancel/removeAll/one-shot及只靠原闹钟跨server/guest死亡已通过。干净首装38.009秒、Google6项9.613秒、匿名注册9.672秒、实际Google输入/系统Paste/两次手势29.968秒通过。没有真实账号认证、下载安装或MCS连接/令牌/投递验收。

当前候选继续按原版REA完整配置链补齐：源码microG补丁0013以manifest默认开启checkin/GCM，保留显式用户OFF；宿主只向精确pin和当前安装代次发送正常RECONNECT，持久进程死亡须等旧Provider实际关闭后才能恢复，自动恢复每boot/代次/五分钟至多三次并显示失败。冷物理Service和无当前lease的系统redelivery不再让客体崩溃或未经授权重放。新增真实默认/关闭持久化、MCS服务器登录、OS心跳与ACK、断网和进程死亡恢复验证；候选尚未构建，不能计入通过。必须重编13补丁microG，旧12补丁工件不适用，详[Google后台链路](google-service-bootstrap.md)。仍不交Y700验收。

2026-10-26 `d4b9a34`整批已核原始证据：核心40289144686三轮121项141.213/105.811/105.738秒，干净AOSP40289153373三轮121项83.186/87.444/88.325秒均通过；两批各三次6项真实输入/剪贴板组及其余独立组通过，44份crash全空，两次真实持久Job跨boot通过。AOSP真实首装27.158秒首次通过：DocumentsUI确实进入Download、坏来源拒绝、选定样本只导入原签名Play40.0.13，重试代次保留，首页真实点击显示Sign in。Google6项6.942秒、匿名注册6.96秒；真实Google IME/系统长按粘贴/完整合成值回读/同PID和两次手势返回Play22.526秒通过，已核六阶段截图。实际Play包与样本内入口SHA相同，未见磁盘/PID错误且GCM包身份校验已通过。没有真实账号认证、下载、令牌或投递，不能称Google业务全过。

下一候选补MCS关联阻断：Service型OS闹钟此前被broadcast-only桥接拒绝。新增真实Service PendingIntent及非导出物理Service入口，保留MAC/boot/发布签名及安装代次、真实OS权限/取消、显式manifest目标和8秒预算；新鲜VM必须在主线程通过私有ticket后才可实际startService。新增真实Service闹钟update/cancel/removeAll/one-shot、类型/可变性拒绝，以及只依赖原OS闹钟跨server/guest死亡的回归，冷恢复必须是新PID与同一系统能力。候选尚未构建；GCM默认仍未开启，sticky/redelivery、FGS型OS闹钟及完整MCS恢复仍待完成，详[Service闹钟](os-service-alarms.md)。

2026-10-26 `01ce1df`整批已收齐：核心40286443679三轮121项114.903/87.457/88.177秒，干净AOSP40286444198三轮121项101.999/107.205/110.377秒均通过；全部独立组通过，44份crash全空，两次真实持久Job同nonce跨boot1→2、原OS job消失且host UI未启动。AOSP Google6项9.549秒、匿名注册9.815秒通过。首装27.824秒仍未进入Download目录，Play0.344秒缺来源为连带失败，未导入商店、未认证/下载。

按两轮失败升级规则重新检查原版安装入口/URI/manifest配置，并核实际文件选择器截图与固定AOSP14：测试的简化MotionEvent构造器将toolType留为UNKNOWN，setSource只改source，而DocumentsUI文件列表按FINGER/STYLUS注册手势处理；普通View按钮能响应不能证明目录手势正确。候选统一测试tap、通知滑除、Google返回边缘及文件夹滚动的显式FINGER事件，并验证source/type/stream/action/坐标；文件夹切换后须真实出现目标文件。生产安装/权限语义未改，不注入选择结果或合成回执，原因仍待实际运行确认。完整REA及派生DEX边界见[首装分析](google-clean-setup.md)。新候选尚未构建，仍不交Y700验收。

2026-10-26 `8a26005`整批已收齐：核心40283518723首轮121项169.275秒1失败（实际通知swipe后OS仍有记录），后两轮121.842/125.407秒通过；干净AOSP40283541941三轮121项81.279/82.677/92.035秒全过，之前全部大小写导致的通知/窗口观察失败消失。两批44份含额外persisted-crash文件全空；两次实际重启Job均同nonce/7010跨boot执行、原OSjob消失、host UI未启动。AOSP Google6项6.797秒、实际匿名注册6.35秒通过。首装21.055秒已完成microG安装并打开DocumentsUI，但截图仍为根目录抽屉且Recent选中，测试未选到坏源；随后Play0.243秒缺来源是连带失败，不能声称已导入商店或完成认证/下载。

当前候选调整DocumentsUI真实稳定等待，沿截图中设备根（实际Build.MODEL）→Download文件夹→精确文件名选择ADB准备的安装包；保留中间PNG/界面树，不注入选择结果或授予额外存储权限。通知swipe前截图此前会被本轮后面的用例覆盖，现按测试名保存；所有通知失败在清理前保留PNG/树/input/window和真实通知enabled/flags/isClearable/channel状态。仍保留单次真实手势/OS消失/receiver回执断言，不修改生产取消路径、不重试抹去失败。新候选待构建。

2026-10-26 `ce4d3e0`完整批次已取回：核心40281061488三轮121项173.305/121.025/121.172秒全过，真实持久Job跨boot恢复通过；干净AOSP40281077154三轮121项169.048/170.885/174.234秒均1失败5错误。两批43份含额外persisted-crash文件全空。AOSP Google6项13.325秒、实际匿名注册7.687秒通过；首装14.499秒未点到准备按钮，Play0.311秒因缺少来源失败，不能称登录/下载或首装已跑通。AOSP持久Job在首页找fixture按钮阶段失败，未重启，不能沿用核心镜像结果。

截图/树证明共同观察根因：AOSP Material主题将动作/fixture/首页按钮显示为MYLITE ACTION、OPEN MYLITE POPUP、准备 GOOGLE 服务与商店；此前测试大小写精确比较误报缺失。通知树action的caseExact=false，而原nonce标题caseExact=true。候选只对真实可见控件标签作忽略大小写匹配，仍须真实触摸/客体回执，业务字段/包名/凭证与文本回读仍精确；首页Job选择保持完整包名唯一匹配，补实际有界滚动及失败XML/PNG，不直接启动客体替代点击。Foreground六轮均真实98→2清除FGS标记后发出一次cancel并消失。当前候选未构建；原GooglePlay镜像短暂缺失历史不被这次AOSP大小写证据抹去。

2026-10-26 `75db13c`整批收齐：核心40273819562三轮121项144.715/108.083/104.433秒通过；完整40273898993首轮121项207.934秒4失败，后两轮111.699/112.182秒通过，全部失败保留。两批共43份含persisted-job-crash文件全空；两次真实持久Job均boot1→2同nonce执行、移除原OS job且未启动host UI。Google6项33.548秒（包含实际签名helper独立ClassLoader/未注册角色拒绝）、匿名注册11.795秒、实际Google输入/系统Paste/同PID/两次真实手势回Play52.092秒通过。真实Play53的what1请求已越过microG包/UID校验；PID unavailable及磁盘错误均未出现，但后续明确GCM disabled，不算注册令牌/推送成功。未认证/下载安装，不交Y700验收。

本轮通知关联失败为首轮Foreground取消后B残留以及随后三个SystemUI动作缺失；不能直接断言共因。已核原版proxy token/通知ID、tag、channel和cancel路径，并对照固定AOSP14：DETACH服务退出与NMS清除FGS标记经不同队列，应用cancel会拒绝仍带FGS标记的通知。候选测试等待真实DETACH标记清除再发出唯一cancel、保留每阶段Bflags；通知触摸前等真实UI空闲，缺节点仍失败并保留截图/树/input/window。生产取消路径未改。首装候选明确microG/Play分别状态、系统DocumentsUI选择原签名完整Play集或固定样本内Play入口，复用PackageStore完整签名/分包/发布校验，不加载原VLite/HMS。新归档大小/目录/CRC及负向回归和干净AOSP实际首装工作流尚待构建；不能计入已通过结果。

2026-10-26 `beb6075`整批已确认：核心40269324714三轮119项114.969/85.478/86.737秒，完整40269348893三轮119项159.145/115.121/113.79秒均通过；独立组、41份空crash与两次实际持久Job重启通过。真实Google引导5项54.865秒/匿名注册24.002秒/实际交互51.508秒通过，已核截图及完整合成值回读、同PID与两次实际手势。系统复制预览真实移除6561ms后才触摸，菜单路由无overlay，收到真实Paste点击。真实存储六轮查询有实际非零容量/配额/可分配空间，Play原磁盘收集错误不再出现。总装仍失败：真实GCM one-way PID0身份丢失，未认证/下载安装。

下一候选按原版REA和实际栈接入每绑定私有能力/原样本地Binder作用域及source-built microG补丁0012；保留可信服务/当前VM和包代次/权限/Service incarnation检查，不启用全局虚拟UID或信任WorkSource。新增真实双端点oneway并发/嵌套清理、实际签名helper的独立ClassLoader及未注册角色拒绝验证，并要求真实Play请求到达包/UID校验之后。新源码尚未构建/云端验收；必须新编microG，旧11补丁工件不适用。详bound-message-identity.md与私有batch-beb6075完整复盘；仍未到Y700验收阶段。

2026-10-26 `2ecf5c5`核心40266205353和完整40266218650已整批取回：两者118项首轮各1失败（160.671/179.593秒），后两轮均全过（113.746/112.493及112.129/117.819秒）；41份crash全空，两次真实持久Job重启通过。完整Google5项47.39秒/匿名注册22.775秒通过，实际Google粘贴54.343秒失败。新输入路由证明系统ClipboardOverlay位于Paste触点上方，守卫前/raw计数也均无内容读取；下一候选等待真实系统预览移除再操作，不放宽权限。通知新增实际UI空闲和滑除前路由/图片，仍保留真实OS消失/receiver结果要求。已知Play存储失败同期仍在，新生产候选按原版REA/固定AOSP补卷列表、缓存与allocation身份归属及真实容量回归。Google流程新增实际存储/Caller PID unavailable业务阻断检查；oneway身份仍待重建，因此不能只凭输入通过宣布业务稳定。此批候选未构建。

独立原版参考`c39ac73`/40267638386已按官方下载并核出精确同hash原样本与有效签名，真实显示GBox用户协议页；官方Android14 x86_64/ARM转译环境中原版容器GMS两次SIGSEGV，实际exit-info指向vlapp01，栈libart JNI GetMethodID/libndk_translation。因此参考失败保留，不能以此称原版Google或ARM性能已跑通；未强制ABI/安装翻译器/占用本机模拟器/Y700。C盘约33.24GiB。

2026-10-26 `bfae89a`整批已取回：核心40263144380三轮各118项157.55/116.045/113.632秒全部通过；完整40263173048首轮118项184.21秒1失败（旧通知实际滑除未消失），后两轮118项116.025/120.858秒全通过。新安装旧主/worker真实退休与当前通知正控五轮通过；Toast真实shown/hidden六轮通过。41份crash全空，两次真实持久Job跨重启通过。Google引导5项36.516秒/匿名注册1项15.842秒通过，实际Google Paste63.55秒仍空，未认证或下载安装。完整复盘见私有batch-bfae89a-complete-review-20261026.md。新候选仅补剪贴板守卫前/raw计数、菜单时刻input/window路由及节点前输入同步，不放宽权限/焦点，不把观察候选称业务修复；尚待云端重验，不交Y700测试。

2026-10-26 `34e8abb`的核心40258847228/完整40258858904已整轮收齐，均失败：核心三轮117项分别1失败/1错误、1失败/2错误、1失败/1错误；完整分别2失败/1错误、1失败/1错误、1失败/1错误。41份crash全空、两次真实重启持久Job与独立各组仍通过。完整Google引导5项16.463秒/匿名注册1项5.855秒通过，但实际Play粘贴33.334秒失败，不能把上一轮的一次成功当稳定；认证与下载未验证。

新通知证据显示真实当前OS sender有效、SystemUI动作已触摸，仍无receiver结果。原始加载日志确认旧主进程和`:worker`均运行旧安装，测试只退休了旧主进程；生产ProcessRegistry随后拒绝旧worker代次。新REA原版安装方法的完整smali证实其有运行包退出分支。下一候选在首次使用新安装时，通过每个旧VM独立的私有能力发出oneway自退出请求，保留原绝对预算并等待实际死亡及完整退休，随后才能使用新代次；不按PID杀未知进程、不复用活旧VM。立即随安装提交退出全部旧VM的屏障仍未完成，源码显式记录此限制。通知回归现在要求旧主/worker都实际退出、新worker处理真实点击，测试不再代生产杀旧主进程。

同批还修正真实无序OS广播不应调用setResult的边界。AOSP14明确不枚举不可触摸Toast窗口，Toast观察改成实际系统通知事件、真实View附着/移除与系统shown/hidden回调和两张截图；原getWindows断言不成立。测试清理复用失效AppTask枚举处理；Paste增加独立点击事件诊断但仍必须完整文本回读。以上新候选尚待云端完整重验，未交付Y700。C盘仅清理109份旧Mybox Gradle转换目录，实际释放14.054GiB，依赖/源码/镜像/密钥保留；空间检查、E盘进程级临时路径和禁止强制删除已写入AGENTS.md。

2026-10-26 `1ca5bbd`的[完整40255428417](https://github.com/xmy953538104/Mybox/actions/runs/40255428417)真实Play交互首次通过（21.694秒）：实际Store→Google网页→IME→长按系统Paste→合成值完整回读且同Login PID→首次右边手势收IME→第二次取消Login回Store。四阶段截图已取回核对；日志证实输入框从IME前y328–383移到y164–217，旧触点确实过期。Google引导5项17.421秒、匿名注册1项5.960秒通过；没有输入真实账号，authentication/download仍false，不称登录或下载安装通过。

本批[核心40255406519](https://github.com/xmy953538104/Mybox/actions/runs/40255406519)三轮117项152.939/127.769/128.069秒各2失败；完整三轮155.28/104.216/102.408秒各1失败。41份crash全空，真实持久Job仍boot恢复；ActivityTask13项、原生Binder身份实验2项均六轮通过，后者未自动启用生产。失败为通知当前代次UI正控按钮缺失（完整首轮较早滑除未消失），及只在核心镜像出现的toast-hidden三轮缺失。已按完整结果关联后查AOSP Toast TN的view/callback弱引用；fixture未保留Toast对象，新的观察候选会保留到真实hide、要求真实节点出现/消失；通知补精确新OS sender正控、旧负例之后清理其残留及当前/失败SystemUI结构和截图。不改生产回调、不合成send、不把控件缺失算成功；新候选尚待重验。全核心业务、性能/长期稳定及ARM64/OEM仍未完成，不访问Y700。

2026-10-26已收齐`21093eb`的[核心40253266000](https://github.com/xmy953538104/Mybox/actions/runs/40253266000)与[完整40253274859](https://github.com/xmy953538104/Mybox/actions/runs/40253274859)：两次三轮各117项均1失败/1错误（核心123.453/102.836/101.969秒，完整123.735/92.165/90.570秒）。两次共41份crash日志全空；真实持久Job均boot1→2执行同nonce并移除原OS任务，host UI未启动。六轮绑定3项、Foreground、组件状态、Job13项、账户终止4项均通过；通知4项中3项通过，独立clipboard/Web三轮各3项也均通过。此前后台保活FATAL与同进程绑定失败本轮未复现，但整轮仍失败，通用跨VM恢复/OOM/性能未验收。

剩余两项均已核完整结果后定位观察问题：新增任务回归在guest.onDestroy早于真实OS删除完成时过早断言；通知第二个nonce查询了第一阶段的OsPendingStore内存快照。新候选保持生产语义，等真实OS句柄不可查后断言，并在新通知实际提交后重新读磁盘快照。完整Google5项36.123秒、匿名注册1项10.889秒通过，真实Play交互64.066秒在长按菜单处失败；截图显示输入框被IME部分覆盖，探针却用IME出现前旧中心点。新候选取当前可触摸区域、避开实际IME，所有Google失败阶段均留图和既有认证诊断；不setText、不读取剪贴板内容、不宣称已证明粘贴根因。集中候选尚未重验，完整认证/下载/安装/打开仍未完成，不使用Y700或本机模拟器。

2026-10-26按用户新要求，已先收齐d2ff核心与总装完整批次，关联全部失败、状态污染及累计crash日志，再统一核对原版REA和AOSP/实际WebView113机制。批次记录位于analysis/mylite-reconstruction/core-parity/batch-d2ff-complete-review-20261026.md，AGENTS已写入同一执行顺序。新候选集中修复绑定身份/死亡/后台保活，保留通知真实UI、canonical Intent及真实clipboard listener候选；AppTask只在枚举时跳过OS已删除的句柄，直接操作错误不变；Google粘贴先增加无内容的读取分层诊断，未放宽焦点或改用setText。此候选未构建/验收。本机可用内存约3.5GiB，因此不启动Gradle/REA JVM或模拟器，后续重验证放云端，避免占用用户机器；Y700仍未访问。

2026-10-26 `d2ff55f` 的[核心40229742417](https://github.com/xmy953538104/Mybox/actions/runs/40229742417)与[完整40229750201](https://github.com/xmy953538104/Mybox/actions/runs/40229750201)均失败。核心三轮115项各4失败/1错误（137.601/113.549/113.265秒）；完整三轮115项前两轮4失败/1错误、第三轮5失败/1错误（173.909/129.065/129.786秒），另有真实AppTask失效任务错误。真实账户错误收尾4项、Job组13项及旧carrier token回归在核心三轮通过，但重启后carrier保活调用startService触犯Android后台限制，实际客体主线程崩溃，不能称Job恢复已通过。另一根因是同进程服务回调继承system Binder身份，被本地客体校验拒绝。新候选正在修复实际绑定与精确死亡清理；通知候选保留真实排队启动，并要求旧通知缺失按钮时有真实OS sender取消证据及当前代次正控。首Intent生命周期隔离和真实clipboard listener候选也未统一构建，不混入d2ff验收。

完整40229750201的Google引导5项21.315秒、匿名注册1项9.393秒通过；同一85361540七包集合实际打开Store→microG引导→Google邮箱网页、真实IME及系统Paste菜单。49.722秒在粘贴文字校验处失败，且重启/Google/Play阶段均捕获非空崩溃日志，正在核对原始工件；不得称登录、手势返回或下载安装已通过。未提交账号信息、未访问Y700、未启动本机模拟器。

d2ff提交前round22完整本地构建34秒、91项JVM零失败/错误/跳过、lint及宿主/测试双APK私有资产审计通过；这些静态/JVM结果不替代上述失败的Android结果。round21在fixture把void的Intent.setClipData放进表达式处编译失败（18秒），拆成局部Intent后重跑；失败日志保留。

候选区分进程PREPARED与Application BOUND，客体/账户/Job经过真实AMS admission后才异步交付实际Service Binder/null；保留原始Job绝对8秒预算与真实结束语义。账号终止错误独立有界派发，正常/死亡清理异常不遗留绑定或名额。carrier停止捕获真实系统Binder和旧mToken，同步执行实际stopServiceToken；同owner新lease与OS mutation串行，旧owner start不清新started，已退休旧group在同slot重放返回inactive私有control。新增真实OS回归仍待云端，不能从静态闭环宣称竞态已运行验收。

`52c1aec` 的[核心40225041045](https://github.com/xmy953538104/Mybox/actions/runs/40225041045)与[完整40225045519](https://github.com/xmy953538104/Mybox/actions/runs/40225045519)均失败，原始工件已验证取回。两次三轮101项均只在账户fixture新增的跨进程证明上失败：生产结果白名单会剔除其自定义PID字段。候选改由真实authenticator写入同nonce/唯一scope的私有回执，保留生产白名单；没有放宽真实token结果。新增纯bound/已stopSelf后stopForeground清理等用例已在两次共六轮实际通过。

完整40225045519的Google引导5项24.391秒/匿名注册1项9.135秒通过；同一85361540七包集合实际打开Store→microG引导→真实Google邮箱网页，输入框获得焦点且实际键盘出现。44.145秒测试在第一次边缘返回未隐藏IME处失败，Play crash buffer为0字节。截图及DisplayPolicy日志未见软件导航栏，AOSP14证据支持导航环境缺失的假设，尚未证明修复。下一候选只在云端模拟器启动设置`qemu.hw.mainkeys=0`并留实际prop/navigation/window证据；新增真实网页长按系统Paste和合成邮箱回读，不提交账号或冒充认证。

`1a16495` 的[通知核心40225566647](https://github.com/xmy953538104/Mybox/actions/runs/40225566647)也失败：三轮104项各3失败/1错误，其中包含上述账户证明、滑除delete回执和冷恢复退出观察。原始日志确认旧客体确实SIG9退出；测试原先未linkToDeath而依赖缓存isBinderAlive。候选保留真实退出要求，改为预登记死亡回执、实际ping和OS PID消失；滑除改为足够距离的单次真实触摸，先核OS通知消失再等客体回执，不直接send/cancel代替用户操作。广播动作生产能力仍待新的云端验收。

服务绑定/Job/账户异步路径候选已完成上述本地构建，尚未通过新Android运行。自动跨进程重连、URI授权、taskRemoved、通知Service/FGS、真实登录/下载/安装及其余全核心差距仍在范围内；不因局部回归通过而删除，也未到Y700验收阶段。同owner的raw Binder同步停止已有静态闭环，另一个“客体main阻塞时停止仍完成”的独立精准回归尚未新增；共享carrier的OOM/性能及孤立迟到start清理仍有差异。

`6a264b4` 的[核心40222582640](https://github.com/xmy953538104/Mybox/actions/runs/40222582640)成功：三轮101项139.925/90.452/93.848秒，含真实system stop入队→A完成→B继续的双Job竞态与九视图/超容量/签名深复制缓存回归；19份crash buffer均0字节，真实OS持久Job跨boot1→2执行并移除原调度记录。双Linux架构检查仍与Android结果分开，不代表ARM64/OEM通过。

同提交[完整40222582953](https://github.com/xmy953538104/Mybox/actions/runs/40222582953)仍失败，但已推进实际业务：三轮101项145.801/77.891/72.573秒及各交互组通过，Google引导5项15.806秒/匿名注册1项6.055秒通过；Play85361540的七个APK hash/size与先前精确样本全部相同，实际启动、商店登录点击及microG LoginActivity已完成。73.993秒测试停在intro按钮识别，截图显示全大写SIGN IN而旧探针只接受Sign in；测试候选仅修正大小写识别，保留package、next_button ID、可见/启用等条件。另有独立真实Play崩溃：VpaService重复stopForeground被GuestComponents的物理started门禁拒绝。新REA确认原版无proxy直接返回、精确Play方法确实stopForeground后stopSelf；候选允许有效token的停止清理，开始前台仍保留原校验，并补纯bound/已stopSelf后重复停止的真实生命周期测试。登录网页、真实认证、下载和安装仍未通过。

当前正在依据[服务绑定分阶段证据](service-binding-reconstruction.md)重建真实AMS admission/异步payload路径，账户与Job内部绑定同步接入；通知广播动作和滑除冷恢复也在补实际SystemUI验收。这些新改动尚未构建或通过云端，不能与上述已通过提交混称。全程未访问Y700、未启动本机模拟器、未修改系统虚拟化配置。

缓存容量/Job迟到消息候选提交前通过round20完整构建38秒、91项JVM零失败/错误/跳过、lint及宿主/测试双APK私有资产审计；后续Android验收见本页首。Job正例必须先核真实systemUID stop已入proxy队列的匹配receipt才触发完成，不依赖cancel返回或sleep猜测；OS ACK和原8秒预算不变。原版Job admission/bind/connected状态与downloads insert/query又取得函数级REA证据，缓存调查与剩余范围见[绑定记录](service-binding-reconstruction.md)。已按用户指定非强制PowerShell清理9份经hash验证的重复传输ZIP、释放462680759字节，解压原始证据/失败轮保留；无Y700/本地模拟器/系统配置访问。

`cc78ece` 的[核心40221056282](https://github.com/xmy953538104/Mybox/actions/runs/40221056282)成功：连续三轮99项83.893/65.412/66.150秒，包含修正后的真实创建前拒绝/创建回执后OS操作与真实订阅监听、通知及移除；各独立交互组也通过。它仍不是Google业务验收。[完整40221056650](https://github.com/xmy953538104/Mybox/actions/runs/40221056650)最终失败：三轮99项、Google引导及匿名注册通过，Play初次RESUMED超时；该轮实际85361640，与后续85361540样本分开记录。此前[5ca04f2完整40220181123](https://github.com/xmy953538104/Mybox/actions/runs/40220181123)仍失败：三轮99项保留旧fixture两错，Google引导5项29.548秒/真实匿名注册1项15.182秒通过；同一Play53七包已完成Application后，初次RESUMED仍超时（48.582秒/1错误）。精确原始工件已验hash取回。

这次Play主进程5503只一次Process created；Application/cache/LoadedApk在onCreate返回及LAUNCH_REWRITTEN均同一实例。主线程真实阻塞bindServiceInstance→GuestComponentCalls→ComponentRouter；冷background6144主线程正在账户枚举，server另线程正扫描AccountDirectory的包资料。约45秒日志有391条queries元素解析警告，结合8条archive metadata缓存和多包扫描揭示缓存抖动；不能认定为整个恢复超时唯一根因。下一候选按16MiB本进程原始Parcel字节限制immutable archive cache，保留当前record读取、flags隔离、深拷贝、组件覆盖和真实签名；超容量视图返回但不缓存、不挤掉热条目。原真实PM计数回归扩展九视图重复扫描零重解析、超容量/热项与SigningInfo隔离，运行待统一检查。Job另有正常完成/排队stop交错误杀共享proxy候选及真实OS收件证据回归，未与首次缺回执混为同因。

`db64117` 的[核心40219620916](https://github.com/xmy953538104/Mybox/actions/runs/40219620916)失败：三轮各99项均1失败/1错误（99.259/79.114/80.006秒），未重试抹去。原始工件已校验；物理混合移除消息→双缓存保护→第二Activity同Application、AppTask五操作/跨包拒绝/死亡边界等三轮实际通过，crash buffer均空。失败一是fixture在super.onCreate已同步发CREATED后才要求pending拒绝；候选把opt-in检查移到super前，并新增CREATED后真实exclude操作及OS flag正例，不扩大生产busy门禁。失败二是测试的真实telephony notify反射被max-target-o隐藏API拒绝（方法确实存在）；候选仅用已打包工具精确取得两个测试通知方法，保留真实OS权限/回调/移除，不扩大生产豁免。两项修正已通过round19完整构建24秒、91项JVM零失败/错误/跳过、lint及宿主/测试双APK私有审计；真实Android重验仍待CI。

同提交[Play专项40219751743](https://github.com/xmy953538104/Mybox/actions/runs/40219751743)仍失败（61.801秒/1错误），实际旧35而非新版53。microG11观测到至少768次完成快照，其中766项prefix授权后null、2项其他授权后null，拒绝/rows/thrown均0，聚合查询耗时21.144秒；并发统计不是启动wall time或最终精确总数。identity847进入/846退出、聚合6.796秒、唯一inflight仅3ms，不支持永久死锁。主线程仍等待Application Future；后续identity拒绝/fatal发生在主启动超时后，不倒推为初始原因。保留原null/授权语义，不伪造配置行。[5ca04f2完整总装40220181123](https://github.com/xmy953538104/Mybox/actions/runs/40220181123)已派发，含精确microG11与首次Job调度失败快照。

2026-10-26追加：Job首次回执缺失现在会在下一用例覆盖同一job之前捕获当时真实OS pending记录/原因、原nonce匹配布尔值和有界scheduler dump，仍保留原20秒预算及原失败。round18完整构建32秒、91项JVM零失败/错误/跳过、lint及宿主/测试双APK私有资产审计通过；本地已精确校验并绑定11补丁microG（40218924200，APK SHA256 9b5e61ae78fb5068507a66ed23f6c0a80f321bfc98ac2926378f2d7dbff336c6）。新增Android能力与完整Google业务尚待云端，不访问Y700或启动本地模拟器。

2026-10-25 最新工作范围见 [OBJECTIVES](OBJECTIVES.md)：核心包含除 GBox 会员/自有账号、HMS、使用限制外的全部功能。恢复工作后只用云端/模拟器，待完整业务与稳定性验证后再协调 Y700，以下历史真机授权不代表本轮仍可访问设备。

`6f87a7b` 的[核心40212553355](https://github.com/xmy953538104/Mybox/actions/runs/40212553355)成功，三轮83项89.126/59.763/59.315秒，真实Config冷/热/OS变更、provider registry重取、外包拒绝、Popup触摸均通过；独立账户UI/权限广播/剪贴板Web/容量/Alarm与原Job重启门禁通过。Settings修复本地最终round9为14秒、80项JVM及双APK审计成功。仍未把该结果视作Play业务通过。新REA另完整恢复原microG此前缺失的664指令Phenotype方法（Smali），确认Photos/Meet/Classroom专用分支及四项静态配置表，和当前开源版本的配置边界见[配置差异](microg-reference-configuration.md)。

`001e859` 的[核心40214089298](https://github.com/xmy953538104/Mybox/actions/runs/40214089298)和[完整总装40214099474](https://github.com/xmy953538104/Mybox/actions/runs/40214099474)均失败，原始报告保留。实际SIG9后的立即Provider/Locale重开取到尚未完成退出记录落盘和清退的死亡reservation；另有测试在OS补全SIGCHLD前读到了真实UNKNOWN记录。候选在共享acquire原绝对预算内等待同一已死亡Binder的有序清退，不重试初始化失败、活Binder或普通错误；容量用例移除自身重试，仍保留连续五次真实Provider死亡恢复。退出记录测试在原10秒内等待实际SIGNALED/SIGKILL，不改写OS结果。独立的Locale配置回调失败尚未证实同因，不能一并宣布解决。

上述完整总装的Google引导5项和匿名注册1项通过；Play已越过DUMP权限阻塞，外层Activity启动请求被系统接受，但初次真实RESUMED回执仍超时，尚未点击登录。七个Play APK的hash/size与此前85361540样本完全相同。日志没有Java/native crash，发现同PID两次Application初始化线索，仍在分析，不能当作商店可用。

候选另支持私有本地完整Play base/split资产集，仍由生产PackageStore验证正式签名/轮换链并唯一提交；公有CI默认禁止打包Play资产，宿主和androidTest APK均完整审计。round13因新增JVM假资产fixture残留了旧单APK入口而失败，已修正fixture；round14完整构建41秒、91项JVM零失败/错误/跳过、lint及双APK私有打包审计通过，Python staging/audit共16项通过。这些结果不替代新的Android或Google业务验收；没有访问Y700或重启本地模拟器。

Play启动调查再补原版bind路径及精确Play Application.onCreate/j的REA：重复日志位于同步实例初始化的布尔门禁内，支持不同实例或字段重置的线索，尚缺运行时对象证据。诊断候选只读记录绑定/启动时LoadedApk与Application/cache是否相同；首次RESUMED超时通过既有宿主认证、当前安装代次和私有诊断票据捕获已有guest/server线程栈，优先主线程，不新建客体、不返回client/token、不改等待预算。新增真实诊断正例及scope/安装/代次/protocol拒绝。round15完整构建35秒、91项JVM、lint与双APK私有审计通过；尚未据此宣称启动根因已修复。

`476879f` 的[核心40216117677](https://github.com/xmy953538104/Mybox/actions/runs/40216117677)成功：三轮87项99.361/68.027/73.523秒，立即死亡恢复/真实退出原因/容量及独立交互组通过。已核原始19份crash buffer均为空，实际Job原ID跨boot1→2执行、原OS Job移除且未启动宿主UI。较早失败轮仍保留。`7229fc3` 的[Play专项40217032855](https://github.com/xmy953538104/Mybox/actions/runs/40217032855)则在Engine.launch的Application主线程预算处失败（59.142秒，1错误），尚未到新增RESUMED诊断；该模式跳过Google引导/匿名注册，不能与先前完整总装的暖态混成同一失败。已派[同提交完整总装40217717238](https://github.com/xmy953538104/Mybox/actions/runs/40217717238)，继续取证，不把核心回归成功当商店可用。

`7229fc3` 的[核心40217032501](https://github.com/xmy953538104/Mybox/actions/runs/40217032501)成功，三轮88项84.699/65.461/63.412秒；[完整总装40217717238](https://github.com/xmy953538104/Mybox/actions/runs/40217717238)失败：前两轮88项通过（115.1/76.917秒），第三轮1失败（99.016秒），是Job首次执行回执缺失、还没执行测试的SIG9；Google引导5项/匿名注册1项通过，Play仍Application预算超时。较早专项40217032855实际是旧Play35、hash与已分析旧样本相同，不能与此前Play53混称同版本对照。新完整工件正在取回，未预设唯一根因。

下一候选依REA补[物理同包移除缓存保护](physical-package-cache.md)、[真实AppTasks](app-tasks.md)及[订阅变化监听](telephony-subscription-listeners.md)。AppTask getter可在onCreate读取实际信息，mutator在未完成生命周期时明确冲突；移除任务后的旧token不能继续创建Activity。新增混合系统消息→两缓存→第二Activity真实resume/destroy且同一Application回归，监听测试也有UUID与实际清理回执。round16完整构建37秒、round17为36秒，均91项JVM零失败/错误/跳过、lint及双APK私有审计通过；独立审查修正了getter busy误拒和测试清理问题，新Android结果尚待CI。复杂task-mode/队列OS admission、敏感radio及其余核心差距没有据此删除。

microG第11补丁只补有界查询类别/结果/耗时观测，保留授权/签名、null、缓存、游标及原异常语义，不记录查询名值或账号。完整11补丁临时Git index顺序应用、77个源码日志审计、实际生产方法/helper JVM行为自检和provenance检查通过；[新服务40218924200](https://github.com/xmy953538104/Mybox/actions/runs/40218924200)成功，APK仍待取回并精确绑定后运行。旧10补丁服务不能冒充11补丁结果。

完整40217717238原始工件已验证取回，确认仍是完全相同的Play53七包集合。该轮主线程等待rpc.j→bdhq.i/e→cjlu.g，Application.onCreate恰在绑定超时后约16ms返回；没有证据可将它混为旧Play35的GServices链或此前第二Application问题。第三轮Job失败发生在首次执行回执、尚未杀executor；缺少该时刻OS调度快照，正在补齐证据。下一候选没有靠延长超时、跳过这轮或伪造完成来记成功。

新候选补[真实进程退出历史](process-exit-history.md)：专用服务查询实际OS记录、受信reservation/安装epoch持久化、归属过滤后限额与scoped trace capability，保留真实异常；不申请DUMP、不把宿主全部记录发给客体。PID复用/旧记录timestamp更新和可检测clock/boot不连续不猜归属；已知记录冷恢复保留。新增实际杀客体、summary/reason/status、跨包拒绝、server冷替换与真实AtomicFile写失败清理回归。round10编译因AtomicFile隐藏exists方法失败，已改为public readFully及真实备份恢复；round11完整构建37秒、84项JVM零失败/错误/跳过、lint及双APK审计通过。新的Android和真实Play结果尚待运行，不能当作登录或下载安装完成。

退出历史最终候选再收紧为只淘汰真实retired reservation，并避免把两次OS采样的合法字段变化当失败；新增live但clock不连续记录不可淘汰负例。round12完整构建34秒、85项JVM零失败/错误/跳过、lint及双APK审计通过。独立审查未发现其他具体阻断问题；实际ANR/native trace字节仍不在已通过范围内。

2026-10-26：`16589b4` 的[核心40210729960](https://github.com/xmy953538104/Mybox/actions/runs/40210729960)成功，三轮81项113.118/76.953/76.940秒；含真实Android ART目录监听/xattr和宿主identity路由隔离。同期[完整总装40210730952](https://github.com/xmy953538104/Mybox/actions/runs/40210730952)失败：三轮81项、各交互组、Google引导5项和真实匿名注册1项通过；这次新版Play85361540及七个实际split成功通过生产签名校验并提交，但Application因历史进程退出查询要求物理DUMP权限而被拒绝，尚未到登录/下载，不能称业务通过。已成功捕获加密精确APK集合供继续分析。

较早[核心40209984627](https://github.com/xmy953538104/Mybox/actions/runs/40209984627)的首次Popup触摸确有主线程崩溃：Autofill读取DeviceConfig时绕过既有适配，将guest包和host UID直接交给真实SettingsProvider。后轮成功不能抵消它。按REA和真实framework补齐[Settings四缓存与进程provider别名](settings-provider-cache.md)，保留真实Binder、引用计数、配置变化与拒绝；新增真实Config冷/热/变化及负例回归。本地round8完整构建33秒、80项JVM、lint与双APK审计通过，新的Android回归尚待运行。旧Play35精确失败APK也已取回并验真实签名，继续分析其GServices/ApplicationFuture链；未重新启动本地模拟器或访问Y700。

后续独立审查发现同current更新可以先去掉PoR、再用逆向链绕过方向检查；候选现拒绝丢失/截短既有非平凡签名历史，并用真实APK验证拒绝裸B更新、完整链更新成功、冷恢复保留代次与CE/DE数据以及后续逆向拒绝。新增固定宿主identity Provider路由，跳过全部虚拟manifest扫描但仍委托真实PM；负例放入损坏客体record，确保普通查询继续失败且宿主查询不受干扰。完整本地构建38秒、80项JVM、lint与双APK审计通过，Android新回归尚待CI。十补丁[microG源码40209985505](https://github.com/xmy953538104/Mybox/actions/runs/40209985505)已成功；尚未把该结果写成Play启动或业务通过。

2026-10-26 候选补齐[真实签名轮换](google-signing-rotation.md)与Android ART目录监听/xattr探针；本地完整构建41秒、79项JVM零失败/错误/跳过、lint及双APK审计通过。Google角色仍只以旧证书为根并验证真实PoR，不信PM/record的history字符串。microG第十补丁只在一次GServices query中复用已解析身份，保留同PID快路/standalone/异常与权限边界，无跨请求缓存；真实Git连续应用十补丁、生产方法JVM行为自检及身份负向自检已通过，新的microG APK及Android总装仍需全新CI，不能复用九补丁结果。跨SDK有效签名迁移、真实Play新签名样本、Android缓存性能均未验收。

`c7de65b` 的[核心40207730922](https://github.com/xmy953538104/Mybox/actions/runs/40207730922)成功：三轮各79项Android（108.344/75.883/76.978秒），真实Activity PendingIntent/通知点击/Popup/Dialog/Toast与恢复主线程nonce通过；账户UI、权限广播、剪贴板/Web、容量、Alarm各三轮及OS原Job重启恢复也通过。九张窗口分阶段截图及原始报告已取回核对。`68de537` 新增目录监听/xattr的ARM64 Linux三轮已通过，实际regular xattr支持、symlink xattr被底层拒绝；Android ART新增探针尚待下一构建。独立Play40208752048仍在Application初始化超时，但这次已成功产出加密APK取证，正在下载；不把核心成功写成Google业务通过。

`c7de65b` 的[独立Play诊断40207747502](https://github.com/xmy953538104/Mybox/actions/runs/40207747502)失败：这次物理商店仍为旧签名35.6.11，已导入但Application初始化超时。新增计数实际观察到1034次身份RPC、1033次已返回，已返回总5027ms、最长55ms、全部低于200ms；快照仅一次22ms在途，不能判成长期RPC死锁。加密采集的record检测因ADB exec-out自动转义与脚本预转义叠加失败，已修正三处参数并补spy检查，等待真实云端重验。原生候选新增[目录监听与扩展属性路径](native-path-operations.md)，双ABI源码检查通过，真实运行尚待CI。未重新启动本地模拟器或访问Y700。

`c45451c` 的[核心40204835042](https://github.com/xmy953538104/Mybox/actions/runs/40204835042)失败：三轮77项，首轮9失败/1错误（182.902秒），后两轮各10失败（171.951/172.1秒）。普通Activity send遗漏framework默认splashScreenStyle；恢复路径的宿主Toast又被guest-only校验误拒绝，实际触发两次客体崩溃与系统crash dialog，后续Job/Locale/Notification/Window均未完成Activity入口，不能写成这些服务各自已正常或已证实死锁。修正改为严格比较实际framework默认Bundle，Toast仅对本地真实host/绑定guest放行；恢复测试另增唯一nonce的实际onCreate主线程回执，防止仅PID/Binder尚活而误过。新修复待Android重验。

同提交[总装40204869831](https://github.com/xmy953538104/Mybox/actions/runs/40204869831)仍失败；独立Google设置/签名等5项30.943秒、真实匿名注册1项8.981秒通过。该轮物理Play版本85361640，当前证书`7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053`，系统历史包含原Google `f0fd6c...`；当前仅认可旧current signer，导入被拒，未到此前Play35 Future等待点。需要核对真实APK proof-of-rotation及全链授权/更新语义，不能仅根据包名或一串history放宽。采集因没有committed Store记录而失败，尚未取回该APK；单独Play诊断40206633012亦保留为失败，不冒充总装通过。

本轮核对实际云端结果：`0e33a30` 的[核心40187307024](https://github.com/xmy953538104/Mybox/actions/runs/40187307024)成功，但[真实Play诊断40187307439](https://github.com/xmy953538104/Mybox/actions/runs/40187307439)失败，仍停在 Play35.6.11 Application.onCreate 的 GServices 查询链。microG 121899038 字节的完整校验已减少到一次434ms；不能继续把重复整包SHA当作当前已证实根因。Play/server/microG线程快照相差数十毫秒，不能证明它们是同一个长期阻塞RPC或死锁。下一轮增加无参数/账号内容的身份RPC次数、耗时和有界在途快照。

本地候选补齐真实 Activity PendingIntent、标准通知正文/Activity按钮、窗口归属及Toast透传，并新增实际SystemUI触摸和Popup/Dialog/Toast检查；尚未取得本轮Android运行结果。Activity取消/更新与OS已接受投递的竞态按签名快照和独立one-shot认领处理，不保证跨系统exactly-once。广播/Service/RemoteInput/RemoteViews通知、分享/URI授权、危险权限、设备环境与其余差距仍在范围内，不能因这些候选代码存在而称完整复刻。

本轮另确认615条游标泄漏警告，对开源microG增加第九个补丁：空查询仍返回null，但关闭未交付调用者的MatrixCursor。原方法在JVM行为检查失败，补丁后通过；非空游标交付和原异常语义保留。此结果不是Android/SQLite/Play验收，也未证明该泄漏是Play超时根因；总装必须重新构建microG，不能复用仅含八个补丁的旧产物。

本地候选两轮构建成功、67项JVM零失败、lint及双APK审计通过。WHPX实际Window四项中三项通过（含Popup/Dialog真实触摸），Toast失败并崩溃于独立 `Toast.sService` 未接代理；已补该缓存但未重跑。Activity两项失败：普通send自动携带callerDisplayId而被旧options门禁拒绝，另有测试错误地期望fill-in覆盖base同名extra（AOSP实际保留base）。实现/断言已据系统源码修正，后续等待云端。通知测试被本机保护脚本中止，没有完整结果，不计通过。监控103秒/114秒两次宿主空闲内存低于4GiB（3583/4063MiB）后自动停止模拟器；本轮无新WHEA、无模拟器Windows应用错误，宿主仍为20:05启动，未访问Y700。当前内存余量下不重启本机模拟器，继续云端验证。

以上修正后的第三轮本地构建、68项JVM、lint和双APK审计成功；不能据此覆盖此前Android失败记录。取消后的客体capability保持Binder引用，避免因从active map移除而丢失原creator/type及取消语义；真正交给OS的通知仍使用物理Activity sender。microG修复单独提交为`ebe9f8c`并启动[新服务构建40204530698](https://github.com/xmy953538104/Mybox/actions/runs/40204530698)，完整总装和新运行结果尚待后续确认。

`fde5796` 的 [Play 诊断40181791456](https://github.com/xmy953538104/Mybox/actions/runs/40181791456)保留为失败。服务端线程栈证实 `IdentityProvider.call → TrustedMicrog.require → verify → SHA-256` 位于 Play/microG 的等待链内。当前补丁对只读 APK 的完整校验结果做进程内缓存，仍逐次核对真实签名、安装路径、文件身份和纳秒修改/变化时间，变化后完整重验；见[校验缓存与边界](sealed-service-trust.md)。此前 Y700 两项缓存负例/并发测试通过（6.493秒）；其后四项真实 microG 检查为两通过、两失败，失败涉及英文设置标题断言和实际唤醒锁归属，尚未解决。不得把这轮写成四项通过或 Play 问题已解决，实际云端 Play 仍待新运行。

`6b22fce` 的[全量40180751442](https://github.com/xmy953538104/Mybox/actions/runs/40180751442)成功：三轮67项103.663/62.935/61.789秒，三轮剪贴板+Web交互11.405/8.996/8.454秒，Alarm四项三轮48.451/48.464/48.220秒，账户UI、权限广播、容量及真实OS重启恢复通过，崩溃日志为空。新诊断票据补丁另有Y700 10项组合检查，不能把该全量结果直接归于更晚诊断提交。

`29b7f76` 的[Play诊断40181232795](https://github.com/xmy953538104/Mybox/actions/runs/40181232795)仍失败；票据诊断实际取得9条microG线程栈，提供方Binder线程停在 GServicesProvider.query→MyboxIdentity.isCallingSelf→resolve→ContentResolver.call，正等待自有身份解析服务。下一步捕获同一失败时刻的engine_server栈，核对身份解析、锁或重复验签，不降低信任要求。

最新Y700组合检查（10项，9.913秒）通过：设备管理只读边界、诊断票据负例、IME同步/异步参数保留、autofill身份、语言持久化与真实资源/进程冷恢复、Activity/Service/Receiver状态、剪贴板及实际WebView键盘/长按粘贴。真实Google网页另手动验证合成文字复制→清空→快捷键粘贴，以及再次清空→长按“粘贴”菜单→实际点击恢复文字，页面存活；未提交合成邮箱或任何账号信息。此结果不等于账号认证或Play下载。

后续现场：`f55456d` 的[全量40179779311](https://github.com/xmy953538104/Mybox/actions/runs/40179779311)失败，每轮66项中65项通过/1错误（72.061/45.764/49.248秒），是新增 LocaleRuntimeTest 在已有客体运行时重新安装同一fixture，触发正确的代次保护。已改为复用现有fixture，不放宽代次校验；真实资源/冷恢复断言保留。三轮剪贴板/Web输入、Alarm等独立组通过，整轮仍不计通过。新修正待CI。

[Play专项40179785062](https://github.com/xmy953538104/Mybox/actions/runs/40179785062)再次取得完整关联线程栈，工作线程阻塞在 ContentProvider.query，不能归因为Service绑定。Y700 FlClash原未连接，接通原节点后TLS失败；改用现有配置中测通节点后，Mybox对accounts/android.clients/play三个Google域名真实HTTPS全部成功（529/609/407ms，无凭据、无TLS绕过）。真实Google网页及键盘已出现；左侧手势未隐藏IME，手动右侧手势实际先隐藏IME、再取消并回到microG账户页。自动化手势改为使用实际屏幕尺寸和右边缘，原失败保留。

网络恢复后继续真实使用，又复现Play后台设备管理者组件查询失败，随后前台通知准备要求[Activity PendingIntent及交互通知](activity-pending-intents.md)。前者已补只读虚拟“无设备管理者”语义并通过实机边界一项0.023秒，管理操作仍委托真实系统并保留拒绝；后者尚未实现，不能宣称Play可用。未输入账号或验证码。

2026-10-25 用户重新授权 GitHub 和 Y700。`2a20ef6` 已成功推送，GitHub 阻断已解除。[独立云端诊断40178732974](https://github.com/xmy953538104/Mybox/actions/runs/40178732974)仍失败，但首次取得主线程栈：Play35.6.11 在 Application.onCreate→jqe.i→oq.i→aond.get 中 WAITING，尚缺相关工作线程栈，不能断言唯一服务等待环。后续诊断增加有限线程栈，不输出账号内容。

Y700 采用样本内正式签名 Play40.0.13 对照，保留已有用户数据，依次复现应用语言权限崩溃、动态Receiver状态未实现。已独立补[客体语言](mylite-locales.md)和[Receiver开关与投递前重校验](component-states.md)，并真实到达商店登录首页、点击进入microG登录引导。实机语言/IME/autofill边界四项0.086秒、语言资源/冷启动恢复一项2.332秒、组件状态一项3.685秒、剪贴板与实际WebView键盘/长按/粘贴两项6.221秒通过。Google网页、账号认证、下载和安装尚未验收；中文业务测试另修正登录页切换时同名按钮的观察竞态，原失败保留。

以下保留此前各轮历史记录；旧的“推送受阻”“Y700暂停”和“未取得主线程栈”状态已被以上新结果取代。

最新独立业务诊断 `40176806288` 进一步确认镜像内 Play35.6.11 的当前/历史证书均匹配正式 Google pin，导入及代次保留通过，随后真实冷启动在30秒主线程预算处失败。两个客体的 native 初始化成功；缺少故障前主线程栈，尚不能断言唯一等待环。REA 又确认原版经真实 AM 绑定与 StubService.onBind，当前 Mylite 的同步自定义绑定路径存在差异；详见[服务绑定重建记录](service-binding-reconstruction.md)。超时现场捕获与 Android15异步IME补丁留在本地，未有新的CI运行。

`701294a` 的正版 Play 镜像业务检查最终失败：三轮63项核心、三轮交互、Google引导5项和匿名注册通过，但镜像内 Play APK 未匹配当前锁定的 Google 正式签名，导入被正确拒绝。后续 `9bd700b` 的[独立业务诊断40176806288](https://github.com/xmy953538104/Mybox/actions/runs/40176806288)读取实际证书与签名历史；不降低生产信任要求，也不将该单项运行当作全量回归。更完整的输入操作并未因此验收完成。

Android15异步IME入口和更准确的商店签名错误文案已在本地补齐并通过构建；后续提交/推送被自动审批以“blocked by policy”拦截，未提供具体原因。这些改动目前留在工作树，不能用已有CI代替其新测试运行。

`d97cbc8` 的[核心40175095089](https://github.com/xmy953538104/Mybox/actions/runs/40175095089)成功：三轮63项69.990/43.790/47.918秒；真实剪贴板+WebView键盘/长按/粘贴三轮10.093/6.311/6.290秒，未记录 AutofillManager 的5000ms回调超时，崩溃日志均为空；真实boot1→2后原job7010/nonce匹配、新PID2108、原OS任务删除且宿主UI未启动。新增Autofill适配仅启用API34，绑定真实Activity/token并保留原系统回调，其他API未计为通过。

`030279e` 的[核心40174667226](https://github.com/xmy953538104/Mybox/actions/runs/40174667226)与[总装40174667736](https://github.com/xmy953538104/Mybox/actions/runs/40174667736)成功。核心三轮62项84.760/54.104/54.371秒；总装三轮62项96.674/56.832/60.757秒，真实剪贴板+WebView交互三轮15.232/12.945/12.805秒，Google引导5项37.243秒、匿名注册10.063秒及真实重启通过。已人工查看键盘、Paste菜单及合成文字进入DOM后的截图。该版本仍有随后发现的autofill超时，不能把此通过当作完整交互性能验收；前后不同runner耗时也不能直接等同于同机性能对照。

`701294a` 新增[正版Play镜像业务40175409957](https://github.com/xmy953538104/Mybox/actions/runs/40175409957)，当前运行中：从镜像自带的真实签名Play导入容器、实际点击登录、Google邮箱输入框、真实键盘、边缘返回手势关闭IME/取消登录并返回商店。它不输入账号、不假报认证或下载通过。功能差距与后续验收已按用户动作整理为[业务差距清单](user-journeys.md)，设备配置的Java/Native链见[设备环境记录](device-environment.md)。Y700仍暂停访问。

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
