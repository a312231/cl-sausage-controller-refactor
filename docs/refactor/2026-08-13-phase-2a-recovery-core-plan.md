# 第二阶段 2A：人工故障恢复核心边界计划

日期：2026-08-13
基线：当前未提交的阶段 1A/1B 工作树
源码版本：`versionName=2.5.42`、`versionCode=292`（仅表示源码，不代表现场 APK）

## 1. 结论

第二阶段先抽离“人工恢复是否允许、恢复事务如何提交/回滚、云端确认结果如何解释”三个纯决策，不修改任何恢复业务规则。

- 新增纯 JVM `:recovery-core`；
- 迁入现有 `ManualRecoveryGatePolicy`、`ManualFaultRecoveryCoordinator`、`ManualRecoveryCloudConfirmationPolicy`；
- App 仍拥有 Modbus 读取、动作组恢复、温控写入、AppConfig 持久化、MQTT/HTTP 和 UI；
- 下位机协议、寄存器、动作组、故障状态、恢复顺序和中文提示保持不变；
- 只新增稳定结果码，替代 UI 对中文 `reason` 的精确字符串比较；
- 模块迁移严格保留现有异常传播和回滚行为；异常收口缺口另立安全切片，不在 2A 顺手修复；
- 不把 `KaoChangOperate`、`MaintenanceFragment` 或整个恢复系统一次性重写。

## 2. 当前调用链与耦合证据

人工恢复入口：

`MaintenanceFragment.btnErroStatus`
→ 防重复点击和维护操作 gate
→ 丢弃 journal / 紧急复位残留现场确认
→ `MaintenanceModbusRecoveryConnector.ensureConnected`
→ `evaluateManualRecoveryHeating`
→ `KaoChangOperate.confirmManualDeviceRecoveryResidue` 或 `recoverActionGroupStateAfterModbusReady`
→ 读取旧动作状态寄存器 201；搬盘忙态时读取结果寄存器 203
→ `ManualRecoveryGatePolicy.evaluate`
→ `KaoChangOperate.recoverFaultHeatingAndRuntimeState`
→ `ManualFaultRecoveryCoordinator.recover`
→ 强制算法关闭
→ 按当前烤盘恢复三区温控
→ 再次检查 Modbus、`moveStatus` 和动作组恢复阻断
→ `AppConfig.saveAppConfigDurable` 清除故障、保持算法关闭
→ 失败时恢复故障快照并再次停热
→ `MaintenanceFragment.confirmManualRecoveryInCloud`
→ MQTT `runtime_state` ACK；失败时 HTTP 更新和回读补偿
→ `ManualRecoveryCloudConfirmationPolicy.evaluate`。

当前状态所有权：

- `AppConfig`：`onlineStatus/errorStatus/errorCode/errorCategory/errorReason/isEnable/moveStatus`；
- `KaoChangOperate`：温控 transition lock、动作组 owner/session、恢复阻断和物理状态同步；
- `VMModbusHelper`：真实下位机连接与寄存器读写；
- `MaintenanceFragment`：用户确认、UI 提示、操作防重和云端确认编排；
- 现有三个纯类：只接收值、接口和 lambda，不直接依赖 Android、MQTT、HTTP 或 Modbus。

最难耦合点暂不在 2A 拆除：

1. 维护页一个点击流程同时处理丢弃 journal、紧急复位、动作组残留、温控和云确认；
2. `KaoChangOperate` 同时拥有温控锁、机械 owner/session、持久化适配器和恢复后物理状态同步；
3. `FaultHeatPauseManager` 同时管理烤制计时顺延、食品安全待丢弃和 durable journal；
4. `ActionGroupRecoveryBootstrap` 需要跨命令/结果寄存器、session、资源锁和订单 owner 判断；
5. 自动 Modbus 恢复与人工恢复共享温控恢复能力，但进入条件和故障来源不同；
6. 本地恢复成功与云端 MQTT/HTTP 确认是两个阶段，目前由 Fragment 编排；
7. 中文 `reason` 同时承担 UI 文案、日志和个别控制分支，缺少稳定机器码。

## 3. 目标模块边界

新增 `:recovery-core`，包名 `cn.niuyannet.kaochang.recovery`。

允许依赖：

- Kotlin/JDK；
- JUnit（仅测试）。

禁止依赖：

- `android.*`；
- AppConfig、KaoPan、KaoChangOperate、MaintenanceFragment；
- MQTT、HTTP、Fastjson；
- VMModbusHelper、串口、寄存器读写和动作组实现；
- 系统时钟、线程、协程、Timer、Handler、文件或数据库。

迁移内容：

1. `ManualRecoveryGatePolicy`
   - 输入仍是 App 已经取得的连接/动作组/201/203/`moveStatus` 证据；
   - 核心不主动读寄存器；
   - 输出 `canRecoverDeviceStatus + code + reason`。
2. `ManualFaultRecoveryCoordinator`
   - 保持当前顺序：初始检查 → 快照 → 算法关闭 → 温控恢复 → 提交前复核 → durable 保存；
   - 只在当前源码已经回滚的分支保持现有顺序：恢复故障快照 → 停热；不得把直接传播的异常悄悄改成回滚；
   - `ManualFaultRecoveryStateStore` 仍由 App 适配；
   - transition lock 仍由 App 拥有并传入，核心不创建线程或全局锁。
3. `ManualRecoveryCloudConfirmationPolicy`
   - 只解释已经取得的 ACK/回读值；
   - MQTT listener、超时、HTTP JSON 解析继续留在 App。

### 现有协调器异常语义（2A 必须原样保持）

当前源码并非“任一步失败都会回滚”。迁移测试必须锁定以下实际顺序：

- `initialCondition` 返回 `false`：返回 `Rejected(INITIAL_CONDITION_CHANGED)`，不读取快照、不关算法、不恢复温控；
- `initialCondition` 抛异常：异常直接传播，不读取快照、不执行回滚；
- `stateStore.snapshot` 抛异常：异常直接传播，不关算法、不执行回滚；
- `forceAlgorithmDisabled` 抛异常：异常直接传播，不调用 `restoreFault` 或 `stopHeating`；该回调可能已部分修改内存状态，2A 不改变；
- `restoreHeating` 抛异常：返回 `Failed(HEATING_RESTORE_EXCEPTION)`，先尝试 `restoreFault`，再尝试 `stopHeating`；
- `restoreHeating` 返回 `false`：返回 `Failed(HEATING_RESTORE_FAILED)`，同样先恢复故障快照再停热；
- `commitGuard` 返回 `false`：返回 `Rejected(COMMIT_GUARD_CHANGED)`，恢复故障快照并停热；
- `commitGuard` 抛异常：异常直接传播，当前不会恢复故障快照或重新停热；2A 不改变；
- `saveRecovered` 抛异常：返回 `Failed(DURABLE_SAVE_FAILED)`，恢复故障快照并停热；
- 回滚中 `restoreFault` 抛异常会被吞掉，但仍继续尝试 `stopHeating`；`stopHeating` 抛异常也被吞掉，不能覆盖原始结果。

这些传播缺口可能需要安全修复，但涉及温控已经恢复后的异常收口，必须另立行为变更切片，明确快照可用性和停热顺序，并进行真实温控/Modbus 验证。

## 4. 稳定结果码

新增枚举但保留现有中文 `reason`，避免 UI、日志和现场文案变化。

最低需要：

- `INITIAL_CONDITION_CHANGED`；
- `HEATING_RESTORE_EXCEPTION`；
- `HEATING_RESTORE_FAILED`；
- `COMMIT_GUARD_CHANGED`；
- `DURABLE_SAVE_FAILED`；
- gate 的 `MODBUS_DISCONNECTED / ACTION_GROUP_NOT_READY / LEGACY_ACTION_NOT_IDLE / PAN_MOVE_NOT_CONFIRMED / ALLOWED`；
- App 在动作组残留关闭失败时直接构造 gate 决策，使用专用 `ACTION_GROUP_RESIDUE_CLOSEOUT_FAILED`，不得冒充 `ACTION_GROUP_NOT_READY`；
- cloud 的 `ACK_TIMEOUT / ACK_NOT_APPLIED / LOCAL_STATE_CHANGED / REMOTE_STATE_MISMATCH / CONFIRMED`；
- App HTTP 补偿直接构造 cloud 决策时：本地状态变化=`LOCAL_STATE_CHANGED`，HTTP 更新后回读/解析失败=`HTTP_VERIFICATION_FAILED`，回读状态匹配=`CONFIRMED`，回读状态不匹配=`REMOTE_STATE_MISMATCH`。

所有 App 侧直接构造 `Decision` 的位置必须显式传入上述 code，并逐项保持当前中文 reason 原文；禁止复用语义不符的 code。

`MaintenanceFragment` 当前对 `"恢复提交前运行态已变化"` 的精确比较改为判断 `INITIAL_CONDITION_CHANGED`。除此之外，所有用户可见字符串保持原样。

## 5. 测试先行与等价门禁

先在 `recovery-core` 写/迁移测试，使其在实现迁移前失败，再补最小实现。

必须覆盖：

- 当前三个类已有全部场景；
- 每个分支的稳定 code 与原中文 reason 成对断言；
- `initialCondition=false` 时不得关闭算法、恢复温控或保存状态；
- 温控抛异常、返回 false、提交前 guard 变化、durable 保存失败的事件顺序和回滚顺序完全一致；
- 分别覆盖 `initialCondition/stateStore.snapshot/forceAlgorithmDisabled/commitGuard` 抛异常，断言仍按上节现状直接传播且没有新增回滚动作；
- `restoreFault` 自身抛异常时仍尝试停热；`stopHeating` 抛异常不覆盖原失败结果；
- 并发测试至少创建两个 coordinator 实例，传入同一个 transition lock 和同一个状态存储并并发调用，断言两段完整事务不交错；禁止只测同一 coordinator 实例；
- App 源码门禁确认真实 Modbus 读数仍在 App 获取，核心模块没有 VMModbusHelper，并精确锁定：未连接分支仍在 App 拒绝；201 仍读取 `action_address_status`；只有 `moveStatus == 3` 才读取 203 的 `action_address_move_result`；
- `KaoChangOperate` 接线门禁确认：真实 `faultHeatTransitionLock` 传给 coordinator；safety guard 仍覆盖目标状态 `0..2`、Modbus 已连接、`moveStatus == 0` 和动作组阻断/并行补货暂停例外；
- 接线门禁继续确认：`forceAlgorithmDisabled` 同时关闭 `KaoChangAlgorithm.isEnabled` 和 `AppConfig.isEnable`；温控回调调用 `restoreHeatingStateFromCurrentPansLocked`；失败回调调用 `stopHeatingForFault`；
- durable store 门禁逐字段锁定 `onlineStatus/errorStatus/errorCode/errorCategory/errorReason/isEnable` 的快照、成功保存和故障恢复，并确认 `syncRecoveredFaultHeatPhysicalState` 仍在 coordinator 返回且退出 transition lock 后执行；
- App 源码门禁确认 `MaintenanceFragment` 使用稳定 code，不再按中文 reason 决定控制分支；
- App 侧 `ManualRecoveryGatePolicy.Decision` 与 `ManualRecoveryCloudConfirmationPolicy.Decision` 的全部直接构造点都有 code/reason 配对测试。

验证命令：

```text
.\gradlew.bat :recovery-core:test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat test assembleDebug
```

## 6. Gradle 与精确文件边界

实施文件必须包含：

- `settings.gradle.kts`：新增 `include(":recovery-core")`，保留 `:scheduler-core`；
- `app/build.gradle.kts`：新增 `implementation(project(":recovery-core"))`，保留 scheduler 依赖；
- `recovery-core/build.gradle.kts`：复用已声明的 Kotlin JVM 插件；JDK 17 toolchain，Java source/target 与 Kotlin `jvmTarget` 均为 11；仅 JUnit 测试依赖；
- 将三个纯类迁到 `recovery-core/src/main/kotlin/cn/niuyannet/kaochang/recovery/`；
- 将对应纯测试迁到 `recovery-core/src/test/kotlin/cn/niuyannet/kaochang/recovery/`，App 接线/源码门禁继续留在 App 测试；
- 更新 `MaintenanceFragment`、`KaoChangOperate`、`ManualRecoveryCloudHttpConfirmation` 及相关 App 测试 import；
- 根 `build.gradle.kts` 和版本目录已由 1A 提供 Kotlin JVM 插件，本切片不重复修改。

## 7. 明确不做

- 不改变人工恢复允许条件；
- 不改 201/203 或任何 300/400 寄存器；
- 不自动确认未知机械结果；
- 不改动作组 owner/session、资源锁或 emergency reset fence；
- 不改温控目标、故障停热、烤制计时和待丢弃算法；
- 不合并人工恢复与自动 Modbus 恢复；
- 不改 MQTT/HTTP 协议、ACK、重试或超时；
- 不改 UI 布局和操作流程；
- 不处理断网订单业务边界；
- 不发布、不部署、不操作设备。

## 8. 验收与回滚

源码/JVM 验收：

- `recovery-core` 无 Android/I/O/硬件/网络依赖；
- 现有恢复规则和事件顺序全等；
- App 只负责采集证据、执行端口和展示结果；
- 中文文案不再作为控制分支标识；
- 全量测试与 Debug 构建通过。

现场证据边界：

- 2A 不改变现场行为，因此不授权设备测试；
- JVM/构建不能证明真实 Modbus 重连、三区温控写入、动作组残留收口或云端 ACK；
- 后续任何恢复行为变更必须使用匹配 APK、设备号、时间戳、201/203 和相关 300/400 寄存器、温控写入结果、MQTT ACK/HTTP 回读及最终烤盘/设备状态验证。

回滚：

- 从 `settings.gradle.kts` 删除 `include(":recovery-core")`，但保留 `include(":scheduler-core")`；
- 从 `app/build.gradle.kts` 只删除 `implementation(project(":recovery-core"))`；
- 删除 `recovery-core/`，将三个类及纯测试恢复到 App 原路径/原包，恢复所有 App import；
- 将 UI 稳定 code 判断恢复到 2A 前等价代码；
- 保留根 Kotlin JVM 插件、版本目录和 scheduler-core，它们属于已经通过审核的 1A/1B；
- 无数据库、配置或设备状态迁移，回滚不需要数据转换。
