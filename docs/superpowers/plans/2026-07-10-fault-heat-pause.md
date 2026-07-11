# 故障停热与40分钟配置实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变整批调度算法的前提下，让每个故障周期都可靠暂停计时，并在温控恢复写成功后才继续，同时补齐40分钟配置的实时下发。

**Architecture:** 复用 `FaultHeatPauseManager` 的持久化暂停状态作为唯一幂等门禁；让温控写链路返回真实结果并由恢复入口统一决定是否结束暂停。配置同步沿用现有 `runtime_control/configPatch`，只增加 `bakingTime` 字段及活动烤盘回刷。

**Tech Stack:** Kotlin/Android、JUnit4、Java/Spring Boot、MQTT runtime_control、Gradle、Maven。

---

### Task 1: 二次故障与恢复确认

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/FaultHeatRecoverySourceTest.kt`

- [ ] 先写源码回归测试，要求故障幂等门禁读取 `faultHeatPauseActive`，自动恢复必须检查温控恢复结果后才能清故障。
- [ ] 运行定向测试并确认因当前内存守卫和无返回值恢复接口而失败。
- [ ] 让温区写入返回 `ModbusOperationResult`，批量恢复返回成功/失败；仅成功后结束暂停。
- [ ] 删除 `faultHeatingStopped` 跨周期门禁，改用持久化暂停状态。
- [ ] 运行定向测试和 `FaultHeatPauseManagerTest`。

### Task 2: 维护态统一停热暂停

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/RemoteMaintenanceHeatPauseSourceTest.kt`

- [ ] 写失败测试，要求远端从运营/工作中进入维护态时调用 `stopHeatingForFault`。
- [ ] 运行测试确认当前只关闭算法。
- [ ] 在维护态转换副作用中建立暂停并尝试停热，不改变 0/2 状态语义。
- [ ] 运行维护态、远端运行控制和算法状态测试。

### Task 3: bakingTime 实时配置补丁

**Files:**
- Modify: `D:/LeYe/cl-sausage-server/ruoyi-admin/src/main/java/com/ruoyi/iot/DeviceConfigPatchBuilder.java`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/net/RemoteRuntimeConfigPatchApplier.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/net/DataManagementAPI.kt`
- Test: `D:/LeYe/cl-sausage-server/ruoyi-admin/src/test/java/com/ruoyi/iot/DeviceConfigPatchBuilderTest.java`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/net/RemoteRuntimeConfigPatchApplierTest.kt`

- [ ] 分别写后端补丁包含 `bakingTime`、设备拒绝非正数并应用40分钟的失败测试。
- [ ] 运行两侧定向测试确认失败。
- [ ] 补齐双边白名单，并在设备应用后调用现有活动烤盘回刷函数。
- [ ] 运行两侧定向测试。

### Task 4: 回归验证

- [ ] 运行 `./gradlew.bat test`。
- [ ] 运行 `mvn -pl ruoyi-admin -am -DskipTests compile`。
- [ ] 检查 `HighCurrentTrafficMode.kt`、`HighHeatSupplementResumePlanner.kt`、`SupplementReturnBindingResolver.kt` 无 diff。
- [ ] 记录真机仍需验证：断线看门狗、503/504/505 实际回读、二次断线、恢复升温与整批回搬。
