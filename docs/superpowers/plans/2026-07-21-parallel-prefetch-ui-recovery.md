# 双 lane 补肠预取与待补肠 UI 恢复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development and superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 恢复 recovery blocked 下 durable “待补肠/待回搬”UI，并在默认关闭的灰度开关下安全恢复真正双 lane 补肠预取。

**Architecture:** UI 使用独立只读 durable intent accessor，调度 accessor 保持恢复门禁。双 lane 使用独立耐久会话、订单 barrier、物理单写者和专用恢复器；当前 `2.5.0` 的孤儿回搬、非需求空位和九目标门禁保持不变。

**Tech Stack:** Kotlin、Java、Android SharedPreferences、Kotlin Coroutines、JUnit 4、Gradle 8.7。

---

## 执行边界

- 切片一独立完成、验证和审核后才能开始切片二。
- 不整提交 cherry-pick `bf61fa8` 或覆盖 `KaoChangOperate.kt`、`MaintenanceFragment.kt`。
- `enableParallelSupplementPrefetch` 默认值必须是 `0`。
- 本计划不授权 commit、push、升版、APK、蒲公英或 ADB；这些步骤只能在用户再次明确授权后执行。

### Task 1: recovery blocked 下恢复 durable UI 标记

**Files:**
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/KaoPanUiReservationStateBuilderTest.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/fragments/MaintenanceLowerPlatformUiSourceTest.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoPanUiReservationStateBuilder.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt`

- [ ] **Step 1: 将旧“恢复阻塞时隐藏未完成目标”测试改为新契约**

在 `KaoPanUiReservationStateBuilderTest` 将 `apply_shouldSuppressReservationsWhileMoveRecoveryIsBlocked` 改名为 `apply_shouldKeepPendingSupplementVisibleWhileRecoveryIsBlocked`，保持一个空目标盘和一个未完成 intent，调用：

```kotlin
KaoPanUiReservationStateBuilder.apply(pans, intent, recoveryBlocked = true)
assertEquals(
    KaoPan.UI_RESERVATION_WAIT_SUPPLEMENT,
    pans.first { it.positionSn == 13 }.uiReservationState
)
assertEquals("A", pans.first { it.positionSn == 13 }.uiReservationTaste?.tasteCode)
```

- [ ] **Step 2: 添加维护页只读 accessor 源码门禁测试**

在 `MaintenanceLowerPlatformUiSourceTest` 添加：

```kotlin
@Test
fun maintenanceGrid_shouldUseDurableIntentWithoutOpeningSchedulerGate() {
    val fragment = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
    val refresh = extractFunctionBody(fragment, "private fun refreshKaoPanGrid()")
    val operate = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt")
    val accessor = extractFunctionBody(operate, "fun maintenanceKaoPanIntentForUi()")
    val schedulerAccessor = extractFunctionBody(operate, "fun activeSupplementBatchIntentForUi()")

    assertTrue(refresh.contains("maintenanceKaoPanIntentForUi()"))
    assertFalse(refresh.contains("completedHeatReturnIntentForUi()"))
    assertTrue(accessor.contains("supplementBatchIntentRepository.loadActive()"))
    assertFalse(accessor.contains("isActionGroupRecoveryBlocked()"))
    assertTrue(schedulerAccessor.contains("isActionGroupRecoveryBlocked()"))
}
```

- [ ] **Step 3: 运行 RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.KaoPanUiReservationStateBuilderTest' --tests 'cn.niuyannet.kaochang.android.fragments.MaintenanceLowerPlatformUiSourceTest' --console=plain
```

Expected: FAIL；builder 仍将未完成目标过滤为 `NONE`，且新 accessor 不存在。

- [ ] **Step 4: 最小实现**

在 `KaoChangOperate` 添加精确只读入口：

```kotlin
fun maintenanceKaoPanIntentForUi(): SupplementBatchIntent? =
    supplementBatchIntentRepository.loadActive()
```

在 `KaoPanUiReservationStateBuilder.apply` 删除 recovery blocked 下裁剪为 completed-only 的分支，始终调用：

```kotlin
applyActiveIntent(pans, activeIntent)
return pans
```

保留 `recoveryBlocked` 参数以避免扩大调用方改动，并用 `@Suppress("UNUSED_PARAMETER")` 标明它只为接口兼容存在。不得改 `activeSupplementBatchIntentForUi()`。

在 `MaintenanceFragment.refreshKaoPanGrid()` 使用：

```kotlin
activeIntent = KaoChangOperate.maintenanceKaoPanIntentForUi()
```

继续传递 `recoveryBlocked`，不得在 UI 层修改 intent。

- [ ] **Step 5: 运行 GREEN 和切片一全量验证**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.KaoPanUiReservationStateBuilderTest' --tests 'cn.niuyannet.kaochang.android.fragments.MaintenanceLowerPlatformUiSourceTest' --console=plain
.\gradlew.bat :app:testDebugUnitTest --console=plain
git diff --check
```

Expected: 定向测试和 Debug 全量测试均 PASS，`git diff --check` 无输出。

- [ ] **Step 6: 独立审核**

fresh Codex 只读检查：recovery blocked 仅影响机械续作；UI 仍按 durable intent 和真实盘位过滤；调度 accessor 未放开。若有 P1，停在切片一修复；无 P1 才进入 Task 2。

### Task 2: 动作 RUNNING 确认与双 lane runner

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/AtomicActionCore.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/ActionGroupRunners.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/InActionGroupPrefetchPipelineRunner.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/AtomicActionExecutorTest.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/ActionGroupRunnerTest.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/InActionGroupPrefetchPipelineRunnerTest.kt`

- [ ] **Step 1: RED 测试**

添加并运行：

```text
AtomicActionExecutorTest.execute_shouldInvokeRunningConfirmedExactlyOnce
AtomicActionExecutorTest.execute_shouldNotInvokeRunningConfirmedForFastCompletionWithoutRunning
InActionGroupPrefetchPipelineRunnerTest.parallelPipeline_shouldStartNextPrefetchOnlyAfterCurrentIn6RunningConfirmed
InActionGroupPrefetchPipelineRunnerTest.parallelPipeline_shouldFallbackToStartingNextPrefetchAfterIn6SuccessWithoutRunningSignal
InActionGroupPrefetchPipelineRunnerTest.parallelPipeline_shouldNeverDispatchNextIn4BeforeCurrentIn7Completes
```

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionExecutorTest' --tests 'cn.niuyannet.kaochang.android.services.actiongroup.InActionGroupPrefetchPipelineRunnerTest' --console=plain
```

Expected: FAIL；当前 API 无 `onRunningConfirmed`，runner 在 `in7` 后才启动下一根。

- [ ] **Step 2: 最小实现并 GREEN**

逐方法参考 `842b85c`、`86e6e71`、`6a8ff2a` 与 `3b28e2b`，只移植 `onRunningConfirmed` 严格一次回调、`parallelEnabled`、lane role 回调、`supervisorScope`、prefetch 停止标记、promotion、物理回写回调和 `in4` barrier。保留当前候选换箱、pause reason、异常转换和串行 fallback。关闭开关时的动作序列必须与 `a7192da` 相同。

### Task 3: durable 双 lane 会话和物理单写者

**Files:**
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementPrefetchPipelineSession.kt`
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/SharedPreferenceSupplementPrefetchSessionStore.kt`
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementPrefetchPhysicalStateCoordinator.kt`
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/recovery/DurableEvidenceReadResult.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementPrefetchSessionRepositoryTest.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementPrefetchPhysicalStateCoordinatorTest.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/recovery/DurableEvidenceCodecTest.kt`

- [ ] **Step 1: RED 测试**

覆盖 revision 并发更新、生命周期单向、订单 barrier 竞争、候选换箱、损坏 JSON fail closed、durable 写失败、重复 `in2/in7` 幂等和物理保存失败保留 session。

Run RED/GREEN:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchSessionRepositoryTest' --tests 'cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchPhysicalStateCoordinatorTest' --tests 'cn.niuyannet.kaochang.android.services.recovery.DurableEvidenceCodecTest' --console=plain
```

- [ ] **Step 2: 最小实现并 GREEN**

逐类参考 `6a8ff2a`、`3b28e2b` 和 `bf61fa8` 的最终版本，只复制独立类及 tri-state durable 读取；不得复制其对 `SupplementBatchIntent`、孤儿回搬或 `LowToHighDemandGate` 的修改。

### Task 4: 订单 barrier、专用恢复和资源互斥

**Files:**
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/AtomicExistingActionMonitor.kt`
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementPrefetchRecoveryCoordinator.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/AtomicExistingActionMonitorTest.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementPrefetchRecoveryCoordinatorTest.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementPrefetchResumePlannerTest.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementPrefetchRecoveryWiringSourceTest.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/ParallelSupplementOrderEntrySourceTest.kt`
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementPipelineFailureRetentionPolicy.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/ResourceLocks.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementPauseController.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementRecoverySafetySourceTest.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/ActionGroupRecoveryBootstrapTest.kt`

- [ ] **Step 1: RED 测试**

覆盖订单在下一根 `in1/in2/in3` 到达、合法双 RUNNING、非法组合、快照不稳定、缺失 RUNNING 证据、活动 session 阻止通用恢复/搬盘/空闲归零，以及 `a2b5a58` 的串行 `in7` 安全暂停。

- [ ] **Step 2: 最小实现并 GREEN**

逐类参考 `b0761aa`、`3b28e2b`、`2f44b72`、`bf61fa8`、`a2b5a58`；在当前 `KaoChangOperate` 中按方法接线，不覆盖 slice1/slice2 现有逻辑。专用恢复必须先于 `ActionGroupRecoveryBootstrap`。`MainActivity` 和 `OrderActivity` 统一调用 `KaoChangOperate.requestSupplementPauseForOrder(orderNo)` 与 `clearSupplementPauseAndResume(orderNo, reason)`，不得继续直接写 `SupplementPauseController`。`SupplementPipelineFailureRetentionPolicy` 在失败、取消或恢复失败时保留 session、资源锁和 `moveStatus`，直到专用恢复器确认安全终态。

### Task 5: 并行 E5 箱级收口和灰度配置

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/LowerPlatformIsolationPolicy.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfig.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfigBean.java`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/net/RemoteRuntimeConfigPatchApplier.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/LowerPlatformIsolationPolicyTest.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/init/AppConfigParallelPrefetchTest.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/net/RemoteRuntimeConfigPatchApplierTest.kt`

- [ ] **Step 1: RED 测试**

添加：失败结果必须携带 `laneRole`；并行普通 E5 不因 `hasPrefetchedNext` 升级整机维护；prefetch lane 在 `in2` 明确返回 `0x00E5` 且没有扣箱不确定性时，先隔离故障箱并记入 failed candidates，再原子更新同一 lane 的 sourceBoxNo、候选游标和库存快照后换同口味候选；同口味候选耗尽时将当前 target 标记 `skippedBySupplyDegrade`，因此该 target 不再显示 `WAIT_SUPPLEMENT`，但其他未完成 target 的 UI 保留；清理已到安全终态的失败 lane/session并释放补肠锁、恢复可调度 `moveStatus`，允许算法规划其他口味。delivery 已完成而 prefetch E5 耗尽时先完成 delivery 的 `in7` 物理收口，再跳过失败 prefetch target，不 promotion 该 lane。只有危险升降台错误、Modbus 断连、终态不明确、扣箱/落盘不确定或证据不一致才保留 session/锁并进入维护；开关缺省及非法远端值均为 `0`。

- [ ] **Step 2: 最小实现并 GREEN**

把 `hasPrefetchedNext` 从整机维护判据改为 lane 收口输入，不再把它本身当整机维护条件。明确 E5 安全终态下候选耗尽时，当前 target 以 `skippedBySupplyDegrade` 收口，清理安全终态 lane/session、释放补肠锁并恢复可调度 `moveStatus`；其他口味和其他未完成 durable target 继续调度及显示。只有 Modbus、终态或物理证据不确定时才保留 session/锁并维护。保留危险终端错误、Modbus 失败和证据不一致门禁。配置字段精确为：

```java
public int enableParallelSupplementPrefetch = 0;
```

只有 `enableActionGroupPrefetch == 1 && enableParallelSupplementPrefetch == 1` 才启用真并行；活动 session 不受远端关开关影响。

### Task 6: 最终验证与独立审核

- [ ] **Step 1: 定向测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.actiongroup.InActionGroupPrefetchPipelineRunnerTest' --tests 'cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchSessionRepositoryTest' --tests 'cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchPhysicalStateCoordinatorTest' --tests 'cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchRecoveryCoordinatorTest' --tests 'cn.niuyannet.kaochang.android.services.LowerPlatformIsolationPolicyTest' --tests 'cn.niuyannet.kaochang.android.services.KaoPanUiReservationStateBuilderTest' --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionExecutorTest' --tests 'cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRunnerTest' --tests 'cn.niuyannet.kaochang.android.services.actiongroup.SupplementPrefetchResumePlannerTest' --tests 'cn.niuyannet.kaochang.android.services.ParallelSupplementOrderEntrySourceTest' --tests 'cn.niuyannet.kaochang.android.services.SupplementPrefetchRecoveryWiringSourceTest' --tests 'cn.niuyannet.kaochang.android.services.SupplementRecoverySafetySourceTest' --tests 'cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupRecoveryBootstrapTest' --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.recovery.DurableEvidenceCodecTest' --tests 'cn.niuyannet.kaochang.android.init.AppConfigParallelPrefetchTest' --tests 'cn.niuyannet.kaochang.android.net.RemoteRuntimeConfigPatchApplierTest' --tests 'cn.niuyannet.kaochang.android.fragments.MaintenanceLowerPlatformUiSourceTest' --console=plain
```

- [ ] **Step 2: 全量与构建**

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat test --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
git diff --check
```

- [ ] **Step 3: 独立 Codex 只读审核**

结论只能为 Approve 或列出 P0/P1/P2。P1 未关闭不得提交、发布或真机灰度。

- [ ] **Step 4: 用户授权门禁**

只有用户明确授权后才能提交、push、升版、生成正式 APK、上传蒲公英或通过 ADB 安装。
