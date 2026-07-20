# 烤肠调度稳定性最小修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不重写调度算法、不扩展支付或 MQTT 的前提下，恢复补热回搬意图和 UI 的可恢复性，并让初始化/库存异常产生的空位不再误触发低转高，同时禁止正常高并发批次以少于九根启动。

**Architecture:** 第一切片把“补热区实际存在但原 intent 不存在”作为人工确认后的显式重绑定流程：纯 planner 只按当前安全空位稳定配对，repository 先耐久保存逐根 binding，UI 再从 binding 投影“待回补”。第二切片把低转高门禁的非需求空位集合耐久化，空位产生时记录原因、填入后清除；正常售卖空位不记录为 blocked。初始化和库存/动作故障仍由现有批次 intent 负责续补，低转高过渡及高并发稳态在调用规划器后统一要求完整九根和九个不同回搬目标。

**Tech Stack:** Kotlin、Java 11、Android SharedPreferences、Fastjson、JUnit 4、现有 `KaoPanHelper`/`SupplementBatchIntentRepository`/`LowToHighDemandGate` 调度链；不增加后端、支付、MQTT、双 lane 或真机动作协议。

---

## 约束、边界和交付判定

- 本计划只覆盖两个可独立审核的切片；不得顺手实施 E5 箱隔离、订单 ACK、退款、durable outbox、双 lane 并行或后端改动。
- 本轮只写计划文档，不执行任何步骤、不运行 Gradle、不修改业务代码、不提交、不部署、不调用 Claude。
- 生产规则以 `docs/sausage-scheduling-rules.md` 为行为基线；其中“回搬绑定用于容量规划，不自动等于非需求空位”的澄清必须保留。
- 切片一退出标准：intent 丢失时没有自动猜历史目标；运维明确确认后可稳定建立并持久化逐根回搬绑定；重启后 UI 仍显示待回补；缺口或口味未知时不产生可售状态。
- 切片二退出标准：初始化/库存/动作异常造成的空位不进入低转高有效需求；正常售卖空位仍能在 13~18 活动批次存在时触发低转高；所有正常新建高并发批次都必须有 25~33 九个目标和九个不同回搬目标。
- 任何实现、commit、APK、上传蒲公英、ADB 或真机验证都必须另行获得用户明确授权；计划批准不等于实施授权。

## Phase 0：当前代码事实和允许复用的模式

### 已核对的生产符号

- `SupplementBatchIntent.kt:18-48` 已有逐根字段 `positionSn`、`tasteCode`、`returnPositionSn`、完成/供给跳过/故障超时状态以及 `originBatchId`/`originBatchType`；持久化 key 为 `sp_supplement_batch_intent_json`，`saveDurable` 使用 `PreferenceUtils.saveStringPreferenceDurable`。
- `SupplementReturnBindingResolver.kt:28-70` 先使用 intent 精确回搬，`allowLegacyFallback=true` 时才会在“唯一同口味空位”条件下自动猜目标；该兜底与本方案不相容，三个生产调用点在 `LowCurrentTrafficMode.kt:568-575`、`HighCurrentTrafficMode.kt:1231`、`HighCurrentTrafficMode.kt:1398`。
- `KaoPanUiReservationStateBuilder.kt:8-23,25-69` 只从 active intent 派生 transient 的 `KaoPan.uiReservationState/uiReservationTaste`；无 intent 时前区不会显示待回补。
- `MaintenanceFragment.kt:264-302,996-1060` 已有维护状态刷新和“确认续补安全”按钮，但没有“按当前空位建立回搬绑定”的人工入口；`refreshKaoPanGrid()` 在 `1158-1166` 调用 UI builder。
- `fragment_maintenance.xml:44-57` 已有维护按钮区域，可在现有 `btn_confirm_supplement_safety` 后增加单独控件，避免复用下平台/动作组恢复语义。
- `SupplementBatchIntent.kt:544-578` 中的 `SupplementBatchIntentRepository` 已有 `markReturned`、`markSourceCleared`、`reopenSupplyDegradedTarget`；`removeBinding` 在 `618-638` 通过 durable save/clear 收口。
- `LowToHighDemandGate.kt:92-118` 当前只统计 `!isHasSausage` 后扣除内存 `blockedFrontPositions`；`blockedFrontPositions` 当前主要来自过保丢弃，且重启丢失。
- `LowCurrentTrafficMode.kt:97-117,160-212,225-245` 先处理 pending intent、初始化和低热补肠，再调用低转高门禁；当前没有耐久的初始化/库存/动作异常 blocked 记录。
- `HighCurrentTrafficMode.kt:524-555` 和 `1063-1090` 调用 `HighHeatSupplementResumePlanner.planInitialTargets` 后只判断非空；`HighHeatSupplementResumePlanner.kt:37-113` 会按库存返回少于九个目标，这是诊断结果，不代表允许正常启动残缺批次。
- `TrafficInitializationResumePlanner.kt:14-37` 已能在前区部分初始烤制、补热区为空时返回初始化续补目标；本计划不重写其区域判定，只增加调用方门禁和测试覆盖。
- `KaoChangOperate.kt:4447`、`4959` 等成功填入目标位置后已有 `LowToHighDemandGate.clearPosition` 调用；人工清空入口在 `KaoChangAlgorithm.kt:1185` 调用 `clearAll`，需要改为记录非需求空位而不是无条件忘记原因。

### 规则澄清

- “有效空位”是需求证据，不是容量预留的同义词。正常售卖形成的空位，即使已经绑定给 13~18 回搬，仍可计入低转高；绑定只阻止 25~33 抢占该位置。
- 初始化未完成、库存不足、E5/取肠动作失败、人工清盘、过保丢弃形成的空位必须进入 blocked 集合；填入真实烤肠后由现有 `clearPosition` 清除。
- 初始化中断优先续补；库存补充后优先收口当前低并发批次，再重新判断低转高。不得因库存恢复本身直接切换模式。
- intent 丢失时先把补热区烤肠保持为“待回搬保温”，不猜历史目标；运维一次确认后按当前安全空位稳定排序重新绑定。

## 切片一：意图丢失的人工重绑定和 UI 恢复

### Task 1：先写纯 planner 的失败测试

**Files:**
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/OrphanSupplementReturnPlannerTest.kt`
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/OrphanSupplementReturnPlanner.kt`

- [ ] **Step 1: 写四个 RED 测试**

在 `OrphanSupplementReturnPlannerTest` 使用现有 `SupplementReturnBindingResolverTest` 的 `frontPan`/`sourcePan` fixture 风格，测试类包名为 `cn.niuyannet.kaochang.android.services`：

```kotlin
@Test
fun plan_shouldAssignMatureSourcesToSortedCurrentEmptyFrontSlots() {
    val sources = listOf(sourcePan(25, "A"), sourcePan(26, "A"), sourcePan(27, "A"))
    val front = (1..9).map { position ->
        frontPan(position, null).apply { isHasSausage = position !in setOf(2, 5, 8) }
    }

    val result = OrphanSupplementReturnPlanner.plan(sources, front, emptySet())

    assertEquals(listOf(25, 26, 27), result.bindings.map { it.sourcePositionSn })
    assertEquals(listOf(2, 5, 8), result.bindings.map { it.returnPositionSn })
    assertEquals(null, result.failure)
}

@Test
fun plan_shouldRejectWhenAnySourceTasteMissing() {
    val sources = listOf(
        sourcePan(25, "A"),
        sourcePan(26, "A").apply { taste = null }
    )

    val result = OrphanSupplementReturnPlanner.plan(sources, (1..9).map { frontPan(it, null) }, emptySet())

    assertTrue(result.bindings.isEmpty())
    assertEquals(OrphanSupplementReturnPlanner.Failure.NO_TASTE, result.failure)
}

@Test
fun plan_shouldNotUseOccupiedOrReservedFrontSlots() {
    val sources = listOf(sourcePan(13, "B"), sourcePan(14, "B"))
    val front = (1..9).map { frontPan(it, "B").apply { isHasSausage = it == 1 } }

    val result = OrphanSupplementReturnPlanner.plan(sources, front, setOf(2))

    assertEquals(listOf(3, 4), result.bindings.map { it.returnPositionSn })
}

@Test
fun plan_shouldSkipReturnPositionsHeldByExistingCompletedBindings() {
    val sources = listOf(sourcePan(25, "A"), sourcePan(26, "A"))
    val front = (1..9).map { frontPan(it, "A") }

    val result = OrphanSupplementReturnPlanner.plan(sources, front, setOf(1, 2))

    assertEquals(listOf(3, 4), result.bindings.map { it.returnPositionSn })
}

@Test
fun plan_shouldRejectWhenFrontCapacityIsInsufficient() {
    val sources = (25..27).map { sourcePan(it, "A") }
    val front = (1..9).map { frontPan(it, "A").apply { isHasSausage = it !in setOf(2, 5) } }

    val result = OrphanSupplementReturnPlanner.plan(sources, front, emptySet())

    assertTrue(result.bindings.isEmpty())
    assertEquals(OrphanSupplementReturnPlanner.Failure.INSUFFICIENT_TARGETS, result.failure)
}
```

测试类末尾定义的 fixture 必须保持输入只读：

```kotlin
private fun frontPan(positionSn: Int, tasteCode: String?): KaoPan = KaoPan().apply {
    this.positionSn = positionSn
    isHasSausage = false
    status = KaoPan.STATUS_EMPTY
    taste = tasteCode?.let { Taste(it) }
}

private fun sourcePan(positionSn: Int, tasteCode: String): KaoPan = KaoPan().apply {
    this.positionSn = positionSn
    isHasSausage = true
    status = KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
    holdingTime = 2_000L
    taste = Taste(tasteCode)
}
```

The exact API introduced by the tests is:

```kotlin
internal object OrphanSupplementReturnPlanner {
    enum class Failure { NO_TASTE, INSUFFICIENT_TARGETS, UNSAFE_SOURCE }
    data class Binding(val sourcePositionSn: Int, val tasteCode: String, val returnPositionSn: Int, val originBatchType: SupplementBatchType)
    data class Result(val bindings: List<Binding>, val failure: Failure?)
    fun plan(sources: List<KaoPan>, frontPans: List<KaoPan>, reservedFrontPositions: Set<Int>): Result
}
```

- [ ] **Step 2: 运行 RED**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.OrphanSupplementReturnPlannerTest'
```

Expected: FAIL because `OrphanSupplementReturnPlanner` and its `plan`/`Binding`/`Result` symbols do not exist.

- [ ] **Step 3: 写最小 planner 实现**

在新文件中按以下顺序实现：按 `sourcePositionSn` 升序过滤 13~18/25~33 且有肠、状态为 `STATUS_WAITING_RETURN_KEEP_WARM` 的源盘；任一源盘口味为空返回 `NO_TASTE`；按源盘区域选择前区范围（13~18 对应 1..9，25~33 对应 1..21）；候选目标必须 `!isHasSausage && status == STATUS_EMPTY`，排除 `reservedFrontPositions`；先取同口味且 `isHasGrilling` 的目标，再取口味为空且未预留目标，最后取其它安全空位，所有候选按盘位升序；候选数量不足时返回空 bindings 和 `INSUFFICIENT_TARGETS`；不修改任何 `KaoPan`，不写 SharedPreferences，不调用机械动作。

- [ ] **Step 4: 运行 GREEN**

```powershell
.\gradlew :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.OrphanSupplementReturnPlannerTest'
```

Expected: all five tests PASS.

Risk: planner 若修改输入盘对象，会提前改变 UI/调度事实。Rollback: 删除新文件并恢复测试；禁止在 planner 中写盘、扣库存、开加热或调用 `KaoChangOperate`。

### Task 2：为人工重绑定增加耐久 repository API

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementBatchIntent.kt:18-29,103-193`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementBatchIntentRepositoryTest.kt`

- [ ] **Step 1: 写 RED 测试**

在现有 `SupplementBatchIntentRepositoryTest` 添加：

```kotlin
@Test
fun rebindCompletedHeatTargets_shouldPersistNewOriginAndReturnBindings() {
    val store = MemorySupplementBatchIntentStore()
    val repository = repository(store)
    val bindings = listOf(
        ManualSupplementReturnBinding(25, "A", 2, SupplementBatchType.HIGH_HEAT_25_33),
        ManualSupplementReturnBinding(26, "A", 5, SupplementBatchType.HIGH_HEAT_25_33)
    )

    val saved = repository.rebindCompletedHeatTargets(bindings, "运维确认按当前空位回搬")

    assertEquals(SupplementBatchType.UNKNOWN, saved?.type)
    assertEquals(listOf(25, 26), saved?.targets?.map { it.positionSn })
    assertEquals(listOf(2, 5), saved?.targets?.map { it.returnPositionSn })
    assertEquals(listOf(true, true), saved?.targets?.map { it.completed })
    assertTrue(saved!!.targets.all { it.originBatchId?.startsWith("manual-rebind:") == true })
    assertEquals(listOf(SupplementBatchType.HIGH_HEAT_25_33, SupplementBatchType.HIGH_HEAT_25_33), saved.targets.map { it.originBatchType })
}

@Test
fun rebindCompletedHeatTargets_shouldRejectDuplicateSourceOrReturnPosition() {
    val store = MemorySupplementBatchIntentStore()
    val repository = repository(store)

    assertNull(repository.rebindCompletedHeatTargets(
        listOf(
            ManualSupplementReturnBinding(25, "A", 2, SupplementBatchType.HIGH_HEAT_25_33),
            ManualSupplementReturnBinding(25, "A", 3, SupplementBatchType.HIGH_HEAT_25_33)
        ),
        "重复绑定测试"
    ))
}

@Test
fun rebindCompletedHeatTargets_shouldPreserveExistingCompletedBinding() {
    val existing = SupplementBatchIntent(
        batchId = "existing",
        type = SupplementBatchType.LOW_HEAT_13_15,
        targets = mutableListOf(
            SupplementBatchIntentTarget(
                positionSn = 13,
                tasteCode = "B",
                returnPositionSn = 1,
                completed = true,
                originBatchId = "existing",
                originBatchType = SupplementBatchType.LOW_HEAT_13_15
            )
        )
    )
    val store = MemorySupplementBatchIntentStore().apply { save(existing) }
    val repository = repository(store)

    val saved = repository.rebindCompletedHeatTargets(
        listOf(ManualSupplementReturnBinding(25, "A", 2, SupplementBatchType.HIGH_HEAT_25_33)),
        "补充孤儿绑定"
    )

    assertEquals(listOf(13, 25), saved?.targets?.map { it.positionSn })
    assertEquals(listOf(1, 2), saved?.targets?.map { it.returnPositionSn })
}
```

Define the new value type in `SupplementBatchIntent.kt` before using it:

```kotlin
data class ManualSupplementReturnBinding(
    val sourcePositionSn: Int,
    val tasteCode: String,
    val returnPositionSn: Int,
    val originBatchType: SupplementBatchType
)
```

- [ ] **Step 2: 运行 RED**

```powershell
.\gradlew :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.SupplementBatchIntentRepositoryTest.rebindCompletedHeatTargets_shouldPersistNewOriginAndReturnBindings' --tests 'cn.niuyannet.kaochang.android.services.SupplementBatchIntentRepositoryTest.rebindCompletedHeatTargets_shouldRejectDuplicateSourceOrReturnPosition' --tests 'cn.niuyannet.kaochang.android.services.SupplementBatchIntentRepositoryTest.rebindCompletedHeatTargets_shouldPreserveExistingCompletedBinding'
```

Expected: FAIL with unresolved `ManualSupplementReturnBinding`/`rebindCompletedHeatTargets`.

- [ ] **Step 3: 实现最小 durable rebind**

在 `SupplementBatchIntentRepository` 增加 `rebindCompletedHeatTargets(bindings, reason): SupplementBatchIntent?`：校验 source 唯一、return 唯一、source 属于 13..18/25..33、target 属于对应前区、口味非空；从 `store.load()` 读取现有 intent，若存在未完成目标或 `requiresManualConfirm` 返回 null；保留现有 `retainedCompletedHeatReturnTargets()`，并拒绝与新 binding 的 source/return 冲突；创建 `batchId = "manual-rebind:${clock()}"`，每个新增 target 写 `completed=true`、`originBatchId=batchId`、`originBatchType`、`returnPositionSn`，以 `SupplementBatchType.UNKNOWN` 作为容器 type，把保留目标和新增目标一起 `saveDurableOrThrow` 后返回。该方法不清理源盘、不改变前区盘状态、不自动调用 `markReturned`。

- [ ] **Step 4: 运行 GREEN**

```powershell
.\gradlew :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.SupplementBatchIntentRepositoryTest.rebindCompletedHeatTargets_shouldPersistNewOriginAndReturnBindings' --tests 'cn.niuyannet.kaochang.android.services.SupplementBatchIntentRepositoryTest.rebindCompletedHeatTargets_shouldRejectDuplicateSourceOrReturnPosition' --tests 'cn.niuyannet.kaochang.android.services.SupplementBatchIntentRepositoryTest.rebindCompletedHeatTargets_shouldPreserveExistingCompletedBinding'
```

Risk: rebind 若覆盖未完成 intent 会丢失续补目标。Rollback: 只回滚本方法和新增测试；禁止把 rebind 接到普通自动调度入口。

### Task 3：关闭自动猜测，接入运维一次确认和 UI

**Files:**
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/OrphanSupplementReturnCoordinator.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/OrphanSupplementReturnCoordinatorTest.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementReturnBindingResolver.kt:28-70`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt:568-575`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt:1231,1398`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt:2184-2203`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoPanUiReservationStateBuilder.kt:8-69`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt:264-302,996-1060,1158-1166`
- Modify: `app/src/main/res/layout/fragment_maintenance.xml:44-57`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementReturnBindingResolverTest.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/KaoPanUiReservationStateBuilderTest.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/fragments/MaintenanceLowerPlatformUiSourceTest.kt`

- [ ] **Step 1: 先写 RED 行为测试**

在 `SupplementReturnBindingResolverTest` 先保留现有的 `allowLegacyFallback=true` 参数，新增一个“旧 fallback 必须被拒绝”的 RED 断言：构造 source 18/A、唯一同口味空位 3、`activeIntent=null`，传入 `allowLegacyFallback=true`，断言 `targetPan == null` 和 `NO_BINDING`。当前实现会成功绑定，因此该 RED 真正覆盖现有生产 fallback。第一步实现先让该参数路径也返回 `NO_BINDING`，GREEN 后再删除参数，并同步修改现有 `SupplementReturnBindingResolverTest.kt:120` 和三个生产调用点，最后再运行同一回归测试。

在 `SupplementReturnBindingResolverTest` 增加：

```kotlin
@Test
fun resolveReturnTarget_shouldRejectMissingIntentEvenWhenUniqueSameTasteEmptyExists() {
    val frontPans = (1..9).map { position -> frontPan(position, if (position == 3) "A" else "B") }
    val result = SupplementReturnBindingResolver.resolveReturnTarget(
        sourcePan = sourcePan(18, "A"),
        frontPans = frontPans,
        activeIntent = null,
        allowedTypes = setOf(SupplementBatchType.LOW_HEAT_16_18),
        returnPositionRange = 1..9,
        allowLegacyFallback = true
    )

    assertNull(result.targetPan)
    assertNull(result.returnPositionSn)
    assertEquals(SupplementReturnBindingResolver.FailureReason.NO_BINDING, result.failureReason)
    assertFalse(frontPans.first { it.positionSn == 3 }.isHasGrilling)
}
```

在 `KaoPanUiReservationStateBuilderTest` 增加：

```kotlin
@Test
fun apply_shouldShowManuallyReboundReturnAfterReload() {
    val source = KaoPan().apply {
        positionSn = 25
        isHasSausage = true
        status = KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
        holdingTime = 2_000L
        taste = Taste("A")
    }
    val target = blankPan(2)
    val intent = SupplementBatchIntent(
        batchId = "manual-rebind:1000",
        type = SupplementBatchType.UNKNOWN,
        targets = mutableListOf(
            SupplementBatchIntentTarget(
                positionSn = 25,
                tasteCode = "A",
                returnPositionSn = 2,
                completed = true,
                originBatchId = "manual-rebind:1000",
                originBatchType = SupplementBatchType.HIGH_HEAT_25_33
            )
        )
    )

    KaoPanUiReservationStateBuilder.apply(listOf(target, source), intent)

    assertEquals(KaoPan.UI_RESERVATION_WAIT_RETURN, target.uiReservationState)
    assertEquals("A", target.uiReservationTaste.tasteCode)
    assertTrue(source.isHasSausage)
    assertEquals(KaoPan.STATUS_WAITING_RETURN_KEEP_WARM, source.status)
}

@Test
fun apply_shouldKeepCompletedHeatReturnVisibleWhenRecoveryIsBlocked() {
    val source = KaoPan().apply {
        positionSn = 25
        isHasSausage = true
        status = KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
        holdingTime = 2_000L
        taste = Taste("A")
    }
    val target = blankPan(2)
    val intent = SupplementBatchIntent(
        type = SupplementBatchType.UNKNOWN,
        targets = mutableListOf(
            SupplementBatchIntentTarget(
                positionSn = 25,
                tasteCode = "A",
                returnPositionSn = 2,
                completed = true,
                originBatchId = "manual-rebind:1000",
                originBatchType = SupplementBatchType.HIGH_HEAT_25_33
            )
        )
    )

    KaoPanUiReservationStateBuilder.apply(listOf(target, source), intent, recoveryBlocked = true)

    assertEquals(KaoPan.UI_RESERVATION_WAIT_RETURN, target.uiReservationState)
    assertEquals("A", target.uiReservationTaste.tasteCode)
}
```

在 `MaintenanceLowerPlatformUiSourceTest` 增加：

```kotlin
@Test
fun maintenanceLayout_shouldExposeOrphanReturnButton() {
    val layout = sourceFile("app/src/main/res/layout/fragment_maintenance.xml")
    assertTrue(layout.contains("@+id/btn_rebind_orphan_returns"))
}

@Test
fun maintenanceFragment_shouldExplainUnboundHeatSources() {
    val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
    val updateBody = extractFunctionBody(source, "private fun updateLowerPlatformUi()")
    assertTrue(updateBody.contains("补热区存在未绑定烤肠"))
    assertTrue(updateBody.contains("需要按当前空位建立回搬目标"))
}

@Test
fun orphanReturnClick_shouldRequireExplicitConfirmationAndCallRebindOnly() {
    val source = sourceFile("app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt")
    val clickBody = extractFunctionBody(source, "binding.btnRebindOrphanReturns.setOnClickListener")
    assertTrue(clickBody.contains("setPositiveButton"))
    assertTrue(clickBody.contains("previewOrphanSupplementReturns"))
    assertTrue(clickBody.contains("rebindOrphanSupplementReturnsToCurrentEmptyFront"))
    assertTrue(clickBody.contains("expectedBindings"))
    assertTrue(clickBody.contains("confirmOrphanSupplementSourceTaste"))
    assertTrue(clickBody.contains("PAN_PERSIST_FAILED"))
    assertFalse(clickBody.contains("moveKaoPanToKaoPan"))
}
```

- [ ] **Step 2: 运行 RED**

```powershell
.\gradlew :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.SupplementReturnBindingResolverTest.resolveReturnTarget_shouldRejectMissingIntentEvenWhenUniqueSameTasteEmptyExists' --tests 'cn.niuyannet.kaochang.android.services.KaoPanUiReservationStateBuilderTest.apply_shouldKeepCompletedHeatReturnVisibleWhenRecoveryIsBlocked' --tests 'cn.niuyannet.kaochang.android.fragments.MaintenanceLowerPlatformUiSourceTest.maintenanceLayout_shouldExposeOrphanReturnButton' --tests 'cn.niuyannet.kaochang.android.fragments.MaintenanceLowerPlatformUiSourceTest.orphanReturnClick_shouldRequireExplicitConfirmationAndCallRebindOnly'
```

Expected: resolver assertion FAIL because legacy fallback still binds pan 3；recovery-blocked UI assertion FAIL because builder returns before projection；layout/click tests FAIL because the new control does not exist. `apply_shouldShowManuallyReboundReturnAfterReload` is a regression GREEN test for the already-supported normal projection, not a RED claim.

- [ ] **Step 3: 删除自动猜测路径**

先让 `allowLegacyFallback=true` 路径也返回 `NO_BINDING` 并运行 Step 2 的 resolver 测试得到 GREEN；随后作为无行为变化重构删除 `allowLegacyFallback` 参数、`selectUniqueLegacyTarget` 和无 intent 时的自动 `restoreReturnReservation`。同步删除三个生产调用点和现有 `SupplementReturnBindingResolverTest.kt:120` 的命名参数，再运行整个 resolver 测试类。保留精确 intent binding 的 `TARGET_OCCUPIED`/`TARGET_STATUS_UNSAFE` 失败语义。

- [ ] **Step 4: 增加可测试的 preview/commit 边界和失败结果**

在新 `OrphanSupplementReturnCoordinator.kt` 定义：

```kotlin
internal class OrphanSupplementReturnCoordinator(
    private val repository: SupplementBatchIntentRepository,
    private val savePansDurable: (List<KaoPan>) -> Unit
) {
    enum class Failure {
        NO_TASTE, INSUFFICIENT_TARGETS, UNSAFE_SOURCE,
        CONFLICT, STALE_PREVIEW, INTENT_PERSIST_FAILED, PAN_PERSIST_FAILED
    }
    data class OperationResult(
        val bindings: List<OrphanSupplementReturnPlanner.Binding>,
        val failure: Failure?
    )
    fun preview(sources: List<KaoPan>, frontPans: List<KaoPan>, reservedFrontPositions: Set<Int>): OperationResult
    fun commit(expectedBindings: List<OrphanSupplementReturnPlanner.Binding>, sources: List<KaoPan>, frontPans: List<KaoPan>, reservedFrontPositions: Set<Int>, reason: String): OperationResult
}
```

`preview` 只映射 planner failure；`commit` 必须按当前盘位重新规划并与 `expectedBindings` 完全比较，不同则返回 `STALE_PREVIEW`；repository 返回 null 映射 `CONFLICT`；intent durable 异常映射 `INTENT_PERSIST_FAILED`；intent 成功后前区 durable 异常映射 `PAN_PERSIST_FAILED` 并保留 intent 供重启投影，不能显示成功或清 intent。

在 `OrphanSupplementReturnCoordinatorTest` 增加精确测试：

| 方法名 | 输入 | 关键断言 |
|---|---|---|
| `preview_shouldExcludeExistingRetainedReturnPositions` | existing 13->1，orphan 25/A，空位1/2 | binding 为25->2，不选择1 |
| `commit_shouldRejectStalePreviewWithoutPersisting` | preview 为25->2，确认前2被占用 | `STALE_PREVIEW`，store 未新增25 |
| `commit_shouldReportIntentPersistFailure` | `saveDurable=false` store | `INTENT_PERSIST_FAILED`，pan saver 未调用 |
| `commit_shouldReportPanPersistFailureAndKeepIntent` | repository 成功，pan saver 抛异常 | `PAN_PERSIST_FAILED`，store 仍含25->2 |
| `commit_shouldReportConflict` | existing return 2 与新25->2冲突 | `CONFLICT`，existing binding 不变 |

RED/GREEN command:

```powershell
.\gradlew :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.OrphanSupplementReturnCoordinatorTest'
```

Expected RED before creating coordinator: unresolved class；Expected GREEN after implementation: all five tests PASS.

- [ ] **Step 5: 在 `KaoChangOperate` 接入只做元数据的运维入口**

增加以下精确方法：

```kotlin
fun previewOrphanSupplementReturns(): OrphanSupplementReturnCoordinator.OperationResult
suspend fun rebindOrphanSupplementReturnsToCurrentEmptyFront(expectedBindings: List<OrphanSupplementReturnPlanner.Binding>, reason: String): OrphanSupplementReturnCoordinator.OperationResult
fun confirmOrphanSupplementSourceTaste(sourcePositionSn: Int, tasteCode: String): Boolean
fun completedHeatReturnIntentForUi(): SupplementBatchIntent?
```

实现要求：先调用现有 `HighHeatSupplementResumePlanner.normalizeLegacySellableKeepWarmSources` 纠正 25~33 的历史原地保温状态；读取 13..18、25..33 中 `isHasSausage && status == STATUS_WAITING_RETURN_KEEP_WARM` 且没有 exact binding 的源盘；`reservedFrontPositions` 必须来自 active intent 的所有有效 `returnPositionSn`；preview 不写状态，commit 用 expected bindings 做二次校验。`confirmOrphanSupplementSourceTaste` 只允许修改 orphan source，tasteCode 必须来自当前烤肠箱非空口味集合，写 `Taste(tasteCode)` 后调用 `KaoPanHelper.saveKaoPanListDurable`，不搬盘。`completedHeatReturnIntentForUi` 无视 action-group recovery gate，但只返回 `retainedCompletedHeatReturnTargets()` 的 copy，不暴露未完成补肠目标。

修改 `KaoPanUiReservationStateBuilder.apply`：`recoveryBlocked=true` 时只投影 completed heat return bindings，继续隐藏 `WAIT_SUPPLEMENT`；`MaintenanceFragment.refreshKaoPanGrid` 在 recovery blocked 时传 `completedHeatReturnIntentForUi()`。这样重启恢复阻塞期间仍显示“待回补”，但不会把未确认续补目标开放给自动调度。

- [ ] **Step 6: 维护页增加预览、口味确认和提交按钮**

在 XML 增加 `Button`：id `btn_rebind_orphan_returns`，文案“按当前空位建立回搬目标”，默认 `gone`。点击先调用 `previewOrphanSupplementReturns()`；成功时确认框逐条显示 `sourcePositionSn -> returnPositionSn / tasteCode`，正按钮把同一 bindings 传给 commit，commit 再校验当前盘位；`STALE_PREVIEW` 要求重新预览。`NO_TASTE` 时不提交 binding，而是列出缺口源盘，并从 `KaoPanHelper.getKaoPanBoxList()` 的非空 tasteCode 生成单选项；运维选择后调用 `confirmOrphanSupplementSourceTaste`，durable 成功后重新预览。任何持久化失败显示明确失败文案，不显示“已建立”。

`updateLowerPlatformUi()` 在现有下平台状态文本上方优先显示“补热区存在未绑定烤肠，需要按当前空位建立回搬目标”。不能复用“确认续补安全”按钮，不能在点击路径调用整机恢复或机械搬运。

- [ ] **Step 7: 运行切片一 GREEN**

```powershell
.\gradlew :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.OrphanSupplementReturnPlannerTest' --tests 'cn.niuyannet.kaochang.android.services.OrphanSupplementReturnCoordinatorTest' --tests 'cn.niuyannet.kaochang.android.services.SupplementBatchIntentRepositoryTest' --tests 'cn.niuyannet.kaochang.android.services.SupplementReturnBindingResolverTest' --tests 'cn.niuyannet.kaochang.android.services.KaoPanUiReservationStateBuilderTest' --tests 'cn.niuyannet.kaochang.android.fragments.MaintenanceLowerPlatformUiSourceTest'
```

Expected: all selected tests PASS. Slice 1 risk: UI transient 字段仍不持久化；依赖是 durable intent，重启时 builder 从 intent 重投影。禁止让 orphan planner 直接把源盘当可售或自动清理源盘。

## 切片二：非需求空位耐久门禁和完整九目标启动

### Task 4：为低转高 gate 增加耐久 blocked 状态

**Files:**
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/LowToHighDemandStateRepositoryTest.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/LowToHighDemandGate.kt:20-158`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/LowToHighDemandGateTest.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt:1650-1655,2380-2523,3467-3679,4609-4613,4959`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt:1185`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt:149-186,225-245`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt:430-450,1012-1044`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementSupplyDegradeSourceTest.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/LowHeatSupplementSafetySourceTest.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficModeSourceTest.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/ManualDemandSourceTest.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementIntentStartPolicyTest.kt`

- [ ] **Step 1: 写 state repository RED 测试**

新增 `LowToHighDemandStateRepositoryTest`，使用内存 store，测试：

```kotlin
@Test
fun saveAndReload_shouldKeepBlockedPositionsAcrossRepositoryInstances() {
    val store = MemoryLowToHighDemandStateStore()
    LowToHighDemandStateRepository(store).recordNonDemandEmpty(5, "initialization_gap")
    assertEquals(setOf(5), LowToHighDemandStateRepository(store).blockedPositions())
}

@Test
fun clearPosition_shouldRemoveOnlyFilledPosition() {
    val store = MemoryLowToHighDemandStateStore()
    val repository = LowToHighDemandStateRepository(store)
    repository.recordNonDemandEmpty(5, "supply_gap")
    repository.recordNonDemandEmpty(6, "action_failure")
    repository.clearPosition(5, "补肠成功写入")
    assertEquals(setOf(6), repository.blockedPositions())
}

@Test
fun blockedPositions_shouldFailClosedWhenStoredJsonIsCorrupt() {
    val store = MemoryLowToHighDemandStateStore(loadStatus = LowToHighDemandLoadStatus.CORRUPT)
    val repository = LowToHighDemandStateRepository(store)

    assertEquals((1..21).toSet(), repository.blockedPositions())
}

@Test
fun decode_shouldReturnCorruptForInvalidNonBlankJson() {
    val result = SharedPreferenceLowToHighDemandStateStore.decode("{broken")

    assertEquals(LowToHighDemandLoadStatus.CORRUPT, result.status)
    assertEquals(null, result.state)
}
```

定义精确的最小接口：`recordNonDemandEmpty(positionSn: Int, reason: String)`、`clearPosition(positionSn: Int, reason: String)`、`blockedPositions(): Set<Int>`、`clearAll(reason: String)`；`LowToHighDemandGate` 对外新增 `@Synchronized fun recordNonDemandEmpty(positionSn: Int, reason: String): Boolean`，其余现有调用名保持不变。SharedPreferences key 固定为 `sp_low_to_high_demand_gate_state_json`，durable 写使用 `PreferenceUtils.saveStringPreferenceDurable`，不把原因写入云端设备快照。

生产实现先定义以下稳定边界，再由 gate 委托：

```kotlin
data class LowToHighDemandState(
    val blockedFrontPositions: MutableSet<Int> = linkedSetOf()
)

enum class LowToHighDemandLoadStatus { EMPTY, PRESENT, CORRUPT }

data class LowToHighDemandLoadResult(
    val status: LowToHighDemandLoadStatus,
    val state: LowToHighDemandState? = null
)

interface LowToHighDemandStateStore {
    fun load(): LowToHighDemandLoadResult
    fun saveDurable(state: LowToHighDemandState): Boolean
    fun clearDurable(): Boolean
}
```

实现时在测试文件中提供完整内存实现，避免单元测试触碰 Android SharedPreferences：

```kotlin
private class MemoryLowToHighDemandStateStore(
    private var loadStatus: LowToHighDemandLoadStatus = LowToHighDemandLoadStatus.EMPTY
) : LowToHighDemandStateStore {
    private var state: LowToHighDemandState? = null

    override fun load(): LowToHighDemandLoadResult = when (loadStatus) {
        LowToHighDemandLoadStatus.CORRUPT -> LowToHighDemandLoadResult(LowToHighDemandLoadStatus.CORRUPT)
        LowToHighDemandLoadStatus.EMPTY -> LowToHighDemandLoadResult(LowToHighDemandLoadStatus.EMPTY)
        LowToHighDemandLoadStatus.PRESENT -> LowToHighDemandLoadResult(
            LowToHighDemandLoadStatus.PRESENT,
            state?.copy(blockedFrontPositions = state!!.blockedFrontPositions.toMutableSet())
        )
    }

    override fun saveDurable(value: LowToHighDemandState): Boolean {
        state = value.copy(blockedFrontPositions = value.blockedFrontPositions.toMutableSet())
        loadStatus = LowToHighDemandLoadStatus.PRESENT
        return true
    }

    override fun clearDurable(): Boolean {
        state = LowToHighDemandState()
        loadStatus = LowToHighDemandLoadStatus.PRESENT
        return true
    }
}
```

- [ ] **Step 2: 运行 RED**

```powershell
.\gradlew :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.LowToHighDemandStateRepositoryTest'
```

Expected: FAIL because state repository and memory store do not exist.

- [ ] **Step 3: 实现 gate 状态并保持现有 API 兼容**

在 `LowToHighDemandGate.kt` 内增加 `LowToHighDemandState`、load result、store 和 repository。`SharedPreferenceLowToHighDemandStateStore.decode(raw: String?): LowToHighDemandLoadResult` 是不依赖 Android context 的纯解析函数：空字符串返回 `EMPTY`；合法 JSON 返回 `PRESENT`；非空 JSON 解析失败返回 `CORRUPT`。Repository 对 `CORRUPT` 返回 1..21 全部 blocked，下一次写入以该 fail-closed 集合作为基底；`pruneObservedFilledPositions` 再按真实有肠盘逐个 durable 解除。`LowToHighDemandGate` 保留现有方法名并委托 repository；测试提供 `replaceStateStoreForTest`/`resetStateStoreForTest`。不能把损坏状态静默解释为“所有空位都是售卖需求”。

- [ ] **Step 4: 运行 state GREEN**

```powershell
.\gradlew :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.LowToHighDemandStateRepositoryTest' --tests 'cn.niuyannet.kaochang.android.services.LowToHighDemandGateTest'
```

- [ ] **Step 5: 接入非需求来源**

在 `KaoChangOperate` 增加私有 helper `recordNonDemandFrontForSupplementTarget(batchId: String?, targetPanNo: Int, reason: String)`：从 active intent 找到 target；初始化 target 用 `target.positionSn`，低热/高热 target 用 `target.returnPositionSn`；只有位置在 1..21 时调用 `LowToHighDemandGate.recordNonDemandEmpty`。

同文件增加两个 internal 纯策略函数，并由 `moveSausagesToKaoPansByTaste`/`moveSausagesToKaoPansByTasteInternal` 调用：

```kotlin
internal fun shouldCreateSupplementIntent(
    targets: List<KaoChangOperate.SupplementTarget>,
    batchType: SupplementBatchType?
): Boolean =
    targets.size > 1 ||
        targets.any { it.returnPositionSn != null } ||
        batchType?.let { SupplementBatchIntentRepository.isInitializationBatch(it) } == true

internal fun initializationGapPositions(
    requestedTargets: List<KaoChangOperate.SupplementTarget>,
    batchType: SupplementBatchType?
): Set<Int> =
    if (batchType?.let { SupplementBatchIntentRepository.isInitializationBatch(it) } == true) {
        requestedTargets.map { it.kaoPan.positionSn }.filter { it in 1..21 }.toSet()
    } else {
        emptySet()
    }
```

`moveSausagesToKaoPansByTaste` 必须用 `shouldCreateSupplementIntent` 替换当前 `targets.size > 1 || returnPositionSn != null`。`moveSausagesToKaoPansByTasteInternal` 在调用 `planExecutableSupplementTargets` 前，对 `initializationGapPositions(validTargets, batchTypeOverride)` 全部执行 durable `recordNonDemandEmpty(..., "initialization_gap")`；因此即使同口味库存过滤后 `executableTargets` 为空并在当前 2481 行提前返回，原始初始化缺口仍保留。若仍有一个可执行初始化目标，`createIntent=true`，必须先 `begin` 再进入串行动作。

接入点必须覆盖：

- 串行 `moveSausagesToKaoPansByTasteSerial` 的 `SupplyDegraded`、`LowerPlatformIsolated`、`HardFailure`。
- 默认启用预取时的 `handlePipelineSupplementFailure` 四类收口：下平台隔离、初始化 hard failure、pipeline supply degrade，以及 3635 行开始的最终通用 hard-failure/maintenance 分支；每一类都必须在 `markSkippedBySupplyDegrade`/`markFailure` 或清 intent 前记录。
- 初始化 gap 在库存过滤和 intent 创建前按全部 requested targets 记录；这同时覆盖低/高初始化、单目标初始化、部分口味无库存和全部目标不可执行。成功写入继续沿用已有 `clearPosition`。
- 批量人工清盘/重置把实际清空的 1..21 记录为 `manual_clear`，不再调用会忘记原因的 `clearAll`。
- `KaoChangOperate.kt:1650-1655` 和 `4609-4613` 的单盘维护取肠/恢复成功，对 1..21 调用 `recordNonDemandEmpty(..., "manual_clear")`，不能继续 `clearPosition`；正常订单售卖成功路径保持不记录 blocked。

过保丢弃继续使用 `recordExpiredDiscard`。

在 `SupplementSupplyDegradeSourceTest` 增加 `pipelineFailure_shouldRecordNonDemandBeforeEveryTerminalBranch`：提取 `handlePipelineSupplementFailure`，分别定位 isolation、initialization hard failure、pipeline supply degrade 和最终通用 `markFailure` 四段，断言每段 `recordNonDemandFrontForSupplementTarget` 位于 terminal mark 之前；最终段还要覆盖 `hasPrefetchedNext`、`IR_PICK_FAILED` maintenance 和 `markActionGroupFailure` 的共同入口。在 `HighCurrentTrafficModeSourceTest` 增加 `highInitialization_shouldUseTypedCommonSupplementEntry`：断言 `resumeHighInitialization` 和 `initHighTrafficMode` 都以 `HIGH_INITIALIZATION_1_21` 调用通用补肠入口。

新增 `SupplementIntentStartPolicyTest`：

```kotlin
@Test
fun shouldCreateSupplementIntent_shouldKeepSingleInitializationTargetDurable() {
    val target = KaoChangOperate.SupplementTarget(frontPan(9, "A"), "A")

    assertTrue(shouldCreateSupplementIntent(listOf(target), SupplementBatchType.LOW_INITIALIZATION_1_9))
    assertTrue(shouldCreateSupplementIntent(listOf(target), SupplementBatchType.HIGH_INITIALIZATION_1_21))
    assertFalse(shouldCreateSupplementIntent(listOf(target), null))
}

@Test
fun initializationGapPositions_shouldKeepAllRequestedTargetsBeforeTasteStockFiltering() {
    val targets = listOf(
        KaoChangOperate.SupplementTarget(frontPan(8, "A"), "A"),
        KaoChangOperate.SupplementTarget(frontPan(9, "B"), "B")
    )
    val availability = planExecutableSupplementTargets(
        targets = targets,
        listBox = listOf(box(positionSn = 18, tasteCode = "A", num = 1))
    )

    assertEquals(listOf(8), availability.executableTargets.map { it.kaoPan.positionSn })
    assertEquals(listOf(9), availability.skippedTargets.map { it.targetPositionSn })
    assertEquals(setOf(8, 9), initializationGapPositions(targets, SupplementBatchType.LOW_INITIALIZATION_1_9))
}

@Test
fun initializationGapPositions_shouldKeepSingleTargetWhenNoMatchingTasteStockExists() {
    val target = KaoChangOperate.SupplementTarget(frontPan(9, "B"), "B")
    val availability = planExecutableSupplementTargets(
        targets = listOf(target),
        listBox = listOf(box(positionSn = 18, tasteCode = "A", num = 5))
    )

    assertTrue(availability.executableTargets.isEmpty())
    assertEquals(listOf(9), availability.skippedTargets.map { it.targetPositionSn })
    assertEquals(setOf(9), initializationGapPositions(listOf(target), SupplementBatchType.LOW_INITIALIZATION_1_9))
}

private fun frontPan(positionSn: Int, tasteCode: String): KaoPan = KaoPan().apply {
    this.positionSn = positionSn
    taste = Taste(tasteCode)
    status = KaoPan.STATUS_EMPTY
    isHasSausage = false
}

private fun box(positionSn: Int, tasteCode: String, num: Int): KaoPanBox = KaoPanBox().apply {
    this.positionSn = positionSn
    this.tasteCode = tasteCode
    this.num = num
}
```

`LowHeatSupplementSafetySourceTest` 原先对 `createIntent = targets.size > 1 || ...` 的字符串断言必须改为断言 `shouldCreateSupplementIntent(targets, batchTypeOverride)`，避免旧实现被测试锁死。

新增 `ManualDemandSourceTest`：

| 方法名 | 输入/检查范围 | 关键断言 |
|---|---|---|
| `manualPanRemoval_shouldRecordNonDemandInsteadOfClearingGate` | `KaoChangOperate.kt` 单盘维护取肠两个成功块 | 包含 `recordNonDemandEmpty` 和 `manual_clear`，不含 `clearPosition` |
| `normalSale_shouldNotRecordNonDemand` | 正常订单取肠成功函数块 | 不含 `recordNonDemandEmpty`，售卖空位仍由 gate 计为需求 |
| `batchReset_shouldRecordEveryClearedFrontPosition` | `KaoChangAlgorithm.kt` 批量重置函数块 | 1..21 清盘位置逐个记录 `manual_clear` |

RED command:

```powershell
.\gradlew :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.SupplementIntentStartPolicyTest' --tests 'cn.niuyannet.kaochang.android.services.SupplementSupplyDegradeSourceTest.pipelineFailure_shouldRecordNonDemandBeforeEveryTerminalBranch' --tests 'cn.niuyannet.kaochang.android.services.HighCurrentTrafficModeSourceTest.highInitialization_shouldUseTypedCommonSupplementEntry' --tests 'cn.niuyannet.kaochang.android.services.ManualDemandSourceTest'
```

Expected: policy tests FAIL because the functions do not exist；pipeline/manual tests FAIL because current branches do not record non-demand；high initialization test is a regression guard and should PASS before and after implementation.

- [ ] **Step 6: 补 gate 行为测试**

在 `LowToHighDemandGateTest` 增加：

```kotlin
@Test
fun effectiveEmptyFrontPositions_shouldExcludePersistedInitializationAndSupplyGaps() {
    LowToHighDemandGate.recordNonDemandEmpty(5, "initialization_gap")
    LowToHighDemandGate.recordNonDemandEmpty(6, "supply_gap")

    assertEquals(
        setOf(1, 2, 3, 4, 7, 8, 9),
        LowToHighDemandGate.effectiveEmptyFrontPositions(frontPans(emptyPositions = (1..9).toSet()))
    )
}

@Test
fun canTriggerLowToHigh_shouldStillCountSaleEmptyPositionsThatHaveReturnBindings() {
    assertTrue(
        LowToHighDemandGate.canTriggerLowToHigh(
            frontPans = frontPans(emptyPositions = (1..8).toSet()),
            lowHeatPans = lowHeatPans(activeBakingPositions = (13..18).toSet())
        )
    )
}

@Test
fun canTriggerLowToHigh_shouldRejectWhenEightEmptyPositionsAreOnlyNonDemandGaps() {
    (1..8).forEach { LowToHighDemandGate.recordNonDemandEmpty(it, "manual_clear") }

    assertFalse(
        LowToHighDemandGate.canTriggerLowToHigh(
            frontPans = frontPans(emptyPositions = (1..8).toSet()),
            lowHeatPans = lowHeatPans(activeBakingPositions = (13..18).toSet())
        )
    )
}
```

这些测试复用同一测试类已有的 `frontPans` 和 `lowHeatPans` fixture；RED 命令：

```powershell
.\gradlew :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.LowToHighDemandGateTest.effectiveEmptyFrontPositions_shouldExcludePersistedInitializationAndSupplyGaps' --tests 'cn.niuyannet.kaochang.android.services.LowToHighDemandGateTest.canTriggerLowToHigh_shouldStillCountSaleEmptyPositionsThatHaveReturnBindings' --tests 'cn.niuyannet.kaochang.android.services.LowToHighDemandGateTest.canTriggerLowToHigh_shouldRejectWhenEightEmptyPositionsAreOnlyNonDemandGaps'
```

Expected: first two new methods fail before implementation，原因分别是持久状态不存在和 gate 尚未区分“绑定但来自售卖”与“非需求 blocked”；实现后同命令必须 PASS。

Risk: 把所有回搬目标都 blocked 会使低转高永远不触发；必须按失败/库存/初始化/人工来源记录，正常售卖触发的低热回搬不记录 blocked。Rollback: 可只回退 state repository 和记录调用点，保留现有过保丢弃逻辑。

### Task 5：统一完整九目标启动门禁

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/HighHeatSupplementResumePlanner.kt:37-113`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt:524-555,1063-1090`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/HighHeatSupplementResumePlannerTest.kt`
- Modify: `app/src/test/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficModeSourceTest.kt`

- [ ] **Step 1: 写完整批次判定 RED 测试**

在 `HighHeatSupplementResumePlannerTest` 增加：

```kotlin
@Test
fun isCompleteInitialPlan_shouldAcceptNineDistinctTargets() {
    val targets = (25..33).mapIndexed { index, source ->
        HighHeatSupplementResumePlanner.ResumeTarget(
            kaoPan = supplementPan(source, "A", false),
            tasteCode = "A",
            returnPositionSn = index + 1
        )
    }
    assertTrue(HighHeatSupplementResumePlanner.isCompleteInitialPlan(targets))
}

@Test
fun isCompleteInitialPlan_shouldRejectPartialOrDuplicateReturnTargets() {
    val partial = (25..31).map { source ->
        HighHeatSupplementResumePlanner.ResumeTarget(supplementPan(source, "A", false), "A", source - 24)
    }
    val duplicate = (25..33).map { source ->
        HighHeatSupplementResumePlanner.ResumeTarget(supplementPan(source, "A", false), "A", 1)
    }
    assertFalse(HighHeatSupplementResumePlanner.isCompleteInitialPlan(partial))
    assertFalse(HighHeatSupplementResumePlanner.isCompleteInitialPlan(duplicate))
}
```

- [ ] **Step 2: 运行 RED**

```powershell
.\gradlew :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.HighHeatSupplementResumePlannerTest.isCompleteInitialPlan_shouldAcceptNineDistinctTargets' --tests 'cn.niuyannet.kaochang.android.services.HighHeatSupplementResumePlannerTest.isCompleteInitialPlan_shouldRejectPartialOrDuplicateReturnTargets'
```

Expected: FAIL because `isCompleteInitialPlan` does not exist.

- [ ] **Step 3: 实现纯判定并接入两个新建入口**

在 planner 增加 `fun isCompleteInitialPlan(targets: List<ResumeTarget>): Boolean`：目标数必须为 9，source positions 排序等于 25..33，return positions 全非空、位于 1..21 且 distinct 数为 9。`HighCurrentTrafficMode.kt:549` 和 `1063` 之后，在 `heating(3)` 及 `moveSausagesToKaoPansByTaste` 之前都调用该判定；失败时只记录库存/目标不足原因并返回，不创建新 intent、不启动三区加热。已有正在执行的残缺 intent 仍由 `resumeIncompleteHighHeatSupplement` 续补，不受该新建门禁影响。

- [ ] **Step 4: 增加 source guard 测试并运行 GREEN**

在 `HighCurrentTrafficModeSourceTest` 增加源码门禁：低转高和高并发稳态新批次代码块都必须出现 `isCompleteInitialPlan`，且该调用位于 `heating(3)` 之前；测试还必须断言不存在只以 `targets.isNotEmpty()` 作为启动条件的分支。运行：

```powershell
.\gradlew :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.HighHeatSupplementResumePlannerTest' --tests 'cn.niuyannet.kaochang.android.services.HighCurrentTrafficModeSourceTest'
```

Expected: all selected tests PASS；七/八根库存结果只能作为诊断并等待补货或下一轮规划，不能正常启动部分新批次。

Risk: 把“已开始但被订单/硬件打断”的批次误判为新建批次会阻塞恢复；判定只放在两个 `planInitialTargets` 新建入口，不能放进 `planResumeTargets` 或 `handlePendingSupplementBatchIfAny`。

### Task 6：切片二综合验证和交接

- [ ] **Step 1: 运行切片二定向测试**

```powershell
.\gradlew :app:testDebugUnitTest --tests 'cn.niuyannet.kaochang.android.services.LowToHighDemandStateRepositoryTest' --tests 'cn.niuyannet.kaochang.android.services.LowToHighDemandGateTest' --tests 'cn.niuyannet.kaochang.android.services.SupplementIntentStartPolicyTest' --tests 'cn.niuyannet.kaochang.android.services.ManualDemandSourceTest' --tests 'cn.niuyannet.kaochang.android.services.TrafficInitializationResumePlannerTest' --tests 'cn.niuyannet.kaochang.android.services.SupplementSupplyDegradeSourceTest' --tests 'cn.niuyannet.kaochang.android.services.HighHeatSupplementResumePlannerTest' --tests 'cn.niuyannet.kaochang.android.services.HighCurrentTrafficModeSourceTest'
```

- [ ] **Step 2: 运行 Android 模块测试/编译**

```powershell
.\gradlew :app:testDebugUnitTest
.\gradlew :app:compileDebugKotlin
```

若资源/XML 或 Java `KaoPan.java` 被修改，再追加：

```powershell
.\gradlew :app:assembleDebug
```

- [ ] **Step 3: 双仓协议检查**

本计划不应修改后端；执行以下检查确认没有跨仓协议漂移：

```powershell
git -C D:\LeYe\cl-sausage-controller diff --name-only -- app/src/main/java app/src/main/res
git -C D:\LeYe\cl-sausage-server diff --name-only -- ruoyi-admin/src/main/java/com/ruoyi/iot/MqttProtocol.java
```

预期第二条为空；若出现后端协议改动，停止并退回范围审查。

- [ ] **Step 4: 文档和工作树检查**

```powershell
git diff --check
git status --short
$needle = ('占' + '位')
Select-String -Path docs/superpowers/plans/2026-07-20-sausage-scheduling-stability.md -Pattern $needle -SimpleMatch
```

预期 `git diff --check` 无输出，扫描无匹配；工作树中的 `.idea/codeStyles/Project.xml` 和 `docs/sausage-scheduling-rules.md` 用户改动不得被覆盖。

### 切片二回滚和停止条件

- 任一定向测试失败，先停止，不把失败改成“允许部分批次”的业务绕过。
- 持久 blocked 状态解析失败时，保守返回空集合并记录日志会重新产生误触发风险；因此实现必须在写入失败时抛出/保留旧值，不能静默清空。
- 发现 `LowToHighDemandGate` 需要跨后端字段、MQTT 事件或订单状态才能判断来源时，停止本切片，记录为独立设计问题。
- 发现 intent 丢失时需要猜历史目标或自动搬盘才能继续时，停止自动路径，保持人工重绑定入口，不扩展机械恢复。

## 计划后审核和实施顺序

1. 当前对话完成文档自检后，由独立 fresh Codex 子 agent 只读审核本计划、`docs/sausage-scheduling-rules.md`、当前 controller 代码和现有测试；审核结论只能是 `Approve` 或 P0/P1/P2。
2. 若审核列出问题，只在当前规划对话修计划文档，不让审核 agent 改文件，不进入实现。
3. 只有审核 `Approve` 且用户另行授权后，才新开实施对话；实施对话先执行切片一，不能顺手执行切片二。
4. 切片一完成后新开 fresh Codex 做实际 diff 只读审核；问题由实施对话修复，审核对话不改文件。
5. 切片二需要用户再次明确授权；两个切片都通过定向测试、模块测试/编译、双仓协议检查和 `git diff --check` 后，才讨论提交或发布。任何 commit 只能在用户明确授权后执行。
