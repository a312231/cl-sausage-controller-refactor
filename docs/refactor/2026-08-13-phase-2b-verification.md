# 第二阶段 2B：人工恢复异常安全收口验证

日期：2026-08-13
源码版本：`versionName=2.5.42`、`versionCode=292`（仅表示当前源码，不代表现场 APK）

## 1. 本次结果

- `initialCondition` 和故障快照读取异常不再使维护页协程无说明退出，分别返回稳定失败 code；
- 已取得故障快照后，关闭算法异常、温控异常/失败、提交复查异常/变化、耐久保存异常均执行 fail-closed：先尝试耐久回退故障态，再尝试三区目标温度写 0；
- 原始失败、故障态回退异常、目标温度写 0 异常分别保留，互不覆盖；
- 核心 `stopHeating` 端口由 `Unit` 改为 `Boolean`，App 现有 `stopHeatingForFault()` 的返回值不再丢失；
- Boolean 只命名并解释为 503/504/505 写调用结果，不宣称寄存器回读、真实温度下降或物理停热；
- UI 根据故障态耐久回退、三区写 0 调用的组合显示证据边界；不再固定显示“已停止加热”；
- 管理日志分别记录根因和两个回滚端口错误的类型/消息；
- 业务 `Exception` 返回稳定结果；`VirtualMachineError` 等进程级 `Error` 不包装为业务失败，快照后只 best-effort 回退/写 0 后原样抛出；
- 恢复 journal 产生的烤盘时间调整仍在 coordinator 返回后、transition lock 外无条件进入既有物理增量同步；
- 未修改下位机协议、寄存器地址、动作组、owner/session、订单、库存、业务 MQTT/HTTP 指令或调度算法选择；新增 W 日志内容会沿既有云日志 MQTT 通道上报。

## 2. 测试先行证据

### 核心红灯

先修改 `ManualFaultRecoveryCoordinatorTest`，首次执行 `:recovery-core:test` 在测试编译阶段失败：缺少新增 code、`rollbackOutcome`、Boolean 回滚字段。这证明测试在生产实现之前生效，不是环境失败。

补最小核心实现后，强制重跑 `:recovery-core:test --rerun-tasks` 成功。

### App 红灯

先新增 UI 证据矩阵与接线门禁，首次定向 App 测试因 `ManualRecoveryFailureDisplayPolicy` 尚不存在而编译失败。

补最小展示策略、日志和接线后定向测试成功；随后完整 `:app:testDebugUnitTest` 成功。

## 3. 自动化覆盖

`recovery-core` 覆盖：

- 四个新增异常 code 与中文 reason；
- 初始检查/快照异常不执行算法关闭、正温恢复、回滚或写 0；
- 算法关闭/提交复查异常按现有 transition lock 顺序 fail-closed；
- 既有温控异常/false、guard=false、durable save 异常根因和顺序不变；
- 原始 cause、故障回退 error、写 0 error 同时保留；
- `restoreFault` 成功/抛异常 × 写 0 true/false/抛异常六种组合；
- 返回 false 与端口抛异常可区分；
- 两个 coordinator 实例共享同一 lock/store 时事务不交错。
- 快照前致命 `Error` 原样抛出且不变更状态；快照后致命 `Error` best-effort 回退/写 0 后原样抛出；回退自身出现致命 `Error` 时仍尝试写 0。

App 覆盖：

- 初始条件变化、恢复前异常、四种回滚调用证据组合的精确用户文案；
- 回滚证据缺失兜底不再声称“设备保持维护”，只说明设备仍未恢复正常；
- 所有回滚文案禁止“已停止加热”“已重新停热”“物理确认”；
- 写 0 调用均返回成功时仍提示实际温控状态需现场确认；
- 错误格式保留异常类型/消息，null 与返回 false 可区分；
- 管理日志包含 failure code、root cause、两个回滚布尔值和两个回滚 error；
- `stopHeatingForFault()` Boolean 仍为 lambda 最后表达式；
- coordinator 后无提前 return，锁外同步早于结果返回；
- 2A 的 Modbus 连接、201、仅 `moveStatus==3` 读 203、真实 lock、安全 guard、六字段状态存储门禁保持；
- `faultHeatStopConfirmed` 写入与现有轮询重试测试保持通过；
- scheduler-core 算法 A 等价、可插拔策略/测试 B 和默认关闭影子模式测试保持通过。

## 4. 最终验证

执行：

```text
.\gradlew.bat :recovery-core:test --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat test assembleDebug --no-daemon --console=plain
git diff --check
```

结果：

- 所有命令成功；
- 最终全量构建：284 个 Gradle task，4 个执行、280 个复用缓存；
- XML 汇总：493 个测试套件文件、3314 次执行、0 failure、0 error、0 skipped；
  - App Debug：1643 次；
  - App Release：1643 次；
  - recovery-core：18 次；
  - scheduler-core：8 次；
  - libnative Debug/Release：各 1 次；
- 3314 是 Debug/Release 等重复执行总数，不是唯一测试方法数量；
- recovery-core Android/网络/硬件库 import 与构建依赖扫描：0 命中；域模型中的 `modbusConnected` Boolean 和稳定 code 不属于库依赖；
- recovery-core `catch(Throwable)` / `runCatching` 扫描：0 命中；
- recovery-core class major version：55，即 Java 11 字节码；
- `git diff --check`：通过；
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`；
- APK 大小：50,387,175 字节；
- APK SHA-256：`ACA767880C9B1C38D6E11A8F06C49B5812627976508196E38FBD5B9505E911FB`。

构建仍有既有 32 位 `armeabi-v7a` Google Play 警告和 Gradle 9 弃用提示；2B 未修改 ABI 或发布配置。

## 5. 独立审核状态

2B 实施前计划审核共两轮：

- 第一轮 `Request Changes`：纠正“写调用成功=物理停热”的越界表述；要求分别记录回滚错误；要求精确锁定 commitGuard 异常后仍执行锁外 journal 同步；
- 主会话逐项核对后确认准确并修订；
- 第二轮最终 `Approve`，P0/P1/P2/P3 均无。

2B 首轮实现审核为 `Request Changes`：

- P1：`Throwable` / `runCatching` 会把 `VirtualMachineError`、`ThreadDeath` 等进程级信号包装成普通业务失败；
- P2：缺失 rollback outcome 的 UI 兜底仍误写“设备保持维护”；
- P3：新增 W 日志会沿既有云日志链进入 `ACTION_DEVICE_LOG` MQTT，不能声称 MQTT payload 完全不变。

主会话逐项查源码确认三项准确，已改为业务 `Exception` 稳定收口、致命 `Error` best-effort 回退后原样抛出，修正兜底文案，并收紧 MQTT 证据表述；新增对应测试后重新完成全量验证。

修订实现第二轮审核最初因“两个回滚端口抛同一个 Error 会触发 self-suppression”给出 P1。主会话没有直接接受该推断，而是先补精确测试并反编译当前产物：

- 两个端口抛同一个预创建 `VirtualMachineError` 的测试在不改生产代码时直接通过，原对象引用不变且写 0 端口已执行；
- 当前协调器字节码调用 `kotlin.ExceptionsKt.addSuppressed`；Kotlin stdlib 1.9.23 在两个引用相同时通过 `if_acmpeq` 直接返回，并非直接调用 Java self-suppression；
- 审核会话据此撤回该 P1，最终门禁为 `Approve`，P0/P1/P2/P3 均无；精确测试保留为未来 Kotlin 升级的回归门禁。

主会话再次核对后同意最终 `Approve`：2B 的源码/JVM/构建范围可以结束，但不扩大为真机或现场验证结论。

## 6. 真实证据边界

已验证：源码结构、JVM 规则、App 接线、Debug/Release JVM 测试、Debug APK 构建。

未验证：

- 503/504/505 在真实下位机上的写入和回读；
- 写调用返回成功后真实加热元件是否停止、温度是否下降；
- RS485 断连、部分写成功、下位机忙态时的实际表现；
- 真实 MQTT ACK、HTTP 回读、机械、库存和烤盘现场一致性；
- 长时间运行和进程/整机掉电恢复。

因此不能把 Boolean、JVM 测试或 APK 构建表述为真实停热、RS485 或机械验证。

## 7. 工作树边界

- 仅修改独立重构仓库当前未提交工作树；
- `D:\LeYe\cl-sausage-controller` 生产仓库仍仅有用户原有未跟踪文件，本阶段未触碰；
- 未提交、未推送、未发布、未部署、未操作设备。

## 8. 回滚

2B 没有数据、协议或配置迁移。回滚只撤销新增异常 code、rollback outcome、Boolean 核心端口、展示策略与对应测试；保留已审核的 recovery-core 模块迁移、scheduler-core、影子模式和阶段 1/2A 工作。
