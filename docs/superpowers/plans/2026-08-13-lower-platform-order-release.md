# 下平台半故障不阻塞上平台售卖 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让双 lane 补肠在 `in4` 下发前明确失败时隔离下平台并精准释放补肠资源，同时禁止隔离态错误续补。

**Architecture:** 新增纯策略判断双 lane 失败会话是否停在 `in1~in3` 明确终态，并复用现有 sessionId、mechanicalClaimRevision、owner 和 resourceMask 释放路径。故障处理顺序固定为先落盘下平台隔离，再精准收口；收口失败则使用已有 `LOWER_PLATFORM_RECOVERY_REQUIRED` 拒绝新订单。续作入口统一复用运行态门禁，隔离或禁补时不下发任何 `in` 动作。

**Tech Stack:** Kotlin、Android、JUnit4、Gradle

---

### Task 1: 固化 in4 前物理边界和隔离续作门禁

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/LowerPlatformIsolationPolicy.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/LowerPlatformIsolationPolicyTest.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/ParallelSupplementRuntimePauseSourceTest.kt`

- [ ] **Step 1: 写失败测试**

增加 `ParallelSupplementSemiFaultClosurePolicy` 的期望用例：`in1=0x00EE` 和 `in3=0x00F0`（已扣库存、未落盘）允许收口；`in4`、Modbus/Timeout、未观察到本轮执行、owner 不匹配、另一 lane 尚未完成 `in7` 均拒绝。增加源码接线测试，要求 `parallelSupplementRuntimeAvailable()` 同时检查 `lowerPlatformStatus != ISOLATED` 和 `supplementEnabled == YES`。

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.LowerPlatformIsolationPolicyTest" --tests "cn.niuyannet.kaochang.android.services.ParallelSupplementRuntimePauseSourceTest"`

Expected: FAIL，原因是新策略不存在且续作门禁缺少隔离/禁补条件。

- [ ] **Step 3: 最小实现纯策略和门禁**

策略只接受 `AtomicActionResult.Failed(freshExecutionObserved=true)`，失败 lane 必须匹配 action/source/target、停在 `TERMINAL_CONFIRMED` 或 `PHYSICAL_APPLIED`、`panPlacementApplied=false`；DELIVERY 失败要求无 prefetch lane，PREFETCH 失败要求 delivery lane 已完成 `in7` 且物理落盘。运行态门禁增加现有两个字段，不新增配置。

- [ ] **Step 4: 运行测试确认 GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.LowerPlatformIsolationPolicyTest" --tests "cn.niuyannet.kaochang.android.services.ParallelSupplementRuntimePauseSourceTest"`

Expected: PASS。

### Task 2: 接入双 lane 故障精准收口和拒单兜底

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/LowerPlatformRuntimeStateReporter.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/ParallelSupplementPipelineWiringSourceTest.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/LowerPlatformRuntimeStateReporterTest.kt`

- [ ] **Step 1: 写失败测试**

源码接线测试要求 `handlePipelineSupplementFailure` 在落盘隔离后调用半故障收口；精准释放必须传入当前 session 的 mechanicalClaimRevision 和 resourceMask。状态测试要求收口失败时写 `LOWER_PLATFORM_RECOVERY_REQUIRED`，成功时保持空拒单原因。

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.ParallelSupplementPipelineWiringSourceTest" --tests "cn.niuyannet.kaochang.android.services.LowerPlatformRuntimeStateReporterTest"`

Expected: FAIL，原因是双 lane 普通半故障没有收口接线和拒单兜底。

- [ ] **Step 3: 最小接线**

让 `handlePipelineSupplementFailure` 返回是否安全收口。先调用 `markLowerPlatformIsolatedRuntimeState`，再读取当前双 lane session 并通过纯策略校验；通过后调用现有 `releaseSupplementMoveClaimIfOwned`，该方法已按 owner/revision/mask CAS 恢复 `moveStatus=0` 并释放资源。不通过或释放失败时保留全部证据并写现有拒单原因；不直接写 `moveStatus=0`，不调用全局清锁。

- [ ] **Step 4: 运行测试确认 GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.ParallelSupplementPipelineWiringSourceTest" --tests "cn.niuyannet.kaochang.android.services.LowerPlatformRuntimeStateReporterTest"`

Expected: PASS。

### Task 3: 完整验证和独立审核

**Files:**
- Verify only: all changed production/test files

- [ ] **Step 1: 定向回归**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.LowerPlatformIsolationPolicyTest" --tests "cn.niuyannet.kaochang.android.services.ParallelSupplementRuntimePauseSourceTest" --tests "cn.niuyannet.kaochang.android.services.ParallelSupplementPipelineWiringSourceTest" --tests "cn.niuyannet.kaochang.android.services.LowerPlatformRuntimeStateReporterTest"`

Expected: PASS。

- [ ] **Step 2: 完整单元测试与差异检查**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL，0 failed。

Run: `git diff --check`

Expected: 无输出，exit 0。

- [ ] **Step 3: 独立 Codex 审核**

审核范围仅为设计提交后的实现 diff，重点检查：`in4` 物理边界、正常订单暂停不受影响、owner/revision/mask 精准释放、`moveStatus=2` 不被覆盖、失败时拒单且保留证据。最多两轮；任何未解决 P1 均停止提交实现。
