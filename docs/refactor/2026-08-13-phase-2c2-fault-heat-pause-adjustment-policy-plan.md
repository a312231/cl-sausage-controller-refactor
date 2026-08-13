# 第二阶段 2C-2：故障停热烤盘时间补偿纯决策提取计划

日期：2026-08-13
重构仓库：`D:\LeYe\cl-sausage-controller-refactor`
分支：`develop`
实施前 HEAD：`8b1868d6b1eedd4590fa22a0e7cad09bf9e8b636`
源码版本：`versionName=2.5.42`、`versionCode=292`

源码版本不代表现场 APK。本计划不授权提交、推送、发布、部署、安装或设备操作。

## 1. 选择本切片的理由

调度算法 A 的完整 Profile 和进程级固定边界已经建立，但真实算法 B 尚无经用户确认的业务规则，不能为了展示“可切换”而伪造生产 B。当前五类 A 子策略也已有独立固定种子差分测试，现阶段再造空泛回放框架不能直接降低现场风险。

用户另一个最高优先目标是“偶发故障难恢复”。`FaultHeatPauseManager` 当前把纯烤盘时间补偿计算与 Android 时钟、AppConfig、KaoPan、journal、持久化、日志和状态发布混在同一对象中。先提取这段确定性计算，可以在不触碰温控和机械的前提下锁住食品安全边界，并为后续 journal/recovery 状态机拆分建立稳定输入输出。

## 2. 当前代码事实

当前恢复正温链路为：

```text
KaoChangOperate.applyConfiguredTemperatureByPanStateInternal(finishFaultPause=true)
  -> FaultHeatPauseManager.previewFaultHeatPauseEnd
  -> FaultHeatPauseManager.prepareRecoveryJournalIfNeeded
  -> fail-closed 门禁
  -> setAreaTargetTemperature 写入各区目标温度
  -> FaultHeatPauseManager.endFaultHeatPauseAndAdjustIfNeeded
  -> durable 保存烤盘/config，清除 pause/journal
  -> 必要时同步物理烤盘状态
```

`FaultHeatPauseManager.calculatePauseAdjustment` 当前是私有纯计算，但直接接收 App 的 `KaoPan` 和 `AppConfigBean`：

- `nowMs < pauseStartedAtMs`：`CLOCK_REGRESSED`，全局 fail-closed；
- 物理盘位 `1..33` 存在烤肠，且暂停时长严格大于归一化异常阈值：`ANOMALY_TOO_LONG`，全局 fail-closed；
- 任一烤制盘 `startTime > nowMs`：按 `CLOCK_REGRESSED` fail-closed；
- 烤制盘只在故障暂停与本盘烤制区间的真实重叠不超过 abort 阈值时顺延 `startTime`；严格大于阈值时进入待丢弃；
- 保温盘不顺延；`closeTime > 0` 且 `nowMs - holdingTime > closeTime` 时进入待丢弃；等于保温期限仍不丢弃；
- 当前减法、加法和 Long 溢出语义均为 Kotlin/Java 原生行为，2C-2 只等价迁移，不顺便修正。

计算结果随后才由 App 执行：修改 `KaoPan.startTime`、合并 `pendingDiscardPositions`、写食品安全告警、生成/重放 journal、durable save、日志和状态发布。

另外有两个不能被名称误导的现状：

- `previewFaultHeatPauseEnd` 不是纯只读。它先归一化陈旧 `holdingTime`，必要时保存烤盘、保存 config 并写日志，然后才计算 preview；2C-2 必须完整保留这些副作用及其顺序。
- preview、journal prepare、finalize 和 `adjustPansForPause` 各自在调用时捕获自己的 `nowMs` 与当前快照。journal 重放还会把 pause 起点推进到已覆盖时间，再计算剩余区间；它们只能共享同一 policy 公式，禁止复用同一次旧结果或旧时间快照。

`PowerLossRecoveryGuard.recoverPansAfterColdStart` 也会调用 `adjustPansForPause`。因此 2C-2 除故障恢复正温外，还必须保持断电冷启动的 normalize、补偿、config 回写和 `lastRuntimeAliveAtMs` 更新顺序。

## 3. 本切片目标

在 `:recovery-core` 新增无 Android、I/O、时钟、线程和通信依赖的 `FaultHeatPauseAdjustmentPolicy`：

- 只接收不可变烤盘快照、调用方提供的 `nowMs/pauseStartedAtMs` 和已归一化的两个阈值；物理盘位有肠证据必须由 core 从同一快照按现有 `positionSn in 1..33 && hasSausage` 推导，禁止接收可与快照矛盾的外部 Boolean；
- 只返回暂停时长、逐输入项不可变决策、顺延盘位、保温盘位、待丢弃盘位、目标 startTime、稳定告警枚举和 fail-closed 标记；
- 不修改输入，不持有跨调用状态，不生成 UUID，不解析 JSON，不读取 AppConfig/KaoPan；
- App 将 core 结果映射回现有 `EndPauseResult`，继续在原位置执行副作用；
- `previewFaultHeatPauseEnd`、`adjustPansForPause`、journal 准备和 finalize 必须分别用各自当前快照/时间调用同一 core policy，不得共享一次旧结果，也不得在 App 内保留第二套决定公式。

## 4. 建议核心模型

```kotlin
data class FaultHeatPanSnapshot(
    val positionSn: Int,
    val hasSausage: Boolean,
    val startTimeMs: Long,
    val holdingTimeMs: Long,
    val closeTimeMs: Long
)

data class FaultHeatPauseAdjustmentInput(
    val nowMs: Long,
    val pauseStartedAtMs: Long,
    val bakingPauseAbortMs: Long,
    val pauseAnomalyMs: Long,
    val pans: List<FaultHeatPanSnapshot>
)

enum class FaultHeatPauseAlert {
    CLOCK_REGRESSED,
    ANOMALY_TOO_LONG
}

data class FaultHeatPauseAdjustment(...)

object FaultHeatPauseAdjustmentPolicy {
    fun calculate(input: FaultHeatPauseAdjustmentInput): FaultHeatPauseAdjustment
}
```

逐盘输出建议按输入索引而不是只按 `positionSn` 标识，至少包含 `inputIndex`、`positionSn`、分类/动作、决策时 overlap 和可选 `targetStartTimeMs`。这样能保持输入顺序和重复 position 的当前列表/Map 语义。汇总字段必须由同一逐盘决策派生，不能由调用方分别构造出互相矛盾的 summary。

当前 App 有一个只影响诊断文本的特殊顺序：先把汇总 `targetStartTimes` 按 position 写回第一个匹配 `KaoPan`，再遍历所有 pending 对象并用对象写回后的 `startTime` 重新计算日志 overlap。重复 position 的异常列表可能因此出现“日志 overlap 不等于决策 overlap”。为保持 2.5.42 原样，本切片明确允许 `FaultHeatPauseManager.actualPauseOverlapMs` 只作为日志参数保留在 App main；它不得参与 core 输入、顺延/丢弃、target Map、fail-closed、journal 或任何状态修改。源码门禁必须证明这一唯一例外。

输入构造时必须防御调用方集合后续变更；逐盘输出、汇总集合和 Map 也必须不可由调用方反向修改。阈值由 App 现有 `normalizedFaultBakingPauseAbortMs` 和 `normalizedFaultPauseAnomalyMs` 计算后传入，核心不复制默认配置值。

核心继续按现有字段判定：

- baking：`hasSausage && startTimeMs > 0 && holdingTimeMs <= 0`；
- holding：`hasSausage && holdingTimeMs > 0`。

不得引入新的状态枚举后偷偷改变 2.5.42 对异常字段组合的优先级。

## 5. 明确非目标

- 不改变恢复正温调用顺序、三温区目标或 Modbus 写入；
- 不修改 `KaoChangOperate`、动作组、RS485/Modbus、寄存器和下位机协议；
- 不修改 recovery journal schema、JSON、UUID、持久化 key、重放或清除顺序；
- 不修改故障暂停开始/结束、人工恢复、断电恢复、食品安全待丢弃清除流程；
- 不修改 MQTT/HTTP payload、日志发布、UI、订单、补货、自清洁或维护；
- 不新增线程、协程、Timer、Executor、Service、连接或 Android 依赖；
- 不修正当前 Long 溢出、时钟回拨或阈值业务口径；发现问题只记录后续项；
- 不实现调度算法 B、Profile 选择持久化或运行中热切换。

## 6. 修改白名单

生产文件：

- `recovery-core/src/main/kotlin/cn/niuyannet/kaochang/recovery/FaultHeatPauseAdjustmentPolicy.kt`（新增）；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/FaultHeatPauseManager.kt`。

测试文件：

- `recovery-core/src/test/kotlin/cn/niuyannet/kaochang/recovery/FaultHeatPauseAdjustmentPolicyTest.kt`（新增）；
- `app/src/test/java/cn/niuyannet/kaochang/android/services/FaultHeatPauseManagerTest.kt`；
- `app/src/test/java/cn/niuyannet/kaochang/android/services/FaultHeatPauseAdjustmentCoreWiringSourceTest.kt`（新增）。

证据文件：

- 本计划；
- `docs/refactor/2026-08-13-phase-2c2-fault-heat-pause-adjustment-policy-verification.md`（实施后新增）；
- `docs/refactor/rollback/phase-2c2/pre-slice-manifest.md`（实施前新增）；
- `docs/refactor/2026-08-13-target-architecture-decisions.md`（只更新 2C-2 已完成状态，实施成功后才修改）。

若需要修改 `KaoChangOperate`、journal schema/codec、AppConfig、KaoPan、Gradle、MQTT、Modbus 或其他业务文件，说明当前适配边界不成立，必须停止并重新审核。

## 7. TDD 与等价矩阵

先写 recovery-core 失败测试，再加最小实现；再写 App 接线门禁并取得接线前红灯。

至少覆盖：

1. 无烤肠、普通 baking、普通 holding 和无关盘；
2. 暂停只与本盘开始烤制后的区间重叠，startTime 晚于 pauseStartedAtMs 时只补实际重叠；
3. overlap 小于、等于、严格大于 baking abort 阈值；
4. holding 剩余、刚好到期、超过 1ms，`closeTime<=0` 不丢弃；
5. `nowMs < pauseStartedAtMs` 全局 fail-closed；
6. 任一未来 baking start 全局 fail-closed；
7. 有物理烤肠时 pause 小于、等于、严格大于 anomaly 阈值；无物理烤肠时不因超长暂停触发全局异常；
8. 全局 fail-closed 时所有 baking 进入待丢弃且不生成目标 startTime，holding 仍按旧规则列入 holding/pending；
9. 每个输入项的决策 overlap 与当前公式一致；App 仅允许在 target Map 写回后为 pending 日志重算 overlap，不得把该诊断值反馈给任何决定或状态；
10. 输入列表、逐盘决策和输出集合/Map 的不可变快照；
11. 乱序输入的输出排序、重复 pending 去重，以及当前重复 position 的列表保留/Map 后项覆盖语义不得无意变化；增加“同 position 前项超阈 pending、后项正常 shifted”的 App 适配测试，证明 Map 由后项覆盖且现有 `firstOrNull` 仍只修改第一个匹配对象；
12. 代表性的 `Long.MIN_VALUE/MAX_VALUE` 差值和 startTime 加法边界与当前实现逐项等价；
13. App 旧测试所有断言继续通过，且核心结果映射后的 `EndPauseResult` 字段完全一致；
14. 源码门禁证明 `FaultHeatPauseManager` 只有一处出现 core policy 调用（集中在私有适配器），App 内不再保留暂停时长、future baking、abort/anomaly 或 holding expiry 的第二套决定公式；`actualPauseOverlapMs` 只能出现在 pending 日志参数路径，不能出现在返回值、门禁、journal、pan/config 修改条件或 core 输入；preview、prepare、finalize/adjust 可多次调用适配器，但各自必须重新读取当前快照和当前 `nowMs`；
15. `adjustPansForPause` 仍只在 core 返回后修改 pan/config；preview 仍先完成现有 stale normalization、烤盘/config 保存和日志，再按当前时间调用 policy；journal 重放后的 finalize 必须按推进后的 pause 起点重新计算剩余区间；
16. `PowerLossRecoveryGuard` 仍按原顺序执行 journal replay、cold-start 分支、stale normalization、`adjustPansForPause`、config 持久化和 heartbeat 更新；
17. recovery-core 主源码无 Android、App、MQTT/HTTP、Modbus、JSON、UUID、文件、系统时钟和线程依赖。

为防止测试只比较“同一份错误实现”，App 等价测试必须保留现有具名边界断言，并必须用固定种子生成满足当前字段域及代表性异常字段组合的快照，由测试侧从 HEAD 私有决定公式冻结的独立 oracle 与 core 比较至少 10,000 组。oracle 禁止调用新 policy、App 适配器或任何共享决策 helper；旧决定公式只能存在于 test source，不能留在 App main。App 中允许保留的唯一 overlap 公式只服务写回后的日志兼容，不属于该决策 oracle。

## 8. 验证层级

```powershell
.\gradlew.bat :recovery-core:test --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*FaultHeatPause*" --tests "*FaultHeatRecovery*" --tests "*PowerLossRecoveryGuard*" --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --no-daemon --max-workers=1 --console=plain
.\gradlew.bat test :app:assembleDebug --no-daemon --max-workers=1 --console=plain
git diff --check
```

同时核对：

- 变更文件严格属于白名单；
- recovery-core main class major 为 55，禁止依赖扫描为 0；
- `KaoChangOperate` SHA-256 与实施前一致；
- journal schema/codec、AppConfig、KaoPan、MQTT、Modbus 和协议文件 SHA-256 与实施前一致；
- Debug APK 仅记录大小和 SHA-256，不作为现场可用证据。

## 9. 验收标准

- 当前 2.5.42 故障停热补偿结果在静态/JVM 范围内等价；
- 纯核心拥有计算，App 只负责快照映射和副作用；
- 恢复正温前 journal 与 fail-closed 门禁顺序不变；
- 未引入额外资源生命周期、连接或后台任务；
- 独立审核会话A无未关闭 P0/P1，主会话复核结论可靠；
- 实施完成后先停在未提交、未推送状态，等待用户授权里程碑提交。

## 10. 精确回滚边界

实施前记录所有白名单文件和关键禁止修改文件的存在状态、tracked 状态及 SHA-256。

- 删除本切片新增 core policy/test 和源码门禁测试；
- 只逆向 `FaultHeatPauseManager` 的快照映射/core 调用 hunk，恢复 2C-2 前私有计算；
- 只逆向既有 App 测试中为 core 接线增加的断言，不改原业务数据；
- 删除本切片验证记录和 manifest，逆向目标架构文档单一状态 hunk；
- 禁止使用 reset、stash、整文件覆盖或回滚到 2C-2 以前提交的方式破坏既有 1A~1D-3、2A~2C-1、3A~3B-1 成果。

## 11. 证据边界

本切片最多证明：当前源码的故障停热时间补偿和食品安全候选判断已成为纯、确定、可测试的核心决策，App 接线在 JVM 范围内保持等价，工程仍可构建 Debug APK。

不能证明：真实断电/时钟、RK3568 文件系统落盘、真实烤盘温度、MQTT/HTTP、RS485/Modbus、下位机响应、温控写入、机械动作或现场长稳。上述证据必须在后续测试设备联调中单独取得。
