# Business Closeout Minimal Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复营业结束丢弃重新开热和断连竞态，并让维护页现有人工清盘入口只接管营业收口自身残留，之后可继续使用“恢复正常状态”。

**Architecture:** 保持现有营业收口、DiscardJournal 和维护恢复框架不变，只增加三个小边界：营业收口不刷新正温；认领/命令准入读取非阻塞 Modbus master 快照；维护页以 `DISCARDED` 接管 `source=营业结束丢弃` 的既有 journal。全盘清空后仅折叠低并发字段并清 closeout pending，不清故障、不启算法。

**Tech Stack:** Kotlin、Android SharedPreferences、JUnit 4、Gradle Android unit tests

---

## 文件结构

- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPending.kt` — 收口常量和纯策略。
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt` — 丢弃后温控、非阻塞 Modbus 准入、人工 journal 接管入口。
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt` — 人工清盘后的低并发/pending 最小收口。
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt` — 两个人工按钮复用同一营业收口接管函数。
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPendingTest.kt` — 纯策略行为测试。
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutSourceTest.kt` — 关键生产调用点接线守卫。
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutManualTakeoverTest.kt` — 人工接管范围和最终状态测试。

### Task 1: 营业收口丢弃保持三区停热

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPending.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt:9918-9930`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPendingTest.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutSourceTest.kt`

- [ ] **Step 1: 写失败测试**

在 `BusinessCloseoutPendingTest.kt` 增加真实纯函数行为测试：

```kotlin
@Test
fun shouldRefreshTemperatureAfterDiscard_shouldSkipOnlyBusinessCloseout() {
    assertFalse(shouldRefreshTemperatureAfterDiscard(businessCloseout = true))
    assertTrue(shouldRefreshTemperatureAfterDiscard(businessCloseout = false))
}
```

在 `BusinessCloseoutSourceTest.kt` 增加接线断言，限定 `discardSausage` 成功分支必须通过该策略决定是否刷新：

```kotlin
@Test
fun discard_shouldNotRefreshTemperatureForBusinessCloseout() {
    val discard = functionBlock(
        operateSource(),
        "suspend fun discardSausage(",
        "private fun isAutomaticMechanicalCommandStillAllowed("
    )
    assertTrue(discard.contains("shouldRefreshTemperatureAfterDiscard(businessCloseout)"))
    assertFalse(discard.contains("if (result==ActionExecutionResult.SUCCESS){\n            applySuccessfulTakePanState"))
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.BusinessCloseoutPendingTest" --tests "cn.niuyannet.kaochang.android.services.BusinessCloseoutSourceTest"
```

Expected: FAIL，原因是 `shouldRefreshTemperatureAfterDiscard` 尚不存在或生产接线尚未出现。

- [ ] **Step 3: 写最小实现**

在 `BusinessCloseoutPending.kt` 增加：

```kotlin
internal const val BUSINESS_CLOSEOUT_DISCARD_SOURCE = "营业结束丢弃"

internal fun shouldRefreshTemperatureAfterDiscard(businessCloseout: Boolean): Boolean =
    !businessCloseout
```

在 `KaoChangOperate.discardSausage` 成功分支将无条件刷新改为：

```kotlin
if (shouldRefreshTemperatureAfterDiscard(businessCloseout)) {
    refreshTemperatureAfterStablePanMutation("过保丢弃完成清空烤盘：pan=${kaoPan.positionSn}")
}
```

在 `KaoChangAlgorithm` 调用营业收口丢弃时使用 `BUSINESS_CLOSEOUT_DISCARD_SOURCE`，避免恢复入口依赖重复字符串。

- [ ] **Step 4: 运行测试并确认 GREEN**

Run 同 Step 2。

Expected: `BusinessCloseoutPendingTest` 与 `BusinessCloseoutSourceTest` 全部 PASS。

- [ ] **Step 5: 提交切片**

```powershell
git add -- app/src/main/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPending.kt app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPendingTest.kt app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutSourceTest.kt
git commit -m "fix(controller): 营业收口丢弃保持停热"
```

### Task 2: 增加无主动探测的 Modbus 最终准入

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPending.kt:92-108`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt:10001-10021`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPendingTest.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutSourceTest.kt`

- [ ] **Step 1: 写失败测试**

将 `canIssueBusinessCloseoutDiscardCommand` 测试改为显式传入 `modbusMasterAvailable`，并增加不可用用例：

```kotlin
@Test
fun canIssueBusinessCloseoutDiscardCommand_shouldRejectMissingModbusMaster() {
    assertTrue(canIssueBusinessCloseoutDiscardCommand(2, true, 0, 0, 0, false, false, false, true))
    assertFalse(canIssueBusinessCloseoutDiscardCommand(2, true, 0, 0, 0, false, false, false, false))
}
```

在 `BusinessCloseoutSourceTest.kt` 增加：

```kotlin
@Test
fun closeoutDiscardAdmission_shouldUseLocalModbusSnapshotWithoutProbe() {
    val gate = functionBlock(
        operateSource(),
        "private fun isBusinessCloseoutDiscardStillAllowed(",
        "/**\n     * 读取烤盘温度"
    )
    assertTrue(gate.contains("VMModbusHelper.currentDiagnosisSnapshot().connected"))
    assertFalse(gate.contains("VMModbusHelper.connectStatus()"))
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.BusinessCloseoutPendingTest" --tests "cn.niuyannet.kaochang.android.services.BusinessCloseoutSourceTest"
```

Expected: FAIL，原因是策略尚无 `modbusMasterAvailable`，准入函数尚未读取本地快照。

- [ ] **Step 3: 写最小实现**

扩展纯策略：

```kotlin
internal fun canIssueBusinessCloseoutDiscardCommand(
    onlineStatus: Int,
    automaticRestSource: Boolean,
    errorStatus: Int,
    inspectionMode: Int,
    faultHeatPauseActive: Int,
    orderPending: Boolean,
    recoveryBlocked: Boolean,
    lowerPlatformBlocked: Boolean,
    modbusMasterAvailable: Boolean
): Boolean = onlineStatus == 2 &&
    automaticRestSource &&
    errorStatus == 0 &&
    inspectionMode == 0 &&
    faultHeatPauseActive == 0 &&
    !orderPending &&
    !recoveryBlocked &&
    !lowerPlatformBlocked &&
    modbusMasterAvailable
```

在 `isBusinessCloseoutDiscardStillAllowed` 调用时传入：

```kotlin
modbusMasterAvailable = VMModbusHelper.currentDiagnosisSnapshot().connected
```

该函数同时被机械 `claimGate` 和 `COMMAND_INTENT` 准入调用，因此两处都复用同一无 I/O 门禁。

- [ ] **Step 4: 运行测试并确认 GREEN**

Run 同 Step 2。

Expected: 两个测试类全部 PASS；源代码守卫确认锁内没有 `connectStatus()`。

- [ ] **Step 5: 提交切片**

```powershell
git add -- app/src/main/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPending.kt app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPendingTest.kt app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutSourceTest.kt
git commit -m "fix(controller): 收紧营业收口断连准入"
```

### Task 3: 维护页定向接管营业收口 journal

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPending.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt:1310-1495`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt:1401-1624`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutManualTakeoverTest.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutSourceTest.kt`

- [ ] **Step 1: 写失败测试**

新增纯范围判断：

```kotlin
class BusinessCloseoutManualTakeoverTest {
    @Test
    fun policy_shouldAllowOnlyBusinessCloseoutJournal() {
        assertTrue(canManuallyTakeOverBusinessCloseoutDiscard(
            DiscardJournal("owner", 7, DiscardCheckpoint.COMMAND_INTENT, 1L, source = BUSINESS_CLOSEOUT_DISCARD_SOURCE)
        ))
        assertFalse(canManuallyTakeOverBusinessCloseoutDiscard(
            DiscardJournal("owner", 7, DiscardCheckpoint.COMMAND_INTENT, 1L, source = "过保到期")
        ))
        assertFalse(canManuallyTakeOverBusinessCloseoutDiscard(null))
    }
}
```

在 `BusinessCloseoutSourceTest.kt` 断言两个维护入口都在等待空闲前调用同一 suspend 接管函数，且接管函数固定传入 `DiscardPhysicalResult.DISCARDED`，不修改 `confirmDiscardJournalRecovery` 的通用策略。

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.BusinessCloseoutManualTakeoverTest" --tests "cn.niuyannet.kaochang.android.services.BusinessCloseoutSourceTest"
```

Expected: FAIL，原因是范围策略和维护入口接管函数尚不存在。

- [ ] **Step 3: 写最小纯策略和服务入口**

在 `BusinessCloseoutPending.kt` 增加：

```kotlin
internal fun canManuallyTakeOverBusinessCloseoutDiscard(journal: DiscardJournal?): Boolean =
    journal?.source == BUSINESS_CLOSEOUT_DISCARD_SOURCE
```

在 `KaoChangOperate` 增加仅供维护页调用的入口：

```kotlin
suspend fun takeOverBusinessCloseoutDiscardForManualPanClear(reason: String): DiscardJournalRecoveryResult {
    val journal = pendingDiscardJournalForRecovery()
        ?: return DiscardJournalRecoveryResult(true, "没有营业收口丢弃残留")
    if (!canManuallyTakeOverBusinessCloseoutDiscard(journal)) {
        return DiscardJournalRecoveryResult(false, "存在非营业收口丢弃残留，拒绝自动接管", journal.positionSn)
    }
    return confirmDiscardJournalRecovery(DiscardPhysicalResult.DISCARDED, reason)
}
```

- [ ] **Step 4: 接入两个维护按钮**

在 `MaintenanceFragment` 增加一个 suspend 包装函数，通过 `Dispatchers.IO` 调用服务入口；失败时提示并停止本轮。`performClearAll` 与 `performDiscardAndResetPan` 都在 `waitUntilMachineIdleOrTimeout` 前调用它，不新增 Job 或循环：

```kotlin
private suspend fun takeOverBusinessCloseoutDiscardIfNeeded(reason: String): Boolean {
    val result = withContext(Dispatchers.IO) {
        KaoChangOperate.takeOverBusinessCloseoutDiscardForManualPanClear(reason)
    }
    if (!result.ok) {
        ToastUtils.showLong(result.reason)
    }
    return result.ok
}
```

- [ ] **Step 5: 运行测试并确认 GREEN**

Run 同 Step 2。

Expected: 两个测试类全部 PASS，非营业收口 journal 仍走原人工恢复流程。

- [ ] **Step 6: 提交切片**

```powershell
git add -- app/src/main/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPending.kt app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutManualTakeoverTest.kt app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutSourceTest.kt
git commit -m "fix(controller): 允许人工接管营业收口残留"
```

### Task 4: 人工清盘后折叠低并发并解除 pending

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPending.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutManualTakeoverTest.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutSourceTest.kt`

- [ ] **Step 1: 写失败测试**

增加纯状态折叠测试，明确不清故障、不改维护态、不启算法：

```kotlin
@Test
fun manualPanClear_shouldFoldOnlyConcurrencyFields() {
    val config = AppConfigBean().apply {
        modeType = 2
        transitionMode = 2
        highConcurrencyResidualDrain = 1
        onlineStatus = 3
        errorStatus = 2
        isEnable = 0
    }

    foldBusinessCloseoutAfterManualPanClear(config)

    assertEquals(1, config.modeType)
    assertEquals(0, config.transitionMode)
    assertEquals(0, config.highConcurrencyResidualDrain)
    assertEquals(3, config.onlineStatus)
    assertEquals(2, config.errorStatus)
    assertEquals(0, config.isEnable)
}
```

源代码守卫要求两个维护清盘成功路径都调用 `KaoChangAlgorithm.finalizeBusinessCloseoutAfterManualPanClear()`，并且调用发生在 `KaoPanHelper.clearKaoPan()` 之后。

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.BusinessCloseoutManualTakeoverTest" --tests "cn.niuyannet.kaochang.android.services.BusinessCloseoutSourceTest"
```

Expected: FAIL，原因是纯折叠函数和最终化入口尚不存在。

- [ ] **Step 3: 写最小实现**

在 `BusinessCloseoutPending.kt` 增加：

```kotlin
internal fun foldBusinessCloseoutAfterManualPanClear(config: AppConfigBean) {
    config.modeType = 1
    config.transitionMode = 0
    config.highConcurrencyResidualDrain = 0
}
```

在 `KaoChangAlgorithm` 增加：

```kotlin
fun finalizeBusinessCloseoutAfterManualPanClear(): Boolean {
    if (!businessCloseoutPendingRepository.isPending()) return true
    if (KaoPanHelper.getKaoPanList().any { it.isHasSausage }) return false
    val config = AppConfig.getAppConfig()
    val previousModeType = config.modeType
    val previousTransitionMode = config.transitionMode
    val previousResidualDrain = config.highConcurrencyResidualDrain
    foldBusinessCloseoutAfterManualPanClear(config)
    val configSaved = runCatching {
        AppConfig.saveAppConfigDurable(config)
        true
    }.getOrElse {
        config.modeType = previousModeType
        config.transitionMode = previousTransitionMode
        config.highConcurrencyResidualDrain = previousResidualDrain
        false
    }
    if (!configSaved) return false
    if (!businessCloseoutPendingRepository.clearDurably()) return false
    businessCloseoutHeatingPausedInProcess = false
    return true
}
```

配置落盘失败恢复内存旧值；pending 清理失败则保留已落盘的安全低并发配置并返回 false，允许再次点击幂等完成。

- [ ] **Step 4: 接入两个清盘成功路径**

在两处 `KaoPanHelper.clearKaoPan()`、`KaoPanHelper.init()` 和停热成功后调用最终化入口。返回 false 时保持维护态、提示“营业收口状态未清，请再次点击”，不执行“恢复正常状态”。

- [ ] **Step 5: 运行测试并确认 GREEN**

Run 同 Step 2。

Expected: 全部 PASS；测试证明只折叠并发字段，故障、维护态和算法关闭保持不变。

- [ ] **Step 6: 提交切片**

```powershell
git add -- app/src/main/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPending.kt app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutManualTakeoverTest.kt app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutSourceTest.kt
git commit -m "fix(controller): 人工清盘收口低并发状态"
```

### Task 5: 组合验证

**Files:**
- Verify only; no new production scope.

- [ ] **Step 1: 运行全部针对性测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.BusinessCloseoutPendingTest" --tests "cn.niuyannet.kaochang.android.services.BusinessCloseoutSourceTest" --tests "cn.niuyannet.kaochang.android.services.BusinessCloseoutManualTakeoverTest" --tests "cn.niuyannet.kaochang.android.services.DiscardJournalTest" --tests "cn.niuyannet.kaochang.android.services.ManualPanResetRuntimeStateHelperTest"
```

Expected: BUILD SUCCESSFUL，所列测试 0 failures。

- [ ] **Step 2: 运行 Debug 全量单测**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL，所有 Debug unit tests 0 failures。

- [ ] **Step 3: 运行 Release 全量单测**

```powershell
.\gradlew.bat :app:testReleaseUnitTest
```

Expected: BUILD SUCCESSFUL，所有 Release unit tests 0 failures。

- [ ] **Step 4: 核对范围和工作树**

```powershell
git diff --check 7bb26026..HEAD
git diff --stat 7bb26026..HEAD
git status --short
```

Expected: `diff --check` 无输出；只有计划列出的生产/测试文件变化；工作树为空。

- [ ] **Step 5: 审核交付边界**

确认没有新增线程、协程作用域、Timer、Executor、锁、主动 Modbus 轮询或后端改动。记录 APK、真机、RS485/Modbus 时序和机械动作未验证，不合入 `develop`、不推送、不发布。
