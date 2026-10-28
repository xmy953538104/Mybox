# 调试与保留会话修复（候选，待 CI）

本轮不访问 Y700。先修复已证实的会话可见性差异，并提供应用内的排查手段；搜索 HTTP400、旧 OPEN 会话与商店 INSTALLING 的对应恢复尚未证明解决，不把这次修改当成完整业务修复。

## 原版依据与修复边界

原 GBox SHA256 `6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257`，classes4 DEX `ac17de00d90982e036853c804fd9ae304cd28309f4ea1f3862df887060d16d09`。

- 服务构造 `f.<init>` 130字节，REA `ev_1809f1956d32add5c1b4d8c1874b360fb490f7d685c689f6d789064dd36c70c2`：创建空 SparseArray 和 Handler/HandlerThread，本方法没有从磁盘恢复会话。不能据此推断原版全局没有别的恢复入口，也不能因此抹掉 Mylite 的暂存下载。
- `f.p0` 创建208字节，`ev_ffdbaba0d5dc49faa1d1c33e17c550a0d0cf6fe7557e1057fb95b6b9088e2c6a`：按调用者限制数量，分配目录，注册会话，再派发 created。
- `getMySessions`118字节 `ev_6a3860c969cbdb545a908a0a38796cac2184f1e888d0065a7834cc52336ad6c5`、`getAllSessions`110字节 `ev_524022c3d3738041ac153c53b48a6d5e4946563e4579930eac4cab4ff4a50b85`、`getSessionInfo`48字节 `ev_4a0dea8d500a7348e33a94027beabf855be78641ad467671a95b712b41b0afe7`：从同一当前 map 取值，前者还按 installer/user 过滤。
- 完成入队 `f$b.e`150字节 `ev_2cf64e11a23a47035b33126cd2100ae9ed63c07885b866639c4a47d4e8130d2a` → Handler → `f$b.g`104字节 `ev_2197e4eb2d5bcd05a927e89afafb5ecdb40ac5e9e84180d6d6b15ab97b8bab4e`：已销毁或失败会话移出当前 map，成功与失败回调的先后不同。关联实际安装、销毁/缓存配置、回调分派证据见 install-flow-parity 与既有 original-installer-chain 报告。

Mylite 保留内部历史日志、归属/签名检查及有效 OPEN/SEALED/COMMITTING 会话，但不再向商店的公开 SessionInfo/枚举返回 SUCCEEDED/FAILED/ABANDONED 历史。安装 fixture 需要从真实平台接口确认成功及 abandon 后不可见，不能继续断言历史会话一直 sealed 可查。这个修复只解决已知差异；不自动提交、放弃或删除现场旧 OPEN 会话，不修改 Play 数据库。

## 调试入口与实际覆盖

首页“调试日志”默认关闭；打开后复现，再点“导出诊断日志”，由 Android 系统文件保存器选择 ZIP 位置。关闭立即停止各进程新的事件写入，已有日志保留；“清除诊断日志”只清理自己的四个事件文件，不触及应用、账号或下载。无需 root 或存储权限。

`no_backup/diagnostics` 中四段 JSONL 各256KiB，合计最多1MiB，另有空开关/锁文件。多个进程检查同一磁盘开关，文件锁保护轮转/导出；事件写入遇竞争跳过并累计 dropped，磁盘失败不改变业务结果。记录 wall/monotonic 时间、PID、线程、进程 epoch/角色、已校验绑定包与代次、调用编号、会话/Job数值、阶段、异常类型与最多8个自有引擎栈帧。异常消息、Intent/Bundle 内容、账号、密码、令牌、APK、数据库、原始 logcat/URL/HTTP 正文均不导出。

覆盖引擎 RPC、启动/绑定异常、安装客户端入口与参数拒绝、服务端会话操作/暂存文件/持久化/提交/通知、描述符错误和关闭、Job 虚拟/物理编号/调度结果/实际执行/回执、原生初始化 errno、包管理与连接管理异常。导出前由仅宿主可调用的服务端接口记录全部会话状态、暂存字节、writer 数与状态码；另外记录实际待调度 OS Job 编号及宿主 VPN/validated/系统代理是否存在的位图，不采集代理地址或凭据。

网络补充是按需、限时的自有 UID 日志快照：最多读取最后1000行、3秒读取预算，只保留 Volley/Finsky 中已知 Play 路由标签和 HTTP 数值。原 Store40 `jfa.a` 的 REA `ev_0f34a1be83f6c49c76b9abbac16f1435259fa182d8e7d4dde99dd14bf33f8a98` 及原始 DEX 字符串核到 `Unexpected response code %d for %s` 和带 `[rc=%d]` 的日志格式，解析器据此提取固定标签，丢弃 URL 查询参数和正文。日志不可读、未输出或被覆盖均不代表网络正常；这不是 TLS/抓包/FDFe 请求体分析，也不能将 HTTP400 一概归因于代理。

## 验证要求

JVM 检查默认关闭、跨实例开关/重启读取、轮转上限、锁竞争、写盘失败不影响业务、消息/非法字段剔除和网络标签解析；云端真实宿主/独立 server 检查开关、持久事件与 ZIP 导出。安装 fixture 使用真实 PFD/回调及 SessionInfo/枚举；完整三轮核心与干净 AOSP Google 批次仍必须收齐。现有已登录业务数据只在未来另行安排的 Y700 窗口验证，本轮不占用设备。
