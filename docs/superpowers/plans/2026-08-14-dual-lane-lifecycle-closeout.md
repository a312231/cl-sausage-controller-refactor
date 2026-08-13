# 双 lane 生命周期最小收口实施计划

> **执行要求：** 逐任务执行；每个切片都必须先得到 RED，再做最小 GREEN，并在提交前接受独立只读审核。

**目标：** 修复 `ReplanRemaining` 对已释放补肠资源的重复收口，以及 pending 批次 `BLOCKED` 误阻断成熟回搬/过期丢弃的问题。

**约束：** 只改 Android 上位机当前双 lane 生命周期控制流；复用 `faultHeatTransitionLock`、`SupplementMechanicalCloseoutCoordinator` 和既有调度入口。不新增线程、协程、轮询、定时器、长期锁、通用状态机或跨仓协议。

**技术栈：** Kotlin、Android、JUnit4、Gradle。

---

## Task 1：让补肠机械收口支持严格的幂等完成

**文件：**

- 修改：`app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementMechanicalCloseoutCoordinator.kt`
- 修改：`app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementMechanicalCloseoutCoordinatorTest.kt`

### 1.1 先写失败测试

新增真实行为测试：

- owner 已清、该 owner 的资源已不存在、订单 claim 不存在且 `moveStatus=0`，返回 `ClosedIdle`，不写状态、不释放其他 owner。
- owner 已清、该 owner 的资源已不存在、有效订单 claim 存在且 `moveStatus=2`，返回 `ClosedKeepingOrder`，不覆盖订单。
- owner 已清但本 owner 资源仍存在，拒绝收口并保留证据。
- owner 已清但 `moveStatus`/订单 claim 矛盾，拒绝收口。
- owner 读取、订单读取或 `moveStatus` 读取异常，返回稳定的失败结果，不抛出到调度循环。

运行：

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.actiongroup.SupplementMechanicalCloseoutCoordinatorTest" --rerun-tasks
```

预期：新增测试因缺少幂等收口入口而失败。

### 1.2 最小实现

在现有 coordinator 增加一个明确命名的幂等入口，例如：

```kotlin
fun closeOrConfirmAlreadyClosed(
    request: SupplementMechanicalCloseoutRequest
): SupplementMechanicalCloseoutResult
```

实现规则：

- 所有读取、判定、状态提交和资源释放继续在同一个 `transitionLock` 内。
- owner 存在时完全复用现有严格 closeout 行为。
- owner 不存在时只确认事实，不做清理：
  - 本 owner 资源必须已不存在；
  - 无订单 claim 时只接受 `moveStatus=0`；
  - 有效订单 claim 时只接受 `moveStatus=2`；
  - 其他组合全部 `Rejected`。
- 不释放其他 owner，不清订单 claim，不写 `moveStatus`。
- 读取失败返回 `Failed/Rejected`，保留当前证据。

### 1.3 验证并提交 Task 1 核心

重复运行 1.1 命令，要求 GREEN；随后运行：

```powershell
git diff --check
git diff -- app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementMechanicalCloseoutCoordinator.kt app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementMechanicalCloseoutCoordinatorTest.kt
```

暂不提交，先完成 Task 2 接线测试；切片 1 最终合并为一个原子提交。

---

## Task 2：把 `ReplanRemaining` 接到幂等精确收口

**文件：**

- 修改：`app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- 修改：`app/src/test/java/cn/niuyannet/kaochang/android/services/ParallelSupplementOrderEntrySourceTest.kt`
- 视现有测试结构最小修改：`app/src/test/java/cn/niuyannet/kaochang/android/services/ParallelSupplementRuntimePauseSourceTest.kt`

### 2.1 先写失败测试

测试要求：

- `ReplanRemaining` 不再调用接受 `{1, 2}` 的旧 `releaseMechanicalMove`。
- 收口前重新读取同一 session，核对 `sessionId` 和 session revision。
- closeout request 使用 session 中的 `mechanicalClaimRevision` 与 `resourceMask`，不能误用 planner revision。
- 只有 `ClosedIdle/ClosedKeepingOrder` 才继续完成并发布；`Rejected/Failed/Inconsistent` 都进入维护并保留 `moveStatus`。
- 已被订单占用的 `moveStatus=2` 不被写0。

运行相关测试并确认 RED。

### 2.2 最小接线

在 `ReplanRemaining` 分支中：

1. 在既有 `faultHeatTransitionLock` 保护下重新加载 session；
2. 校验 `sessionId`、session revision、batch/target 与 preparation 一致；
3. 用 session 的 `mechanicalClaimRevision/resourceMask` 调用 `closeOrConfirmAlreadyClosed`；
4. 仅接受两个 Closed 结果；
5. 再执行现有 batch 完成记录和运行态发布；
6. 删除重复的裸 `actionGroupLocks.release` 和旧 `{1,2}` 释放调用。

任何证据矛盾或耐久失败保持原有 fail-closed 维护路径。

### 2.3 切片 1 验证、审核、提交

运行：

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.actiongroup.SupplementMechanicalCloseoutCoordinatorTest" --tests "cn.niuyannet.kaochang.android.services.ParallelSupplementOrderEntrySourceTest" --tests "cn.niuyannet.kaochang.android.services.ParallelSupplementRuntimePauseSourceTest" --rerun-tasks
git diff --check
```

请独立 Codex agent 只读审核切片 1：资源 owner/revision/mask、订单 `moveStatus=2`、重复调用幂等、失败保留证据、无新并发资源。若有 P1，停止并只修该 P1；通过后提交：

```powershell
git add <切片1精确文件>
git commit -m "fix(controller): 幂等收口双 lane 已完成会话"
```

---

## Task 3：统一 pending 批次返回值语义

**文件：**

- 修改：`app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- 新增或修改：`app/src/test/java/cn/niuyannet/kaochang/android/services/PendingSupplementBatchHandlingTest.kt`

### 3.1 先写失败测试

为 `PendingSupplementBatchHandling` 写可执行行为测试：

- 只有 `RESUMED` 表示本轮已经执行机械补肠，应结束本轮。
- `BLOCKED` 与 `WAITING_MANUAL_CONFIRMATION` 都阻止新补肠。
- `BLOCKED` 与 `WAITING_MANUAL_CONFIRMATION` 不表示应跳过成熟回搬/丢弃。
- `NONE` 不阻止正常补肠。

### 3.2 最小实现

在现有 enum 上增加小型只读语义属性/函数，不新增状态：

```kotlin
val completedMechanicalWorkThisRound: Boolean
val blocksNewSupplement: Boolean
```

保证返回值语义集中且低/高并发入口一致使用。

---

## Task 4：低并发和高并发正常态继续成熟收口

**文件：**

- 修改：`app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt`
- 修改：`app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt`
- 修改或新增：`app/src/test/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficModeSourceTest.kt`
- 修改：`app/src/test/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficModeSourceTest.kt`

### 4.1 先写失败测试

低并发：

- `BLOCKED` 不在 pending handler 后直接 return。
- `RESUMED` 仍保存盘位并结束本轮。
- `BLOCKED/WAITING` 令 `canStartSupplement=false`，但后续 `checkLowHeatSausage16_18/checkLowHeatSausage` 仍可到达。

高并发正常态：

- 只有 `RESUMED` 直接 return。
- `BLOCKED/WAITING` 禁止 `resumeHighInitialization`、恢复残缺批次和新补肠。
- 既有 `checkHighHeatSausage`、成熟回搬和丢弃路径仍可执行。
- 高并发两个过渡入口现有正确语义保持不变。

运行定向测试并确认 RED。

### 4.2 最小实现

- 低并发：只在 `completedMechanicalWorkThisRound` 时 return；`canStartSupplement` 同时受 `blocksNewSupplement` 约束。
- 高并发正常态：只在 `completedMechanicalWorkThisRound` 时 return；所有新补肠/续补条件统一增加 `!blocksNewSupplement`，不改变成熟检查、回搬、丢弃和售卖条件。
- 不调整动作顺序，不新增机械调用，不清物理状态。

### 4.3 切片 2 验证、审核、提交

运行 pending 语义、低并发、高并发正常态及两个过渡入口相关测试。请独立 Codex agent 只读审核：`BLOCKED` 只禁补新肠、`RESUMED` 防止同轮双动作、成熟/丢弃不被跳过、订单/故障/机械门禁不放宽。若有 P1，停止并只修该 P1；通过后提交：

```powershell
git add <切片2精确文件>
git commit -m "fix(controller): 避免阻断双 lane 成熟收口"
```

---

## Task 5：整体回归与最终审核

### 5.1 全量验证

```powershell
.\gradlew.bat --no-daemon test --rerun-tasks
git diff --check
git status --short --branch
```

核对：

- 两个切片各自只有一个原子提交；
- 未改版本号、后端、退款、人工复位、MQTT/HTTP；
- 未新增线程、协程、轮询、定时器或长期锁；
- 未触碰无关未跟踪文件。

### 5.2 整体独立审核

请新的 Codex agent 对 `53fe6bba..HEAD` 只读整体审核，重点验证：

- 幂等收口不会释放其他 owner 或覆盖 `moveStatus=2`；
- 失败时不制造“moveStatus=0 但 owner/资源仍占用”的裂态；
- `BLOCKED` 不阻断成熟回搬/过期丢弃，且不允许新补肠；
- `RESUMED` 后不会同轮再做第二个机械动作；
- 无范围膨胀。

仅在无 P0/P1 后报告完成。保留分支，不合并、不推送、不升级版本；真机 Modbus 和机械验证单独安排。
