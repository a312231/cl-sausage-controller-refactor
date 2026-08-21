# 17:42 动作恢复误维护修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复补肠恢复扫描与新订单并发时误入维护、清空活动 `moveStatus=2`，以及 `out4` 已确认后履约终态倒退的问题。

**Architecture:** 保持 Android 单仓和现有 `faultHeatTransitionLock`、session/revision、机械 owner/CAS 体系。将恢复结果从 Boolean/统一维护拆成可延期与真故障两类；扫描前后比较动作会话身份和检查点；维护收口检测活动 OUT/订单 owner 时保留忙态；履约结果与资源收口结果分离，确保 `out4` 确认后不再发送失败终态。

**Tech Stack:** Kotlin、Android、JUnit4、kotlinx.coroutines、Gradle 8.7。

---

### Task 1：恢复扫描跨越新 OUT 会话时返回延期

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/ActionGroupRecoveryBootstrap.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/ActionGroupRecoveryBootstrapTest.kt`

- [ ] 添加失败测试：FakeClient 顺序读取寄存器期间把 store 从无会话切换为新的 `OUT_SELL`；断言结果为 `DeferredActiveOperation`，不释放资源、不清 session。
- [ ] 运行 `ActionGroupRecoveryBootstrapTest`，确认因缺少 typed defer 或仍返回 `MaintenanceRequired` 而失败。
- [ ] 新增 `ActionGroupRecoveryResult.DeferredActiveOperation(reason)`；扫描前后读取 session evidence，sessionId/revision/checkpoint/type/resourceMask 变化时返回延期。
- [ ] 对活动 OUT 的 guard 拒绝返回延期；读取失败、真实错误码、非连续残留继续返回 `MaintenanceRequired`。
- [ ] 重跑测试并确认通过。

### Task 2：补肠入口正确消费延期结果

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/ActionGroupRecoveryOutcomePolicyTest.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/ActionGroupModbusRecoveryLockingSourceTest.kt`

- [ ] 添加失败测试：补肠恢复期间出现 order claim、order pause、`moveStatus=2` 或活动 OUT 时，策略返回 `DEFER` 而不是维护。
- [ ] 运行聚焦测试，确认失败原因是现有 Boolean API 丢失延期语义。
- [ ] 引入最小纯策略 `ActionGroupRecoveryOutcomePolicy`，区分 `READY/DEFER/MAINTENANCE`；恢复前后在 `faultHeatTransitionLock` 内重读 claim/pause/onlineStatus/moveStatus/session，Modbus IO 不持锁。
- [ ] 补肠预取收到 `DEFER` 时仅结束本轮、不得标记恢复成功、不得继续启动批次；其他恢复入口保持 fail closed。
- [ ] 重跑聚焦测试并确认通过。

### Task 3：维护收口不得清除活动 OUT 忙态

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/ActionGroupMaintenanceMoveStatusPolicyTest.kt`

- [ ] 添加失败测试：存在 order claim、CLAIMED `OUT_SELL/OUT_MANUAL` session 或匹配 resource owner 时，维护策略必须保留 `moveStatus=2`；无 owner 的真实残留按既有规则处理。
- [ ] 运行测试并观察 RED。
- [ ] 新增纯策略并在 `enterActionGroupMaintenance` 的同一 `faultHeatTransitionLock` 临界区内读取证据、决定是否保留；证据读取损坏时 fail closed 保留。
- [ ] 发布状态和 UI 刷新保持在锁外，不增加全局锁。
- [ ] 重跑测试并确认通过。

### Task 4：out4 履约终态不可倒退

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt`
- Modify as needed: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/ActionGroupOrderStatus.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/ui/OrderDeliveryTerminalMonotonicityPolicyTest.kt`

- [ ] 添加失败测试：`out4Confirmed=true` 且 owner/resource 收口失败时，单根履约仍保持完成，设备收口结果单独标记未知，不产生 status=5。
- [ ] 运行测试并观察 RED。
- [ ] 新增最小结构化结果或纯策略，将 `deliveryConfirmed` 与 `mechanicalCloseoutOk` 分开；`out4` 已确认后锁存维护可以继续，但订单事件不得由2倒退到5。
- [ ] 对未到 out4 的真实机械失败继续发送 status=5；多根订单在已交付根数与后续停止执行之间保持准确计数。
- [ ] 重跑测试并确认通过。

### Task 5：强制验证与独立审核

**Files:**
- Review: all modified production and test files

- [ ] 运行全部新增和相关 actiongroup/order 聚焦测试。
- [ ] 运行 `./gradlew.bat test --rerun-tasks`，读取 XML 统计测试数、failure、error、skipped。
- [ ] 运行 `git diff --check`、`git status --short`，核对仅包含本切片文件。
- [ ] 新建独立 Codex 会话只读审核 diff、根因覆盖、TDD 证据与未验证边界。
- [ ] 修复所有 P0/P1 后重跑相同验证；停在 `READY_FOR_INDEPENDENT_REVIEW`，不升级版本、不提交推送、不发布。

## 反模式门禁

- 不通过简单移动 `shouldPauseSupplementForOrder` 一行来声称根治 TOCTOU。
- 不弱化 guarded 门禁，不把未知物理状态当成功。
- 不全局清锁，不新增跨域全局锁，不直接无 owner 写 `moveStatus=0`。
- 不把11:57 MQTT ACK、10:51 下位机复位稳定性、14:27 并发模式语义带入本分支。
- 不把 JVM 测试当作 RS485、下位机、机械或现场验证。
