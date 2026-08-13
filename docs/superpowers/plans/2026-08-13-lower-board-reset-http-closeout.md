# 下位机复位与订单自动收口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让普通下位机复位随时可执行，999 成功后一次冷重启并释放旧运行资源，通过幂等 HTTP 关闭/退款未确认 `out4` 的订单剩余数量，且不改变普通复位前的稳定业务状态。

**Architecture:** Android 使用一个单槽耐久 `ResetCloseoutRecord` 保存当前订单最小交付证据和复位阶段；只有 `PREPARED/RESET_WRITTEN` 阶段临时禁售。后端新增设备侧 HTTP 聚合服务，先把旧订单移出运行门禁并 owner-safe 释放工作锁，再复用现有退款记录与补偿机制处理退款。旧 MQTT status=5 补偿方案不恢复，切片1保持不变。

**Tech Stack:** Kotlin/Android 12、SharedPreferences commit、现有 Modbus/机械写栅栏、OkGo HTTP、Java 8/Spring Boot 2.5/MyBatis/Redis、JUnit4/Mockito。

---

### Task 1: Android 最小复位记录与纯策略

**Files:**
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/reset/ResetCloseoutRecord.kt`
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/reset/ResetCloseoutStore.kt`
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/reset/ResetCloseoutPolicy.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/reset/ResetCloseoutPolicyTest.kt`

- [ ] **Step 1: 写失败测试**

覆盖 `ORDER_ACTIVE` 不阻塞、`PREPARED/RESET_WRITTEN` 阻塞、只累计明确 `out4` 成功、剩余数量不小于 0、普通复位恢复原状态、紧急复位保持维护、损坏 JSON 不被当成已完成。

核心接口固定为：

```kotlin
enum class ResetCloseoutPhase { ORDER_ACTIVE, PREPARED, RESET_WRITTEN, SERVER_CLOSED }
enum class ResetKind { NORMAL, EMERGENCY }
data class ResetOrderItem(val itemId: Long, val quantity: Int, val confirmedOut4: Int)
data class ResetCloseoutRecord(
    val resetId: String?,
    val kind: ResetKind?,
    val phase: ResetCloseoutPhase,
    val orderNo: String?,
    val items: List<ResetOrderItem>,
    val previousOnlineStatus: Int,
    val previousOrderBlockReason: String,
    val resetWrittenAtMs: Long?
)
```

- [ ] **Step 2: 运行 RED**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.reset.ResetCloseoutPolicyTest"`

Expected: FAIL，因为上述类型与策略尚不存在。

- [ ] **Step 3: 最小实现并同步落盘**

`ResetCloseoutStore` 只暴露 `load/saveDurable/clearDurable`，真实实现复用 `PreferenceUtils.saveStringPreferenceDurable`；不增加队列、协程或轮询器。validator 拒绝负数量、完成数大于总数、复位阶段缺少 `resetId` 或写入时间。

- [ ] **Step 4: 运行 GREEN**

Run 同 Step 2。Expected: PASS。

### Task 2: Android 订单证据接线但绝不作为准入门禁

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/reset/ResetCloseoutStore.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/reset/ResetOrderEvidenceTest.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/reset/ResetOrderWiringSourceTest.kt`

- [ ] **Step 1: 写失败测试**

验证订单接受后同步保存 `orderNo/items`，每次 `out4` 明确成功先同步递增再上报 status=2；未知、E8、超时、通信异常不递增；正常订单完成后可清理；遗留 `ORDER_ACTIVE` 不参与 `acceptIncomingOrderForMechanicalDispatch` 的拒单判断。

- [ ] **Step 2: 运行 RED**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.reset.ResetOrderEvidenceTest" --tests "cn.niuyannet.kaochang.android.services.reset.ResetOrderWiringSourceTest"`

Expected: FAIL，现有订单流没有该耐久证据。

- [ ] **Step 3: 最小接线**

在现有订单接收串行边界内保存 `ORDER_ACTIVE`；只在 `TakeSausageResult.TAKEN_BY_USER` 对应的明确 `out4` 成功处递增。保存失败不能伪造完成，但也不能让一个非复位的旧快照成为永久拒单原因；正常终态幂等清理。

- [ ] **Step 4: 运行 GREEN 与订单回归**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.reset.*" --tests "cn.niuyannet.kaochang.android.ui.OrderNoDispatchSourceTest" --tests "cn.niuyannet.kaochang.android.utils.OrderStateManagerUpgradeGateTest"`

Expected: PASS。

### Task 3: Android 普通/紧急复位统一协调

**Files:**
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/reset/LowerBoardResetCoordinator.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/fragments/TestFragment.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/EmergencyResetAppRestarter.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/reset/LowerBoardResetCoordinatorTest.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/EmergencyResetRestartCoordinatorTest.kt`

- [ ] **Step 1: 写失败测试**

验证普通复位不调用 `canRunMaintenance`，在所有 moveStatus/寄存器状态下均尝试 999；999 失败恢复原 gate、绝不释放/退款/重启；999 成功才进入 `RESET_WRITTEN` 并安排一次重启；普通复位不改原 `onlineStatus/errorStatus/isEnable/lowerPlatformStatus`，紧急复位保持维护。

- [ ] **Step 2: 运行 RED**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.reset.LowerBoardResetCoordinatorTest" --tests "cn.niuyannet.kaochang.android.services.EmergencyResetRestartCoordinatorTest"`

Expected: FAIL，普通复位仍有忙闲门禁且没有统一协调器。

- [ ] **Step 3: 最小实现**

协调器顺序固定为：同步保存 `PREPARED` → 临时 `SYSTEM_RESET_IN_PROGRESS` → 建机械写栅栏 → 写 999 → 同步保存 `RESET_WRITTEN` → 安排一次冷重启。写失败反向恢复本次新增状态。复用现有 `EmergencyResetMechanicalWriteGate`，不增加第二把全局锁。

- [ ] **Step 4: 运行 GREEN**

Run 同 Step 2。Expected: PASS。

### Task 4: Android 新进程本地收口、HTTP 重试和自检提示

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/MyApp.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/net/NetApi.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/fragments/TestFragment.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfigBean.java`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/reset/ResetStartupCloseoutTest.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/reset/ResetSelfCheckDisplayTest.kt`

- [ ] **Step 1: 写失败测试**

覆盖新进程仅在 `RESET_WRITTEN` 后清当前订单 claim、PROCESSING、moveStatus、所有旧机械 session/owner/resource/pause；不清库存、烤盘、journal、故障状态。HTTP 失败只保留耐久 gate，不持有机械锁；HTTP 成功且满 10 秒后恢复原 orderBlockReason/稳定状态。自检显示原始值并增加“读数不代表回零完成”。

- [ ] **Step 2: 运行 RED**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.reset.ResetStartupCloseoutTest" --tests "cn.niuyannet.kaochang.android.services.reset.ResetSelfCheckDisplayTest"`

Expected: FAIL，新进程还没有普通复位收口和 HTTP 调用。

- [ ] **Step 3: 最小实现**

复用现有紧急复位本地 closeout 能力并扩为显式 `resetId` 收口；资源清理完成后立即释放锁再发 HTTP。重试只挂在启动恢复、网络恢复和维护页重新进入三个现有触发点，不新增定时线程。新进程检测相同 reset 后取消待执行 Alarm，确保一次可见启动。

- [ ] **Step 4: 运行 GREEN 和切片1回归**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.reset.*" --tests "cn.niuyannet.kaochang.android.services.SupplementMechanicalCloseoutCoordinatorTest" --tests "cn.niuyannet.kaochang.android.services.LowerPlatformRuntimeStateReporterTest"`

Expected: PASS。

### Task 5: 后端 HTTP 请求模型与幂等订单收口服务

**Files:**
- Create: `ruoyi-admin/src/main/java/com/ruoyi/app/reset/ManualResetCloseoutRequest.java`
- Create: `ruoyi-admin/src/main/java/com/ruoyi/app/reset/ManualResetCloseoutService.java`
- Modify: `ruoyi-admin/src/main/java/com/ruoyi/app/AppController.java`
- Modify: `ruoyi-admin/src/main/java/com/ruoyi/cl/service/DeviceOrderLockService.java`
- Test: `ruoyi-admin/src/test/java/com/ruoyi/app/reset/ManualResetCloseoutServiceTest.java`

- [ ] **Step 1: 在后端创建同名临时分支并写失败测试**

保留用户已有文档改动，执行 `git switch -c fix/lower-platform-order-release`。测试覆盖：无订单成功、全未交付、部分 `out4` 成功、多订单项、重复 reset、请求完成数小于后端时取后端值、超过总数拒绝、工作锁 owner 已变化不误删。

- [ ] **Step 2: 运行 RED**

Run: `mvn -pl ruoyi-admin -am -Dtest=ManualResetCloseoutServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，请求模型和服务尚不存在。

- [ ] **Step 3: 最小实现事务收口**

接口固定为 `POST /api/app/device/manualResetCloseout`。服务按设备身份解析订单；优先请求 orderNo，其次工作锁 owner，最后仅允许唯一已支付运行中订单。每项使用 `max(serverFinish, controllerConfirmed)`，把剩余项置退款中、主单退出运行中门禁，清 processing count，并调用 `deleteWorkingLockIfOwner`。不扫描历史订单，不删除其他 owner。

- [ ] **Step 4: 运行 GREEN**

Run 同 Step 2。Expected: PASS。

### Task 6: 后端退款意图先落盘、退款失败不阻塞

**Files:**
- Modify: `ruoyi-admin/src/main/java/com/ruoyi/app/reset/ManualResetCloseoutService.java`
- Modify: `ruoyi-admin/src/main/java/com/ruoyi/cl/service/IKcOrderService.java`
- Modify: `ruoyi-admin/src/main/java/com/ruoyi/cl/service/impl/KcOrderServiceImpl.java`
- Test: `ruoyi-admin/src/test/java/com/ruoyi/app/reset/ManualResetCloseoutServiceTest.java`
- Test: `ruoyi-admin/src/test/java/com/ruoyi/cl/service/impl/KcOrderServiceImplRefundTest.java`

- [ ] **Step 1: 写失败测试**

验证微信不可达时退款记录仍为可补偿状态、订单不再是 `status=1`、working lock 已释放、重复请求复用同一订单项退款记录；已退款/退款中/已完成数量不重复退款。

- [ ] **Step 2: 运行 RED**

Run: `mvn -pl ruoyi-admin -am -Dtest=ManualResetCloseoutServiceTest,KcOrderServiceImplRefundTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，现有退款入口的错误返回还不能表达“意图已可靠保存但微信待重试”。

- [ ] **Step 3: 最小扩展现有退款能力**

新增窄接口返回 `REFUND_SAVED/REFUND_COMPLETED/INVALID`，只复用现有 `prepareRefundIntent`、订单退款锁和 orphan 补偿；不新增表、通用 outbox 或支付状态机。HTTP 在所有退款意图可靠保存后返回可开放下单；微信调用结果不重新写工作锁。

- [ ] **Step 4: 运行 GREEN**

Run 同 Step 2。Expected: PASS。

### Task 7: 跨仓临时禁售口径与完整回归

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfigBean.java`
- Modify: `ruoyi-admin/src/main/java/com/ruoyi/cl/domain/KcDevice.java`
- Modify: `ruoyi-admin/src/main/java/com/ruoyi/cl/support/DeviceOrderAvailabilityMessageHelper.java`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/reset/ResetStartupCloseoutTest.kt`
- Test: `ruoyi-admin/src/test/java/com/ruoyi/cl/support/DeviceOrderAvailabilityMessageHelperTest.java`

- [ ] **Step 1: 写 RED 并实现常量对齐**

两端只新增 `SYSTEM_RESET_IN_PROGRESS`，后台文案固定为“设备复位中，请稍后下单”。复位完成时恢复保存的旧原因，不能把 `LOWER_PLATFORM_RECOVERY_REQUIRED` 清空。

- [ ] **Step 2: 跑两仓定向测试**

Android: `./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.reset.*"`

Backend: `mvn -pl ruoyi-admin -am -Dtest=ManualResetCloseoutServiceTest,DeviceOrderAvailabilityMessageHelperTest,KcOrderServiceImplRefundTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 3: 跑完整验证**

Android: `./gradlew test`

Backend: `mvn test`

两仓分别运行 `git diff --check`。Expected: 全部退出码 0、测试报告无 failure/error。

### Task 8: 独立审核与提交边界

**Files:**
- Review: 两仓从各自切片基线到当前 HEAD 的完整 diff

- [ ] **Step 1: 自审范围**

确认旧 MQTT 单槽补偿代码未恢复；没有通用 outbox、数据库迁移、新后台轮询、库存/journal 清理或普通复位自动恢复运营。

- [ ] **Step 2: fresh Codex 独立只读审核**

审核重点：999 失败不误清、999 成功后无旧协程重占、HTTP/退款失败不阻塞下一单、owner-safe 锁释放、普通/紧急状态语义、`out4` 计数、重复请求幂等、切片1不回归。

- [ ] **Step 3: 最多两轮处理审核**

任何 P0/P1 必须修复并重新跑定向及完整测试；第二轮仍有 P1 时停止，不提交实现、不合入 develop，直接报告用户。

- [ ] **Step 4: 审核通过后提交并报告**

仅精确暂存本任务文件，Android 与后端分别提交到 `fix/lower-platform-order-release`。先报告 commit、测试、审核和真机未验证边界；不自动合入 develop、不删除分支。
