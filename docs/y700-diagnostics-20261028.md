# 00f5f8d 保留数据实机诊断

2026-10-28 23:06–23:14，北京时间，TB321FU / Android14 / ARM64。用户询问当前能否测试后开启本轮设备操作；未启动本地模拟器、Java或Gradle。

主包SHA256为`5ac35178765a7dbc44c8a2dc7ca52572a3c4f340af7c9bc1f88cff28e1b31075`，覆盖安装被Android接受。升级瞬间账号摘要与大小不变、20个旧会话及已有快照中的暂存文件信息不变。结束时旧20个会话快照仍一致，账号文件5624字节；未读取凭据、清数据或改Play数据库。

| 实际操作 | 结果与限制 |
| --- | --- |
| 首页开启调试 | Switch实际勾选，跨进程事件落盘 |
| 商店首次启动 | 恢复前台时`checkSignatures`触发未实现保护，EngineException导致崩溃 |
| 随后系统自动恢复 | 到达目录页，没有重新登录；不等于认证和下载全流程通过 |
| 输入Google Translate并搜索 | 出现重试；诊断ZIP确认同进程`getItems HTTP400`，不归因于代理 |
| 下载、安装、打开Google应用 | 被上游失败阻断，本轮未验收 |
| 系统文件保存器导出 | 实际选择保存，产生59992字节ZIP；CRC、schema、构建SHA、上限检查通过 |
| 结束 | 关闭调试、结束采集，返回系统主页 |

导出含2795条按PID/epoch/mono_ns去重事件，maxDropped为5；轮转与竞争意味着不是无损全轨迹。包查询还记录非法名称IOException；搜索附近记录ContentObservers的UnsupportedOperationException及对应IPC异常，原因是当前服务对同步/未知flags明确拒绝。未取得实际flags数值和原版对应动态分支，不能把该异常猜成HTTP400根因。取消误触请求后出现isMultiPackage/getNames的IllegalStateException，不应描述成Google应用安装时的失败。

操作失误：商店自动恢复改变了页面，一次基于旧首页坐标的点击误触推荐应用安装。随后通过当前页面唯一“取消”控件取消；唯一新增会话1000449941为ABANDONED且暂存为空，旧会话均保留。保留这一观察，不删除失败记录；后续操作改为即时UI唯一控件匹配，匹配缺失或歧义时不输入。

REA第一诊断复核已留存的原版签名初始化、fake-signature元数据、checkSignatures/checkUidSignatures比较及配置链；新增原Store40 `Lzks;->K(Context,String)`完整230字节，Evidence `ev_8b378f1e46accfff8a316a552b6783865c4720ee23dd98210e6ba4de0be975d4`。原DEX逐字节核验，两处Rizin负分支目标按原始有符号偏移修正。该方法先调用PackageManager.checkSignatures，再进行PackageInfo及远程开关/额外签名回退。Mylite实际栈命中GuestPackageQueries末尾未实现Google身份操作保护，未正常返回比较结果。实际传入的另一包名、上游调用配置、现代签名轮换和UID视图仍须补全，不能用恒定签名相同来消除崩溃，也不能改变安装/账号凭据ACL。

完整私有观察、原始固定schema ZIP、REA、保留数据摘要及assert核验位于`analysis/mylite-reconstruction/y700-diagnostics-00f5-20261028`，不上传账号库或原版代码。生产实现本轮未改。下一轮应先补完整查询链、同步通知配置和HTTP400独立证据，再选择修复与完整云端回归；当前结果不能标成安装转圈已解决。
