# 第二阶段 2B：人工恢复异常安全收口与停热结果保真计划

日期：2026-08-13
基线：已通过独立审核的阶段 1A/1B 与 2A 当前未提交工作树
源码版本：`versionName=2.5.42`、`versionCode=292`（只表示源码，不代表现场 APK）

## 1. 结论

2B 只修复人工故障恢复协调器中两个已有安全缺口：

1. 已取得故障快照后，`forceAlgorithmDisabled` 或温控恢复后的 `commitGuard` 抛异常时，当前代码直接逸出，不恢复故障态，也不重新停热；
2. App 的 `stopHeatingForFault()` 已返回三区目标温度写 0 是否全部成功，但协调器端口是 `() -> Unit`，结果被丢弃；页面仍固定提示“已回退故障状态并停止加热”。

本切片采用 fail-closed：

- 恢复前检查/快照异常转成稳定失败结果，不执行正温恢复；
- 已取得快照且后续任一步异常或失败时，按现有顺序尝试“故障状态耐久回退 → 三区重新停热”；
- 分别记录故障状态是否回退、三区目标温度写 0 调用是否全部返回成功，不用一个笼统布尔值掩盖部分失败；
- 保留原始失败 code/cause，回滚失败不得覆盖根因；
- UI 和管理日志只陈述已经取得的调用证据；即使写 0 调用全部返回成功，也禁止表述为“已停止加热”或物理停热确认。

不改下位机协议、寄存器地址、恢复入口、动作组/owner/session、MQTT/HTTP、订单、库存、调度策略或 UI 布局。

## 2. 当前源码证据

### 2.1 两个异常缺口

`recovery-core/.../ManualFaultRecoveryCoordinator.kt` 当前顺序：

```text
initialCondition
→ stateStore.snapshot
→ forceAlgorithmDisabled
→ restoreHeating（已捕获异常）
→ commitGuard
→ stateStore.saveRecovered（已捕获异常）
```

当前：

- `initialCondition` 抛异常：直接逸出；
- `snapshot` 抛异常：直接逸出；
- `forceAlgorithmDisabled` 抛异常：快照已经存在，算法内存态可能已部分关闭，但不回滚、不停热；
- `commitGuard` 抛异常：正温恢复可能已经完成，但不回滚、不停热；
- 维护页协程只有 `finally`，没有 `catch`，上述异常不会形成可读恢复结果。

### 2.2 三区写 0 调用结果被丢弃

`KaoChangOperate.stopHeatingForFault()`：

- 依次向 503/504/505 写 0；
- `allWritesSucceeded` 只有三区都成功才为 `true`；
- 写入 `faultHeatStopConfirmed=1/0`；
- 返回 `Boolean`。

但 `ManualFaultRecoveryCoordinator.recover` 当前参数为：

```kotlin
stopHeating: () -> Unit
```

App lambda 最后调用 `stopHeatingForFault(...)` 时，其 `Boolean` 被按 `Unit` 丢弃。`MaintenanceFragment.applyManualRecoveredState` 对除初始条件变化外的所有失败固定显示：

```text
已回退故障状态并停止加热
```

因此当前 JVM 层不知道真实停热是否确认，UI 文案也可能超过证据。

### 2.3 现有安全基础必须保留

- 恢复事务仍使用 App 持有的 `faultHeatTransitionLock`；
- `restoreFault` 恢复 `onlineStatus/errorStatus/errorCode/errorCategory/errorReason`，并保持 `isEnable=0`；
- `stopHeatingForFault` 仍拥有故障暂停、三区写 0、`faultHeatStopConfirmed` 和后续轮询重试；
- `syncRecoveredFaultHeatPhysicalState` 仍在 transition lock 外执行，因为恢复 journal 产生的烤盘时间调整即使随后重新暂停，也必须进入现有物理状态同步链。

这里的 `faultHeatStopConfirmed=1` 和 Boolean 只表示本次 503/504/505 写调用均返回 `ModbusOperationStatus.SUCCESS`。当前实现没有寄存器回读或真实温度/加热状态确认；`VMModbusHelper.isDebug=true` 时甚至会跳过硬件直接返回成功。因此 2B 不使用“物理停热确认”口径。

## 3. 目标核心模型

在 `:recovery-core` 增加纯数据：

```kotlin
data class ManualFaultRecoveryRollbackOutcome(
    val faultStateRestored: Boolean,
    val targetTemperatureZeroWritesSucceeded: Boolean,
    val faultStateRestoreError: Throwable? = null,
    val targetTemperatureZeroWriteError: Throwable? = null
) {
    val allRollbackOperationsSucceeded: Boolean
}
```

含义严格限定：

- `faultStateRestored=true`：`stateStore.restoreFault(snapshot)` 正常返回，即 App 已完成故障态耐久保存；
- `targetTemperatureZeroWritesSucceeded=true`：App 的 `stopHeatingForFault()` 正常返回 `true`，只表示本次 503/504/505 三次目标温度写 0 调用均返回成功；不表示寄存器已回读、不表示真实温度已下降，也不表示物理加热已经停止；
- 返回 `false` 或抛异常都记为未确认；
- outcome 只描述本次收口尝试，不声称真实机械、温度传感器或下位机物理状态。

`ManualFaultRecoveryResult.Rejected/Failed` 增加可空 `rollbackOutcome`；`Success` 不带回滚结果。根因 code 与回滚结果正交，禁止用 `ROLLBACK_FAILED` 覆盖原始失败。

新增稳定 code 与中文 reason：

| code | reason | 是否回滚 |
|---|---|---|
| `INITIAL_CONDITION_EXCEPTION` | `恢复前运行态检查异常` | 否，尚未取得快照/执行恢复 |
| `STATE_SNAPSHOT_EXCEPTION` | `故障状态快照读取异常` | 否，尚未关闭算法/执行恢复 |
| `ALGORITHM_DISABLE_EXCEPTION` | `关闭烤制算法异常` | 是，快照已取得且可能部分修改状态 |
| `COMMIT_GUARD_EXCEPTION` | `温控恢复后运行态复查异常` | 是，正温可能已恢复 |

既有 code/reason 保持：

- `INITIAL_CONDITION_CHANGED / 恢复提交前运行态已变化`；
- `HEATING_RESTORE_EXCEPTION / 温控恢复异常`；
- `HEATING_RESTORE_FAILED / 温控恢复未全部成功`；
- `COMMIT_GUARD_CHANGED / 温控恢复后运行态已变化`；
- `DURABLE_SAVE_FAILED / 恢复状态耐久保存失败`。

## 4. 精确行为矩阵

| 分支 | 正温恢复 | 故障态回退 | 三区目标温度写 0 调用 | 返回 |
|---|---:|---:|---:|---|
| 初始条件=false | 不执行 | 不执行 | 不执行 | 原 `Rejected` |
| 初始条件抛异常 | 不执行 | 不执行 | 不执行 | `Failed(INITIAL_CONDITION_EXCEPTION)` |
| 快照抛异常 | 不执行 | 不执行 | 不执行 | `Failed(STATE_SNAPSHOT_EXCEPTION)` |
| 关闭算法抛异常 | 不执行 | 尝试 | 尝试并保留 Boolean | `Failed(ALGORITHM_DISABLE_EXCEPTION)` |
| 温控抛异常 | 已尝试 | 尝试 | 尝试并保留 Boolean | 原 `Failed(HEATING_RESTORE_EXCEPTION)` |
| 温控返回 false | 已尝试 | 尝试 | 尝试并保留 Boolean | 原 `Failed(HEATING_RESTORE_FAILED)` |
| 提交复查=false | 已成功 | 尝试 | 尝试并保留 Boolean | 原 `Rejected(COMMIT_GUARD_CHANGED)` |
| 提交复查抛异常 | 已成功 | 尝试 | 尝试并保留 Boolean | `Failed(COMMIT_GUARD_EXCEPTION)` |
| 耐久保存抛异常 | 已成功 | 尝试 | 尝试并保留 Boolean | 原 `Failed(DURABLE_SAVE_FAILED)` |
| 全部成功 | 已成功 | 不执行 | 不执行 | 原 `Success` |

回滚内部始终：

1. 先尝试 `restoreFault`；
2. 无论第一步成功或抛异常，都继续尝试 `stopHeating`；
3. `stopHeating=false`、`stopHeating` 抛异常均形成 `targetTemperatureZeroWritesSucceeded=false`；
4. 返回原根因 code/cause，并附 rollback outcome。

## 5. App 接线与用户提示

### 5.1 硬件端口

将核心端口改为：

```kotlin
stopHeating: () -> Boolean
```

App lambda 直接返回现有 `KaoChangOperate.stopHeatingForFault(...)` 的 Boolean。核心仍不知道寄存器号、Modbus 或 Android。

### 5.2 UI 文案只按证据显示

`MaintenanceFragment` 继续按稳定 code 分支，不比较中文 reason。失败详情：

- `INITIAL_CONDITION_CHANGED`：保留“未重复执行温控恢复，请重新检查当前状态”；
- 恢复前检查/快照异常且无 rollback：`恢复前检查未完成，未执行温控恢复；设备仍未恢复正常`；
- 两项调用均成功：`故障状态已回退，三区目标温度写0调用均返回成功；设备仍未恢复正常，实际温控状态仍需现场确认`；
- 状态回退未确认、写 0 调用均成功：`三区目标温度写0调用均返回成功，但故障状态耐久回退未确认；设备仍未恢复正常，请检查日志；实际温控状态仍需现场确认`；
- 状态回退确认、写 0 调用未全部成功：`故障状态已回退，但三区目标温度写0调用未全部成功；设备仍未恢复正常，请检查下位机`；
- 两项均未确认：`故障状态回退与三区目标温度写0调用均未确认；设备仍未恢复正常，请立即检查下位机`。

这里刻意不写“设备保持维护”，因为入口可能来自 `onlineStatus=1 + errorStatus!=0`；`onlineStatus`（营业状态）与故障/恢复结果继续分开表达。

管理日志增加并分别记录：

- 根因 code、根因 cause 类型/消息；
- `faultStateRestored` 与 `faultStateRestoreError` 类型/消息；
- `targetTemperatureZeroWritesSucceeded` 与 `targetTemperatureZeroWriteError` 类型/消息。

返回 `false` 时对应 error 为空，从而能与“端口抛异常”区分。日志只展开类型/消息，不持久化 Throwable 对象；不改变业务 MQTT/HTTP 指令 payload，但新增 W 日志内容会沿既有云日志通道进入 `ACTION_DEVICE_LOG` MQTT。

### 5.3 不新增自动重试

2B 不在核心循环重试硬件写入，避免一次点击内无界写寄存器。现有 `faultHeatStopConfirmed != 1` 轮询重试和人工再次恢复入口保持不变。

## 6. 测试先行

先修改测试形成失败，再改生产实现。

### 6.1 recovery-core 单元测试

必须覆盖：

- 新增四个 code 与中文 reason 成对断言；
- initial condition / snapshot 异常不调用算法关闭、正温恢复、故障回退或停热；
- forceAlgorithmDisabled 异常会按顺序回退故障态并停热；
- commitGuard 异常发生在正温恢复之后，并按顺序回退/停热；
- 既有温控异常/false、guard=false、save 异常仍保持根因 code 与顺序；
- `restoreFault` 成功/抛异常 × `stopHeating` true/false/抛异常的关键组合；
- 第一项抛异常仍必须执行第二项；
- 原始 cause、状态回退 error、写 0 error 必须同时保留且互不覆盖；返回 false 时写 0 error 必须为空；
- `VirtualMachineError` 等进程级 `Error` 不得包装成业务失败：快照前原样抛出；快照后先 best-effort 尝试故障态回退和写 0，再原样抛出；回退自身出现致命错误时仍必须尝试写 0；
- Success 的路径和事件顺序不变，rollback outcome 为空；
- 两个 coordinator 共享同一 transition lock/store 的事务仍不交错。

### 6.2 App 接线门禁

必须覆盖：

- `stopHeating` lambda 返回 `stopHeatingForFault` 的 Boolean，不再被强制为 Unit；
- UI 对五种证据状态使用稳定字段，且不把 `reason` 当控制标识；
- 所有回滚文案均不得出现“已停止加热”“已重新停热”或“物理确认”；写调用成功时必须带“实际温控状态仍需现场确认”；
- 管理日志包含 code、根因、两个回滚布尔值及两个回滚 error 的类型/消息；
- 精确门禁：`commitGuard` 抛异常且 `restoreHeating` 已产生非空 `pauseEndResult` 时，`recover` 返回失败后仍无条件执行 `syncRecoveredFaultHeatPhysicalState`；禁止在失败分支提前 return 丢失恢复 journal 产生的烤盘时间同步；
- `faultHeatStopConfirmed` 的现有写入与轮询重试门禁保持；
- 201/203、300/400、温控 503/504/505、AppConfig 字段、MQTT/HTTP 接线均未改变；
- `scheduler-core` 的算法 A/B/C 可插拔边界和当前仅生产算法 A 的事实不受影响。

### 6.3 验证命令

```text
.\gradlew.bat :recovery-core:test --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat test assembleDebug --no-daemon --console=plain
git diff --check
```

继续核对 recovery-core Java 11 字节码和禁止依赖扫描。

## 7. 风险与取舍

- 这是行为修复，不再保持 2A 的“异常直接传播”现状；变化仅发生在原本异常/部分失败路径；
- fail-closed 可能让设备更长时间保持维护，但不会在收口证据不足时误报恢复成功；
- `stopHeating=false` 只证明写 0 调用未全部成功，不能推断实际加热一定仍在或一定已停；
- 不捕获/吞掉进程级崩溃后的物理结果，断电/进程杀死仍依赖已有 durable journal、启动恢复和现场检查；
- 不尝试在纯核心判断 Modbus 或真实温度；硬件真实性继续由 App 端口和现场证据负责。

## 8. 明确不做

- 不改下位机程序、Modbus 地址或动作结果语义；
- 不改恢复 gate、动作组残留关闭、owner/session 或资源锁；
- 不改烤制时间补偿/待丢弃规则；
- 不增加无限/固定次数硬件重试；
- 不升级 MQTT 5，不改 ACK/HTTP 补偿；
- 不实现新的调度算法 B/C，不改变当前算法 A 的运行选择；
- 不改 UI 布局；
- 不提交、不推送、不发布、不部署、不操作设备。

## 9. 验收与现场边界

源码/JVM/构建验收：

- 所有业务 `Exception` 形成稳定结果，不再使维护页恢复协程无说明退出；
- 已取得快照后的业务异常执行 fail-closed 收口；进程级 `Error` 仅 best-effort 收口后原样抛出，不伪装为可恢复业务失败；
- 三区目标温度写 0 调用 Boolean 不丢失，且不被表述为物理停热；
- UI/日志不超过已取得的调用证据；
- 纯核心仍无 Android/硬件/网络/I/O 依赖；
- 全量测试和 Debug 构建通过。

真实现场未验证项必须继续标注：

- 503/504/505 实际写 0；
- Modbus 断连/半成功时的下位机状态；
- 故障暂停与三区实际温度；
- 真实 MQTT/HTTP 最终状态；
- 恢复期间机械/库存/烤盘现场一致性。

后续若获得单独设备授权，使用匹配 APK、设备号、时间戳、503/504/505 写入结果、`faultHeatStopConfirmed`、MQTT/HTTP 回读和现场温区状态验收。JVM 测试或 APK 构建不能替代该证据。

## 10. 回滚边界

2B 无数据结构、协议或配置迁移。回滚只需：

- 删除新增 code 与 rollback outcome；
- 将 `stopHeating` 端口恢复为 Unit；
- 恢复 2A 的异常传播测试和 UI 详情分支；
- 保留已审核通过的 `recovery-core` 模块迁移、稳定 code 基础、scheduler-core 和全部阶段 1/2A 工作。
