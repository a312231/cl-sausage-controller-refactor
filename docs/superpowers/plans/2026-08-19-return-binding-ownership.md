# Return Binding Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 阻止补热回搬目标重复绑定，并对旧重复 intent 进入可审计、无新增机械入口的维护收口。

**Architecture:** Pending 恢复与 generic 残缺恢复分责，planner 纯化、Repository 唯一性落盘门禁、KaoChangOperate 中央机械门禁组成三层防线。旧重复状态使用独立 fingerprint 证据进入维护，只能在现有清盘流程完成后无机械释放精确 origin 绑定。

**Tech Stack:** Kotlin、Android 12、JUnit4、Gradle 8.7、SharedPreferences JSON intent。

---

### Task 1: Repository 目标唯一性与冲突证据

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementBatchIntent.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementBatchIntentRepositoryTest.kt`

- [ ] **Step 1: 写失败测试**：覆盖 new 内部重复、retained-new 重复、旧重复首次加载保存一次、稳定轮询零写、集合变化更新、普通确认不清专用门禁、独立故障字段保留。
- [ ] **Step 2: 运行红灯**

Run: `./gradlew :app:testDebugUnitTest --tests '*SupplementBatchIntentRepositoryTest'`

Expected: 新增测试因缺少冲突字段、fingerprint 和唯一性校验失败。

- [ ] **Step 3: 最小实现**：增加独立冲突字段、`requiresAnyManualConfirmation()`、稳定 fingerprint、`begin` 唯一门禁、load 首次/变化写入和专用精确 resolve。
- [ ] **Step 4: 运行绿灯**：同一测试类全部通过。

### Task 2: Low/High generic planner 禁止借用活动预留

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/LowHeatSupplementResumePlanner.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/HighHeatSupplementResumePlanner.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/LowHeatSupplementResumePlannerTest.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/services/HighHeatSupplementResumePlannerTest.kt`

- [ ] **Step 1: 写现场失败测试**：13 skipped 后不得借 14→2；16→1/17→4/18→5 活动时不得生成 13→1/15→5；高并发 active intent 同样不得 fallback。
- [ ] **Step 2: 红灯**：运行两个 planner 测试类，确认旧代码确实生成错误目标。
- [ ] **Step 3: 最小实现**：`activeIntent != null` 返回空计划；仅 null 走 legacy，planner 不修改 `KaoPan.taste/isHasGrilling`，本轮目标去重。
- [ ] **Step 4: 绿灯**：运行两个 planner 测试类。

### Task 3: Pending/generic 调用职责与 admission 结果

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Test: strong-related low/high resume and pending tests under `app/src/test/java/cn/niuyannet/kaochang/android/services/`

- [ ] **Step 1: 写失败测试**：pending 用 `existingBatchId` 恢复并阻断同轮新批次；generic 仅新 intent admitted 才接管；begin 拒绝时 taste、预留、store、机械调用不变。
- [ ] **Step 2: 红灯**：运行新增/修改测试，确认无条件 `true` 和预写状态被捕获。
- [ ] **Step 3: 最小实现**：增加 resume 专用 `SupplementBatchAttemptResult(admitted,movedCount,batchId)` 包装，不改普通 Int API；先 admit 再应用 resume 状态。
- [ ] **Step 4: 绿灯**：运行强相关测试。

### Task 4: 旧冲突中央维护门禁

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Test: strong-related conflict gate tests

- [ ] **Step 1: 写失败测试**：冲突触发 `isEnable=0 / inspectionMode=1 / onlineStatus=3`；稳定状态不重复发布；漂移后重锁；自动补肠、自动搬盘、普通 clear 全部拒绝且 journal/202 前调用次数为 0。
- [ ] **Step 2: 红灯**：运行冲突门禁测试。
- [ ] **Step 3: 最小实现**：中央 conflict 查询、状态锁维护投影、补肠 admission 与搬盘 internal 前置拒绝、普通 clear 拒绝。
- [ ] **Step 4: 绿灯**：运行冲突门禁测试。

### Task 5: Maintenance 两个清盘入口后的专用无机械收口

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/fragments/MaintenanceFragment.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Test: `app/src/test/java/cn/niuyannet/kaochang/android/fragments/` 下 source tests
- Test: conflict resolve service tests

- [ ] **Step 1: 写失败测试**：普通确认不清门禁；两个清盘入口严格先执行原清盘再调用专用 resolve；stale fingerprint、源盘有肠、订单/journal/claim/锁存在均失败；成功只清专用字段且不自动恢复营业。
- [ ] **Step 2: 红灯**：运行 Maintenance/source 与 resolve 测试。
- [ ] **Step 3: 最小实现**：复用现有按钮展示说明；记录 `batchId+updatedAtMs+fingerprint`；清盘完成后调用无机械 resolve；不接入 `moveKaoPanToKaoPanManual`，不新增 XML。
- [ ] **Step 4: 绿灯**：运行相关测试。

### Task 6: 综合验证与独立实施审核

**Files:**
- Verify all approved production/test/doc files only

- [ ] **Step 1: 运行针对性测试集合**。
- [ ] **Step 2: 运行 `./gradlew test --no-daemon`**，记录总数与失败数。
- [ ] **Step 3: 核对 `git diff --check`、文件范围、无新增 `moveKaoPanToKaoPanManual` 调用和无新 202/203 路径。
- [ ] **Step 4: 主代理按获批条款自查并将真实 diff、测试证据交《审核者B》；P0/P1 未清零不得继续。**

### Task 7: 版本、最终构建、提交与推送

**Files:**
- Modify after implementation review Approve: `app/build.gradle.kts`

- [ ] **Step 1: 版本改为 `versionCode=298`、`versionName="2.5.48"`。**
- [ ] **Step 2: 重新运行 `./gradlew test --no-daemon`。**
- [ ] **Step 3: 运行 `./gradlew assembleDebug --no-daemon`。**
- [ ] **Step 4: 核对最终 diff 和制品，不宣称真机/RS485验证。**
- [ ] **Step 5: 一个详细中文 commit，快进合入 develop，非 force push origin/develop，比较本地/远端 SHA，删除临时分支。**
