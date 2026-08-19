# 营业结束低并发收口最小实施计划

**目标：** 营业结束请求不再因订单、故障或普通调度早退而丢失；只有全部烤肠确认丢弃且现有机械证据收口后，才提交低并发并清除请求。

**边界：** 只新增一个耐久 `closeoutPending`，复用现有烤盘持久化、`DiscardJournal`、`PanMoveJournal`、补肠 intent/binding、紧急复位 fence、`moveStatus` 和动作恢复门禁。不新增线程、协程、Timer、Executor、锁、等待循环、数据库、寄存器复读或协议字段。

## Task 1：最小 pending store 与纯判定

**Files:**
- Create: `app/src/main/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPending.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutPendingTest.kt`

1. 先写失败测试，证明 pending 只在状态变化时同步落盘，损坏非空值失败关闭为 pending，写入失败不伪造状态。
2. 增加纯判定：非营业时间且设备处于运营/出餐，或故障维护且仍有烤肠时捕获请求；人工维护、待投运不自动捕获。
3. 只使用现有 `PreferenceUtils.saveStringPreferenceDurable()`，无变化不重复 `commit()`。

## Task 2：早退前捕获与启动恢复栅栏

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt`
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`
- Create: `app/src/test/java/cn/niuyannet/kaochang/android/services/BusinessCloseoutSourceTest.kt`

1. 在 `pollKaoPanStatus()` 的故障、营业状态、检修、Modbus 和算法早退之前，按当前营业时间与状态耐久捕获 pending。
2. pending 存在时不进入普通高低并发调度，也不启动新补肠。
3. `recoverBusinessStartIfNeeded()` 在任何自动恢复前检查 pending；pending 未清时保持原状态。
4. 无订单且从运营中进入收口时，先耐久切换休息中并发布状态，阻止新订单；订单/机械忙只保留 pending 并立即返回。
5. 旧 `endBusinessHours()` 不再执行“先休息、后忽略丢弃结果”的链路，只负责请求耐久收口。

## Task 3：逐盘失败关闭与最终低并发提交

**Files:**
- Modify: `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangAlgorithm.kt`
- Extend tests from Tasks 1–2.

1. pending 机械阶段只在休息来源为自动营业结束、无订单、无故障/检修、算法开启、Modbus 已连接、`moveStatus=0`、无动作/搬盘/丢弃/紧急复位恢复阻断时运行。
2. 收口前复用现有补肠 intent 清理；人工确认或未完成目标仍在时立即返回，不启动丢弃。
3. 对当前仍有肠的烤盘逐盘调用现有 `discardSausage()`；每次检查布尔返回值，第一处失败立即停止本轮。命令未知由现有 `DiscardJournal + moveStatus=4 + 维护态` 负责，禁止自动重发。
4. 每盘之间重新检查订单和机械门禁，不等待订单，不持有新增资源。
5. 重新证明全盘为空、`pendingDiscardPositions` 为空、补肠 intent/binding 为空、无动作/搬盘/丢弃/紧急复位证据、无订单且 `moveStatus=0` 后，耐久提交：
   - `modeType=1`
   - `transitionMode=0`
   - `highConcurrencyResidualDrain=0`
   - `onlineStatus=2`
   - `restStatusSource=AUTO_END_BUSINESS`
   - `isEnable=0`
6. 配置提交成功后才耐久清 pending。两步之间断电时，重启会再次证明全清并幂等清理；配置提交失败时恢复内存字段并保留 pending。

## Task 4：验证、提交与审核

1. 运行定向 store/policy/source 测试，保留 RED→GREEN 证据。
2. 扫描新增代码，确认没有线程、Timer、Executor、Mutex、等待循环或新锁。
3. 运行 `./gradlew.bat test --no-daemon`，统计 Debug/Release XML 的 tests/failures/errors。
4. 精确暂存本切片文件，单独提交。
5. 交审核者 A 只读复核；通过后才做切片 A+B 组合回归。未经用户另行授权不合并、不推送、不打包发布、不部署、不操作真机。
