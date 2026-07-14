# 回搬绑定根治实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用持久化的“目标原始批次身份 + 精确回搬收口 + 搬盘 journal”替代按物理工位推断，根治低转高、高转低、重启和故障恢复中的幽灵“待回补”。

**Architecture:** `SupplementBatchIntentTarget` 保存不可变的原始批次身份；补肠完成只表示源盘已形成，搬盘/取肠/丢弃成功时精确终结对应绑定。202/203 搬盘新增本地 journal，按“journal -> 硬件动作 -> 烤盘耐久状态 -> 绑定耐久状态 -> moveStatus=0 durable -> 清 journal”顺序收口，启动时先恢复动作事实再开放 UI 和调度。

**Tech Stack:** Kotlin/Java 11、Android SharedPreferences、Fastjson、JUnit 4、Modbus 202/203 搬盘协议。

---

## Phase 0：代码事实和允许使用的现有 API

### 已核对事实

- `SupplementBatchIntentRepository.begin` 会把旧 completed heat target 合并到新 intent，但旧 target 当前没有自己的原始批次身份，见 `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementBatchIntent.kt:101-167`。
- `KaoPanUiReservationStateBuilder.applyActiveIntent` 会把 completed target 的 `returnPositionSn` 直接投影为 `WAIT_RETURN`，没有确认源盘是否仍对应原绑定，见 `KaoPanUiReservationStateBuilder.kt:21-47`。
- `KaoPanUiReservationStateBuilder` 还会独立根据 `13..18` 的有肠/口味和 `25..33` 的物理状态推断前区 `WAIT_RETURN`；即使 binding 已精确终结，这两条推断仍可能制造幽灵待回补，必须从新 schema 主链移除。
- 202/203 搬盘成功后先改内存盘状态，再调用非耐久的 `saveKaoPanListSync`；当前没有独立搬盘 journal，见 `KaoChangOperate.kt:4100-4185`。
- IN/OUT 已有 `ActionGroupSession` 和 durable SharedPreferences store，可复用其持久化模式，但不能把 PAN_MOVE 直接塞进 IN/OUT 枚举，见 `services/actiongroup/ResourceLocks.kt:66-95`、`SharedPreferenceActionGroupSessionStore.kt:8-38`。

### 允许使用的 API

- `PreferenceUtils.saveStringPreferenceDurable(file, key, value): Boolean`，内部使用 `SharedPreferences.commit()`。
- `PreferenceUtils.saveListPreferenceDurable(file, key, list): Boolean`。
- `KaoPanHelper.saveKaoPanListDurable(list)`，失败会抛出 `IllegalStateException`。
- 现有 `KaoPanHelper` 没有箱库存 durable API；必须新增 `saveKaoPanBoxListDurable(list)`，与烤盘使用同一 `commit()` 语义，不能把 `saveKaoPanBoxListSync` 当作关键成功边界。
- `SharedPreferenceActionGroupSessionStore` 的 `check(durableSave)` 模式可作为新 store 的实现参考。

### 禁止的捷径

- 不给 `KaoPan` 添加云端可见的 token 字段，避免改变 HTTP `kaoPanList` 和快照 hash。
- 不再用 `positionSn + isHasSausage` 证明绑定所有权。
- 不全局清理 `isHasGrilling` 或口味。
- 不只改 UI，也不只排除 `STATUS_SELLABLE_KEEP_WARM`。
- 不能在 pan 状态耐久保存前清 binding 或 journal。
- 不能在盘箱状态和 binding 耐久收口前清 `ActionGroupSession` 或把 `moveStatus` 恢复为 0；PanMoveJournal 必须保留到 `moveStatus=0` durable 成功后再清理，journal 存在期间统一 recovery gate 仍阻止新调度。
- `SupplyDegraded` 必须区分“同口味库存为 0/暂时不可供给”和 `IR_PICK_FAILED`、下平台隔离等硬件不确定性；前者保留 incomplete target 等待人工补货，后者保留人工确认门禁，不能共用 `skippedBySupplyDegrade` 终态。

### 调度状态机覆盖矩阵

| 调度阶段 | 真实入口/批次 | 本方案的行为 | 必须保持的不变量 |
|---|---|---|---|
| 低并发初始化 | `LowCurrentTrafficMode.initLowTrafficMode/resumeLowInitialization`，`LOW_INITIALIZATION_1_9` | target 写入原始批次身份；无 `returnPositionSn`，补肠全部完成后按现有语义清 intent，不创建回搬 binding | 不得把初始化 target 保留成 heat return binding；IN 掉电恢复仍可续补 |
| 低并发稳态 | `LOW_HEAT_13_15`、`LOW_HEAT_16_18` | 每个 13~18 target 保存 LOW 来源身份；回搬成功后精确 `markReturned` | 两组补热可依次/并行存在；不能互相清 binding |
| 低转高过渡 | `HighCurrentTrafficMode.lowTrafficModeTransition` | retained LOW target 保留原身份；新 `HIGH_HEAT_25_33` target 使用新身份；两类 target 可同容器共存 | 13~18 旧 LOW 和 25~33 新 HIGH 不得因容器 `intent.type` 被混淆 |
| 高并发初始化 | `HighCurrentTrafficMode.initHighTrafficMode/resumeHighInitialization`，`HIGH_INITIALIZATION_1_21` | 与低并发初始化相同：只有补肠生命周期，没有回搬 binding | 直接高并发开机和部分初始化恢复不能被 heat binding 门禁阻塞 |
| 高并发稳态 | `HIGH_HEAT_25_33` | 25~33 target 保存 HIGH 来源身份；回搬 1~21 时 journal 和 binding 原子收口 | 完整批次、残缺批次、故障续补都只能终结自己的 target |
| 高转低过渡 | `HighCurrentTrafficMode.highTrafficModeTransition` | 不创建新的普通高并发批次；继续消费有效 HIGH binding；10~21/25~33 搬回均走统一 journal | 停补不等于清真实 binding；订单暂停后可继续；完成前不能进入低并发新补热 |
| 低并发残留消化 | `modeType=1 && highConcurrencyResidualDrain=1`，`drainHighConcurrencyResidual` | 继续消费 HIGH binding，全部收口后再清 residual drain | 不能把高并发残留误归类成 LOW 13~18 binding |
| 回到低并发稳态 | `modeType=1, transitionMode=0, residualDrain=0` | active store 中只允许真实未完成 LOW/初始化 target；旧 HIGH/LOW returned target 必须已终结 | 新一轮 13~18 补热不得继承上一循环 returnPosition |

所有阶段共用两条底层事实：补肠成功由 IN session/target 收口，任何 202/203 烤盘搬移都由 PanMoveJournal 收口。阶段切换只能改变允许创建/消费的 target 类型，不能重新解释已有 target 的来源身份。

### 故障、维护和库存恢复覆盖矩阵

| 中断场景 | 恢复前状态 | 恢复规则 | 禁止行为 |
|---|---|---|---|
| IN 补肠动作硬件故障，动作未确认完成 | ActionGroupSession + 原 target + `requiresManualConfirm` | 维护人员确认现场安全，恢复 IN session；算法重新开启后 `handlePendingSupplementBatchIfAny` 续补同一 `originBatchId` | 恢复正常按钮直接新建批次；清掉原 returnPosition |
| IN 动作物理完成但应用未回写 | ActionGroupSession 保留完成证据 | 启动/维护恢复先按现有 IN recovery 回写盘箱，再 durable `markCompleted` | 仅看 pan 位置猜完成；重复扣箱库存 |
| 202/203 回搬中进入维护 | PanMoveJournal 保留 PREPARED/COMMAND_INTENT/ISSUED/RUNNING 检查点 | 先恢复 journal；确认本轮完成后 durable 回写 pan、精确 `markReturned`、`moveStatus=0`，最后清 journal；证据不充分则保持维护人工确认 | 重发 202；看到旧 `203=2` 就认成功 |
| 普通库存不足，尚未下发取肠动作 | heat target 保持 incomplete；availability 为无可执行同口味箱 | 不设置 `skippedBySupplyDegrade`；人工补货成功并 durable 保存箱库存后，下一轮用同一 target/origin 重新规划并续补 | 把“num=0”当终态跳过；补货后创建重复 return binding |
| 初始化批次库存不足 | 已上盘部分保留，初始化 intent 可按现有部分开盘规则收口 | 人工补货后根据空前区创建新的 LOW/HIGH initialization batch；初始化 target 不带 return binding | 为已关闭初始化批次恢复旧 batchId；把初始化 target 保留成 heat binding |
| IR_PICK_FAILED/下平台供给降级 | 可能涉及箱位故障，但不一定进入整机维护 | 不因箱库存数恢复而自动续补；必须由人工维护成功清除对应箱故障后重新评估。只有确认没有半路烤肠/动作不确定性时，才允许把目标恢复为 incomplete | 仅把 `num` 改大就清故障；自动重试物理状态不确定的 target |
| 故障超过补热续补窗口 | remaining target 按现有 10 分钟规则取消 | 清理该 target 的 return binding，已真实上盘 target 仍保留并正常回搬 | 补货或恢复后复活已取消 target；清理已上盘真实 binding |

维护页“恢复正常状态”只负责完成 Modbus、IN/OUT session 和 PanMoveJournal 恢复并解除维护门禁；仍保持算法关闭。现场手动重新开启算法后，调度入口才能消费同一批次 remaining target。人工补货成功必须以 `NetApi.updateStock` 成功、本地箱库存 durable 保存、对应供给故障恢复完成为可见边界。

---

## Phase 1：为每个 target 保存不可变来源身份

**Files:**

- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementBatchIntent.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementBatchIntentRepositoryTest.kt`

- [ ] **Step 1：先写失败测试**

覆盖：新 target 写入 `originBatchId/originBatchType`；旧 completed target 合并进新 HIGH intent 时原值不变；旧 JSON 缺字段时能读取为 `null/UNKNOWN`，不能回填成新容器的 batchId。

```kotlin
assertEquals(oldLowBatchId, retained.originBatchId)
assertEquals(SupplementBatchType.LOW_HEAT_13_15, retained.originBatchType)
assertEquals(newHighBatchId, newTarget.originBatchId)
assertEquals(SupplementBatchType.HIGH_HEAT_25_33, newTarget.originBatchType)
```

- [ ] **Step 2：运行测试，确认当前失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.SupplementBatchIntentRepositoryTest"
```

- [ ] **Step 3：增加 nullable 字段并在 begin 首次持久化前赋值**

```kotlin
data class SupplementBatchIntentTarget(
    var positionSn: Int = 0,
    var tasteCode: String? = null,
    var returnPositionSn: Int? = null,
    var completed: Boolean = false,
    var skippedBySupplyDegrade: Boolean = false,
    var skipReason: String? = null,
    var cancelledByFaultTimeout: Boolean = false,
    var cancelReason: String? = null,
    var originBatchId: String? = null,
    var originBatchType: SupplementBatchType? = null
)
```

`begin` 必须先生成新 batchId，再创建新 target；retained target 只复制，不覆盖来源字段。所有按类型过滤的逻辑优先使用 `target.originBatchType`，旧 schema 才进入迁移策略。

- [ ] **Step 4：运行定向测试并提交**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.SupplementBatchIntentRepositoryTest"
```

提交建议：`fix(android): 保留补热回搬目标原始批次身份`

---

## Phase 2：补齐 binding 的精确终结 API 和耐久 store

**Files:**

- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementBatchIntent.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementReturnBindingResolver.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementBatchIntentRepositoryTest.kt`

- [ ] **Step 1：写 markReturned/markSourceCleared 失败测试**

匹配键必须是 `originBatchId + originBatchType + sourcePositionSn`。同位置的新批次 target 不得被旧批次清理；删除最后一个 retained target 后 store 应为空。

```kotlin
repository.markReturned(
    originBatchId = oldBatchId,
    originBatchType = SupplementBatchType.LOW_HEAT_13_15,
    sourcePositionSn = 13,
    returnPositionSn = 1
)
```

- [ ] **Step 2：把关键 binding 写入从 apply 改为 durable commit**

为 `SupplementBatchIntentStore` 增加返回成功/失败的 durable 方法，SharedPreferences 实现调用 `saveStringPreferenceDurable`。关键动作收口失败必须抛错并保持设备不可继续调度。

同时把所有会改变批次生命周期的操作统一到 durable 边界：`begin`、`markCompleted`、`markSourceCleared`、故障超时取消、部分开盘清理、`clear` 都不得继续走异步 `apply()`。`markCompleted`/`markSourceCleared` 必须返回明确结果，调用方在失败时进入维护而不是继续清理会话或恢复 `moveStatus`。

- [ ] **Step 3：实现两个幂等入口**

- `markReturned(...)`：正常搬盘成功后删除精确 retained binding。
- `markSourceCleared(...)`：源盘被手动取走、订单异常取走或丢弃后取消精确 binding。

不得只按 `positionSn` 删除，不得把前区 `isHasGrilling/taste` 当成所有权依据。

故障超时释放未完成目标时先 durable 记录 target 已取消，再 durable 清理对应前区投影；若第二步失败，保留批次和维护门禁，启动恢复可重试，不能先清盘后丢失 binding。

- [ ] **Step 4：运行测试并提交**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.SupplementBatchIntentRepositoryTest"
```

提交建议：`fix(android): 精确终结补热回搬绑定`

---

## Phase 3：增加 202/203 搬盘 journal

**Files:**

- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/PanMoveJournal.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/PanMoveJournalTest.kt`

- [ ] **Step 1：写 store 和状态机失败测试**

```kotlin
enum class PanMoveCheckpoint { PREPARED, COMMAND_INTENT, COMMAND_ISSUED, OBSERVED_RUNNING }

data class PanMoveJournal(
    val moveId: String,
    val sourcePositionSn: Int,
    val targetPositionSn: Int,
    val originBatchId: String?,
    val originBatchType: SupplementBatchType?,
    val checkpoint: PanMoveCheckpoint,
    val createdAtMs: Long
)
```

测试 durable 保存失败时不得进入下一检查点；相同 moveId 重放必须幂等。

- [ ] **Step 2：实现 SharedPreferencePanMoveJournalStore**

完全复用 `SharedPreferenceActionGroupSessionStore` 的 `check(saveStringPreferenceDurable(...))` 模式，使用独立 key，不改后端协议。

- [ ] **Step 3：实现恢复决策纯函数**

规则：只有 durable `OBSERVED_RUNNING` 后观察到 `203=2` 才能自动确认本轮成功；仅 `203=2` 但从未观察到 running 可能是旧完成值，必须进入维护人工确认，禁止重发 202。

- [ ] **Step 4：运行测试并提交**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.PanMoveJournalTest"
```

提交建议：`feat(android): 增加烤盘搬移掉电恢复journal`

---

## Phase 4：把 journal 接入真实搬盘链路

**Files:**

- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/fragments/TestFragment.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/PanMoveRecoveryPolicyTest.kt`

- [ ] **Step 1：写关键顺序测试**

验证顺序固定为：

```text
PREPARED durable
-> COMMAND_INTENT durable
-> 写 202
-> COMMAND_ISSUED durable
-> 观察 203=1
-> OBSERVED_RUNNING durable
-> 观察 203=2
-> KaoPanHelper.saveKaoPanListDurable
-> markReturned durable
-> moveStatus=0 durable
-> journal.clear durable
```

顺序测试必须注入 `moveStatus=0` durable 提交失败和 journal 清理失败两个窗口：前者保持 journal/维护门禁，后者由启动按已收口状态幂等清 journal，均不得重复下发 202。

- [ ] **Step 2：在 `moveKaoPanToKaoPanInternal` 接入 journal**

PREPARED 落盘失败不得写 202；`COMMAND_INTENT` 落盘失败不得写 202；写 202 后若 `COMMAND_ISSUED` 未能落盘，启动一律按“命令可能已执行”进入维护人工确认，绝不清 journal 或重发 202。pan 落盘失败不得 markReturned；markReturned 失败不得清 journal。任何失败都保持维护门禁，不能把 `moveStatus` 先恢复为空闲。成功收口顺序固定为“pan durable -> 精确 markReturned durable -> `moveStatus=0` durable -> journal clear durable”；`moveStatus` 落盘失败必须保留 journal，journal 清理失败则由 bootstrap 根据已收口的 pan/binding/moveStatus 幂等清理。所有 202/203 失败、超时、通信断开分支都不得在 pan/binding 未确认 durable 时直接清零 `moveStatus`。

`TestFragment.kt` 中直接写 202/读 203 的测试/校准搬盘必须接入同一 journal；若该入口只允许维护校准，则必须在存在 active heat binding 或算法运行时明确拒绝，并将旁路范围写入验收，不得宣称所有 202/203 已具备掉电安全。

- [ ] **Step 3：接入源盘非正常清空入口**

在 `applySuccessfulTakePanState` 的真实成功调用链、OUT 恢复成功、丢弃成功、维护手动取肠成功后调用 `markSourceCleared`。仅当该盘匹配 active heat binding 时处理，无绑定时保持原行为。

`moveKaoPanToKaoPanManual` 若源盘存在 active heat binding，只允许搬到该 binding 的 `returnPositionSn`；其它目标必须先人工取消 binding 并进入维护确认，禁止成功后误删或留下失效 binding。

- [ ] **Step 4：人工清盘同时清 journal**

现有维护页重置 pan 和 clear intent 后，再 durable clear PanMoveJournal；任何一步失败均提示维护状态未完全恢复。

- [ ] **Step 5：运行定向测试并提交**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.PanMoveRecoveryPolicyTest"
```

提交建议：`fix(android): 原子收口搬盘状态与回搬绑定`

---

## Phase 5：固定启动恢复顺序和冲突门禁

**Files:**

- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt:1163-1268`
- Verify callers: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt:1247`
- Verify callers: `app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt:476`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/PanMoveBootstrapPolicyTest.kt`

- [ ] **Step 1：写 bootstrap 决策测试**

顺序必须是：加载 pan/intent -> 在任何其它 `loadActive()` 调用前完成旧 schema migration/reconcile 并 durable 保存 -> 恢复 IN/OUT session -> 恢复 PanMoveJournal -> durable 回写盘箱 -> reconcile binding -> 开放 UI/算法。迁移失败或存在歧义时保持维护，不能让 UI/调度先读取未迁移数据。

- [ ] **Step 2：在统一恢复入口增加冲突门禁**

在 `KaoChangOperate.recoverActionGroupStateAfterModbusReady` 内先读取 IN/OUT ActionGroupSession 和 PanMoveJournal。两者同时存在时，不猜动作先后，进入维护态人工确认；UI 显示恢复中，自动补肠/搬盘/出餐不得开放。增加进程级只读 `bootstrapReady/recoveryBlocked` 门禁：在 migration、IN/OUT session 和 PanMoveJournal 全部恢复完成前，`activeSupplementBatchIntentForUi`、MaintenanceFragment/Adapter 和所有自动调度入口只能返回恢复中/阻塞，不能提前调用未迁移的 `loadActive()`。`KaoChangAlgorithm` 和维护页继续调用这个统一入口，不新增第二套 bootstrap。

- [ ] **Step 3：实现 journal 幂等恢复**

若目标 pan 已耐久落位、binding 已删除而 journal 尚在，则只清 journal；若物理结果不可信，则保持 journal 并进入维护，不重发 202。

恢复门禁还必须覆盖“session 已存在但盘箱 durable 写入未完成”的 IN 成功态：先按 session 的 `expectedStockAfter/lastConfirmedStage` 补齐箱和盘，再 durable 收口 binding，最后才清 session、释放资源锁和恢复 `moveStatus`。因此必须改造普通串行 IN、预取流水线 IN、IN 恢复成功三条路径，禁止在 `finally` 中提前清 `ActionGroupSession`。

- [ ] **Step 4：运行测试并提交**

提交建议：`fix(android): 固定动作会话与搬盘journal恢复顺序`

---

## Phase 6：旧数据迁移与 UI 防御

**Files:**

- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementBatchIntent.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoPanUiReservationStateBuilder.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficModeStateHelper.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementReturnBindingResolver.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/model/KaoPanHelper.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceBoxFragment.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/KaoPanUiReservationStateBuilderTest.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementBatchIntentMigrationTest.kt`

- [ ] **Step 1：写本次日志序列回归测试**

构造 `13->1、14->2、15->5、16->6、17->7、18->8` 已回搬，1/2/5/6/7 被取空，随后 `33->13`；断言 1/2/5/6/7 为 `UI_RESERVATION_NONE`。

- [ ] **Step 2：定义确定性迁移规则**

- 旧 target 源盘为 BAKING/WAITING_RETURN、绑定唯一且不存在 25~33 回搬冲突：生成原始 LOW 身份。
- 旧 completed target 源盘为空：清理旧 binding。
- 源盘为 SELLABLE 或存在跨模式复用歧义：不按 position 认领，抑制 UI，等待模式边界清理或人工确认。
- 禁止把旧 target 的 origin 回填成当前容器 intent.batchId。

- [ ] **Step 3：修改 UI 契约**

新 schema completed target 只有在源盘状态和 `originBatchType` 一致时显示待回补；journal 存在时显示恢复中且不开放调度。正常 completed、尚未开始回搬且无 journal 的 target 仍可显示，不能把“无 journal”当失效依据。

把 `KaoPanUiReservationStateBuilder.apply(pans, activeIntent)` 改为显式接收只读的 PanMoveJournal/recovery gate 投影；存在未收口 journal 时不再生成普通 `WAIT_RETURN`，由统一维护/恢复状态展示接管。不能让 UI 自己直接访问 store，也不能只在调度层拦截而让页面继续显示旧 reservation。

增加启动早期测试：在 `bootstrapReady=false`、migration 未完成或 journal/session 未收口时，即使旧 intent 和 13..18/25..33 物理盘状态存在，也不得投影 `WAIT_RETURN` 或开放调度。

删除新 schema 主链中的两类物理位置推断：不能再用 `13..18.any { isHasSausage }`、前区 `isHasGrilling/taste` 或 `25..33` 的物理状态直接生成 `WAIT_RETURN`。`inferLowHeatReturnTargets`、`inferHighHeatReturnTargets` 只能作为一次性旧 schema 迁移/人工确认辅助，且迁移结果必须先持久化 origin 身份；迁移完成后 UI 只消费 binding/journal，不再按位置自行补全。`applyActiveIntent` 也必须校验 target 的 `originBatchId/originBatchType` 与源盘、返回盘，不能只看 `returnPositionSn`。

- [ ] **Step 4：删除位置推断的主状态职责**

`collectOldLowHeatReturnReservations` 仅保留旧 schema 兼容分支，新 schema 必须从 target 原始身份读取；不得让 `13..18.any { isHasSausage }` 再决定绑定所有权。

同时更新 `SupplementReturnBindingResolver`、`HighCurrentTrafficModeStateHelper`、`KaoChangOperate.activeSupplementBatchTargetPositions` 等按 `intent.type`/物理位置过滤的调用点：混合 LOW retained + HIGH 新批次时以每个 target 的 `originBatchType` 为准。

- [ ] **Step 5：运行测试并提交**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.KaoPanUiReservationStateBuilderTest" --tests "cn.niuyannet.kaochang.android.services.SupplementBatchIntentMigrationTest"
```

提交建议：`fix(android): 按批次身份生成待回补状态`

- [ ] **Step 6：修正供给降级终态语义**

把普通“同口味箱 `num=0`/暂时无可执行库存”从 `skippedBySupplyDegrade` 改为保留 incomplete target；人工补货并以 `NetApi.updateStock` 成功、`KaoPanHelper.saveKaoPanBoxListDurable` 成功、箱故障已恢复为边界后，沿同一 `originBatchId` 续补。`IR_PICK_FAILED`、`IR_THROW_FAILED`、下平台隔离和动作结果不确定仍进入 `requiresManualConfirm` 或独立硬件故障状态；若历史路径已经写入 skipped，必须提供显式 `reopenSupplyDegradedTarget`，保留 origin/return，不得靠增加库存数自动复活。串行和预取两条 `SupplyDegraded` 路径都必须覆盖。

- [ ] **Step 7：增加完整调度循环测试**

新增状态机序列测试，至少覆盖以下两条完整循环；每一步都断言 active target 的 `originBatchType`、remaining/retained 数量、journal 状态和 UI reservation：

```text
LOW_INITIALIZATION_1_9
-> 低并发稳态 LOW_HEAT_13_15/16_18
-> 低转高（旧 LOW + 新 HIGH_HEAT_25_33 并存）
-> 高并发稳态
-> 高转低
-> highConcurrencyResidualDrain
-> 低并发稳态重新开新 LOW heat batch
```

```text
HIGH_INITIALIZATION_1_21
-> 高并发稳态 HIGH_HEAT_25_33
-> 高转低
-> highConcurrencyResidualDrain
-> 低并发稳态
-> 再次低转高
```

初始化阶段必须分别覆盖完整初始化、部分初始化续补、IN 动作成功后掉电恢复；过渡阶段必须覆盖订单暂停、补肠残缺批次、搬盘 journal 恢复和源盘被人工取走/丢弃。

- [ ] **Step 8：增加维护恢复与人工补货测试**

```text
LOW/HIGH heat batch补到一半
-> IN硬件故障进入维护
-> 保留origin和remaining target
-> 人工确认安全并恢复正常
-> 手动开启算法
-> 续补同一target且不重复return binding
```

```text
heat batch补到一半同口味库存变为0
-> remaining target保持incomplete/BLOCKED
-> 人工加满烤肠箱并恢复箱故障状态
-> 下一轮续补原target
-> originBatchId、returnPositionSn不变
```

另测初始化批次库存不足后的“部分开盘 -> 补货 -> 新初始化批次”，以及 `IR_PICK_FAILED` 未完成人工维护时即使库存数增加也不得自动续补。所有测试分别在低并发稳态、低转高、高并发稳态和高转低保留故障批次路径执行。

- [ ] **Step 9：增加 IN durable 收口回归测试**

分别注入以下故障点并断言重启后的唯一结果：盘状态写入失败、箱库存写入失败、binding 写入失败、session 清理失败。任何注入点都必须保留可恢复证据，不能出现“物理已完成但 session/binding 已丢失”或“session 已清但盘箱仍是旧状态”。同时覆盖普通串行 IN、预取 IN、IN 恢复成功和维护人工补货后的续补。

---

## Phase 7：完整验证和真机验收

- [ ] **Step 1：运行完整单元测试**

```powershell
.\gradlew.bat test
```

要求：0 个失败；现有环境初始化失败必须先定位，不能忽略后宣称通过。

- [ ] **Step 2：构建调试包**

```powershell
.\gradlew.bat assembleDebug
```

- [ ] **Step 3：静态反模式检查**

确认新 schema 路径不再用以下模式判所有权：

```text
positionSn in 13..18 && isHasSausage
positionSn in 25..33 && isHasSausage
全局 isHasGrilling=false
源盘状态落盘前 clear binding/journal
203=2 且未观察 running 就确认成功
```

增加静态/单测断言：新 schema 下仅改变 13..18 或 25..33 的物理状态、但不存在匹配的 `originBatchId/originBatchType` target 时，`inferLowHeatReturnTargets`、`inferHighHeatReturnTargets`、`applyActiveIntent`、`collectOldLowHeatReturnReservations`、`SupplementReturnBindingResolver` 和 `activeSupplementBatchTargetPositions` 均不得新增 UI reservation 或调度 binding。

- [ ] **Step 4：真机序列验收**

覆盖：低并发两组补热并行、低转高期间 25~33 补肠、旧批次回搬后连续出餐、33->13 复用、搬盘过程中断电、out1 后断电、IN/OUT session 与 move journal 冲突、人工清盘。

- [ ] **Step 5：发布前双模型 diff 审核**

使用 `D:\LeYe\tools\claude-review.ps1` 独立审核实际 diff、持久化迁移、硬件动作顺序和回滚风险。

---

## 验收标准

- 新批次复用旧物理工位时，旧 binding 不会认领新烤肠。
- 搬盘成功后 binding 在同一 durable 收口链中精确终结。
- 任一关键落盘失败都不会继续下发下一硬件动作或开放调度。
- 重启不会因 203 旧完成值重复搬盘或误判成功。
- 本次日志序列中工位 1、2、5、6、7 不再显示幽灵“待回补”。
- 真实未完成补肠、等待回搬、故障续补和高并发回搬仍保持原行为。
- 不修改 MQTT/HTTP 后端协议，不向 `KaoPan` 增加新云端字段。
