# 补肠口味逐批收敛实施计划

> **执行约束：** 按 TDD 在当前会话逐项执行；本次未获授权，不提交、不推送、不发布、不安装 APK、不操作设备。

**目标：** 仅在低/高并发新补肠批次创建时，根据有效库存目标比例和前区有效占位，稳定分配本批口味并逐批收敛。

**架构：** 新增纯计算 `SupplementTasteMixPlanner`，输入目标数量、剩余库存、按前区位置去重的有效占位和本批可分配位置，输出稳定的位置到口味计划。低并发 `planLowHeatSupplementTargets` 与高并发 `planInitialTargets` 接入；`planResumeTargets`、动作组、耐久批次、回搬和调度阈值保持不变。

**技术栈：** Kotlin、JUnit 4、Android Gradle Plugin。

---

### 任务 1：纯口味规划器 RED/GREEN

**文件：**
- 新建：`app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementTasteMixPlanner.kt`
- 新建：`app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementTasteMixPlannerTest.kt`

- [ ] 先写失败测试，覆盖新增口味优先、混合缺口、两批收敛、旧口味残留不算占位、位置去重、库存不足、单口味、无库存和稳定并列。
- [ ] 运行定向测试，确认因规划器缺失而 RED。
- [ ] 实现最小纯计算逻辑：先按相对缺口、再按绝对缺口、最后按口味编码；每次分配同步更新占位和本轮剩余库存。
- [ ] 重跑规划器测试，确认 GREEN。

### 任务 2：低并发新批次接入

**文件：**
- 修改：`app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt`
- 修改：`app/src/test/java/cn/niuyannet/kaochang/android/services/LowHeatSupplementReservationResultTest.kt`

- [ ] 先添加低并发失败测试：目标 `A=6/F=3` 时，已有 `A=6` 的三个空位规划 `F,F,F`；`isHasGrilling` 与耐久承诺按前区位置只计一次。
- [ ] 运行定向测试确认 RED。
- [ ] 让新批次规划读取 1~9 实物、有效预占和耐久回搬承诺；只修改本批空位口味，不改变 3/6 阈值、动作和失败收口。
- [ ] 重跑低并发与规划器测试确认 GREEN。

### 任务 3：高并发新批次接入

**文件：**
- 修改：`app/src/main/java/cn/niuyannet/kaochang/android/services/HighHeatSupplementResumePlanner.kt`
- 修改：`app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt`
- 修改：`app/src/test/java/cn/niuyannet/kaochang/android/services/HighHeatSupplementResumePlannerTest.kt`

- [ ] 先添加高并发失败测试：目标按前区 21 位计算、仍生成 25~33 九目标、回搬位置不变、受保护预占不改口味。
- [ ] 运行定向测试确认 RED。
- [ ] 仅修改 `planInitialTargets` 的口味选择；`planResumeTargets` 原样恢复旧批次口味。
- [ ] 重跑高并发、低并发与规划器测试确认 GREEN。

### 任务 4：完整验证与边界复核

**文件：**
- 复核本次全部 diff，不修改维护页、状态字段、MQTT、动作组、锁、journal 和版本号。

- [ ] 运行三类定向测试并记录通过数。
- [ ] 运行 `./gradlew.bat test`，统计 Debug/Release XML 的 tests/failures/errors/skipped。
- [ ] 运行 `git diff --check`、`git status --short`，确认无格式错误、无越界文件、无提交推送。
- [ ] 明确未验证 RS485、Modbus、MQTT broker、真机页面、真实库存与机械回搬。
