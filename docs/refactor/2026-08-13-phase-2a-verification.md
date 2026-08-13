# 第二阶段 2A：人工故障恢复核心迁移验证

日期：2026-08-13
源码版本：`versionName=2.5.42`、`versionCode=292`（仅表示本次源码，不代表现场 APK）

## 1. 本次结果

- 新增纯 Kotlin/JVM 模块 `:recovery-core`；
- 将 `ManualFaultRecoveryCoordinator`、`ManualRecoveryGatePolicy`、`ManualRecoveryCloudConfirmationPolicy` 从 App 迁入核心模块；
- 新增稳定结果码，原有中文 `reason` 原文保留；
- `MaintenanceFragment` 不再用中文失败文案判断控制分支；
- Modbus 连接与 201/203 读取、动作组恢复、温控写入、AppConfig 耐久保存、MQTT/HTTP 和 UI 仍由 App 持有；
- 未修改下位机协议、寄存器地址、机械动作、云端协议、恢复入口或操作流程；
- 未提交、未推送、未发布、未部署、未操作设备。

## 2. 等价性证据

协调器保留原顺序：

`初始条件 → 状态快照 → 关闭算法 → 恢复温控 → 提交前复查 → 耐久保存`

并锁定现有异常边界：

- `initialCondition`、`snapshot`、`forceAlgorithmDisabled`、`commitGuard` 抛异常时仍直接传播，没有新增回滚；
- 温控抛异常/返回失败、提交前条件变化、耐久保存失败时，仍按“恢复故障快照 → 重新停热”收口；
- 恢复故障快照自身抛异常时仍继续尝试停热；停热异常不覆盖原失败结果；
- 两个独立 coordinator 实例共享同一 transition lock 和 state store 时，完整事务不交错。

App 接线门禁锁定：

- 未连接时由 App 拒绝；
- 201 仍读取 `action_address_status`；
- 只有 `moveStatus == 3` 时才读取 203 `action_address_move_result`；
- `faultHeatTransitionLock` 仍由 App 传入；
- safety guard 仍覆盖目标状态 `0..2`、Modbus 连接、`moveStatus == 0`、动作组恢复阻断与并行补货暂停例外；
- 算法内存态和 `AppConfig.isEnable` 仍同时关闭；
- 温控恢复和失败停热仍调用原 App 硬件端口；
- `onlineStatus/errorStatus/errorCode/errorCategory/errorReason/isEnable` 的快照、成功保存与故障恢复字段保持原样；
- 恢复后的物理状态同步仍在 coordinator 返回且退出 transition lock 后执行。

## 3. 自动化验证

执行：

```text
.\gradlew.bat :recovery-core:test --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat test assembleDebug --no-daemon --console=plain
git diff --check
```

结果：

- `:recovery-core:test`：成功，强制重跑；
- App Debug 单元测试：成功；
- 全量 `test assembleDebug`：成功，284 个 Gradle task 中 15 个执行、269 个复用缓存；
- XML 汇总：491 个测试套件文件、3293 次执行、0 failure、0 error、0 skipped；
  - App Debug：1636 次；
  - App Release：1636 次；
  - recovery-core：11 次；
  - scheduler-core：8 次；
  - libnative Debug/Release：各 1 次；
- 以上数字包含 Debug/Release 对同一 App 测试的重复执行，不能当作 3293 个唯一测试用例；
- `recovery-core` class major version：55，即 Java 11 字节码；
- `git diff --check`：通过；
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`；
- APK 大小：50,456,018 字节；
- APK SHA-256：`62BAE3C49AA053D2A5A1FB68AB14A00DB546ECBFD6D2132FC05C6BDC435890E1`。

构建存在既有 32 位 `armeabi-v7a` Google Play 警告和 Gradle 9 兼容性弃用提示；本切片未修改 ABI 或发布配置。

## 4. 证据边界

已验证：源码结构、纯 JVM 规则、App 接线、Debug/Release JVM 测试、Debug APK 构建。

未验证：

- 真实 RS485/Modbus 重连；
- 201/203 及 300/400 寄存器现场值；
- 三区温控实际写入与停热；
- 动作组 owner/session 的真实机械收口；
- 真实 MQTT ACK、HTTP 回读和后台最终状态；
- 长时间设备稳定性。

因此，本记录不把 JVM 测试或 APK 构建表述为真实硬件验证。

## 5. 回滚边界

回滚仅撤销 `:recovery-core`：恢复三个类及其纯测试到 App 原包，撤销 App import、稳定 code 分支和 Gradle 模块依赖。`scheduler-core`、调度算法 A/B 可插拔边界、影子模式及阶段 1 文档必须保留。

## 6. 独立审核

“重构上位机审核会话A”先给出 `Approve`，无 P0/P1/P2，指出两项 P3 测试门禁可加强：分别锁定成功保存/回滚的 `isEnable=0`，以及扩大纯模块禁止依赖扫描。

主会话核对后确认两项建议准确，补强测试但未修改生产代码；对应 App 测试类重新执行成功。审核会话再次只读复核后的最终门禁为：

```text
Approve
P0：无
P1：无
P2：无
P3：无
```

主会话同意该审核结论；其依据覆盖当前源码和 JVM 测试，不外推为真实 MQTT、RS485、温控或机械验证。
