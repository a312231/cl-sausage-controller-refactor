# Mechanical Core 2.5.51 Rebaseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task. Use one shared checkout and one implementation slice; only the review agent may run independently and read-only.

**Goal:** 在Q=`2f0b36e0`（`2.5.51/301`）上恢复已保全的pure `mechanical-core` TDD现场，补齐out4不可逆物理效果单调性，并完成唯一`MechanicalOperationCoordinator`。

**Architecture:** 从保全提交`3d5918a`重新应用纯Kotlin/JVM模块；Operation继续以operationId/revision统一机械身份，并新增已确认不可逆effect key集合，防止后续step或资源收口失败覆盖out4交付事实。2.5.51 V2 Inbox保持上游adapter，不把dispatchId/hash/expiry/ACK字段复制进pure core，也不在本切片接App。

**Tech Stack:** Kotlin/JVM 1.9、Java 11 bytecode、JUnit 4、Gradle 8.7。

---

## 1. 起点与门禁

- 仓库：`D:\LeYe\cl-sausage-controller-refactor`
- 分支：`fix/mechanical-core-foundation-2.5.51`
- 起点develop：`b41f8dab6176d3856d6b8cfc8a7ccee40b16866f`
- 当前源码：`2.5.51/301`
- Q merge：`676bf0d2a1bd37ad4bec6c26fa97ed09500915ed`，第二父为Q。
- partial保全：本地/远端`fix/mechanical-core-2.5.48-partial-preserve@3d5918aa19944b6b4765532f2977c5db43253a3d`。
- 保全提交明确为WIP/RED；重新应用后必须重新验证，不能沿用2.5.48测试结论。
- 本计划审核P0/P1清零前，不cherry-pick保全提交、不修改core源码。

## 2. 非目标

- 不接入`app/build.gradle.kts`，不让App调用MechanicalOperationCoordinator。
- 不修改`OrderDispatchInboxStore`、MQTT协议、后端、SQL、支付或退款。
- 不把`dispatchId`、`payloadHash`、offer/commit expiry或ACK状态放进pure core。
- 不迁移旧session/claim/ResourceLocks/moveStatus写入点；它们留到影子adapter切片。
- 不实现算法B、新UI、MQTT5、运行时算法热切换或双lane。
- 不发布、不部署、不操作设备。

## 3. 修改白名单

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
- `docs/refactor/2026-08-21-mechanical-core-2.5.51-rebaseline-plan.md`
- `docs/refactor/2026-08-21-mechanical-core-2.5.51-rebaseline-verification.md`
- `docs/refactor/CURRENT_STATUS.md`

## 4. 2.5.51新增不变量

1. `physicalResult/effectState`只描述当前step；Operation另持久保存`appliedEffectKeys`，每个key表示对应历史step已经`SUCCEEDED`且effect已耐久应用，不等于当前step的physicalResult。
2. `appliedEffectKeys`只能来自`index <= currentStepIndex`的steps，不得为空；禁止预填未来step key。
3. `effectState == APPLIED`当且仅当当前step的effect key已在集合中；`REQUESTED`首step集合必须为空。
4. `markEffectApplied`在同一次operation revision推进中加入当前step effect key；重复调用不重复添加。
5. `startNextStep`重置当前step result/effect，但必须保留`appliedEffectKeys`。
6. out4建模为独立step；out4 effect已加入集合后，out5失败、取消、超时或owner/resource收口失败只能进入`RECOVERY_REQUIRED`，不得删除out4 effect key。
7. 当前step尚未确认成功时，不得新增其effect key。
8. 2.5.49的10秒仅允许adapter触发精确closeout核验，不进入core release API。
9. 普通Coordinator API继续要求当前boot gate OPEN；恢复专用API只能持有耐久authorization执行。
10. Inbox commit只有在后续adapter成功创建/认领operation后才能标记EXECUTION_STARTED；该跨store桥不在本切片实现。

## 5. 实施任务

### Task 1: 提交计划和激活状态

- [ ] 精确暂存本计划和`CURRENT_STATUS.md`，审核会话A只读审核。
- [ ] 提交并普通push当前fix；不包含core文件。

### Task 2: 重新应用partial WIP并确认RED/GREEN边界

- [ ] 核对保全远端SHA仍为`3d5918a`、第一父为`524027e`、tree恰12路径。
- [ ] 执行 `git cherry-pick --no-commit 3d5918aa19944b6b4765532f2977c5db43253a3d`，只恢复12路径，不把WIP/RED commit带入当前分支历史。
- [ ] 核对工作树只带入保全12路径；App无mechanical-core依赖/引用；HEAD仍为计划提交。
- [ ] 运行`:mechanical-core:compileKotlin`，预期GREEN；运行`:mechanical-core:compileTestKotlin`，预期仅因Coordinator/CoordinatorResult缺失而RED。

### Task 3: 先完成既有Coordinator RED/GREEN

- [ ] 创建`MechanicalOperationCoordinator.kt`最小API壳：类型、构造器、方法签名精确匹配现有测试；所有行为方法统一返回`CoordinatorResult.Rejected("NOT_IMPLEMENTED")`，不得写ledger或调用effect port。
- [ ] 运行`:mechanical-core:compileTestKotlin`，预期GREEN；运行`MechanicalOperationCoordinatorTest`，预期行为断言RED。
- [ ] 按既有Coordinator测试逐项实现：boot gate、pending/running/paused/terminal、单CAS handoff、dispatch intent、迟到回调、幂等effect、startup recovery、proof/cursor和逐边恢复收口。
- [ ] 每完成一个行为组运行定向测试，最终`MechanicalOperationCoordinatorTest`全部GREEN。
- [ ] 再运行`:mechanical-core:test --rerun-tasks`，确认WIP已有模型/状态机/资源/仲裁/恢复测试在Q上全部GREEN。

### Task 4: 用TDD加入不可逆effect单调性

**Test first:** `MechanicalOperationStateMachineTest.kt`

```kotlin
@Test
fun `out4 effect remains confirmed when out5 fails and operation enters recovery`() {
    var operation = orderOperation(steps = listOf(step("out4"), step("out5")))
    operation = completeCurrentStep(operation, attemptId = "out4-attempt")
    assertTrue("effect-out4" in operation.appliedEffectKeys)

    operation = applied(MechanicalOperationStateMachine.startNextStep(operation, operation.token()))
    operation = dispatchCurrentStep(operation, attemptId = "out5-attempt")
    operation = applied(
        MechanicalOperationStateMachine.confirmResult(
            operation,
            requireNotNull(operation.currentCommandAttempt),
            MechanicalPhysicalResult.UNKNOWN
        )
    )

    assertEquals(MechanicalOperationState.RECOVERY_REQUIRED, operation.state)
    assertTrue("effect-out4" in operation.appliedEffectKeys)
    assertFalse("effect-out5" in operation.appliedEffectKeys)
}
```

- [ ] 运行该测试，确认RED是`appliedEffectKeys`尚不存在。
- [ ] 在`MechanicalOperation`末尾新增：

```kotlin
val appliedEffectKeys: Set<String> = emptySet()
```

- [ ] 构造校验要求所有key属于`index <= currentStepIndex`的steps且非空；`REQUESTED`为空；当前key存在与`effectState=APPLIED`双向一致。
- [ ] `markEffectApplied`加入当前key；所有copy/下一step/暂停/恢复/terminal保持集合。
- [ ] 增加未来key、REQUESTED预填、effectState/key不一致、重复effect、out4后out5 FAILED/UNKNOWN/取消/超时、完成收口失败保持集合的构造矩阵测试。
- [ ] 运行状态机和资源测试至GREEN。

### Task 5: 完整验证和审核

- [ ] 检查最终路径严格属于白名单，`git diff --check`通过。
- [ ] source gate确认App没有`implementation(project(":mechanical-core"))`和Coordinator引用。
- [ ] 运行：

```powershell
.\gradlew.bat :mechanical-core:test --rerun-tasks
.\gradlew.bat test --rerun-tasks
.\gradlew.bat :app:assembleDebug --rerun-tasks
```

- [ ] 创建唯一验证记录，分别写源码、JVM、构建、测试MQTT/后端、真实硬件证据。
- [ ] 审核会话A审核完整diff、测试、jar纯度、out4单调性和证据边界；P0/P1未关闭则停止。
- [ ] 主会话复核后提交、ff-only合入develop、集成后复验、普通push并删除实施fix。
- [ ] partial保全分支只在新里程碑提交、推送且确认可恢复后删除。

## 6. 验收标准

- 重构仓仍为`2.5.51/301`，Q历史完整。
- pure mechanical-core无Android/MQTT/Modbus/文件/时钟/协程依赖，App未接线。
- Coordinator为唯一core写入口，operationId/revision/ledgerRevision/command attempt/effect token门禁覆盖。
- out4不可逆effect在out5及后续收口失败时保持单调，不被FAILED/UNKNOWN覆盖。
- 2.5.49时间宽限未进入资源释放规则；2.5.51 dispatch字段未进入pure core。
- 定向/聚合测试与Debug构建成功，独立审核P0/P1为0。
- 未执行的MQTT、后端部署/SQL、RS485/Modbus、机械、断电、温控和长稳明确标注。

## 7. 回滚

- 计划提交独立回滚不影响Q同步里程碑。
- `cherry-pick --no-commit`前保全分支保持不动；实施分支任何失败都停在现场，不reset/clean。
- core仍未接App，回滚整个模块和`settings.gradle.kts`注册即可恢复Q同步运行链路。
- 最终core源码、测试、模块注册和验证状态形成一个绿色里程碑提交；合入develop后单次revert该里程碑即可完整撤销，不遗留RED模块，不force push。
