# 原版 Native 函数证据与缺口

这次补的是原版 ARM64 原生库的函数体证据，不是新的 Mylite 运行时实现，也不是 Google 业务验收。开发候选仍为 `5fc7eb3`；真实 Play HTTP400、下载安装与翻译 BlobStore 尚未通过。

原 APK SHA256：`6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257`。`lib/arm64-v8a/libvlitecore64.so` 为915888字节，SHA256：`daf9e3844d5d6c750dcd360745d3595621719c0809c8b93fb8b665799c81ac3a`。

## 已核原始结果

分析在一次性 Linux CI 运行 REA 6.7.0、Ghidra 12.1.4、JDK21，未执行原库。完整 Evidence、快照和诊断仅以 CMS AES256-GCM 密文导出，本地解密后按文件逐份核对；原 APK、私有库和原版反编译代码没有进入仓库。本机没有启动 JVM、Gradle、模拟器或访问 Y700。

| 运行 | 实际范围 | 结论 |
| --- | --- | --- |
| [41192691452](https://github.com/xmy953538104/Mybox/actions/runs/41192691452) | JNI_OnLoad，1524字节 | 1份完整函数体，3个间接调用未解析；自动跟进没有得到其他函数，不能按12份预算计覆盖 |
| [41193003968](https://github.com/xmy953538104/Mybox/actions/runs/41193003968) | JNI表、构造函数及直接调用 | 第60份结果超过MCP 10MiB预算；整轮未完成，失败状态保留。快照实际含60份完整函数Evidence，超限结果已恢复 |
| [41193323036](https://github.com/xmy953538104/Mybox/actions/runs/41193323036) | 同一输入的入口与两层已解析内部调用 | 93个独立函数体；5次超限通过完整文件导出恢复；正常关闭，原始输入结束复核不变 |
| [41193684326](https://github.com/xmy953538104/Mybox/actions/runs/41193684326) | JNI入口及四层内部调用，包含本库PLT目标 | 90个独立函数体、36条实际内部跳板边/19个目标；5次超限恢复；正常关闭及输入复核通过 |

93份结果逐一核对实际源SHA、Ghidra版本、Evidence ID、函数体可用性、指令/调用数量及原始限制。最大单份解析文件30731163字节；未把完整大型快照一次加载到本机内存。

26个JNI注册槽对应25个静态唯一入口；其中19个槽对应18个已识别的独立函数体。50个ELF构造函数槽均有函数体证据。69个已识别入口槽逐项通过REA地址到原始文件偏移校验；Ghidra的分析地址不能直接当原ELF地址。

构造函数表包含47项符号重定位及3项相对重定位。只处理相对重定位会漏掉47项；其符号目标已经REA重定位记录交叉核对，解析按[Arm ELF ABI的ABS64定义](https://github.com/ARM-software/abi-aa/blob/main/aaelf64/aaelf64.rst)处理。原始表位置和JNI槽的来源保留于既有固定SHA证据，没有按相邻函数地址猜边界。

## 关联实现与尚未完成的分析

`JNI_OnLoad`完整证据为`ev_4b44151326e3af42bd010e3ede2621a8cb5614427790199dce1150830e4deceb`，其调用关系仍为partial。`start_fp`两个JNI包装汇入同一内部函数，已取到23112字节函数体（`ev_130c8a1ef992a29713ac77d95954a893310a4fbd60829f03b44705e2e3cbbb41`）；大量混淆分支、被反编译器移除的块和23个未解析调用仍需核实，不能把伪代码存在等同理解全部逻辑。

`start_iobox`继续调用内部初始化函数（`ev_1655ba9595f94d8288d5d58bb42430212f44057ddd63cdc54597a54c10f9b3c1`），其中经本库PLT跳板进入`init_env`、`set_freesyscall`与`startIOHook`。先前只跟非thunk函数会漏掉这些本库实现；已依据实际thunk_target和同一函数清单补跟进，拒绝跟进外部、未识别或循环目标。

补充轮的完整工件及90份函数已实际核对。与前93份有43个共同函数，43项Evidence ID及函数体大小均一致；合计140个不同函数地址。补充轮自身29个函数仍有partial调用关系、126个未解析调用点，这些数量与前一轮有重叠，不能简单相加。四层边界内已解析调用队列为空，也不表示所有间接分派已完成。

I/O链已补核以下真实内部实现，而非仅PLT入口：

| 实现 | 原始字节 | Ghidra完整Evidence |
| --- | --- | --- |
| init_env | 176 | `ev_6838ab755c1cf083fdf56163ef59d6d23ab7ccafd1554cebf67853f0d8028108` |
| set_freesyscall | 96 | `ev_8e11b3dd3f7d2900abbd2c47333ca88cf011acf6f06bac014395d3d1d17ac3d6` |
| startIOHook | 4720 | `ev_9333e9defdd10b862b16a215b43abdcee51da4b22b0891051ae4434939f5f43d` |

三段共4992字节另经REA/Rizin逐指令匹配原始ELF字节。`init_env`保存两项路径及状态；`set_freesyscall`设置两项全局值。`startIOHook`先进入信号设置辅助函数，再构造包含实际地址范围的BPF程序，设置no_new_privs并通过实际seccomp调用安装过滤器，按调用结果返回0或负errno。SIGSYS设置辅助函数及映射查找辅助函数也已取得完整函数体；信号回调作为函数指针写入，不属于普通直接callee，回调及完整分派仍需专门跟进。系统契约对应[Linux seccomp过滤器文档](https://docs.kernel.org/userspace-api/seccomp_filter.html)，静态读到安装代码不代表原版已经在本轮执行成功。

未被Ghidra自动识别为独立函数的7个JNI入口：`dont_allow_access`、`ishk`、`getPidSelfUid`、`setPidSelfUid`、`getBhookApi`、`setHookProcMaps`、`setFidaHookEnabled`。部分已有Rizin字节证据；这不表示代码不存在，也不表示其完整生命周期已分析。

93个函数中18个调用关系仍partial，共108个未解析调用点。“已解析直接调用队列为空”只适用于此次两层边界，不表示完整Native调用图已经穷尽。后续还需关联间接分派、构造时写入、系统版本、共享状态和运行时结果。当前不宣称完整兼容、性能等同或原生核心已复刻完成。

## 原版云端运行对照的边界

[41191356207](https://github.com/xmy953538104/Mybox/actions/runs/41191356207)已实际通过ARM转译安装原版，三张截图逐张查看均为首次协议页；未点击协议、未添加个人账号或安装HMS。同一累积日志中的SIGSEGV来自同一子PID，并非三次独立崩溃；还有1个未识别的原生帧，不能将旧字段`nativeFrameCaptureIncomplete=false`解释为完整堆栈。崩溃子进程角色和原因未确认，不能用此环境声称原版功能稳定或作性能基线。
