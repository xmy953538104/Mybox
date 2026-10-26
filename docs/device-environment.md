# 原版设备环境链与 Mylite 重建边界

本页是恢复出的规格，不是已实现功能声明。当前 Mylite 尚没有完整的设备配置层，不能称为已经复刻机型伪装。

## Java 层

2026-10-26补宿主配置来源的函数级REA：`VirtualDeviceInfoHelper`的缓存优先初始化`ev_87064cb01d14d02a06a416f33534192f5ed66fe9603ebcf4c4159b7844a9929c`（398字节）依次读MMKV环境与旧shared/device_env，缺BRAND/MODEL才重新选择；资料选择`ev_b2551c2c11fce9a44be9185586190f8ba658069e7c9dbf4b341a3ca7838e1061`（534字节）先尝试宿主服务端资料，失败可按SDK走本地预设，不必依赖GBox私有服务端。旧样本对API<=28、29、30、31和其余版本分别选Android9、10、11、12、13资料；不能把“其余一概13”直接作为新系统的正确兼容策略。转换`ev_a38916306b619285e8c1befcc34829c52e633474bc5ba9893852973c7cba3678`（546字节）同步Build与对应ro.product/ro.build属性；初始化随后改写UA并以persistent=true交SDK。读缓存、旧JSON和写回另有`ev_772f3ec4cb29de8bbae9c162b7e26353ff2f495b397fc65a3bce691247e646dd`、`ev_540ef08ce6959fa139bfae15b68b5a0c2ea701ecb7ef486766ddb36a3781869b`、`ev_5ea616ba1626f391c24c17414d3251850ac332e7b2f8048441e19f221def5d6b`；七个方法共2864字节均对原classes3.dex校核。MMKV JSON/版本两项的崩溃原子性、完整每应用override与运行时恢复仍未知。原microG编译XML还关闭自身机型选择/导入按钮，这不代表宿主能力不存在。以上是规格证据，Mylite对应完整实现仍未完成。

REA 在原版新 APK 中确认 DeviceEnvInfo 包含 buildFields、systemProperties、androidId、deviceId、serialNo、wifiMac、bluetoothMac、iccId、defaultWebViewUserAgent 与 extras。服务端 device.a 的单例保存到 device_env SharedPreferences；getDeviceInfo 没有应用参数。客户端读取同一配置，应用 Build 静态字段，并逐项调用 Native.addNativeProp。另有应用 overrideDeviceEnvInfo 的覆盖入口及 WebView provider 初始化时设置 UA 的路径；这些覆盖的实际产品默认值/来源仍需完整核验。

因此，重建时必须把 microG、Play 和其他客体的设备快照作用域放在容器/虚拟用户层，保持跨 VM、重启和更新稳定；不能在每个进程启动时各生成另一台设备。应用特例须单独证明，不能从默认全局配置推断任意覆盖规则。

2026-10-25 再次取得 REA 类清单 `ev_fbe6378f214cee5e5a2b7b30bf4775fd1f45886c4add6c66605f4dd8ab197610`，它仅是成员清单。服务端 `setDeviceInfo` 方法证据 `ev_293da2c1cbd7225884159625aec0189bb97bb82f0c87a710a58c95a1d35b279a` 显示先更新内存对象，再按显式 persistent 参数决定写入；不能推断所有更新都落盘。客户端应用入口证据 `ev_ff1f5b28dfff75090cb130111817a843d748f715aad9365da8e7e08851d91bc0` 显示空全局配置直接返回，否则顺序为全局 Build → 全局 native properties → 应用 override → 注册变化监听。它没有证明默认必须伪造型号，也没有证明所有字段可安全热更新。

2026-10-26 新函数级 REA：`updateDeviceInfo`（`ev_d58bdc7800d5d11fcb1ef3cb55b3df73c8a19ccf705721917c1f97dbf320293b`）在已有配置上调用合并方法，随后按 `n0()` 决定写回；保留文本中的 `n0()` 检查 SharedPreferences 既存键，不是当前对象上的 persistent 标记。没有已有对象时不能据此创建默认设备。混淆奇偶分支仍只作静态证据，不承诺运行全部路径。`DeviceEnvInfo.S(DeviceEnvInfo)`（`ev_b887a151f290661eff7281361d6b2a4da20c9ecc08d5b8b8451d9b9b2a783c5f`）对 null 输入不改动；七个标量的非 null 值覆盖旧值（包括空串），非空 buildFields/systemProperties/extras 用 putAll 合并，空集合不清空旧映射。原序列化/默认配置来源及运行恢复仍未全部验收。变化监听中的 `set_prop` 只更新指定native属性，不等于重跑全部Build配置。另有包安装管理链使用该监听通路更新 `cache_key.package_info`；不能把它误当成整台设备资料变更或随意随机Android ID。

REA 证据：字段 `ev_f66a2ed889d1f80230bc2ac7c523b15de79861b6945ec4f11eeca627e13d379e`；Build 应用 `ev_abb050780c1584724df600b8933acb550de88e74e3662c2cd86c379b7bc97747`；native 属性调用 `ev_793c4f02f8581a8b403a8972c769aabc93b16a8d16429fe8846c5026b5787897`。样本和工具身份见[业务差距清单](user-journeys.md)。反编译中的混淆/类型合并分支不能直接当作正确可执行代码。

## Native 层

原 ARM64 libvlitecore64.so SHA-256 为 `daf9e3844d5d6c750dcd360745d3595621719c0809c8b93fb8b665799c81ac3a`，使用已有 Ghidra 12.1.4 工程作只读函数级核对，不把它冒充 REA native 结果。地址列为该工程基址 0x100000 下的地址，原工件及完整反编译结果留在私有分析目录。

| 地址 | 已确认的作用 |
| --- | --- |
| 0x1193f4 | JNI addNativeProp 获取两个 Java UTF 字符串，调用 0x125888 后释放字符串。 |
| 0x125888 | 把名称和值写入 native 配置映射。 |
| 0x125d1c | 查属性映射，并结合允许/阻止键集合决定返回覆盖值、空值或无覆盖。 |
| 0x126820 | __system_property_get 代理；存在 debug./vendor.debug. 的绕行逻辑；没有覆盖则调用真实 getter，有覆盖才替换返回值。混淆条件须结合汇编核对。 |
| 0x126ff0 | 使用 bytehook_hook_all 注册 libc.so 的 __system_property_get 和存在时的 __system_property_read_callback；同函数还有传感器包名处理。 |
| 0x126efc | read_callback 包装：保留原回调及 cookie，交给真实读取函数执行。 |
| 0x126ea8 | 中间回调通过上述 getter 获取值，再调用原 callback，并保留原 name/serial。 |

结论是两条 native 读取路径都要覆盖；Java Build 一处替换不构成完整设备视图。原函数中的固定栈缓冲、混淆空值逻辑及读数缓存语义仍有实现/边界风险，独立重建时应使用明确长度和真实平台约束，不能机械复制原反编译源码。尚未证明所有直接 property-area 读取都经过这些代理。

## 实现和验收次序

先确定默认配置来源与生命周期，再实现同一不可变快照的 Java Build、native property get/read_callback、Settings 标识及 WebView UA 视图。配置更新应经过持久化后在新 VM 代次生效，避免账号/下载过程中设备身份突变。当前不为缺少使用证据的字段生成任意型号；SDK、ABI 与实际能力必须一致。

验证至少包括：同一配置在 microG/Play/普通客体及其第二进程中一致；Java 与两条 native 路径对应；重启/应用更新后保持稳定；宿主与真实系统读数不被修改；未配置字段保留真实结果；无电话权限的查询继续真实拒绝；长/空/非法属性受长度与格式校验。以上尚待实现与运行，不计入当前通过项。
