# 下平台半故障原子收口与售卖放行 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 先建立一个不接业务入口的原子补肠资源收口单元，通过审核后，再让双 lane 补肠在 `in1~in3` 明确失败时隔离下平台、及时释放自身资源并允许上平台售卖。

**Architecture:** 阶段一只在 `services/actiongroup` 增加一个同步协调器：所有最终检查与状态变更都在现有 `faultHeatTransitionLock` 内完成，精确核对补肠 owner/revision/resourceMask、订单 claim 与 `moveStatus`。阶段二只在故障处理和续作门禁接入该协调器；不改维护页，不增加配置、后台任务、轮询、锁或协议字段。

**Tech Stack:** Kotlin、Android、JUnit4、Gradle

---

### Task 1: 阶段一——原子补肠资源收口单元

**Files:**
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementMechanicalCloseoutCoordinator.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/ResourceLocks.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/actiongroup/SupplementMechanicalCloseoutCoordinatorTest.kt`

- [x] **Step 1: 写失败测试**

测试一个纯协调器 API，覆盖：

- 无订单 claim、补肠 owner/revision/mask 精确匹配、`moveStatus=1`：清补肠 owner、释放精确资源、写 `moveStatus=0`；
- 有有效订单 CLAIMED claim 且 `moveStatus=2`：清补肠 owner、释放精确资源、保持 `moveStatus=2`；
- 订单 claim 在最终临界区开始前或临界区内出现：最终判断以锁内快照为准，不走无订单分支；
- 订单 claim 损坏、PREPARED、owner/revision/mask 不匹配、`moveStatus` 不匹配：不修改任何状态；
- moveStatus 耐久写失败或补肠 owner 清理失败：返回失败并保留/恢复忙态；
- 资源已经不存在：允许幂等关闭补肠 owner；资源 mask 变化：拒绝触碰。

- [x] **Step 2: 运行测试确认 RED**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.actiongroup.SupplementMechanicalCloseoutCoordinatorTest"`

Expected: FAIL，原因是协调器及结果类型尚不存在。

- [x] **Step 3: 最小实现协调器**

协调器只依赖现有 `MechanicalClaimOwnerStore`、`MechanicalMoveStatusStore` 和 `ResourceLocks`。为 `ResourceLocks` 增加“持有资源监视器期间执行状态提交，提交成功才删除匹配 owner”的最小原子方法。整个 `close(...)` 在传入的 `transitionLock` 内执行：先读取并验证订单 claim、补肠 owner/revision、当前资源 mask 和 `moveStatus`，再选择 `1→0` 或“保持 2”收口。不得直接清全局资源，不得清订单 claim，不得在锁外预判订单状态。

- [x] **Step 4: 运行测试确认 GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.actiongroup.SupplementMechanicalCloseoutCoordinatorTest"`

Expected: PASS。

- [x] **Step 5: 阶段一完整回归和差异检查**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL，0 failed。

Run: `git diff --check`

Expected: 无输出，exit 0。

- [x] **Step 6: 阶段一独立审核门禁**

审核只检查该协调器及其测试：同锁最终判断、订单 `moveStatus=2` 不被覆盖、owner/revision/mask 精准、失败不误释放、没有业务接线。任何 P1 停止，不进入 Task 2。

### Task 2: 阶段二——接入 in1~in3 双 lane 半故障

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/LowerPlatformIsolationPolicy.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/LowerPlatformIsolationPolicyTest.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/ParallelSupplementPipelineWiringSourceTest.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/ParallelSupplementRuntimePauseSourceTest.kt`

- [x] **Step 1: 写失败测试**

纯策略只允许本轮观察到明确下位机错误的 `in1~in3`，要求 session/action/source/target/checkpoint 与库存事实一致；`in4~in7`、Modbus、Timeout、陈旧结果、落盘失败、证据矛盾全部拒绝。源码接线测试要求先落盘隔离，再调用阶段一协调器；两处双 lane 续作入口在 `ISOLATED` 或 `supplementEnabled=0` 时禁止下发。

- [x] **Step 2: 运行测试确认 RED**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.LowerPlatformIsolationPolicyTest" --tests "cn.niuyannet.kaochang.android.services.ParallelSupplementPipelineWiringSourceTest" --tests "cn.niuyannet.kaochang.android.services.ParallelSupplementRuntimePauseSourceTest"`

Expected: FAIL，原因是策略、协调器接线及隔离续作门禁不存在。

- [x] **Step 3: 最小接入**

失败处理固定为：保存盘箱真实状态成功 → 落盘下平台隔离 → 纯策略确认仍在 `in4` 前 → 调用阶段一协调器。成功后只发布运行态；若已到 `in4`、证据矛盾、落盘失败或原子收口失败，则保留证据并进入既有整机维护，避免出现“仍可付款但补肠资源未释放”的夹层状态；不新增人工恢复逻辑。`in3` 已扣库存事实保留，不回滚、不重放。

- [x] **Step 4: 运行测试确认 GREEN**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.LowerPlatformIsolationPolicyTest" --tests "cn.niuyannet.kaochang.android.services.ParallelSupplementPipelineWiringSourceTest" --tests "cn.niuyannet.kaochang.android.services.ParallelSupplementRuntimePauseSourceTest"`

Expected: PASS。

### Task 3: 阶段二完整验证与最终审核

**Files:**
- Verify only: all changed production/test files

- [x] **Step 1: 完整单元测试与差异检查**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL，0 failed。

Run: `git diff --check`

Expected: 无输出，exit 0。

- [x] **Step 2: 最终独立审核**

审核重点：`in4` 物理边界、库存事实、隔离后不续补、正常订单暂停不受影响、订单 `moveStatus=2` 不被覆盖、无非必要资源/锁/等待任务。任何 P1 停止，不提交实现。

- [x] **Step 3: 精确提交**

只暂存本计划列出的生产和测试文件；不包含 `.gradle-user-home/` 及原有未跟踪文档。不发布、不部署、不操作真机。
