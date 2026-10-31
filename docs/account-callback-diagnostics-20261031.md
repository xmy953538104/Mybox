# 异步账号回调诊断

`7ff2a81`整批结果已拒绝：核心41181086120的第二/三轮及干净41181088527的第一/二轮，在各138项的真实业务夹具执行后，分别缺少错误、token或同trace最终结果的日志。六份实际ZIP保留，四次失败未用成功轮次替代。先复核同一原版onResult完整方法和既有绑定/缓存/UI/清理28方法证据，再关联共享文件锁和快照时序。核心还出现ADB reboot请求超时，属于缺失跨boot/后置crash的独立云端不完整项；干净真实跨boot成功，不能合并成两组成功。

后续候选对转发完成及转发失败的账号记录使用最多100ms的锁竞争等待；普通IPC和received/begin仍保持一次tryLock，超时、中断和IO故障仍报告dropped。锁等待预算并非磁盘IO延迟保证，也不是无损日志。真实认证结果/错误/权限策略不变。回归只在既有业务完成后，以仅日志快照等待真实token/error/同trace交互结束，观察窗口5秒，最后实际ZIP仍需全部满足原断言；不重跑认证或降低要求。用户导出保留原网络/环境采集，新增纯日志快照避免等待期间反复制造日志流量。候选待重新构建和完整云端核验。

Y700 的既有日志包含9次 Play getAuthToken 请求及同步返回，但账号接口的同步返回不代表认证会话最终完成。没有相应的持久化回调结果，无法据此判定是否收到 token、需要交互、认证器返回错误，或者回调转发失败；也不能把 HTTP400直接认作认证失效。

先补核原版认证入口、会话构造/绑定、缓存与签名选择、实际认证器调用、结果/错误、UI及断连/超时/死亡清理28段方法，7,744字节逐指令返回均与固定SHA原DEX匹配。关键证据为入口 `ev_565ec9127854310418ee579215b2300410d1c64711fb2bd6448c061b0e90b1c5`、普通结果 `ev_90dcef7f44749124f909afcf67ac2706cbed691153add03becfe164965222da9`、token结果 `ev_a7fb2bc42815c3e80026f589d3196b62e3fbfd4247ac706e2d2fec6c98a7c618` 和错误 `ev_80b3071aface1724f93f5199471b9ea9bb111318ce843576d6a6a7cdedc0abe1`。原版有 com.android.vending.mgc→com.android.vending 的 options 别名转换，本次实际客体已是正常 vending，不适用该分支。原v0的正常返回是固定true，不将其宽泛授权照搬到Mylite。底层数据库及j缓存的全部实现未在本批穷尽，静态分析也不证明原版运行中的凭据有效。

[AOSP14 AccountManager](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/core/java/android/accounts/AccountManager.java)同样把请求、异步Future和交互结果区分；同用户与显式用户的addAccount入口各有接口。候选保持实际接口名，回归接受这两个已核实入口，仍要求交互和最终结果使用同一真实响应trace。

`GuestAccounts.response`的11处创建点现在附带已知操作名和trace。每次回调先验证实际服务器PID与物理UID，再记录 `<operation>.result_received/error_received`；真实Binder转发返回后记录 `.result_forwarded/error_forwarded`，原异常继续抛出并记录受限类型/位置。forwarded仅代表该次转发返回，不代表Google服务器认可凭据。

结果只读取固定键的存在性，不读取或记录对应内容：

| 位 | 含义 |
| --- | --- |
| 1 | 非null Bundle |
| 2 | 含交互Intent键 |
| 4 | 含token键 |
| 8 | 含布尔结果键 |
| 16 | 含账号数组键 |
| 32 | 同时含账号名/类型键 |

null为0；无法读取存在性时为-1，保留实际回调处理。错误日志仅保留已核数值1、3–9、100、101，其他值记0；实际回调的原始错误码与消息仍完整交给原消费者，不改变业务语义。未记录名字、类型、token、scope、消息、Bundle/Intent内容、URL或数据库。补充已观察到的accounts/appwidget框架类别，仍最多4个框架帧、总计8帧，不写异常消息及源文件名。

回归复用已有真实跨进程认证夹具：唯一nonce的实际token校验、实际认证错误、真实交互及最终新增账号继续执行；另导出本轮固定结构ZIP，核对对应token/error转发与同trace交互完成，检查夹具账号/密码/token canary不出现。ZIP在失败时也保留，debug在finally恢复原值；每个核心轮次清除的仅是该合成诊断工件，绝不清用户账号。原始日志为有界、尽力记录，dropped仍会报告；每条回调自带操作名，不依赖恰好未丢失的begin行才能解释。新增诊断候选尚待编译和两组完整云端，不视作HTTP400修复。

补充整批关联：翻译后续PID17680也确认属于同一客体，另有getAppWidgetIds权限异常。原版appwidget注册/两种解码器/常量处理器六方法及已有公共dispatcher已核：该查询注册固定空数组处理器，多项其他接口也为空操作；这不是完整桌面小部件实现证据。本批未用假的成功状态消除它，也未将其与主进程BlobStore FATAL混为一谈。

Play的vdh.d(PG:11)另经完整原方法6218字节和DEX debug位置映射，确定为“找不到com.android.providers.downloads条目”的非致命告警。原版PM客户端/服务端清单、直接查询、系统/依赖配置11方法1666字节显示，枚举仍以虚拟提交包为主，直接查询另有物理回退策略。当前Mylite也不能据此改成暴露所有物理应用，或伪造Downloads已安装；仓库条目为何缺失及它是否影响HTTP400仍未知。BlobStore租约归属、实际商店搜索/下载/安装/使用继续未通过。
