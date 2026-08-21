# Mechanical Core Foundation Implementation Plan

> **状态（2026-08-21）：PAUSED_FOR_2_5_51_REBASELINE。** 生产 Android 已前进到 Q=`2f0b36e08cedb8f4c06cd4d2550e2cec08558bd7`（`2.5.51/301`）。本计划和当前 partial core 原样保留，但在完成 `2026-08-21-baseline-2.5.51-sync-plan.md`、重新基线化并复审前不得继续执行 Coordinator 或 App 接线步骤。

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task. This repository uses one shared checkout and one implementation slice; do not dispatch parallel editing agents. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不接入 App、不触碰 Modbus/MQTT/HTTP/库存的前提下，建立纯 Kotlin/JVM `mechanical-core`，用单一 `MechanicalOperationCoordinator` 和 `OperationLedgerSnapshot` 定义机械 operation、状态推进、资源租约、固定仲裁、安全 checkpoint、迟到回调围栏、`moveStatus` 投影和冷启动恢复决策。

**Architecture:** 新模块只包含不可变领域模型、ledger/effect 端口和确定性策略；Android SharedPreferences、协程、Modbus、MQTT 与旧业务调用点均留到后续影子接线切片。所有改变先通过 `operationId + revision + ledgerRevision` 校验，再由 ledger compare-and-set 落盘；落盘失败时不返回可执行机械副作用。ledger 最多保存一个 `pending`、一个 `running`、一个不持有资源的 `paused` 和一个不占运行槽的 `terminal` operation；任意固定高优先级候选的安全接管都必须由同一次 CAS 完成。带 boot session 的 admission gate 未由唯一 Coordinator 完成启动恢复前，任何新任务均被阻止。

**Tech Stack:** Kotlin 1.9、Kotlin/JVM、Java 11 bytecode、JUnit 4.13.2、Gradle 8.7。

---

## 1. 切片起点与权限门禁

- 仓库：`D:\LeYe\cl-sausage-controller-refactor`
- 分支：`fix/mechanical-core-foundation`
- 起点：`49f9ce431b38f35d561ab8098d0a118fb556c439`
- 起点源码版本：`2.5.48/298`
- 生产基线：`5e0d2f78d21fa3e3529c04da2cb9edd6c68a61cb`
- 起点 GitHub `develop`：`49f9ce431b38f35d561ab8098d0a118fb556c439`
- 开始实施前必须确认工作树除本计划外干净，并由审核会话A对本计划给出 `Approve`。
- 用户已授权在本目标、白名单、自动化和审核门禁内持续实施、提交和推送；仍不授权发布、部署、真实 MQTT、RS485/Modbus、机械或设备操作。

## 2. 非目标

- 不修改 `app` 源码或 `app/build.gradle.kts`，不把新模块接入任何生产调用链。
- 不迁移或删除 `OrderDeliveryClaim`、`ActionGroupSession`、`SupplementPrefetchPipelineSession`、`SupplementBatchIntent`、`PanMoveJournal`、`DiscardJournal`、`ResetCloseoutRecord`、`ResourceLocks` 或旧 `MechanicalMoveClaimCoordinator`。
- 不改变 `moveStatus` 任何现有写入点；本切片只定义纯投影函数。
- 不实现 SharedPreferences/数据库文件等 Android ledger 适配器，不下发 Modbus，不连接 MQTT/HTTP/后端。
- 不做订单 `out1..out5` 接线、补肠暂停/续作接线、算法 B、新 UI、MQTT 5、运行时算法热切换或双 lane 真并行。
- 不声称 JVM 测试证明真实断电、RS485/Modbus、机械、温控或长稳行为。

## 3. 修改白名单

只允许以下路径发生变化：

- `settings.gradle.kts`
- `mechanical-core/build.gradle.kts`
- `mechanical-core/src/main/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalOperationModels.kt`
- `mechanical-core/src/main/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalOperationStateMachine.kt`
- `mechanical-core/src/main/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalResourcePolicy.kt`
- `mechanical-core/src/main/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalOperationArbiter.kt`
- `mechanical-core/src/main/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalRecoveryPolicy.kt`
- `mechanical-core/src/main/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalOperationCoordinator.kt`
- `mechanical-core/src/test/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalOperationStateMachineTest.kt`
- `mechanical-core/src/test/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalResourcePolicyTest.kt`
- `mechanical-core/src/test/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalOperationArbiterTest.kt`
- `mechanical-core/src/test/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalRecoveryPolicyTest.kt`
- `mechanical-core/src/test/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalOperationCoordinatorTest.kt`
- `docs/refactor/2026-08-20-mechanical-core-foundation-plan.md`
- `docs/refactor/2026-08-20-mechanical-core-foundation-verification.md`
- `docs/refactor/CURRENT_STATUS.md`

任何需要修改 `app/**`、现有 core 模块或白名单外路径的发现，只记录为后续切片输入并立即停止当前实施。

## 4. 状态与资源不变量

1. `operationId` 非空且在 operation 生命周期内不变；每次成功状态改变都令 operation `revision + 1`，每次成功 ledger CAS 都令 `ledgerRevision + 1`。
2. 一个 ledger 最多一个 `pending`、一个 `running`、一个 `paused`、一个 `terminal` operation；四者 `operationId` 两两不同。`pending` 必须是 `REQUESTED`，`paused` 必须是 `PAUSED`，`terminal` 必须是 `OPERATION_COMPLETED` 且不持有资源。完成 CAS 必须把 `running` 清空并写入 `terminal`，不得占住运行槽。
3. `REQUESTED`、`PAUSED`、`OPERATION_COMPLETED` 不持有资源；`RECOVERY_REQUIRED` 默认保留原资源和忙态。
4. 状态主链固定为 `REQUESTED → ADMITTED → COMMAND_PREPARED → COMMAND_DISPATCHED → RESULT_CONFIRMED → EFFECT_APPLIED → STEP_COMPLETED`；最后一步才允许进入 `OPERATION_COMPLETED`，非最后一步只能以新 step 回到 `ADMITTED`。
5. `COMMAND_PREPARED` 表示命令意图已由 ledger CAS 成功耐久记录；只有协调器返回 `DispatchAllowed` 后 adapter 才可下发命令。若进程在真实下发与 `markDispatched` 落盘之间崩溃，冷启动不得仅凭 `COMMAND_PREPARED` 或寄存器空闲判定未下发。
6. 下位机成功结果只能推进到 `RESULT_CONFIRMED`；库存/烤盘效果 CAS 成功后才进入 `EFFECT_APPLIED`；效果失败不得开始下一步。
7. 失败、超时、控制流取消、证据未知均不得自动完成或盲目重发；命令准备后发生不确定性时进入 `RECOVERY_REQUIRED`。
8. 暂停只允许在 `ADMITTED` 或 `STEP_COMPLETED` 且 checkpoint 明确 `SAFE_TO_PAUSE` 时发生；暂停时必须持久化 continuation cursor。`ADMITTED` 恢复到当前 step，非末尾 `STEP_COMPLETED` 恢复到下一 step，末尾 `STEP_COMPLETED` 只允许进入完成收口，绝不重跑已完成 step。
9. 资源释放只由匹配 `operationId + revision` 的暂停或明确完成转换产生；普通 `finally`、超时、取消、内存锁为空均没有释放 API。
10. 每次命令准备生成并持久化 `CommandAttemptToken(operationId, dispatchRevision, stepId, attemptId)`；结果回调先按该 token 识别当前/已确认 attempt。已确认 token 返回 `DuplicateEvidence`，其他旧 token 返回 `StaleCommandEvidence`；普通 owner/revision 不匹配返回 `StaleOperationEvidence`，均不得写 ledger、资源、效果或状态。
11. 固定仲裁优先级不可注入配置：紧急停机/食品安全 ＞ 已准备/已下发且不可安全抢占的当前命令 attempt ＞ 已付款订单出餐 ＞ 安全回搬 ＞ 补肠 ＞ 清洁/普通维护。operation 必须携带受 kind 约束的 `admissionClass`，不能从普通 `PAN_MOVE` 猜测安全回搬。`COMMAND_PREPARED` 已产生外部 dispatch permit，普通候选不得抢占。
12. 只有已暂停 operation 单独存在时 `moveStatus=0`；`RECOVERY_REQUIRED` 仍按 operation 类型投影忙态，禁止错误归零。
13. admission gate 必须绑定当前 `bootSessionId`；上一进程留下的 `OPEN` 不能在新进程复用。除四个明确列出的恢复专用入口外，所有普通 Coordinator API 都必须在当前 boot gate 为 `OPEN` 时才能执行。
14. `RecoveryObservationCursor` 只在恢复 gate 下存在；`applyRecoveredEffect` 成功、terminal 安全收口或 gate 打开时必须清除。`OPEN` gate、`running=null` 或 cursor attempt 不匹配当前 running 的 current/last attempt 都是非法 ledger。

## 5. 文件职责

- `MechanicalOperationModels.kt`：operation、pending/running/paused/terminal ledger、boot admission gate、continuation、command/effect token、resource、evidence、结果枚举及构造校验。
- `MechanicalOperationStateMachine.kt`：只负责单个 operation 的合法状态转换和 revision 围栏。
- `MechanicalResourcePolicy.kt`：资源集合冲突、精确认领、安全暂停释放、恢复重领规则。
- `MechanicalOperationArbiter.kt`：固定优先级完整矩阵和“安全 checkpoint 才暂停补肠”的纯决策。
- `MechanicalRecoveryPolicy.kt`：ledger 读取状态、Modbus 连接、至少两次递增且关联当前 command attempt 的稳定快照和命令/效果证据的冷启动分类。
- `MechanicalOperationCoordinator.kt`：唯一写入口；执行 startup recovery、管理当前 boot admission gate、加载 ledger、校验 token、调用纯策略、CAS 落盘，只有 CAS 成功才返回下一副作用许可；通过幂等 effect 端口收口“效果已写但 ledger CAS 失败”。
- 五个测试文件：分别覆盖状态笛卡尔边界、资源/暂停、仲裁、恢复、CAS/迟到/重复/故障注入。

## 6. 故障注入矩阵

| 场景 | 注入位置 | 预期 |
|---|---|---|
| ledger 初读损坏 | `OperationLedgerPort.load()` 返回 `Corrupt` | 阻止新任务，`RECOVERY_REQUIRED`，无 dispatch |
| CAS 落盘失败 | `compareAndSet` 返回 `false` | 返回 `LedgerConflict`，无状态推进、无资源释放、无 dispatch |
| 命令意图落盘后崩溃 | ledger 为 `COMMAND_PREPARED` | 默认结果不明；只有额外证据明确 `CONFIRMED_NOT_DISPATCHED` 才允许单次下发，不能从寄存器空闲猜测未下发 |
| 恢复下发许可后再次崩溃 | `COMMAND_PREPARED + old DispatchIssued`，物理可能已运行/完成 | 同一 proof 不再签发；匹配新快照先单独 CAS 到 `COMMAND_DISPATCHED`，COMPLETED 再下一 CAS 确认结果，禁止重发/跳级 |
| 下发后断电 | `COMMAND_DISPATCHED` + 稳定 `RUNNING` | 保持忙态并等待，不重发 |
| 已完成但效果未提交 | 稳定 `COMPLETED` + `effect=NOT_APPLIED` | 只允许幂等应用效果，不重发机械命令 |
| Modbus 未连接/超时 | 无稳定快照 | 阻止新任务，保持 ownership/resource |
| 重复结果 | 相同 `CommandAttemptToken` 再次确认 | 第二次返回 `DuplicateEvidence`，不重复应用效果 |
| 迟到结果 | command attempt 或 owner/revision 已变化 | 返回对应 `StaleCommandEvidence` / `StaleOperationEvidence`，ledger 不变 |
| 陈旧完成寄存器 | 两个稳定 `COMPLETED` 但 command 关联缺失/不匹配 | `RECOVERY_REQUIRED`，禁止确认当前结果或应用效果 |
| 效果成功但 ledger CAS 失败 | effect 端口已记录 token，ledger 仍为 `RESULT_CONFIRMED` | 重试同一 token 得到 `ALREADY_APPLIED`，再 CAS，效果不重复 |
| 协程取消等价事件 | `COMMAND_PREPARED` 或以后 | 进入 `RECOVERY_REQUIRED`，不释放资源 |
| 订单打断补肠 | 补肠到安全 checkpoint | 先暂停并释放，再允许订单；不安全时只排队 |
| 暂停与订单准入 CAS 失败 | pending 付款订单 + safe 补肠 | ledger 完全不变，不出现“补肠已停但订单未接入”中间态 |
| `out5` 超时 | 已 dispatch 且结果未知 | `RECOVERY_REQUIRED`，禁止完成订单或归零 |
| `in2` 后重启 | 已 dispatch + 未知/运行快照 | 等待或人工恢复，禁止从头重发 `in1/in2` |
| 暂停后恢复 | paused operation 无资源 | 订单收口并重校验后才重领资源和回到 `ADMITTED` |

## 7. 实施任务

### Task 1: 建立模块与不可变领域模型

**Files:**
- Modify: `settings.gradle.kts`
- Create: `mechanical-core/build.gradle.kts`
- Create: `mechanical-core/src/main/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalOperationModels.kt`
- Test: `mechanical-core/src/test/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalOperationStateMachineTest.kt`

- [ ] **Step 1: 先写模型构造失败测试**

```kotlin
@Test
fun `operation rejects blank id invalid revision empty steps and duplicate step ids`() {
    assertThrows(IllegalArgumentException::class.java) { operation(operationId = "") }
    assertThrows(IllegalArgumentException::class.java) { operation(revision = 0) }
    assertThrows(IllegalArgumentException::class.java) { operation(steps = emptyList()) }
    assertThrows(IllegalArgumentException::class.java) {
        operation(steps = listOf(step("out1"), step("out1")))
    }
}

@Test
fun `ledger permits one pending one running and one distinct paused operation only`() {
    assertThrows(IllegalArgumentException::class.java) {
        OperationLedgerSnapshot(
            ledgerRevision = 1,
            admissionGate = gate(),
            pending = operation(operationId = "same", state = MechanicalOperationState.REQUESTED),
            running = operation(operationId = "same", state = MechanicalOperationState.ADMITTED),
            paused = operation(operationId = "same", state = MechanicalOperationState.PAUSED)
        )
    }
    assertThrows(IllegalArgumentException::class.java) {
        OperationLedgerSnapshot(
            ledgerRevision = 2,
            admissionGate = openGate(),
            running = dispatched(),
            recoveryObservationCursor = matchingCursor(dispatched())
        )
    }
    assertThrows(IllegalArgumentException::class.java) {
        OperationLedgerSnapshot(
            ledgerRevision = 2,
            admissionGate = recoveryGate(),
            running = null,
            recoveryObservationCursor = cursorFor(commandAttempt())
        )
    }
}

@Test
fun `admission gate and recovery authorization reject invalid identity combinations`() {
    assertThrows(IllegalArgumentException::class.java) {
        MechanicalAdmissionGate("", AdmissionGateState.STARTUP_RECOVERY_PENDING)
    }
    assertThrows(IllegalArgumentException::class.java) {
        RecoveryAuthorization.DispatchIssued("", "boot-1", "proof-1", commandAttempt())
    }
    assertThrows(IllegalArgumentException::class.java) {
        RecoveryAuthorization.DispatchIssued("auth-1", "boot-1", "", commandAttempt())
    }
    assertThrows(IllegalArgumentException::class.java) {
        MechanicalAdmissionGate(
            bootSessionId = "boot-2",
            state = AdmissionGateState.RECOVERY_REQUIRED,
            recoveryAuthorization = dispatchAuthorization(bootSessionId = "boot-1")
        )
    }
    assertThrows(IllegalArgumentException::class.java) {
        MechanicalAdmissionGate(
            bootSessionId = "boot-1",
            state = AdmissionGateState.OPEN,
            recoveryAuthorization = dispatchAuthorization(bootSessionId = "boot-1")
        )
    }
    assertThrows(IllegalArgumentException::class.java) {
        MechanicalAdmissionGate(
            bootSessionId = "boot-1",
            state = AdmissionGateState.STARTUP_RECOVERY_PENDING,
            recoveryAuthorization = dispatchAuthorization(bootSessionId = "boot-1")
        )
    }
}

@Test
fun `recovery observation cursor requires a valid attempt and positive monotonic evidence`() {
    assertThrows(IllegalArgumentException::class.java) {
        RecoveryObservationCursor(commandAttempt(), 0, 1)
    }
    assertThrows(IllegalArgumentException::class.java) {
        RecoveryObservationCursor(commandAttempt(), 1, -1)
    }
    assertThrows(IllegalArgumentException::class.java) {
        OperationLedgerSnapshot(
            ledgerRevision = 2,
            admissionGate = recoveryGate(),
            running = dispatched(),
            recoveryObservationCursor = RecoveryObservationCursor(
                oldCommandAttempt(),
                lastAcceptedSampleSequence = 2,
                lastAcceptedSampledAtMs = 10
            )
        )
    }
}
```

- [ ] **Step 2: 运行测试并确认 RED 是模块或类型尚不存在**

Run: `.\gradlew.bat :mechanical-core:test --tests "*MechanicalOperationStateMachineTest"`

Expected: FAIL，原因是 `:mechanical-core`/模型尚未定义，而不是测试语法错误。

- [ ] **Step 3: 注册模块并创建与现有 core 一致的构建文件**

```kotlin
// settings.gradle.kts
include(":mechanical-core")
```

```kotlin
// mechanical-core/build.gradle.kts
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "11"
}

dependencies {
    testImplementation(libs.junit)
}
```

- [ ] **Step 4: 实现领域模型及构造校验**

必须定义以下精确公开类型；所有 data class 在 `init` 中执行上述不变量校验：

```kotlin
enum class MechanicalOperationKind { SUPPLEMENT, ORDER_DELIVERY, PAN_MOVE, DISCARD, CLEANING }
enum class MechanicalOperationState {
    REQUESTED, ADMITTED, COMMAND_PREPARED, COMMAND_DISPATCHED,
    RESULT_CONFIRMED, EFFECT_APPLIED, STEP_COMPLETED,
    PAUSED, RECOVERY_REQUIRED, OPERATION_COMPLETED
}
enum class MechanicalResource { BOTTOM, LIFT, ABOVE, GATE, STICK }
enum class CheckpointSafety { UNSAFE, SAFE_TO_PAUSE }
enum class MechanicalPhysicalResult { NONE, RUNNING, SUCCEEDED, FAILED, UNKNOWN }
enum class MechanicalEffectState { NOT_APPLIED, APPLIED }
enum class MechanicalAdmissionClass {
    FOOD_SAFETY,
    PAID_ORDER_DELIVERY,
    SAFE_RETURN,
    SUPPLEMENT,
    CLEANING_OR_MAINTENANCE
}
sealed interface ResumeCursor {
    data class ExecuteStep(val stepIndex: Int) : ResumeCursor
    data object CompleteOperation : ResumeCursor
}

data class MechanicalStep(
    val stepId: String,
    val commandIdempotencyKey: String,
    val effectIdempotencyKey: String,
    val resources: Set<MechanicalResource>
)
data class MechanicalCheckpoint(val stepId: String, val safety: CheckpointSafety)
data class CommandAttemptToken(
    val operationId: String,
    val dispatchRevision: Long,
    val stepId: String,
    val attemptId: String
)
data class EffectToken(
    val operationId: String,
    val stepId: String,
    val effectIdempotencyKey: String
)
enum class AdmissionGateState { STARTUP_RECOVERY_PENDING, OPEN, RECOVERY_REQUIRED }
sealed interface RecoveryAuthorization {
    val authorizationId: String
    val bootSessionId: String

    data class DispatchIssued(
        override val authorizationId: String,
        override val bootSessionId: String,
        val proofId: String,
        val commandAttempt: CommandAttemptToken
    ) : RecoveryAuthorization

    data class EffectIssued(
        override val authorizationId: String,
        override val bootSessionId: String,
        val effectToken: EffectToken
    ) : RecoveryAuthorization
}
data class RecoveryAuthorizationToken(val bootSessionId: String, val authorizationId: String)
data class RecoveryObservationCursor(
    val commandAttempt: CommandAttemptToken,
    val lastAcceptedSampleSequence: Long,
    val lastAcceptedSampledAtMs: Long
)
data class MechanicalAdmissionGate(
    val bootSessionId: String,
    val state: AdmissionGateState,
    val reason: String? = null,
    val recoveryAuthorization: RecoveryAuthorization? = null
)

data class MechanicalOperation(
    val operationId: String,
    val revision: Long,
    val kind: MechanicalOperationKind,
    val admissionClass: MechanicalAdmissionClass,
    val state: MechanicalOperationState,
    val steps: List<MechanicalStep>,
    val currentStepIndex: Int,
    val heldResources: Set<MechanicalResource>,
    val checkpoint: MechanicalCheckpoint?,
    val resumeCursor: ResumeCursor?,
    val currentCommandAttempt: CommandAttemptToken?,
    val lastConfirmedCommandAttempt: CommandAttemptToken?,
    val physicalResult: MechanicalPhysicalResult,
    val effectState: MechanicalEffectState,
    val recoveryReason: String? = null
)

data class OperationLedgerSnapshot(
    val ledgerRevision: Long,
    val admissionGate: MechanicalAdmissionGate,
    val pending: MechanicalOperation? = null,
    val running: MechanicalOperation? = null,
    val paused: MechanicalOperation? = null,
    val terminal: MechanicalOperation? = null,
    val recoveryObservationCursor: RecoveryObservationCursor? = null
)

sealed interface OperationLedgerRead {
    data object Absent : OperationLedgerRead
    data class Present(val snapshot: OperationLedgerSnapshot) : OperationLedgerRead
    data class Corrupt(val reason: String) : OperationLedgerRead
}

interface OperationLedgerPort {
    fun load(): OperationLedgerRead
    fun compareAndSet(expectedLedgerRevision: Long?, next: OperationLedgerSnapshot): Boolean
}

enum class EffectApplyResult { APPLIED, ALREADY_APPLIED, FAILED }
interface MechanicalEffectPort {
    fun applyOnce(token: EffectToken): EffectApplyResult
}

data class OperationToken(val operationId: String, val revision: Long)
```

构造校验还必须固定 kind/class 组合：`SUPPLEMENT→SUPPLEMENT`、`ORDER_DELIVERY→PAID_ORDER_DELIVERY`、`CLEANING→CLEANING_OR_MAINTENANCE`；`PAN_MOVE` 只允许 `SAFE_RETURN` 或 `CLEANING_OR_MAINTENANCE`；`DISCARD` 只允许 `FOOD_SAFETY` 或 `CLEANING_OR_MAINTENANCE`。这使安全回搬和普通搬盘不可混淆，也不增加热配置入口。所有 boot/authorization/proof ID 必须非空；authorization 的 boot 必须等于 gate boot；recovery authorization 只允许出现在 `RECOVERY_REQUIRED` gate，`OPEN` 和 `STARTUP_RECOVERY_PENDING` 均不得携带。RecoveryObservationCursor 必须绑定完整 command attempt，`lastAcceptedSampleSequence > 0` 且 `lastAcceptedSampledAtMs >= 0`；cursor 只允许在 recovery gate 下存在，必须有 running，且 attempt 必须等于 running 的 `currentCommandAttempt` 或 `lastConfirmedCommandAttempt`，否则 ledger 构造失败并由 adapter 解码为 `Corrupt`。

- [ ] **Step 5: 运行定向测试确认 GREEN**

Run: `.\gradlew.bat :mechanical-core:test --tests "*MechanicalOperationStateMachineTest"`

Expected: 新增模型测试通过。

### Task 2: 固定步骤状态机与 revision 围栏

**Files:**
- Create: `mechanical-core/src/main/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalOperationStateMachine.kt`
- Modify: `mechanical-core/src/test/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalOperationStateMachineTest.kt`

- [ ] **Step 1: 写完整主链、非法跳转和多 step 循环的失败测试**

```kotlin
@Test
fun `single step follows the exact durable phase chain`() {
    val states = mutableListOf(MechanicalOperationState.REQUESTED)
    var current = operation()
    current = applied(MechanicalOperationStateMachine.admit(current, current.token())).also { states += it.state }
    current = applied(MechanicalOperationStateMachine.prepareCommand(current, current.token(), "attempt-1")).also { states += it.state }
    val commandAttempt = requireNotNull(current.currentCommandAttempt)
    current = applied(MechanicalOperationStateMachine.markDispatched(current, commandAttempt)).also { states += it.state }
    current = applied(
        MechanicalOperationStateMachine.confirmResult(
            current,
            commandAttempt,
            MechanicalPhysicalResult.SUCCEEDED
        )
    ).also { states += it.state }
    current = applied(MechanicalOperationStateMachine.markEffectApplied(current, current.token())).also { states += it.state }
    current = applied(MechanicalOperationStateMachine.completeStep(current, current.token())).also { states += it.state }
    current = applied(MechanicalOperationStateMachine.completeOperation(current, current.token())).also { states += it.state }
    assertEquals(MechanicalOperationState.values().filterNot {
        it == MechanicalOperationState.PAUSED || it == MechanicalOperationState.RECOVERY_REQUIRED
    }, states)
}

@Test
fun `next step returns from completed step to admitted and increments step index`() {
    val updated = MechanicalOperationStateMachine.startNextStep(
        twoStepOperationAtFirstStepCompleted(), expectedToken()
    ).updated
    assertEquals(MechanicalOperationState.ADMITTED, updated.state)
    assertEquals(1, updated.currentStepIndex)
}

@Test
fun `wrong owner or revision isolates late callback`() {
    assertTrue(MechanicalOperationStateMachine.confirmResult(dispatched(), oldCommandAttempt()) is TransitionResult.StaleCommandEvidence)
}

@Test
fun `same confirmed command attempt is duplicate throughout all later states`() {
    listOf(
        MechanicalOperationState.RESULT_CONFIRMED,
        MechanicalOperationState.EFFECT_APPLIED,
        MechanicalOperationState.STEP_COMPLETED,
        MechanicalOperationState.PAUSED,
        MechanicalOperationState.RECOVERY_REQUIRED,
        MechanicalOperationState.OPERATION_COMPLETED
    ).forEach { state ->
        val later = operationWithConfirmedAttempt(state)
        assertTrue(
            MechanicalOperationStateMachine.confirmResult(
                later,
                requireNotNull(later.lastConfirmedCommandAttempt),
                MechanicalPhysicalResult.SUCCEEDED
            ) is TransitionResult.DuplicateEvidence
        )
    }
}

@Test
fun `confirm result has an explicit state and evidence matrix`() {
    MechanicalOperationState.values().forEach { state ->
        val current = operationForConfirmMatrix(state)
        val result = MechanicalOperationStateMachine.confirmResult(
            current,
            currentAttemptForMatrix(current),
            MechanicalPhysicalResult.SUCCEEDED
        )
        when {
            state == MechanicalOperationState.COMMAND_DISPATCHED -> assertTrue(result is TransitionResult.Applied)
            current.lastConfirmedCommandAttempt != null -> assertTrue(result is TransitionResult.DuplicateEvidence)
            else -> assertTrue(result is TransitionResult.Rejected || result is TransitionResult.StaleCommandEvidence)
        }
    }
}

@Test
fun `every public transition rejects every illegal source state`() {
    val legalSources = mapOf(
        "admit" to setOf(MechanicalOperationState.REQUESTED),
        "prepareCommand" to setOf(MechanicalOperationState.ADMITTED),
        "markDispatched" to setOf(MechanicalOperationState.COMMAND_PREPARED),
        "markEffectApplied" to setOf(MechanicalOperationState.RESULT_CONFIRMED),
        "completeStep" to setOf(MechanicalOperationState.EFFECT_APPLIED),
        "startNextStep" to setOf(MechanicalOperationState.STEP_COMPLETED),
        "completeOperation" to setOf(MechanicalOperationState.STEP_COMPLETED)
    )
    transitionInvocationsExcludingConfirmResult().forEach { (name, invoke) ->
        MechanicalOperationState.values()
            .filterNot(legalSources.getValue(name)::contains)
            .forEach { state ->
                assertTrue("$name accepted $state", invoke(operationIn(state)) is TransitionResult.Rejected)
            }
    }
}

@Test
fun `operation and ledger invariant matrix either constructs valid state or rejects explicitly`() {
    for (state in MechanicalOperationState.values()) {
        for (hasResources in listOf(false, true)) {
            for (hasCursor in listOf(false, true)) {
                val result = runCatching { operationIn(state, hasResources, hasCursor) }
                assertEquals(isValidCombination(state, hasResources, hasCursor), result.isSuccess)
            }
        }
    }
}
```

- [ ] **Step 2: 运行定向测试确认按预期 RED**

Run: `.\gradlew.bat :mechanical-core:test --tests "*MechanicalOperationStateMachineTest"`

Expected: FAIL，缺少状态机和转换结果。

- [ ] **Step 3: 实现唯一合法边、物理结果和效果顺序**

公开 API 固定为：

```kotlin
sealed interface TransitionResult {
    data class Applied(val updated: MechanicalOperation) : TransitionResult
    data class Rejected(val reason: String) : TransitionResult
    data class StaleOperationEvidence(val expected: OperationToken, val actual: OperationToken) : TransitionResult
    data class StaleCommandEvidence(
        val expected: CommandAttemptToken?,
        val actual: CommandAttemptToken
    ) : TransitionResult
    data object DuplicateEvidence : TransitionResult
}

object MechanicalOperationStateMachine {
    fun admit(current: MechanicalOperation, token: OperationToken): TransitionResult
    fun prepareCommand(current: MechanicalOperation, token: OperationToken, attemptId: String): TransitionResult
    fun markDispatched(current: MechanicalOperation, commandAttempt: CommandAttemptToken): TransitionResult
    fun confirmResult(
        current: MechanicalOperation,
        commandAttempt: CommandAttemptToken,
        result: MechanicalPhysicalResult
    ): TransitionResult
    fun markEffectApplied(current: MechanicalOperation, token: OperationToken): TransitionResult
    fun completeStep(current: MechanicalOperation, token: OperationToken): TransitionResult
    fun startNextStep(current: MechanicalOperation, token: OperationToken): TransitionResult
    fun completeOperation(current: MechanicalOperation, token: OperationToken): TransitionResult
    fun requireRecovery(current: MechanicalOperation, token: OperationToken, reason: String): TransitionResult
}
```

实现必须使用显式 `when (current.state)`；不得使用 ordinal 比较。测试文件必须实现上面列出的 `transitionInvocationsExcludingConfirmResult/isValidCombination` 和独立 confirm evidence 矩阵，不得删减为几个示例状态。`prepareCommand` 在更新后的 operation 中保存 attempt token，token 的 `dispatchRevision` 等于该次更新后的 revision。`markDispatched/confirmResult` 按完整 command token 校验，不用当前 operation revision 冒充回调 identity。`confirmResult` 必须先比较 `lastConfirmedCommandAttempt`：同 token 在所有后续状态都返回 `DuplicateEvidence`；否则只有 `COMMAND_DISPATCHED + currentCommandAttempt` 能处理新结果。`SUCCEEDED` 进入 `RESULT_CONFIRMED` 并保存 token；`FAILED` 或 `UNKNOWN` 进入 `RECOVERY_REQUIRED`；其他旧 token 返回 `StaleCommandEvidence`，无适用 attempt 的非法状态返回 `Rejected`。每个 `Applied` 只增加一次 revision。

- [ ] **Step 4: 运行状态机测试确认 GREEN**

Run: `.\gradlew.bat :mechanical-core:test --tests "*MechanicalOperationStateMachineTest"`

Expected: 全部通过。

### Task 3: 资源租约、安全 checkpoint 与暂停/恢复

**Files:**
- Create: `mechanical-core/src/main/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalResourcePolicy.kt`
- Create: `mechanical-core/src/test/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalResourcePolicyTest.kt`

- [ ] **Step 1: 写 owner/revision、暂停和未知状态失败测试**

```kotlin
@Test
fun `release rejects wrong operation or revision`() {
    val current = admittedOperation()
    assertTrue(MechanicalResourcePolicy.pauseAtCheckpoint(current, OperationToken("other", current.revision)) is TransitionResult.StaleOperationEvidence)
    assertTrue(MechanicalResourcePolicy.pauseAtCheckpoint(current, OperationToken(current.operationId, current.revision - 1)) is TransitionResult.StaleOperationEvidence)
}

@Test
fun `admission claims the current step exact resources and rejects overlap`() {
    val requested = operation(state = MechanicalOperationState.REQUESTED)
    val admitted = applied(MechanicalResourcePolicy.claimForAdmission(requested, requested.token(), emptySet()))
    assertEquals(requested.steps.first().resources, admitted.heldResources)
    assertTrue(
        MechanicalResourcePolicy.claimForAdmission(
            requested,
            requested.token(),
            requested.steps.first().resources
        ) is TransitionResult.Rejected
    )
}

@Test
fun `safe pause releases resources but preserves operation`() {
    val paused = applied(MechanicalResourcePolicy.pauseAtCheckpoint(safeCheckpointOperation()))
    assertEquals(MechanicalOperationState.PAUSED, paused.state)
    assertTrue(paused.heldResources.isEmpty())
    assertEquals("supplement-1", paused.operationId)
    assertEquals(ResumeCursor.ExecuteStep(0), paused.resumeCursor)
}

@Test
fun `pause after completed step resumes at next step and never reruns completed step`() {
    val paused = applied(MechanicalResourcePolicy.pauseAtCheckpoint(firstStepCompleted()))
    assertEquals(ResumeCursor.ExecuteStep(1), paused.resumeCursor)
    val resumed = applied(MechanicalResourcePolicy.resumeAfterRevalidation(paused, paused.token(), emptySet()))
    assertEquals(1, resumed.currentStepIndex)
    assertEquals(MechanicalOperationState.ADMITTED, resumed.state)
}

@Test
fun `pause after final step resumes only for operation closeout`() {
    val paused = applied(MechanicalResourcePolicy.pauseAtCheckpoint(finalStepCompleted()))
    assertEquals(ResumeCursor.CompleteOperation, paused.resumeCursor)
    val resumed = applied(MechanicalResourcePolicy.resumeAfterRevalidation(paused, paused.token(), emptySet()))
    assertEquals(MechanicalOperationState.STEP_COMPLETED, resumed.state)
    assertTrue(resumed.heldResources.isEmpty())
}

@Test
fun `timeout cancellation and recovery required never release resources`() {
    listOf("timeout", "cancelled").forEach { reason ->
        val recovered = applied(MechanicalResourcePolicy.requireRecovery(dispatched(), expectedToken(), reason))
        assertEquals(dispatched().heldResources, recovered.heldResources)
    }
}

@Test
fun `completion clears resources only for exact token`() {
    val current = finalStepCompleted()
    assertTrue(MechanicalResourcePolicy.releaseForCompletion(current, staleToken()) is TransitionResult.StaleOperationEvidence)
    val completed = applied(MechanicalResourcePolicy.releaseForCompletion(current, current.token()))
    assertEquals(MechanicalOperationState.OPERATION_COMPLETED, completed.state)
    assertTrue(completed.heldResources.isEmpty())
}
```

- [ ] **Step 2: 运行资源测试确认 RED**

Run: `.\gradlew.bat :mechanical-core:test --tests "*MechanicalResourcePolicyTest"`

Expected: FAIL，缺少资源策略。

- [ ] **Step 3: 实现精确资源策略**

```kotlin
object MechanicalResourcePolicy {
    fun conflicts(left: Set<MechanicalResource>, right: Set<MechanicalResource>): Boolean =
        left.any(right::contains)

    fun claimForAdmission(
        requested: MechanicalOperation,
        token: OperationToken,
        occupiedByRunning: Set<MechanicalResource>
    ): TransitionResult

    fun pauseAtCheckpoint(
        current: MechanicalOperation,
        token: OperationToken = current.token()
    ): TransitionResult

    fun resumeAfterRevalidation(
        paused: MechanicalOperation,
        token: OperationToken,
        occupiedByRunning: Set<MechanicalResource>
    ): TransitionResult

    fun requireRecovery(
        current: MechanicalOperation,
        token: OperationToken,
        reason: String
    ): TransitionResult

    fun releaseForCompletion(
        current: MechanicalOperation,
        token: OperationToken
    ): TransitionResult
}
```

`claimForAdmission` 只接受 `REQUESTED`，无资源冲突时在同一 operation 更新中进入 `ADMITTED` 并认领当前 step 精确资源。`pauseAtCheckpoint` 仅接受 `ADMITTED` 或 `STEP_COMPLETED`、当前 step 匹配且 safety 为 `SAFE_TO_PAUSE`；成功时清空 `heldResources` 并保存精确 `ResumeCursor`。`resumeAfterRevalidation` 仅接受 `PAUSED`：`ExecuteStep(index)` 在资源无冲突时设置该 index、回到 `ADMITTED` 并重领该 step 资源；`CompleteOperation` 回到 `STEP_COMPLETED` 且不重领资源，只允许随后完成 operation。`requireRecovery` 保留资源。`releaseForCompletion` 只接受最后 step 的 `STEP_COMPLETED` 和精确 token，在同一更新中进入 `OPERATION_COMPLETED` 并清空资源。

- [ ] **Step 4: 运行资源测试确认 GREEN**

Run: `.\gradlew.bat :mechanical-core:test --tests "*MechanicalResourcePolicyTest"`

Expected: 全部通过。

### Task 4: 固定订单优先仲裁

**Files:**
- Create: `mechanical-core/src/main/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalOperationArbiter.kt`
- Create: `mechanical-core/src/test/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalOperationArbiterTest.kt`

- [ ] **Step 1: 写固定优先级和禁止硬抢占测试**

```kotlin
@Test
fun `paid order pauses supplement only at safe checkpoint`() {
    assertEquals(
        ArbitrationDecision.PAUSE_RUNNING_THEN_ADMIT,
        MechanicalOperationArbiter.decide(safeSupplement(), paidOrder(), emergency = false)
    )
    assertEquals(
        ArbitrationDecision.QUEUE_CANDIDATE,
        MechanicalOperationArbiter.decide(dispatchedSupplement(), paidOrder(), emergency = false)
    )
}

@Test
fun `emergency blocks admission without transferring or releasing resources`() {
    assertEquals(
        ArbitrationDecision.EMERGENCY_STOP_REQUIRED,
        MechanicalOperationArbiter.decide(dispatchedSupplement(), paidOrder(), emergency = true)
    )
}

@Test
fun `priority is fixed and has no runtime configuration`() {
    assertEquals(
        listOf(
            MechanicalPriority.EMERGENCY_OR_FOOD_SAFETY,
            MechanicalPriority.CURRENT_ATOMIC_ACTION,
            MechanicalPriority.PAID_ORDER_DELIVERY,
            MechanicalPriority.SAFE_RETURN,
            MechanicalPriority.SUPPLEMENT,
            MechanicalPriority.CLEANING_OR_MAINTENANCE
        ),
        MechanicalPriority.values().toList()
    )
}

@Test
fun `admission classes follow the complete fixed matrix`() {
    val ordered = listOf(
        candidate(MechanicalAdmissionClass.FOOD_SAFETY),
        candidate(MechanicalAdmissionClass.PAID_ORDER_DELIVERY),
        candidate(MechanicalAdmissionClass.SAFE_RETURN),
        candidate(MechanicalAdmissionClass.SUPPLEMENT),
        candidate(MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE)
    )
    ordered.indices.forEach { higher ->
        (higher + 1 until ordered.size).forEach { lower ->
            assertEquals(
                ArbitrationDecision.QUEUE_CANDIDATE,
                MechanicalOperationArbiter.decide(ordered[higher], ordered[lower], emergency = false)
            )
            assertEquals(
                ArbitrationDecision.PAUSE_RUNNING_THEN_ADMIT,
                MechanicalOperationArbiter.decide(
                    safeRunning(ordered[lower].admissionClass),
                    ordered[higher],
                    emergency = false
                )
            )
        }
    }
}

@Test
fun `prepared or dispatched attempt is never silently preempted by ordinary candidate`() {
    listOf(commandPreparedRunning(), dispatchedRunning()).forEach { atomicRunning ->
        MechanicalAdmissionClass.values()
            .filterNot { it == MechanicalAdmissionClass.FOOD_SAFETY }
            .forEach { candidateClass ->
                assertEquals(
                    ArbitrationDecision.KEEP_RUNNING,
                    MechanicalOperationArbiter.decide(
                        atomicRunning,
                        candidate(candidateClass),
                        emergency = false
                    )
                )
            }
        assertEquals(
            ArbitrationDecision.EMERGENCY_STOP_REQUIRED,
            MechanicalOperationArbiter.decide(
                atomicRunning,
                candidate(MechanicalAdmissionClass.FOOD_SAFETY),
                emergency = false
            )
        )
    }
}

@Test
fun `without running operation paused work keeps precedence over equal or lower pending work`() {
    val paused = pausedOperation(admissionClass = MechanicalAdmissionClass.SUPPLEMENT)
    assertEquals(
        ArbitrationDecision.RESUME_PAUSED_BEFORE_CANDIDATE,
        MechanicalOperationArbiter.decideWhenNoRunning(
            paused,
            candidate(MechanicalAdmissionClass.SUPPLEMENT)
        )
    )
    assertEquals(
        ArbitrationDecision.RESUME_PAUSED_BEFORE_CANDIDATE,
        MechanicalOperationArbiter.decideWhenNoRunning(
            paused,
            candidate(MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE)
        )
    )
    assertEquals(
        ArbitrationDecision.ADMIT_CANDIDATE,
        MechanicalOperationArbiter.decideWhenNoRunning(
            paused,
            candidate(MechanicalAdmissionClass.PAID_ORDER_DELIVERY)
        )
    )
}
```

- [ ] **Step 2: 运行仲裁测试确认 RED**

Run: `.\gradlew.bat :mechanical-core:test --tests "*MechanicalOperationArbiterTest"`

Expected: FAIL，缺少仲裁类型。

- [ ] **Step 3: 实现固定仲裁**

```kotlin
enum class MechanicalPriority {
    EMERGENCY_OR_FOOD_SAFETY,
    CURRENT_ATOMIC_ACTION,
    PAID_ORDER_DELIVERY,
    SAFE_RETURN,
    SUPPLEMENT,
    CLEANING_OR_MAINTENANCE
}

enum class ArbitrationDecision {
    ADMIT_CANDIDATE,
    KEEP_RUNNING,
    PAUSE_RUNNING_THEN_ADMIT,
    RESUME_PAUSED_BEFORE_CANDIDATE,
    QUEUE_CANDIDATE,
    EMERGENCY_STOP_REQUIRED
}

object MechanicalOperationArbiter {
    fun decide(
        running: MechanicalOperation?,
        candidate: MechanicalOperation,
        emergency: Boolean
    ): ArbitrationDecision

    fun decideWhenNoRunning(
        paused: MechanicalOperation?,
        candidate: MechanicalOperation
    ): ArbitrationDecision
}
```

`COMMAND_PREPARED` 到 `STEP_COMPLETED` 前视为不可安全抢占窗口，因为 dispatch permit 可能已被外部使用；紧急信号或 `FOOD_SAFETY` 候选遇到该窗口时只返回 `EMERGENCY_STOP_REQUIRED`，不得自动移交或释放资源。其他候选按 operation 的 `admissionClass` 走固定矩阵；低优先级不得打断高优先级。任何高优先级候选只在低优先级 running 的安全 checkpoint 返回 `PAUSE_RUNNING_THEN_ADMIT`。无 running 但有 paused 时，只有严格更高优先级候选可直接准入并保留 paused；同级或更低候选返回 `RESUME_PAUSED_BEFORE_CANDIDATE`。arbiter 构造器和决策函数均不得接受 priority map、数字权重或运行时配置。

- [ ] **Step 4: 运行仲裁测试确认 GREEN**

Run: `.\gradlew.bat :mechanical-core:test --tests "*MechanicalOperationArbiterTest"`

Expected: 全部通过。

### Task 5: 冷启动恢复分类与 moveStatus 派生投影

**Files:**
- Create: `mechanical-core/src/main/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalRecoveryPolicy.kt`
- Create: `mechanical-core/src/test/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalRecoveryPolicyTest.kt`

- [ ] **Step 1: 写稳定双快照、结果不明和投影失败测试**

```kotlin
@Test
fun `startup requires Modbus and two stable snapshots`() {
    assertEquals(RecoveryDecision.BLOCK_NEW_OPERATIONS, decide(connected = false, snapshots = stableIdle()))
    assertEquals(RecoveryDecision.BLOCK_NEW_OPERATIONS, decide(connected = true, snapshots = stableIdle().take(1)))
    assertEquals(RecoveryDecision.RECOVERY_REQUIRED, decide(connected = true, snapshots = unstable()))
}

@Test
fun `completed command with unapplied effect applies effect without redispatch`() {
    assertEquals(
        RecoveryDecision.CONFIRM_RESULT_AND_APPLY_EFFECT,
        decide(operation = dispatched(effect = MechanicalEffectState.NOT_APPLIED), snapshots = stableCompleted())
    )
}

@Test
fun `stable completed snapshots without current command correlation are never accepted`() {
    assertEquals(
        RecoveryDecision.RECOVERY_REQUIRED,
        decide(
            operation = dispatched(effect = MechanicalEffectState.NOT_APPLIED),
            snapshots = stableCompleted(correlation = SnapshotCommandCorrelation.UNAVAILABLE)
        )
    )
    assertEquals(
        RecoveryDecision.RECOVERY_REQUIRED,
        decide(
            operation = dispatched(effect = MechanicalEffectState.NOT_APPLIED),
            snapshots = stableCompleted(correlation = SnapshotCommandCorrelation.MISMATCHED)
        )
    )
}

@Test
fun `not dispatched proof must match current attempt and carry durable proof identity`() {
    val current = commandPrepared()
    val currentAttempt = requireNotNull(current.currentCommandAttempt)
    assertEquals(
        RecoveryDecision.RECOVERY_REQUIRED,
        decide(operation = current, dispatchProof = notDispatchedProof(oldCommandAttempt()))
    )
    assertEquals(
        RecoveryDecision.RECOVERY_REQUIRED,
        decide(operation = current, dispatchProof = notDispatchedProof(currentAttempt, proofId = ""))
    )
    assertEquals(
        RecoveryDecision.DISPATCH_CONFIRMED_NOT_SENT,
        decide(operation = current, dispatchProof = notDispatchedProof(currentAttempt, proofId = "proof-42"))
    )
}

@Test
fun `unknown result stays busy and never projects idle`() {
    val operation = recoveryRequired(kind = MechanicalOperationKind.ORDER_DELIVERY)
    assertEquals(RecoveryDecision.RECOVERY_REQUIRED, decide(operation = operation, snapshots = stableUnknown()))
    assertEquals(2, MoveStatusProjection.from(operation))
}

@Test
fun `paused operation alone projects idle while active kinds map one through five`() {
    assertEquals(0, MoveStatusProjection.from(pausedSupplement()))
    assertEquals(listOf(1, 2, 3, 4, 5), activeKinds().map(MoveStatusProjection::from))
}
```

- [ ] **Step 2: 运行恢复测试确认 RED**

Run: `.\gradlew.bat :mechanical-core:test --tests "*MechanicalRecoveryPolicyTest"`

Expected: FAIL，缺少恢复策略和投影。

- [ ] **Step 3: 实现恢复输入、决策和投影**

```kotlin
enum class RegisterActivity { IDLE, RUNNING, COMPLETED, FAILED, UNKNOWN }
enum class SnapshotCommandCorrelation { MATCHED_CURRENT_ATTEMPT, MISMATCHED, UNAVAILABLE }
data class MechanicalRegisterSnapshot(
    val sampleSequence: Long,
    val sampledAtMs: Long,
    val commandAttempt: CommandAttemptToken?,
    val correlation: SnapshotCommandCorrelation,
    val activity: RegisterActivity,
    val resultCode: Int?
)
enum class DispatchProofOutcome { CONFIRMED_NOT_DISPATCHED, DISPATCHED_OR_MAYBE_DISPATCHED }
data class DispatchRecoveryProof(
    val commandAttempt: CommandAttemptToken,
    val proofId: String,
    val outcome: DispatchProofOutcome
)

enum class RecoveryDecision {
    BLOCK_NEW_OPERATIONS,
    SAFE_IDLE,
    DISPATCH_CONFIRMED_NOT_SENT,
    RECORD_OBSERVED_DISPATCHED,
    WAIT_FOR_RUNNING,
    CONFIRM_RESULT_AND_APPLY_EFFECT,
    APPLY_EFFECT_ONLY,
    CONTINUE_OR_COMPLETE,
    CLOSE_SAFE_TERMINAL,
    RECOVERY_REQUIRED
}

object MechanicalRecoveryPolicy {
    fun decide(
        ledger: OperationLedgerRead,
        modbusConnected: Boolean,
        snapshots: List<MechanicalRegisterSnapshot>,
        dispatchProof: DispatchRecoveryProof?
    ): RecoveryDecision
}

object MoveStatusProjection {
    fun from(operation: MechanicalOperation?): Int
}
```

稳定条件固定为至少两个连续快照的 `sampleSequence` 严格递增、`sampledAtMs` 不倒退，并且 `commandAttempt + correlation + activity + resultCode` 完全相同。只有 `COMMAND_PREPARED`、proof outcome 为 `CONFIRMED_NOT_DISPATCHED`、proof 的完整 command token 等于 operation 当前 attempt、`proofId` 非空且稳定快照为 IDLE，才返回 `DISPATCH_CONFIRMED_NOT_SENT`；缺 proof、旧 attempt proof 或来源为空都必须进入 `RECOVERY_REQUIRED`。

特殊跨 boot 规则：若 ledger 是 `COMMAND_PREPARED + old boot DispatchIssued`，且新快照以完整 token `MATCHED_CURRENT_ATTEMPT` 证明物理 `RUNNING` 或 `COMPLETED`，返回 `RECORD_OBSERVED_DISPATCHED`，只允许先耐久推进到 `COMMAND_DISPATCHED`，绝不返回 dispatch 许可。该 CAS 必须同时把本组最大 `sampleSequence/sampledAtMs` 和 attempt 写入 `RecoveryObservationCursor`。

存在 cursor 时，下一组快照必须绑定同一 attempt，组内序号递增，并且第一条 `sampleSequence` 严格大于 cursor 的 `lastAcceptedSampleSequence`、`sampledAtMs` 不小于 cursor 时间；重放同组或倒退证据必须进入 `RECOVERY_REQUIRED`。接受新的 RUNNING 等待证据时也 CAS 更新 cursor；接受 COMPLETED 并确认结果时在同一 CAS 更新 cursor、推进 `RESULT_CONFIRMED` 并签发 effect authorization。任何 CAS 失败都不得推进 cursor。协议或 adapter 无法关联时为 `UNAVAILABLE`，必须进入 `RECOVERY_REQUIRED`。`COMPLETED + NOT_APPLIED` 不重发命令；损坏 ledger、失败、未知、陈旧或矛盾证据进入 `RECOVERY_REQUIRED`。投影仅对 `REQUESTED/PAUSED/OPERATION_COMPLETED/null` 返回 0，`RECOVERY_REQUIRED` 按 kind 保持 1..5。

- [ ] **Step 4: 运行恢复测试确认 GREEN**

Run: `.\gradlew.bat :mechanical-core:test --tests "*MechanicalRecoveryPolicyTest"`

Expected: 全部通过。

### Task 6: 唯一协调器、CAS 故障和迟到回调隔离

**Files:**
- Create: `mechanical-core/src/main/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalOperationCoordinator.kt`
- Create: `mechanical-core/src/test/kotlin/cn/niuyannet/kaochang/mechanical/MechanicalOperationCoordinatorTest.kt`

- [ ] **Step 1: 写 CAS、重复结果、取消和双 operation 边界失败测试**

```kotlin
@Test
fun `new boot session blocks all admission until coordinator finishes startup recovery`() {
    ledger.setGate(MechanicalAdmissionGate("boot-old", AdmissionGateState.OPEN))
    val fresh = coordinator(bootSessionId = "boot-new")
    assertTrue(fresh.request(paidOrder()) is CoordinatorResult.AdmissionBlocked)
    assertTrue(fresh.admitPending(paidOrder().operationId) is CoordinatorResult.AdmissionBlocked)

    assertTrue(
        fresh.recoverStartup(
            modbusConnected = true,
            snapshots = stableIdle(),
            dispatchProof = null
        ) is CoordinatorResult.StartupGateOpened
    )
    assertTrue(fresh.request(paidOrder()) is CoordinatorResult.Requested)
}

@Test
fun `every ordinary coordinator method checks boot gate before state or side effects`() {
    listOf(
        MechanicalAdmissionGate("boot-old", AdmissionGateState.OPEN),
        MechanicalAdmissionGate("boot-new", AdmissionGateState.STARTUP_RECOVERY_PENDING),
        MechanicalAdmissionGate("boot-new", AdmissionGateState.RECOVERY_REQUIRED)
    ).forEach { closedGate ->
        ordinaryCoordinatorCalls().forEach { call ->
            call.arrange(ledger, closedGate)
            val before = ledger.read()
            val effectsBefore = effectPort.realApplyCount
            assertTrue(call.invoke(coordinator) is CoordinatorResult.AdmissionBlocked, call.name)
            assertEquals(before, ledger.read())
            assertEquals(effectsBefore, effectPort.realApplyCount)
        }
    }
}
```

`ordinaryCoordinatorCalls()` 必须逐项包含 `request`、`admitPending`、`prepareCommand`、`markDispatched`、`confirmResult`、`applyEffect`、`completeStep`、`startNextStep`、`completeOperation`、`pauseAtSafeCheckpoint`、`resumePaused`、`markTimedOut`、`markControlFlowCancelled`；每项的 `arrange` 都创建该方法在 gate OPEN 时本应合法的 ledger 状态，从而证明 gate 检查先于状态校验，并证明 `applyEffect` 未调用 effect port。

```kotlin

@Test
fun `corrupt ledger disconnected Modbus and insufficient snapshots keep startup gate closed`() {
    listOf(
        startupCase(ledgerRead = OperationLedgerRead.Corrupt("bad"), connected = true, snapshots = stableIdle()),
        startupCase(ledgerRead = ledger.read(), connected = false, snapshots = stableIdle()),
        startupCase(ledgerRead = ledger.read(), connected = true, snapshots = stableIdle().take(1))
    ).forEach { case ->
        val result = case.coordinator.recoverStartup(case.connected, case.snapshots, null)
        assertTrue(result is CoordinatorResult.StartupRecoveryBlocked)
        assertTrue(case.coordinator.request(paidOrder()) is CoordinatorResult.AdmissionBlocked)
    }
}

@Test
fun `recovery dispatch proof is consumed by CAS before one permit is returned`() {
    val prepared = commandPrepared()
    ledger.reset(gate = pendingGate("boot-new"), running = prepared)
    val proof = notDispatchedProof(requireNotNull(prepared.currentCommandAttempt), proofId = "proof-42")

    val first = coordinator.recoverStartup(true, stableIdle(), proof) as CoordinatorResult.RecoveryDispatchAllowed
    val persisted = ledger.present().admissionGate.recoveryAuthorization as RecoveryAuthorization.DispatchIssued
    assertEquals("proof-42", persisted.proofId)
    assertEquals(first.authorization, RecoveryAuthorizationToken("boot-new", persisted.authorizationId))

    assertTrue(
        coordinator.recoverStartup(true, stableIdle(), proof) is CoordinatorResult.StartupRecoveryBlocked
    )
    assertTrue(coordinator.markRecoveredDispatched(first.authorization) is CoordinatorResult.Applied)
    assertEquals(MechanicalOperationState.COMMAND_DISPATCHED, ledger.present().running!!.state)
    assertNull(ledger.present().admissionGate.recoveryAuthorization)
}

@Test
fun `matched completed recovery confirms result before issuing effect authorization`() {
    val dispatched = dispatched()
    ledger.reset(gate = pendingGate("boot-new"), running = dispatched)
    ledger.failNextCompareAndSet = true
    assertTrue(
        coordinator.recoverStartup(true, stableCompletedFor(dispatched), null)
            is CoordinatorResult.LedgerConflict
    )
    assertEquals(MechanicalOperationState.COMMAND_DISPATCHED, ledger.present().running!!.state)
    assertNull(ledger.present().admissionGate.recoveryAuthorization)

    val ready = coordinator.recoverStartup(true, stableCompletedFor(dispatched), null)
        as CoordinatorResult.RecoveryEffectReady
    val confirmed = ledger.present().running!!
    assertEquals(MechanicalOperationState.RESULT_CONFIRMED, confirmed.state)
    assertEquals(dispatched.revision + 1, confirmed.revision)
    assertEquals(dispatched.currentCommandAttempt, confirmed.lastConfirmedCommandAttempt)
    assertTrue(ledger.present().admissionGate.recoveryAuthorization is RecoveryAuthorization.EffectIssued)

    ledger.failNextCompareAndSet = true
    assertTrue(coordinator.applyRecoveredEffect(ready.authorization) is CoordinatorResult.LedgerConflict)
    assertEquals(1, effectPort.realApplyCount)
    assertEquals(MechanicalOperationState.RESULT_CONFIRMED, ledger.present().running!!.state)

    assertTrue(coordinator.applyRecoveredEffect(ready.authorization) is CoordinatorResult.Applied)
    assertEquals(1, effectPort.realApplyCount)
    assertEquals(MechanicalOperationState.EFFECT_APPLIED, ledger.present().running!!.state)
    assertNull(ledger.present().admissionGate.recoveryAuthorization)
    assertNull(ledger.present().recoveryObservationCursor)
}

@Test
fun `recovered non-final effect closes one state edge per CAS then opens gate at next step`() {
    val effectApplied = recoveredEffectApplied(stepIndex = 0, stepCount = 2)
    val paused = pausedOperation("paused-supplement")
    val pending = candidate(MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE)
    val oldTerminal = terminalOperation("older-terminal")
    ledger.reset(
        gate = recoveryGate(),
        running = effectApplied,
        paused = paused,
        pending = pending,
        terminal = oldTerminal
    )
    ledger.failNextCompareAndSet = true
    assertTrue(coordinator.continueRecoveredOperation(effectApplied.token()) is CoordinatorResult.LedgerConflict)
    assertEquals(MechanicalOperationState.EFFECT_APPLIED, ledger.present().running!!.state)
    assertEquals(paused, ledger.present().paused)
    assertEquals(pending, ledger.present().pending)
    assertEquals(oldTerminal, ledger.present().terminal)

    val stepCompleted = coordinator.continueRecoveredOperation(effectApplied.token())
        as CoordinatorResult.RecoveryProgressed
    assertEquals(MechanicalOperationState.STEP_COMPLETED, ledger.present().running!!.state)
    assertEquals(AdmissionGateState.RECOVERY_REQUIRED, ledger.present().admissionGate.state)
    assertEquals(paused, ledger.present().paused)
    assertEquals(pending, ledger.present().pending)
    assertEquals(oldTerminal, ledger.present().terminal)

    val beforeSecond = ledger.present()
    ledger.failNextCompareAndSet = true
    assertTrue(coordinator.continueRecoveredOperation(stepCompleted.token) is CoordinatorResult.LedgerConflict)
    assertEquals(beforeSecond, ledger.present())
    val next = coordinator.continueRecoveredOperation(stepCompleted.token)
        as CoordinatorResult.RecoveryProgressed
    assertEquals(MechanicalOperationState.ADMITTED, ledger.present().running!!.state)
    assertEquals(1, ledger.present().running!!.currentStepIndex)
    assertEquals(AdmissionGateState.OPEN, ledger.present().admissionGate.state)
    assertEquals("boot-new", ledger.present().admissionGate.bootSessionId)
    assertNull(ledger.present().recoveryObservationCursor)
    assertEquals(ledger.present().running!!.steps[1].resources, ledger.present().running!!.heldResources)
    assertEquals(paused, ledger.present().paused)
    assertEquals(pending, ledger.present().pending)
    assertEquals(oldTerminal, ledger.present().terminal)
}

@Test
fun `recovered final effect reaches terminal one state edge per CAS and frees running slot`() {
    val effectApplied = recoveredEffectApplied(stepIndex = 0, stepCount = 1)
    val paused = pausedOperation("paused-supplement")
    val pending = candidate(MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE)
    val oldTerminal = terminalOperation("older-terminal")
    ledger.reset(
        gate = recoveryGate(),
        running = effectApplied,
        paused = paused,
        pending = pending,
        terminal = oldTerminal
    )
    val stepCompleted = coordinator.continueRecoveredOperation(effectApplied.token())
        as CoordinatorResult.RecoveryProgressed
    assertEquals(MechanicalOperationState.STEP_COMPLETED, ledger.present().running!!.state)
    assertEquals(paused, ledger.present().paused)
    assertEquals(pending, ledger.present().pending)
    assertEquals(oldTerminal, ledger.present().terminal)

    val beforeSecond = ledger.present()
    ledger.failNextCompareAndSet = true
    assertTrue(coordinator.continueRecoveredOperation(stepCompleted.token) is CoordinatorResult.LedgerConflict)
    assertEquals(beforeSecond, ledger.present())
    coordinator.continueRecoveredOperation(stepCompleted.token)
    assertNull(ledger.present().running)
    assertEquals(MechanicalOperationState.OPERATION_COMPLETED, ledger.present().terminal!!.state)
    assertEquals(effectApplied.operationId, ledger.present().terminal!!.operationId)
    assertTrue(ledger.present().terminal!!.heldResources.isEmpty())
    assertEquals(paused, ledger.present().paused)
    assertEquals(pending, ledger.present().pending)
    assertEquals(AdmissionGateState.OPEN, ledger.present().admissionGate.state)
    assertNull(ledger.present().recoveryObservationCursor)
}

@Test
fun `recovered closeout rejection matrix never mutates ledger`() {
    recoveredCloseoutRejectedCases().forEach { case ->
        case.arrange(ledger)
        val before = ledger.present()
        val result = case.invoke(coordinator)
        assertTrue(result is CoordinatorResult.AdmissionBlocked || result is CoordinatorResult.Rejected)
        assertEquals(case.name, before, ledger.present())
    }
}
```

`recoveredCloseoutRejectedCases()` 必须分别构造：旧 boot gate、authorization 尚未清除、cursor 尚未清除、错误 operation token、running 状态不是 `EFFECT_APPLIED/STEP_COMPLETED`。每个 case 只调用一次 `continueRecoveredOperation`，结果允许为 `AdmissionBlocked` 或 `Rejected`，并断言完整 snapshot 不变。

```kotlin
@Test
fun `failed recovered effect preserves result evidence authorization and cursor`() {
    val issued = issuedEffectAuthorization(boot = "boot-new")
    val confirmed = resultConfirmed()
    val cursor = matchingCursor(confirmed)
    ledger.reset(gate = recoveryGate(issued), running = confirmed, cursor = cursor)
    effectPort.failNext = true
    val before = ledger.present()
    val casCallsBefore = ledger.compareAndSetCallCount

    assertTrue(coordinator.applyRecoveredEffect(issued.token()) is CoordinatorResult.Rejected)
    assertEquals(before, ledger.present())
    assertEquals(casCallsBefore, ledger.compareAndSetCallCount)
    assertEquals(MechanicalOperationState.RESULT_CONFIRMED, ledger.present().running!!.state)
    assertEquals(issued, ledger.present().admissionGate.recoveryAuthorization)
    assertEquals(cursor, ledger.present().recoveryObservationCursor)
    assertTrue(coordinator.continueRecoveredOperation(confirmed.token()) is CoordinatorResult.AdmissionBlocked)
}

@Test
fun `already confirmed recovery signs effect authorization without reconfirming result`() {
    val confirmed = resultConfirmed()
    ledger.reset(gate = pendingGate("boot-new"), running = confirmed)
    val ready = coordinator.recoverStartup(true, stableCompletedFor(confirmed), null)
        as CoordinatorResult.RecoveryEffectReady
    assertEquals(confirmed.revision, ledger.present().running!!.revision)
    assertEquals(confirmed.lastConfirmedCommandAttempt, ledger.present().running!!.lastConfirmedCommandAttempt)
    assertEquals(currentEffectToken(), ready.effectToken)
}

@Test
fun `old boot or wrong recovery authorization cannot produce a side effect`() {
    val current = ledger.present()
    assertTrue(
        coordinator.markRecoveredDispatched(RecoveryAuthorizationToken("boot-old", "auth-x"))
            is CoordinatorResult.AdmissionBlocked
    )
    assertTrue(
        coordinator.markRecoveredDispatched(RecoveryAuthorizationToken("boot-new", "wrong-auth"))
            is CoordinatorResult.AdmissionBlocked
    )
    assertEquals(current, ledger.present())
}

@Test
fun `recovered effect rejects wrong authorization and is single use after success`() {
    val issued = issuedEffectAuthorization(boot = "boot-new")
    ledger.reset(gate = recoveryGate(issued), running = resultConfirmed())
    val before = ledger.present()
    val effectsBefore = effectPort.realApplyCount
    assertTrue(
        coordinator.applyRecoveredEffect(RecoveryAuthorizationToken("boot-old", issued.authorizationId))
            is CoordinatorResult.AdmissionBlocked
    )
    assertTrue(
        coordinator.applyRecoveredEffect(RecoveryAuthorizationToken("boot-new", "wrong-auth"))
            is CoordinatorResult.AdmissionBlocked
    )
    assertEquals(before, ledger.present())
    assertEquals(effectsBefore, effectPort.realApplyCount)

    assertTrue(coordinator.applyRecoveredEffect(issued.token()) is CoordinatorResult.Applied)
    val after = ledger.present()
    val effectsAfter = effectPort.realApplyCount
    assertNull(after.admissionGate.recoveryAuthorization)
    assertTrue(coordinator.applyRecoveredEffect(issued.token()) is CoordinatorResult.AdmissionBlocked)
    assertEquals(after, ledger.present())
    assertEquals(effectsAfter, effectPort.realApplyCount)
}

@Test
fun `dispatch authorization recording is retryable but never reusable`() {
    val issued = issuedDispatchAuthorization()
    ledger.reset(gate = recoveryGate(issued), running = commandPrepared())
    ledger.failNextCompareAndSet = true
    assertTrue(coordinator.markRecoveredDispatched(issued.token()) is CoordinatorResult.LedgerConflict)
    assertEquals(issued, ledger.present().admissionGate.recoveryAuthorization)

    assertTrue(coordinator.markRecoveredDispatched(issued.token()) is CoordinatorResult.Applied)
    val after = ledger.present()
    assertTrue(coordinator.markRecoveredDispatched(issued.token()) is CoordinatorResult.AdmissionBlocked)
    assertEquals(after, ledger.present())
}

@Test
fun `new boot records observed running from the real prepared plus old authorization state`() {
    val prepared = commandPrepared()
    ledger.reset(
        gate = recoveryGate(issuedDispatchAuthorization(boot = "boot-old", commandAttempt = prepared.currentCommandAttempt!!)),
        running = prepared
    )
    val fresh = coordinator(bootSessionId = "boot-new")
    val progressed = fresh.recoverStartup(true, stableRunningFor(prepared, sequenceStart = 1), null)
        as CoordinatorResult.RecoveryProgressed
    assertEquals(RecoveryDecision.RECORD_OBSERVED_DISPATCHED, progressed.decision)
    assertEquals(MechanicalOperationState.COMMAND_DISPATCHED, ledger.present().running!!.state)
    assertEquals(prepared.revision + 1, ledger.present().running!!.revision)
    assertEquals("boot-new", ledger.present().admissionGate.bootSessionId)
    assertNull(ledger.present().admissionGate.recoveryAuthorization)
    assertEquals(2L, ledger.present().recoveryObservationCursor!!.lastAcceptedSampleSequence)
}

@Test
fun `new boot records dispatched before confirming completed and issuing effect authorization`() {
    val prepared = commandPrepared()
    ledger.reset(
        gate = recoveryGate(issuedDispatchAuthorization(boot = "boot-old", commandAttempt = prepared.currentCommandAttempt!!)),
        running = prepared
    )
    val fresh = coordinator(bootSessionId = "boot-new")
    val firstGroup = stableCompletedFor(prepared, sequenceStart = 1)
    ledger.failNextCompareAndSet = true
    assertTrue(fresh.recoverStartup(true, firstGroup, null) is CoordinatorResult.LedgerConflict)
    assertEquals(MechanicalOperationState.COMMAND_PREPARED, ledger.present().running!!.state)
    assertNull(ledger.present().recoveryObservationCursor)

    val first = fresh.recoverStartup(true, firstGroup, null)
        as CoordinatorResult.RecoveryProgressed
    assertEquals(RecoveryDecision.RECORD_OBSERVED_DISPATCHED, first.decision)
    assertEquals(MechanicalOperationState.COMMAND_DISPATCHED, ledger.present().running!!.state)
    assertNull(ledger.present().admissionGate.recoveryAuthorization)
    assertEquals(2L, ledger.present().recoveryObservationCursor!!.lastAcceptedSampleSequence)

    val afterFirst = ledger.present()
    assertTrue(fresh.recoverStartup(true, firstGroup, null) is CoordinatorResult.StartupRecoveryBlocked)
    assertEquals(afterFirst, ledger.present())

    val second = fresh.recoverStartup(
        true,
        stableCompletedFor(ledger.present().running!!, sequenceStart = 3),
        null
    ) as CoordinatorResult.RecoveryEffectReady
    assertEquals(MechanicalOperationState.RESULT_CONFIRMED, ledger.present().running!!.state)
    assertEquals(prepared.revision + 2, ledger.present().running!!.revision)
    assertEquals(4L, ledger.present().recoveryObservationCursor!!.lastAcceptedSampleSequence)
    assertTrue(ledger.present().admissionGate.recoveryAuthorization is RecoveryAuthorization.EffectIssued)
    assertEquals(prepared.operationId, second.effectToken.operationId)
    assertEquals(
        prepared.steps[prepared.currentStepIndex].effectIdempotencyKey,
        second.effectToken.effectIdempotencyKey
    )
}

@Test
fun `dispatch permission is returned only after command intent CAS succeeds`() {
    ledger.failNextCompareAndSet = true
    assertTrue(coordinator.prepareCommand(token(), "attempt-1") is CoordinatorResult.LedgerConflict)
    assertEquals(MechanicalOperationState.ADMITTED, ledger.present().running!!.state)

    val allowed = coordinator.prepareCommand(token(), "attempt-1") as CoordinatorResult.DispatchAllowed
    assertEquals("attempt-1", allowed.commandAttempt.attemptId)
    assertEquals(MechanicalOperationState.COMMAND_PREPARED, ledger.present().running!!.state)
}

@Test
fun `late callback cannot alter replacement owner`() {
    val before = ledger.present()
    assertTrue(coordinator.confirmResult(oldCommandAttempt(), MechanicalPhysicalResult.SUCCEEDED) is CoordinatorResult.StaleCommandEvidence)
    assertEquals(before, ledger.present())
}

@Test
fun `cancellation after dispatch persists recovery and retains resources`() {
    val held = ledger.present().running!!.heldResources
    assertTrue(coordinator.markControlFlowCancelled(token(), "job cancelled") is CoordinatorResult.RecoveryRequired)
    assertEquals(held, ledger.present().running!!.heldResources)
}

@Test
fun `safe supplement pause and paid order admission use one ledger CAS`() {
    assertTrue(coordinator.request(paidOrder()) is CoordinatorResult.Requested)
    val before = ledger.present()
    ledger.failNextCompareAndSet = true
    assertTrue(coordinator.admitPending(paidOrder().operationId) is CoordinatorResult.LedgerConflict)
    assertEquals(before, ledger.present())

    assertTrue(coordinator.admitPending(paidOrder().operationId) is CoordinatorResult.HandoffAdmitted)
    assertEquals(paidOrder().operationId, ledger.present().running!!.operationId)
    assertEquals(safeSupplement().operationId, ledger.present().paused!!.operationId)
    assertNull(ledger.present().pending)
}

@Test
fun `every pausable fixed-priority handoff is applied by one generic CAS path`() {
    fixedPriorityPairs().forEach { (lowerRunning, higherPending) ->
        ledger.reset(gate = openGate(), running = lowerRunning, pending = higherPending)
        val result = coordinator.admitPending(higherPending.operationId)
        assertTrue(result is CoordinatorResult.HandoffAdmitted)
        assertEquals(higherPending.operationId, ledger.present().running!!.operationId)
        assertEquals(lowerRunning.operationId, ledger.present().paused!!.operationId)
        assertNull(ledger.present().pending)
    }
}

@Test
fun `occupied paused slot prevents a second paused owner`() {
    ledger.reset(
        gate = openGate(),
        running = safeRunning(MechanicalAdmissionClass.SUPPLEMENT),
        paused = pausedOperation("older"),
        pending = candidate(MechanicalAdmissionClass.PAID_ORDER_DELIVERY)
    )
    val before = ledger.present()
    assertTrue(coordinator.admitPending(before.pending!!.operationId) is CoordinatorResult.Rejected)
    assertEquals(before, ledger.present())

    ledger.replacePending(candidate(MechanicalAdmissionClass.FOOD_SAFETY))
    val foodSafetyBefore = ledger.present()
    assertTrue(
        coordinator.admitPending(foodSafetyBefore.pending!!.operationId)
            is CoordinatorResult.EmergencyStopRequired
    )
    assertEquals(foodSafetyBefore, ledger.present())
}

@Test
fun `no running operation does not let low priority pending bypass paused work`() {
    val paused = pausedOperation(admissionClass = MechanicalAdmissionClass.SUPPLEMENT)
    ledger.reset(
        gate = openGate(),
        paused = paused,
        pending = candidate(MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE)
    )
    val before = ledger.present()
    assertTrue(coordinator.admitPending(before.pending!!.operationId) is CoordinatorResult.ResumePausedFirst)
    assertEquals(before, ledger.present())

    ledger.replacePending(candidate(MechanicalAdmissionClass.PAID_ORDER_DELIVERY))
    assertTrue(coordinator.admitPending(ledger.present().pending!!.operationId) is CoordinatorResult.Admitted)
    assertEquals(paused.operationId, ledger.present().paused!!.operationId)
}

@Test
fun `effect is idempotent when effect succeeds but ledger CAS fails`() {
    val effectToken = currentEffectToken()
    ledger.failNextCompareAndSet = true
    assertTrue(coordinator.applyEffect(effectToken) is CoordinatorResult.LedgerConflict)
    assertEquals(1, effectPort.realApplyCount)
    assertEquals(MechanicalEffectState.NOT_APPLIED, ledger.present().running!!.effectState)

    assertTrue(coordinator.applyEffect(effectToken) is CoordinatorResult.Applied)
    assertEquals(1, effectPort.realApplyCount)
    assertEquals(MechanicalEffectState.APPLIED, ledger.present().running!!.effectState)
}

@Test
fun `operation completion releases exact resources in the same CAS`() {
    val before = finalStepCompleted()
    val paused = pausedSupplement()
    ledger.replaceRunning(before)
    ledger.replacePaused(paused)
    val result = coordinator.completeOperation(before.token()) as CoordinatorResult.Applied
    assertNull(ledger.present().running)
    assertEquals(before.operationId, ledger.present().terminal!!.operationId)
    assertTrue(ledger.present().terminal!!.heldResources.isEmpty())
    assertEquals(paused.operationId, ledger.present().paused!!.operationId)
    assertEquals(before.revision + 1, result.token.revision)

    assertTrue(coordinator.request(nextOperation()) is CoordinatorResult.Requested)
}
```

- [ ] **Step 2: 运行协调器测试确认 RED**

Run: `.\gradlew.bat :mechanical-core:test --tests "*MechanicalOperationCoordinatorTest"`

Expected: FAIL，缺少协调器。

- [ ] **Step 3: 实现唯一写入口和副作用许可结果**

```kotlin
sealed interface CoordinatorResult {
    data class Requested(val token: OperationToken) : CoordinatorResult
    data class Admitted(val token: OperationToken) : CoordinatorResult
    data class HandoffAdmitted(val running: OperationToken, val paused: OperationToken) : CoordinatorResult
    data class StartupGateOpened(val bootSessionId: String) : CoordinatorResult
    data class StartupRecoveryBlocked(
        val decision: RecoveryDecision,
        val reason: String
    ) : CoordinatorResult
    data class RecoveryProgressed(
        val decision: RecoveryDecision,
        val token: OperationToken
    ) : CoordinatorResult
    data class RecoveryDispatchAllowed(
        val authorization: RecoveryAuthorizationToken,
        val commandAttempt: CommandAttemptToken
    ) : CoordinatorResult
    data class RecoveryEffectReady(
        val authorization: RecoveryAuthorizationToken,
        val effectToken: EffectToken
    ) : CoordinatorResult
    data class AdmissionBlocked(val reason: String) : CoordinatorResult
    data class EmergencyStopRequired(val reason: String) : CoordinatorResult
    data class ResumePausedFirst(val paused: OperationToken) : CoordinatorResult
    data class DispatchAllowed(val commandAttempt: CommandAttemptToken) : CoordinatorResult
    data class Applied(val token: OperationToken) : CoordinatorResult
    data class Paused(val token: OperationToken) : CoordinatorResult
    data class RecoveryRequired(val token: OperationToken, val reason: String) : CoordinatorResult
    data class Rejected(val reason: String) : CoordinatorResult
    data class LedgerConflict(val reason: String) : CoordinatorResult
    data class StaleOperationEvidence(
        val expected: OperationToken,
        val actual: OperationToken?
    ) : CoordinatorResult
    data class StaleCommandEvidence(
        val expected: CommandAttemptToken?,
        val actual: CommandAttemptToken
    ) : CoordinatorResult
    data object DuplicateEvidence : CoordinatorResult
}

class MechanicalOperationCoordinator(
    private val ledger: OperationLedgerPort,
    private val effectPort: MechanicalEffectPort,
    private val bootSessionId: String,
    private val transitionLock: Any = Any()
) {
    fun recoverStartup(
        modbusConnected: Boolean,
        snapshots: List<MechanicalRegisterSnapshot>,
        dispatchProof: DispatchRecoveryProof?
    ): CoordinatorResult
    fun markRecoveredDispatched(authorization: RecoveryAuthorizationToken): CoordinatorResult
    fun applyRecoveredEffect(authorization: RecoveryAuthorizationToken): CoordinatorResult
    fun continueRecoveredOperation(token: OperationToken): CoordinatorResult
    fun request(operation: MechanicalOperation): CoordinatorResult
    fun admitPending(operationId: String, emergency: Boolean = false): CoordinatorResult
    fun prepareCommand(token: OperationToken, attemptId: String): CoordinatorResult
    fun markDispatched(commandAttempt: CommandAttemptToken): CoordinatorResult
    fun confirmResult(commandAttempt: CommandAttemptToken, result: MechanicalPhysicalResult): CoordinatorResult
    fun applyEffect(effectToken: EffectToken): CoordinatorResult
    fun completeStep(token: OperationToken): CoordinatorResult
    fun startNextStep(token: OperationToken): CoordinatorResult
    fun completeOperation(token: OperationToken): CoordinatorResult
    fun pauseAtSafeCheckpoint(token: OperationToken): CoordinatorResult
    fun resumePaused(token: OperationToken): CoordinatorResult
    fun markTimedOut(token: OperationToken, reason: String): CoordinatorResult
    fun markControlFlowCancelled(token: OperationToken, reason: String): CoordinatorResult
}
```

每个公开方法都必须在同一 `synchronized(transitionLock)` 内完成 `load → token/不变量校验 → 纯转换 → compareAndSet`。构造器拒绝空 `bootSessionId`。除 `recoverStartup/markRecoveredDispatched/applyRecoveredEffect/continueRecoveredOperation` 四个恢复专用入口外，所有公开方法（包括 `request/admitPending/prepareCommand/markDispatched/confirmResult/applyEffect/completeStep/startNextStep/completeOperation/pauseAtSafeCheckpoint/resumePaused/markTimedOut/markControlFlowCancelled`）都必须先要求 ledger admission gate 为当前 boot 的 `OPEN`；旧 boot 的 OPEN、`STARTUP_RECOVERY_PENDING` 或 `RECOVERY_REQUIRED` 均返回 `AdmissionBlocked`，不能产生机械、效果或状态副作用。

`recoverStartup` 是唯一打开 gate 或签发恢复授权的入口：它调用恢复策略，并只在 `SAFE_IDLE/CLOSE_SAFE_TERMINAL` 等无需未完成机械副作用的明确安全决策下，以 CAS 写入当前 boot 的 `OPEN`。对 `DISPATCH_CONFIRMED_NOT_SENT`，它必须先以 CAS 把 `RecoveryAuthorization.DispatchIssued(authorizationId, bootSessionId, proofId, commandAttempt)` 写入 gate，原子消费 proof，CAS 成功后只返回一次 `RecoveryDispatchAllowed`；相同 proof 或已有 authorization 再调用只能返回 `StartupRecoveryBlocked`。真实下发后，adapter 只能用该 authorization 调用 `markRecoveredDispatched`；它以 CAS 推进到 `COMMAND_DISPATCHED` 并清除 authorization，gate 仍保持恢复态等待新快照。若在许可返回后、确认下发前崩溃，下一进程不得重发同一 proof。

对 `COMMAND_DISPATCHED + matched COMPLETED`，`recoverStartup` 必须在同一次 CAS 中先调用状态机把 operation 推进到 `RESULT_CONFIRMED`（revision + 1，保存 `lastConfirmedCommandAttempt`），同时写入 `RecoveryAuthorization.EffectIssued`，CAS 成功后才返回 `RecoveryEffectReady`。CAS 失败时 operation 仍为 `COMMAND_DISPATCHED` 且没有 authorization。对本来已是 `RESULT_CONFIRMED` 的 `APPLY_EFFECT_ONLY`，只写入 EffectIssued，不重复确认结果、不增加 operation revision。`applyRecoveredEffect` 只接受当前 boot 且匹配 gate 的 authorization，要求 operation 已是 `RESULT_CONFIRMED`，调用同一个幂等 effect port 后再 CAS 到 `EFFECT_APPLIED`；effect port 返回 `FAILED` 时不执行 ledger CAS，保留 `RESULT_CONFIRMED`、authorization 和 cursor，且 `continueRecoveredOperation` 必须阻止；effect 成功但 CAS 失败时同样保留证据，同 token 重试不重复效果；CAS 成功时清 authorization 与 cursor，但 gate 仍为当前 boot 的 `RECOVERY_REQUIRED`。

`continueRecoveredOperation` 是无外部副作用的专用收口入口，只接受当前 boot `RECOVERY_REQUIRED`、无 recovery authorization、无 cursor 和精确 operation token。每次调用只允许一个固定状态边并执行一次 CAS：`EFFECT_APPLIED → STEP_COMPLETED` 后 gate 仍关闭；再次调用时，非末 step 执行 `STEP_COMPLETED → ADMITTED`、把资源更新为下一 step 精确集合并打开当前 boot gate，末 step 执行 `STEP_COMPLETED → OPERATION_COMPLETED`、用当前 completed operation 替换 terminal、`running=null`、释放资源并打开 gate。每次 CAS 都必须原样复制未参与转换的 paused、pending；非末步还必须原样保留既有 terminal。CAS 失败时 gate、资源、running/paused/pending/terminal/cursor 全部保持原状态；不得在一次调用中跨两条状态边。

`markRecoveredDispatched` 的 CAS 失败必须保留 authorization，允许用同一 authorization 重试“记录已下发”，但成功一次并清除 authorization 后再次调用必须阻止。新 boot 不得复用旧 boot authorization。真实跨 boot 起点若为 `COMMAND_PREPARED + old boot DispatchIssued`，只有与 ledger 当前 attempt 匹配且序号递增的新 `RUNNING/COMPLETED` 双快照才允许 `recoverStartup` 在一次 CAS 中调用 `markDispatched` 语义，推进到 `COMMAND_DISPATCHED`、revision + 1、清除旧 authorization、写入本组最大值的 `RecoveryObservationCursor`，并返回不含 dispatch permit 的 `RecoveryProgressed(RECORD_OBSERVED_DISPATCHED)`。CAS 失败时 operation、gate 和 cursor 全部不变。

即使第一组快照已是 COMPLETED，该次 CAS 也不得同时进入 `RESULT_CONFIRMED`；必须取得绑定同 attempt 且第一条 sequence 严格大于持久化 cursor 的下一组稳定 COMPLETED 快照，再用下一次 CAS 同时更新 cursor、确认结果并签发当前 boot effect authorization。重放第一组必须阻止且 ledger 不变。没有新鲜匹配证据时保持阻止。其他恢复分类写入/保留当前 boot 的 `RECOVERY_REQUIRED` 或 pending gate，并继续阻止新任务。

`request` 只把候选耐久写入 `pending`；`admitPending` 对 arbiter 返回的任何 `PAUSE_RUNNING_THEN_ADMIT` 都必须使用通用单 CAS 完成“running 在安全 checkpoint 暂停并释放资源 + pending 高优先级候选变为 running 且认领精确资源 + pending 清空”，不仅限于补肠→订单。若 paused 槽已占用，普通候选拒绝第二个暂停 owner；`FOOD_SAFETY` 候选返回 `EmergencyStopRequired`，两者都保持 ledger 不变。CAS 失败时 ledger 完全不变。无 running 时，`admitPending` 必须调用 `decideWhenNoRunning`：严格高于 paused 的候选可在一次 CAS 中认领 running 且保留 paused；同级或更低候选返回 `ResumePausedFirst`，不得越过暂停 operation。只有 `prepareCommand` CAS 成功才能返回带完整 `CommandAttemptToken` 的 `DispatchAllowed`；CAS 失败统一返回 `LedgerConflict`。

`applyEffect` 先校验稳定 `EffectToken`，调用 `effectPort.applyOnce`，只接受 `APPLIED/ALREADY_APPLIED` 后再 CAS 为 `EFFECT_APPLIED`。如果效果成功但 CAS 失败，重试必须复用同一 token，由端口返回 `ALREADY_APPLIED` 后完成 ledger CAS，禁止重复库存/烤盘效果。`completeOperation` 必须在精确 operation token 的同一次 CAS 中生成无资源的 `OPERATION_COMPLETED` terminal、设置 `running=null`，并原样保留 paused；terminal 不参与资源冲突和准入。协调器不接收任何“强制释放”或“设置 moveStatus=0”参数。

- [ ] **Step 4: 运行协调器与模块测试确认 GREEN**

Run: `.\gradlew.bat :mechanical-core:test --rerun-tasks`

Expected: `BUILD SUCCESSFUL`，mechanical-core 所有测试 0 failures/errors/skipped。

### Task 7: 全量验证、源码门禁和里程碑收口

**Files:**
- Create: `docs/refactor/2026-08-20-mechanical-core-foundation-verification.md`
- Modify: `docs/refactor/CURRENT_STATUS.md`

- [ ] **Step 1: 检查白名单和无 App 接线**

Run:

```powershell
$base='49f9ce431b38f35d561ab8098d0a118fb556c439'
$tracked=@(git diff --name-only $base)
$untracked=@(git ls-files --others --exclude-standard)
$all=@($tracked + $untracked | Sort-Object -Unique)
$all
git status --porcelain=v1 --untracked-files=all
git diff --check
git grep -n 'implementation(project(":mechanical-core"))' -- app/build.gradle.kts
git grep -n 'MechanicalOperationCoordinator' -- 'app/**'
```

Expected: `$all` 和 porcelain 只出现白名单路径，包含全部未跟踪 `mechanical-core/**`；两个 App 接线 grep 均无匹配。随后用精确路径 `git add -- <白名单文件...>` 暂存，再运行 `git diff --cached --name-only`、`git diff --cached --check` 和 `git diff --cached --stat`；独立审核以 cached diff 为实现全集，禁止用 `$base...HEAD` 代替未提交实现。

- [ ] **Step 2: 运行定向与聚合 JVM 测试**

Run:

```powershell
.\gradlew.bat :mechanical-core:test --rerun-tasks
.\gradlew.bat test --rerun-tasks
```

Expected: 两条命令均 `BUILD SUCCESSFUL`；记录各模块 XML tests/failures/errors/skipped 数量。

- [ ] **Step 3: 仅在模块注册影响构建图时运行 Debug 构建**

Run: `.\gradlew.bat :app:assembleDebug --rerun-tasks`

Expected: `BUILD SUCCESSFUL`。这只证明本地构建，不证明安装、设备或机械行为。

- [ ] **Step 4: 写唯一验证记录和更新状态台账**

验证记录必须分栏写明：源码/Git、mechanical-core/JVM、Debug 构建、测试 MQTT/HTTP/后端未执行、真实 RS485/Modbus/机械/断电/温控/长稳未验证、独立审核结论。`CURRENT_STATUS.md` 只在本里程碑验证完成后更新一次。

- [ ] **Step 5: 审核会话A独立只读审核**

审核范围：本计划、完整 diff、状态机边、资源释放、固定优先级、迟到回调、CAS 故障、测试结果、证据边界。任何 P0/P1 未关闭都不得提交实现里程碑或进入影子观察切片。

- [ ] **Step 6: 主会话复核并形成 fix 分支可回滚里程碑**

审核 `Approve` 后，精确暂存白名单文件并提交：

```text
refactor(mechanical): 建立唯一机械操作纯核心
```

- [ ] **Step 7: 本地集成、集成后复验和 develop 推送**

严格顺序：记录 fix 提交 SHA F → 切换本地 `develop` → 确认 `develop` 仍是起点且 GitHub `origin/develop` 未变化 → `git merge --ff-only F` → 在 `develop` 重新运行 `.\gradlew.bat :mechanical-core:test --rerun-tasks` 和 `.\gradlew.bat test --rerun-tasks` → 确认工作树干净 → `git push origin develop:develop`（禁止 force）→ `git ls-remote origin refs/heads/develop` 比对远端 SHA=本地 SHA → 删除本地 `fix/mechanical-core-foundation`。若任一步失败，停止，不删除 fix 分支，不继续下一切片。

## 8. 验收标准

- 存在且仅存在一个新 `MechanicalOperationCoordinator` 类型；App 尚未引用它。
- operationId、operation revision、command attempt、ledger revision 围栏在测试中覆盖错误 owner、迟到 attempt/revision、重复结果和 CAS 冲突。
- 固定步骤状态机拒绝跨级推进；命令意图 CAS 成功前不返回 dispatch 许可。
- 资源只能由精确 token 的安全暂停或完成转换释放；超时、取消、未知结果均保留忙态和恢复责任。
- 最多一个 pending、一个 running、一个 paused、一个不占运行槽的 terminal；paused/terminal 不持有资源。完成 CAS 清空 running 并保留 terminal；固定高优先级候选只通过通用安全 checkpoint 单 CAS 接管，paused 槽已占用时不得产生第二个暂停 owner。
- 新 boot 必须由唯一 Coordinator 完成 startup recovery 并耐久打开当前 boot admission gate；旧 boot OPEN、损坏 ledger、Modbus 未连接或快照不足时，除携带持久化恢复 authorization 的专用恢复入口外，所有 Coordinator 方法都被阻止。
- 恢复下发必须先 CAS 持久化并消费绑定当前 attempt 的 proof，再只返回一次 `RecoveryDispatchAllowed`；崩溃后同 proof 不得重新签发。恢复效果同样经持久化 authorization 和幂等 effect token 执行。
- 恢复看到匹配 `COMPLETED` 时，必须先在签发 effect authorization 的同一 CAS 中进入 `RESULT_CONFIRMED`；`applyRecoveredEffect` 才能从该中间态幂等推进到 `EFFECT_APPLIED`，任何 CAS 失败都不得跨级。
- 跨 boot 的真实 `COMMAND_PREPARED + old DispatchIssued` 若观察到匹配 RUNNING/COMPLETED，只先 CAS 到 `COMMAND_DISPATCHED` 且不签发 dispatch；COMPLETED 必须用下一组更新序号快照和下一次 CAS 才进入 `RESULT_CONFIRMED`，保证不重发、不跳级。
- 每次接受恢复快照都持久化绑定 attempt 的 `RecoveryObservationCursor`；同组重放、序号倒退或 CAS 失败不得越过游标或确认结果。
- 恢复效果成功后通过 `continueRecoveredOperation` 每次只走一条无副作用状态边；非末 step 到下一 `ADMITTED`、末 step 到 terminal 后才打开 gate，不得永久停在 `EFFECT_APPLIED`。cursor 在 effect 成功或安全终态清除，不能污染下一 step/operation。
- 恢复收口的每次 ledger CAS 原样保留 paused/pending 和不应替换的 terminal；末步只移动当前 running 到 terminal，不能丢失其他 operation ownership。第一条和第二条收口 CAS 失败均有全 snapshot 不变测试。
- 恢复策略要求 Modbus 已连接且至少两个稳定快照；明确未下发还必须有绑定当前完整 command attempt 且 `proofId` 非空的 `CONFIRMED_NOT_DISPATCHED` 证据，不能从寄存器空闲或旧 proof 猜测；已完成未应用效果、仍运行、结果不明、安全终态分类均有测试。
- `MoveStatusProjection` 对活动类型派生 1..5，对 paused/completed/null 派生 0，对 `RECOVERY_REQUIRED` 保持忙态。
- `:mechanical-core:test --rerun-tasks`、聚合 `test --rerun-tasks` 和 `:app:assembleDebug --rerun-tasks` 成功。
- 审核会话A与主会话均无未关闭 P0/P1；证据不混淆真实硬件或后端。

## 9. 逐文件回滚边界

- 删除 `mechanical-core/`：完整撤销纯核心实现与测试，不影响 App 运行链路。
- 从 `settings.gradle.kts` 删除 `include(":mechanical-core")`：撤销构建图注册。
- 删除本计划与本切片验证记录、把 `CURRENT_STATUS.md` 恢复到起点版本：撤销文档状态。
- 因本切片不修改 `app/**`、协议、寄存器、MQTT/HTTP 或旧 owner/session/claim，回滚不需要迁移运行数据，也不会触碰生产仓用户未提交修改。

## 10. 下一切片边界

本切片完成后唯一允许的下一步是“旧状态影子观察”：由 App 只读采集现有 owner/session/claim/resource/checkpoint 并映射为新 core 输入，对比但不接管、不写 `moveStatus`、不下发机械命令。正式订单 `out1..out5` 所有权迁移仍需再后置一个独立切片。
