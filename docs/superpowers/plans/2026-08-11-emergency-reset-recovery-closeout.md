# Emergency Reset Cold-Restart Closeout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让管理员紧急复位在 `999=1` 成功后通过可验证的 App 冷重启终止旧任务，并在下位机确认空闲后清除旧机械协调状态。

**Architecture:** 持久化 fence 只记录 resetId、创建进程实例和阶段，不传播全局 generation。Modbus 写入层在同一串行锁内先封禁动作寄存器再写 `999=1`；写入成功后由系统 AlarmManager 安排启动入口并终止当前进程。重启后的新进程以 fence 为最高优先级保持维护、阻止算法和旧 session 恢复；人工确认与两次空闲证据通过后一次 commit 清协调键。

**Tech Stack:** Kotlin、Android 12/targetSdk 34、AlarmManager/PendingIntent、SharedPreferences、Modbus、JUnit4、Gradle。

---

### Task 1: 将 fence 精简为可验证的进程边界

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/EmergencyResetRecoveryFence.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/EmergencyResetRecoveryFenceTest.kt`

- [ ] 写失败测试：fence 保存 `originProcessInstanceId`；同进程不能收口；不同进程可以；损坏值 fail closed；重复紧急复位可以覆盖旧 `RESET_WRITTEN` fence。
- [ ] 运行 `./gradlew testDebugUnitTest --tests "*EmergencyResetRecoveryFenceTest"`，确认因缺少进程边界字段/策略而失败。
- [ ] 删除 `MechanicalTaskGenerationStore` 和 generation 匹配逻辑，新增进程实例匹配策略。
- [ ] 重跑定向测试，确认通过。

### Task 2: 在 Modbus 串行边界封禁 999 之后的旧机械命令

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/modbus/VMModbusHelper.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/modbus/EmergencyResetMechanicalWriteGateTest.kt`

- [ ] 写失败测试：进入紧急复位写入边界后，寄存器 200/202 被拒绝，999=1 可写；解除 fence 后恢复普通写入；封禁与写 999 在同一同步方法内完成。
- [ ] 运行定向测试确认 RED。
- [ ] 新增纯 Kotlin `EmergencyResetMechanicalWriteGate`，由 `VMModbusHelper` 的统一写锁调用；新增 `writeEmergencyResetAndBlockMechanicalCommands()`，先封禁再在同一锁内写 999。
- [ ] 重跑定向测试确认 GREEN。

### Task 3: 安排可靠冷重启并证明跨进程

**Files:**
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/EmergencyResetAppRestarter.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/EmergencyResetRestartCoordinatorTest.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/fragments/TestFragment.kt`

- [ ] 写失败测试：只有系统重启任务安排成功才终止进程；安排失败保持当前进程和维护态。
- [ ] 运行定向测试确认 RED。
- [ ] 声明 Android 12 的 `SCHEDULE_EXACT_ALARM` 与 Android 13+ 的 `USE_EXACT_ALARM`；用 `setExactAndAllowWhileIdle` + immutable/update-current 的 `StartInitActivity` PendingIntent 安排重启，Android 12+ 先检查 `canScheduleExactAlarms()`；随机 `AppProcessIdentity.currentId` 标识本次进程。
- [ ] 紧急复位 999 成功后 fence 转 `RESET_WRITTEN`，安排成功才 `Process.killProcess`；失败提示管理员重试，不清 fence。
- [ ] 重跑定向测试确认 GREEN。

### Task 4: 启动期 fence 优先保持维护

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/ui/LoadNetActivity.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/ParallelSupplementOrderEntrySourceTest.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/OrderDeliveryRecoverySourceTest.kt`

- [ ] 写失败测试：fence 存在时远端配置、运行态和库存请求及迟到回调不能覆盖本地 `onlineStatus=3/isEnable=0` 或待核对库存；营业时间纠偏、PowerLossRecoveryGuard、算法恢复、订单最终准入和自动机械准入均不启动。
- [ ] 运行定向测试确认 RED。
- [ ] `MyApp` 在任何远端拉取前恢复 fence；`DataManagementAPI` 的 HTTP 回调和 MQTT 运行控制在共享状态转换锁内完成“fence 检查 + 落地”；`LoadNetActivity` 在锁内发现 fence 时直接跳过 merge；`MainActivity.startRuntimeAfterConfigLoaded` 在 fence 存在时只启动通信/维护态上传，不拉取物理快照、不启动恢复和算法。
- [ ] 删除当前方案中 ResourceLocks generation 回调、claim/session store 通用 mutationAllowed 扩展，保留高层 fence 准入拒绝。
- [ ] 重跑定向测试确认 GREEN。

### Task 5: 紧急复位立即发布维护态并允许重复复位

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/fragments/TestFragment.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/fragments/LowerBoardMaintenancePolicyTest.kt`

- [ ] 写失败测试：每次管理员确认都会创建新 resetId；已有 `RESET_WRITTEN` 不阻止再次 999；本地维护落盘后立即触发现有 MQTT/HTTP 状态发布。
- [ ] 运行定向测试确认 RED。
- [ ] 将准备入口改为 suspend：关闭算法、耐久维护、创建新 fence、触发维护态发布；发布异常记录日志但不阻止 999 安全动作。
- [ ] 重跑定向测试确认 GREEN。

### Task 6: 冷重启后的强制收口

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfig.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/ManualRecoverySourceGuardTest.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/OrderDeliveryRecoverySourceTest.kt`

- [ ] 写失败测试：同一进程拒绝；新进程 + 管理员确认 + 两次完整空闲（含 `201=0`、`203=0`、完整 `300/400` 结果）允许；任一次读取失败或 `203` 非空闲拒绝；原子 commit 只清 moveStatus/claim/session/prefetch/fence；物理和业务事实保留。
- [ ] 运行定向测试确认 RED。
- [ ] 收口策略改用 `originProcessInstanceId != AppProcessIdentity.currentId`，不再比较 generation；提交成功后清残留内存占用，再走现有恢复营业路径。
- [ ] 重跑定向测试确认 GREEN。

### Task 7: 全量验证、独立复审和发布

- [ ] 运行所有新增及受影响定向测试。
- [ ] 运行 `./gradlew test`。
- [ ] 运行 `./gradlew :app:assembleRelease`。
- [ ] 运行 `git diff --check`，确认未提交 `.gradle-user-home/` 和两份 2026-08-10 无关计划。
- [ ] 使用现有 Codex 审核 agent 对 `b6f6814` 到当前工作区独立只读复审；任何 P0/P1 停止发布。
- [ ] 无阻断后升级 `versionName=2.5.38`、`versionCode=288`，重新完整测试和 Release 构建。
- [ ] 精确暂存本次切片，详细中文 commit；快进合入 `develop`，推送并核对远端 SHA。
- [ ] 核对 Release APK package/version/signature/SHA-256，运行 `tools/publish-pgyer.ps1 -SkipBuild` 并核对蒲公英版本。
- [ ] 删除完成的本地/远端 `fix/emergency-reset-recovery`，保留用户无关未跟踪文件。
