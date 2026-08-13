# 第一阶段 1C-2：补货口味分配策略纯核心提取计划

日期：2026-08-13
源码基线：`fad00d530bb88c0fb3ace82dcdda038f24626438`
源码版本：`versionName=2.5.42`、`versionCode=292`

说明：源码版本不是现场 APK 版本。本计划不授权提交、推送、发布、部署、安装或操作设备。

## 1. 重新校准“算法 A/B”的范围

1C-1 建立的 `SellCandidateStrategy` 只负责“出餐时选择哪一个已熟烤盘”，并不等于完整的烤制调度算法。当前完整调度至少还包含：

1. 出餐候选选择；
2. 补货目标数量/口味比例生成；
3. 补货批次大小、补热源位置和前区回搬/可分配位置选择；
4. 在上述输入均已确定后的口味混配；
5. 低/高并发模式及过渡条件。

烤制/保温生命周期、食品安全丢弃、订单准入、库存扣减、机械动作编排、Modbus/RS485 和恢复 checkpoint 是固定安全/执行边界，不允许跟随 A/B 任意替换。

因此现在直接增加“算法 A/B”维护页选择器会夸大当前能力：用户看到的“切换算法”只能在上述可替换决策均有明确归属、且被一个执行就绪的 profile 完整覆盖后开放。本切片只抽取第 4 项“给定输入后的口味混配”，不声称已经抽取目标比例、批次大小或位置选择，也不增加配置字段或 UI。

## 2. 当前代码事实

`SupplementTasteMixPlanner.plan` 当前是纯计算，输入为：

- 前区容量；
- 各口味目标数量；
- 各口味可用库存；
- 已占用前区盘位及口味；
- 本批次可分配的前区位置。

当前规则按以下优先级给每个可分配位置选择口味：

1. 过滤空口味、非正目标和非正库存；
2. 先通过 Long 整数交叉乘法比较相对缺口，不使用浮点除法；
3. 再比较绝对缺口；
4. 最后按 `tasteCode` 升序稳定打破平局；
5. 每分配一次同步减少该口味本地剩余库存并增加占用数；
6. 容量用原始 `occupiedTasteByPosition.size` 扣减，空口味条目仍占容量，但不进入已占用口味统计；
7. 可分配位置按第一次出现顺序去重，位置即使也出现在 occupied map 中，旧实现也不会额外排除；
8. `occupiedCountByTaste` 返回分配前的已占用统计，不包含本轮新 assignment；
9. `stockLimited` 精确比较 assignment 数与全部去重可分配位置数，因此容量不足也会返回 true，而不仅表示库存不足；
10. 不超过前区剩余容量、去重后可分配位置数和可用库存。

真实调用链为：低并发 `LowCurrentTrafficMode` 直接调用适配器；高并发 `HighCurrentTrafficMode` 先调用 `HighHeatSupplementResumePlanner`，再由该 planner 间接调用适配器。目标数量分别仍由 `computeKaoPamRatio` 和 `computeHighKaoPamRatio` 生成，批次和位置也仍由 App 决定。输出会影响真实补货口味，但本切片不拥有目标数量或目标位置。

`committedReturnTasteByPosition` 解析 App 的 `SupplementBatchIntent` 恢复/回搬绑定，不属于可替换口味算法，本切片保留在 App。

## 3. 目标边界

在 `:scheduler-core` 增加：

```kotlin
class SupplementTasteMixInput(...)
data class SupplementTasteAssignment(...)
class SupplementTasteMixPlan(...)

interface SupplementTasteMixStrategy {
    val id: StrategyId
    fun plan(input: SupplementTasteMixInput): SupplementTasteMixPlan
}

class RelativeDeficitTasteMixStrategy : SupplementTasteMixStrategy
```

稳定 ID 使用 `relative-deficit-taste-mix-v1`。不兼容规则变更必须使用新 ID，不能原地改写 v1。

核心必须：

- 不依赖 Android、App Bean、库存仓库、MQTT/HTTP、Modbus、I/O、线程或系统时钟；
- 构造输入时对所有 map/list 做防御复制，之后调用方修改原集合不能改变核心输入；
- 不修改输入集合；
- 输出 map/list 使用防御复制的不可修改快照，调用方不能反向修改结果；
- 保持当前过滤、容量、去重、库存递减和三层排序语义；
- 返回确定顺序的不可变快照；
- 不选择箱位、不扣库存、不创建补货 intent、不下发机械动作。

## 4. App 绞杀接线

保留 `SupplementTasteMixPlanner` 作为 App 兼容适配器：

- `plan(...)` 只负责把当前参数构造成纯核心输入、调用唯一生产策略 A，再映射回现有 App `Plan/Assignment`，确保低/高并发调用点无需改动；
- App 适配器不得保留相对缺口、绝对缺口或口味排序的第二套实现；
- `committedReturnTasteByPosition(...)` 原样留在 App，继续拥有 `SupplementBatchIntent` 解释权；
- 生产策略仍只有当前规则 A，不增加假的 B；
- 本切片不接 AppConfig、维护 UI、MQTT 下发或运行时切换。

该接线会进入真实补货规划调用链，但只做行为等价迁移。机械执行、库存扣减、恢复绑定和所有安全门禁保持原实现。

## 5. 文件范围

允许修改：

- `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/SupplementTasteMixStrategy.kt`；
- `scheduler-core/src/test/kotlin/cn/niuyannet/kaochang/scheduler/SupplementTasteMixStrategyTest.kt`；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementTasteMixPlanner.kt`；
- `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementTasteMixPlannerTest.kt`；
- 必要的 App 源码接线门禁测试；
- 本计划、验证记录和目标架构决策文档。

禁止修改：

- `LowCurrentTrafficMode`、`HighCurrentTrafficMode` 和现有调用顺序；
- `SupplementBatchIntent`、库存/箱位扣减、回搬绑定和恢复语义；
- `KaoChangOperate`、动作组、Modbus/RS485 和下位机协议；
- 订单、MQTT/HTTP、自清洁、维护恢复和 UI；
- 1C-1 策略选择/切换/pin；
- 生产仓库 `D:\LeYe\cl-sausage-controller`。

## 6. TDD 与等价矩阵

先加失败测试，再加最小实现。至少覆盖：

1. 新口味缺口优先补齐；
2. 相对缺口优先于绝对缺口；
3. 相对缺口相同后比较绝对缺口，且相对比较保持 Long 整数交叉乘法，不改为浮点；
4. 两者相同后按口味码稳定排序；
5. 连续两批收敛到目标比例；
6. 单口味、无目标、无库存；
7. 不超过可用库存；
8. 重复可分配位置按第一次出现顺序只分配一次；
9. 前区容量不足、0 和负数；容量不足时 `stockLimited` 仍按全部去重位置比较并返回 true；
10. 空口味、0/负目标、0/负库存被过滤；空口味 occupied 条目仍占容量但不进入 `occupiedCountByTaste`；
11. `occupiedCountByTaste` 是分配前统计，不包含新 assignment；
12. occupied 位置和 assignable 位置重叠时保持旧实现行为，不新增隐式排除；
13. 构造核心 input 后修改原 map/list，核心 input 和后续结果均保持不变；策略调用也不得修改输入；
14. 输出 map/list 不能被调用方修改；
15. 固定种子至少 10,000 组输入逐项对照迁移前冻结 oracle，oracle 必须独立保留旧公式、不能调用重写后的 App 适配器；比较目标 map、已占用 map、assignment 顺序和 `stockLimited`，并覆盖空口味占位、容量受限、重复/重叠位置、输入插入顺序和 Int 极值；
16. 既有 `SupplementTasteMixPlannerTest` 全部继续通过；
17. App 源码门禁证明 `plan` 只调用一次 core 策略，App 内不再存在 `selectTaste`、`compareTastePriority`、相对/绝对缺口计算；
18. `committedReturnTasteByPosition` 仍在 App，既有取消/供给降级过滤测试继续通过；
19. 门禁分别锁定：低并发直接调用适配器；高并发由 `HighCurrentTrafficMode -> HighHeatSupplementResumePlanner -> SupplementTasteMixPlanner` 间接调用，禁止给 `HighCurrentTrafficMode` 新增直接 core/适配器旁路；
20. `computeKaoPamRatio`、`computeHighKaoPamRatio`、批次大小和位置选择继续归 App，本切片不得把它们误写为已抽取；
21. scheduler-core 禁止依赖扫描为 0，既有售卖候选 10,000 组差分继续通过。

## 7. 自动化验证

```powershell
.\gradlew.bat :scheduler-core:test --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*SupplementTasteMixPlannerTest" --tests "*SchedulerTasteMixWiringSourceTest" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat test assembleDebug --rerun-tasks --no-daemon --console=plain
git diff --check
```

同时检查：

- scheduler-core main class major 全为 55；
- scheduler-core 禁止依赖/import 扫描为 0；
- 生产 `SupplementTasteMixStrategy` 实现只有 A；
- App 不出现策略配置、UI 或运行时切换入口；
- 生产仓库 tracked 文件无变化。

## 8. 审核与回滚

实施前由“重构上位机审核会话A”只读审核本计划；实现和验证后再审核实际 diff，主会话逐项复核。

回滚只需把 App `plan` 恢复为原本地算法，并删除新增 core 文件、测试和文档。没有新配置 key、数据迁移或协议，回滚不影响 1A/1B/1C-1、2A/2B/2C-1、3A/3B-1。

## 9. 证据边界

本切片可证明：在目标数量、库存、占用和可分配位置均已由 App 确定后，当前口味混配规则已成为纯、确定、可替换的策略 A，App 在 JVM/源码门禁范围内保持等价接线。

不能证明：真实库存正确性、箱位传感器、补货 intent 落盘、MQTT、RS485/Modbus、下位机响应或机械补货效果。APK 构建不替代测试机补货联调。完整算法 A/B profile、配置、UI 和切换仍未完成。
