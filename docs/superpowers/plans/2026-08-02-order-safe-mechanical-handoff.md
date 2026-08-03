# Order Safe Mechanical Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复订单到达时补肠安全交接，并让订单结果、自动搬盘、自动丢弃和自清洁遵守统一的安全门禁。

**Architecture:** 保留现有 `moveStatus + owner + ResourceLocks + durable session/journal` 架构，复用 `faultHeatTransitionLock` 将订单暂停标记与自动命令 `COMMAND_INTENT` 线性化。锁内不做 Modbus、延时、资源锁申请或终态等待；实际机械终态仍由现有 runner、203 结果和 journal 负责。

**Tech Stack:** Kotlin、Java、Android 12、Kotlin coroutines、JUnit 4、Gradle 8.7。

---

### Task 1: 双 lane 控制器默认开启

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfigBean.java`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfig.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/net/RemoteRuntimeConfigPatchApplier.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/init/AppConfigParallelPrefetchTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun newInstall_shouldEnableParallelSupplementPrefetch() {
    assertEquals(1, AppConfigBean().enableParallelSupplementPrefetch)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests cn.niuyannet.kaochang.android.init.AppConfigParallelPrefetchTest`
Expected: FAIL because the current local default is `0`.

- [ ] **Step 3: Write minimal implementation**

Set `enableParallelSupplementPrefetch = 1`; only explicit integer `0/1` from the complete remote payload may override it. Missing or invalid values keep the current value.

- [ ] **Step 4: Run test to verify it passes**

Run the same filtered test. Expected: PASS.

### Task 2: Close nonparallel `Paused("in7")` as a completed root

**Files:**
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementPipelineOrderCloseoutPolicy.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementPipelineOrderCloseoutPolicyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun serialIn7Pause_withCompletedRootAndDurableState_shouldReleaseForOrder() {
    assertEquals(
        SupplementPipelineOrderCloseout.COMPLETED_ROOT,
        SupplementPipelineOrderCloseoutPolicy.classify(
            parallelEnabled = false,
            pausedAfterAction = "in7",
            actionGroupCompleted = true,
            durableStateSaved = true
        )
    )
}
```

Also cover `in1~in3 -> PAUSED_ROOT` and incomplete/non-durable `in7 -> RETAIN`.

- [ ] **Step 2: Run test to verify it fails**

Expected: compilation failure because the policy does not exist.

- [ ] **Step 3: Write minimal implementation**

Classify only a nonparallel, completed, durably saved `Paused("in7")` as `COMPLETED_ROOT`. Wire this decision into the existing nonparallel owner release branch; never save `in7` as a resumable checkpoint and never replay A.

- [ ] **Step 4: Run targeted policy and runner tests**

Run the new policy test plus `InActionGroupPrefetchPipelineRunnerTest`. Expected: PASS.

### Task 3: Resume supplement only after successful whole order

**Files:**
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/OrderSupplementResumePolicy.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/OrderSupplementResumePolicyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun onlySuccessfulOrder_shouldResumeSupplement() {
    assertTrue(OrderSupplementResumePolicy.shouldResume(OrderExecutionOutcome.SUCCESS))
    assertFalse(OrderSupplementResumePolicy.shouldResume(OrderExecutionOutcome.FAILED))
    assertFalse(OrderSupplementResumePolicy.shouldResume(OrderExecutionOutcome.UNKNOWN))
}
```

- [ ] **Step 2: Run test to verify it fails**

Expected: compilation failure because the outcome policy does not exist.

- [ ] **Step 3: Write minimal implementation**

Track `UNKNOWN` initially, assign `SUCCESS` only when `deliveredTotal == totalCount`, assign `FAILED` for a normal incomplete order, and call `clearSupplementPauseAndResume` only for `SUCCESS`. For `FAILED/UNKNOWN`, keep the pause/evidence and ensure `onlineStatus=3`.

- [ ] **Step 4: Run targeted order tests**

Run the new policy test, order source tests, resume planner tests, and mechanical claim tests. Expected: PASS.

### Task 4: Linearize automatic pan-move/discard command admission and gate self-clean starts

**Files:**
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/AutomaticActionStartPolicies.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/AutomaticActionStartPoliciesTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun selfClean_shouldRejectOperatingAndDeliveringStates() { /* onlineStatus 1 and 4 are false */ }

@Test
fun automaticDiscard_shouldRecheckAutoDispatchBeforeEveryPan() { /* order/maintenance state is false */ }
```

- [ ] **Step 2: Run tests to verify they fail**

Expected: compilation failure because the policies do not exist.

- [ ] **Step 3: Write minimal implementation**

Self-clean may start only when `onlineStatus` is neither operating nor delivering, no fault/order/mechanical action is active, and `moveStatus=0`. Automatic discard uses `AutoMechanicalDispatchPolicy`; maintenance reset passes an explicit manual override.

- [ ] **Step 4: Run targeted tests**

Run automatic dispatch, discard loop, self-clean policy, and maintenance reset tests. Expected: PASS.

### Task 5: Full verification and review

- [ ] Run `./gradlew :app:testDebugUnitTest --rerun-tasks --no-daemon` and verify zero failures/errors.
- [ ] Run `./gradlew :app:assembleDebug --rerun-tasks --no-daemon` and verify `BUILD SUCCESSFUL`.
- [ ] Run `git diff --check`.
- [ ] Inspect APK package, version, size, and SHA-256.
- [ ] Dispatch an independent Codex sub-agent for read-only diff review; any unresolved P1 blocks merge/release.
- [ ] Do not commit, push, publish, install, or operate hardware without separate authorization.
