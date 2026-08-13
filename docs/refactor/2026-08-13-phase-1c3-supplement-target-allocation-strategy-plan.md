# 第一阶段 1C-3：补货目标配额纯策略计划

日期：2026-08-13
源码基线：`fad00d530bb88c0fb3ace82dcdda038f24626438`
源码版本：`versionName=2.5.42`、`versionCode=292`

说明：源码版本不代表现场 APK。本计划只描述下一绞杀切片；计划审核通过不代表发布、部署、安装或设备操作授权。

## 1. 为什么做这一片

完整调度 profile 当前至少由以下能力组成：

1. 出餐可售候选选择；
2. 可自动补货库存快照；
3. 补货目标配额；
4. 批次数量、热源和返回/可分配位置；
5. 给定目标与位置后的口味混配；
6. 高低并发模式与过渡决策；
7. 机械安全门禁、动作编排、checkpoint 和恢复。

1A 已抽取第 1 项，1C-2 已抽取第 5 项。1C-3 只抽取第 3 项：给定已经完成安全过滤和按口味聚合的库存快照，以及目标烤盘数和分配场景，计算各口味目标数量。

第 2 项仍由 App 拥有：`KaoPanHelper.isSupplyBoxAvailableForAutoSupplement` 当前依赖 `supplementEnabled`、库存数、口味码、`IR_PICK_FAILED` 和合法源箱位。这些是配置/库存/硬件安全语义，不能由可替换调度策略绕过，也不能把 `AppConfig`、`KaoPanBox` 或下位机错误码泄漏到纯核心。

## 2. 当前源码事实

### 2.1 两种实际分配模式

`KaoPanHelper.computeKaoPamRatio` 被以下路径使用：

- 低并发 1~9 初始化；
- 高并发 1~21 初始化；
- 高转低时 1~9 目标；
- 前区空盘口味预留补齐；
- 低并发补热目标。

它按库存比例计算 Float 理论值，每个被选口味至少给 1 个，随后按小数余数补齐，或从当前分配最多的口味开始减，目标是分配总数等于目标工位数。

`KaoPanHelper.computeHighKaoPamRatio` 只在 `HighHeatSupplementResumePlanner` 中为高并发补热生成 1~21 目标。它不先保证每个口味 1 个，而是先取 Float 比例的整数部分，再按库存排序循环补余数；输出可能保留目标数为 0 的口味键。

### 2.2 必须冻结的细节

1. 只有 App 过滤后的有效箱位参与聚合：全局 `supplementEnabled=0`、`num<=0`、空口味、`IR_PICK_FAILED`、非法源箱位均排除；
2. 同口味多个箱位用 Int 求和，当前实现没有归一化或饱和处理；
3. 总库存先以 Int `sum()`，再转 Float；不得在本切片静默改成 Double、Long 或重新归一化；
4. 口味先按库存降序、口味码升序排列；口味数超过目标工位数时只保留前 N 个；
5. 即使丢弃部分口味，比例分母仍是所有有效口味的总库存，而不是所选口味库存之和；
6. 标准模式的余数降序排序是稳定排序，相同余数继续沿用第 4 条的顺序；
7. 标准模式超额时按当前分配数降序稳定排序，每个候选按现有循环至多处理一次，且不减到 1 以下；不得趁迁移“修正”循环；
8. 高并发补热模式先循环分配未取整余量，再合并整数部分；因此 map 首次插入顺序和零值键都必须保持；
9. `targetPanCount<=0`、有效库存为空或 Int 总和转 Float 后 `<=0f` 时返回空；
10. App 方法仍返回可变 `MutableMap`，调用方按当前迭代顺序给物理烤盘写入口味预留；纯核心输出自身必须是防御复制、不可修改快照；
11. 原有入口、有效库存、丢弃口味和最终分配日志继续由 App 输出，日志失败不影响计算；
12. `computeKaoPamRatio_old` 是未被生产调用的历史算法，本切片不迁移、不删除、不作为算法 B。

## 3. 目标边界

### 3.1 纯核心输入

在 `scheduler-core` 新增：

```kotlin
enum class SupplementTargetScenario {
    STANDARD,
    HIGH_HEAT
}

class SupplementTargetAllocationInput(
    val targetPanCount: Int,
    availableStockByTaste: Map<String, Int>,
    val scenario: SupplementTargetScenario
)
```

`availableStockByTaste` 的业务含义是“App 已按现有硬件和配置安全规则过滤、再按原始口味码聚合的库存快照”。构造时防御复制并包装为不可修改、保持迭代顺序的 map。核心不读取箱位、配置、单例、日志、时间、MQTT 或硬件状态。

### 3.2 策略接口与结果

```kotlin
interface SupplementTargetAllocationStrategy {
    val id: StrategyId
    fun allocate(input: SupplementTargetAllocationInput): SupplementTargetAllocationPlan
}
```

算法 A 为 `InventoryProportionalTargetStrategy`，稳定 ID：

```text
inventory-proportional-target-v1
```

结果至少包含：

- `targetCountByTaste`：精确保持当前迭代顺序和零值键；
- `droppedStockByTaste`：精确保持当前“被暂不分配口味”的顺序与库存数，供 App 保留现有诊断日志。

两个输出均为防御复制、不可修改快照。纯核心不输出日志、不修改库存、不写烤盘、不授权机械动作。

### 3.3 App 兼容适配

保留 `KaoPanHelper.computeKaoPamRatio` 和 `computeHighKaoPamRatio` 的签名、调用点和可变返回类型：

1. 继续记录原始箱位输入；
2. 继续调用 `isSupplyBoxAvailableForAutoSupplement` 完成安全过滤；
3. 继续按当前 `groupBy + sumOf` 聚合，保持 Int 和口味码语义；
4. 调用唯一的算法 A 实例，分别传 `STANDARD` 或 `HIGH_HEAT`；
5. 根据 `droppedStockByTaste` 输出与当前等价的诊断日志；
6. 输出最终配额日志；
7. 把核心只读结果复制为 `MutableMap` 返回给现有调用方。

删除 App 中迁移后的排序、Float 配额、取整和余数循环；硬件过滤、日志、`computeKaoPamRatio_old` 及所有调用方保持在 App。

## 4. 与完整算法 A/B 的关系

本切片将建立第三个可替换调度子策略（此前已有出餐候选和口味混配），不新增 App 配置、UI 或运行时开关，不把它冒充完整算法 A/B。

未来 `SchedulerProfile A/B` 必须原子绑定：

- sell-candidate strategy ID；
- target-allocation strategy ID；
- batch/heat/position strategy ID；
- taste-mix strategy ID；
- traffic-mode strategy ID；
- profile 自身版本。

应用层只能在 1C-1 已定义的完整空闲门禁下切换 profile；已开始流程 pin 整个 profile，不能只热切一个子策略。硬件安全过滤和机械动作门禁不是可替换算法的一部分，任何 profile 都不能绕过。

## 5. 测试先行

### 5.1 纯核心红灯与场景测试

先新增 `SupplementTargetAllocationStrategyTest`，在生产类型不存在时执行 `:scheduler-core:test`，记录因缺少类型导致的预期红灯。

场景至少覆盖：

1. 稳定策略 ID；
2. `STANDARD` 的 16:24 库存分配到 21 个目标得到 8:13；
3. 标准模式每个选中口味至少 1、余数补齐、超额减一和最终总数；
4. `HIGH_HEAT` 的整数部分加循环余量；
5. 高热模式保留零值口味键及精确插入顺序；
6. 口味数大于目标数时库存降序/口味码升序选前 N，且分母仍含被丢弃库存；
7. 库存相同、余数相同、分配数相同时的稳定次序；
8. target 为负、0、空库存和总和 `<=0f`；
9. 输入修改不影响已构造快照，输入/输出集合不可修改；
10. 对实际物理范围重点覆盖 1、3、9、21、33 个目标和多箱同口味聚合后的库存形状。

### 5.2 冻结旧算法差分

测试内独立复制迁移前“聚合后的库存 map -> 配额”算法，不调用生产策略、不调用 App 适配器、不共享排序/配额 helper。固定种子生成至少 10,000 组：

- 不同 map 插入顺序；
- 0~40 种口味；
- 库存相同、极度倾斜、小库存及接近 Int 边界的库存；
- target `-2..33`；
- 两种 scenario。

逐项比较异常/空结果、目标 map 的键值与迭代顺序、零值键、丢弃口味及顺序。随机输入必须限制不会形成不可控的十亿级补余循环；任何现有 Int/Float 溢出行为应由明确边界用例固定，而不是测试挂死。

### 5.3 App 兼容与源码门禁

保留并扩充 `KaoPanHelperRatioTest`：

- `supplementEnabled=0`、空箱、空口味、`IR_PICK_FAILED`、非法源箱位仍被 App 排除；
- 多箱同口味仍按 Int 聚合；
- 两个生产入口分别用同口味 `Int.MAX_VALUE + 1` 锁定 `sumOf<Int>` 回绕后的空结果；
- 两个生产入口分别用不同口味正数库存构造 `values.sum()` 的负回绕、零回绕和正回绕，锁定结果/异常、键值、零值键及迭代顺序；正回绕用例必须限制为可快速终止的 target，且明确保留标准模式单遍减一后仍可能不等于 target 的旧极值行为；
- 现有标准/高热结果、可变返回类型和日志失败不影响结果；
- 所有现有 ratio 测试继续通过。

新增 `SchedulerTargetAllocationWiringSourceTest`：

- 两个生产入口各只调用一次核心策略；
- 两个入口仍先使用 `isSupplyBoxAvailableForAutoSupplement` 聚合有效库存；
- App 入口不再包含 `sortedByDescending`、比例乘法和补余/减一循环；
- `computeKaoPamRatio_old` 原样保留且生产调用点为 0；
- 六个现有调用点仍只调用 `KaoPanHelper` 兼容入口，没有直接实例化核心策略；
- `SupplementTasteMixPlanner` 仍只消费目标 map，不反向读取 App 单例；
- AppConfig/Maintenance 不新增不完整的子策略开关。

## 6. 验证命令

定向：

```powershell
.\gradlew.bat :scheduler-core:test --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*KaoPanHelperRatioTest" --tests "*SchedulerTargetAllocationWiringSourceTest" --rerun-tasks --no-daemon --console=plain
```

完整 JVM 回归：

```powershell
.\gradlew.bat :scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks --no-daemon --console=plain
```

由于修改 App 生产源码，最终重新执行：

```powershell
.\gradlew.bat :app:assembleDebug --rerun-tasks --no-daemon --console=plain
```

同时复算 XML 测试数、APK 大小/SHA-256，执行 `git diff --check`、核心禁止依赖扫描、当前版本核对和生产仓库 tracked 状态核对。

纯核心静态门禁还必须执行：

- `scheduler-core` main/test 不得新增 Android/AndroidX、App 包、MQTT、Modbus/RS485/串口引用；
- `scheduler-core/src/main` 不得新增 `java.io`、`java.nio`、`java.net`、文件、线程/Executor、协程、Timer/Handler、系统时钟、随机数或 UUID 运行时依赖/调用；
- 1C-3 新增的 `scheduler-core/src/test` 代码只允许 `SupplementTargetAllocationStrategyTest` 的 10,000 组差分使用固定常量种子的 PRNG；禁止时间种子、无固定种子的随机源、UUID、文件/网络、线程/协程或系统时钟，确保测试可重复。前序切片已有的固定种子差分和源码结构测试按各自已审核边界保留；
- 核对 `scheduler-core` 依赖清单没有新增运行时库，本切片不得修改其 Gradle 配置；
- 复核 `scheduler-core` 全部 main class 的 class major 为 55（Java 11）。

## 7. 验收标准

1. 10,000 组冻结差分为 0，且 oracle 与生产实现无委托/共享 helper；
2. 两种旧分配模式的键值、迭代顺序、零值键和丢弃诊断一致；
3. App 硬件/配置库存过滤和日志保持，核心没有 Android/App/MQTT/Modbus/RS485/串口依赖；
4. 所有既有调用点和机械写入顺序不变；
5. 全量 JVM 测试和 Debug 构建通过；
6. 独立审核结论无未处理 P0/P1/P2；
7. 生产仓库 tracked 文件无变化；
8. 明确标注未做真实 MQTT、RS485/Modbus、库存现场或机械验证。

## 8. 允许变更文件白名单

当前工作树包含多个前序合法但未提交切片。1C-3 实施只允许触碰以下文件：

1. 新增 `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/SupplementTargetAllocationStrategy.kt`；
2. 新增 `scheduler-core/src/test/kotlin/cn/niuyannet/kaochang/scheduler/SupplementTargetAllocationStrategyTest.kt`；
3. 修改 `app/src/main/java/cn/niuyannet/kaochang/android/model/KaoPanHelper.kt`；
4. 修改 `app/src/test/java/cn/niuyannet/kaochang/android/model/KaoPanHelperRatioTest.kt`；
5. 新增 `app/src/test/java/cn/niuyannet/kaochang/android/model/SchedulerTargetAllocationWiringSourceTest.kt`；
6. 本计划文件；
7. 新增 `docs/refactor/2026-08-13-phase-1c3-supplement-target-allocation-strategy-verification.md`；
8. 修改 `docs/refactor/2026-08-13-target-architecture-decisions.md` 的 1C-3 状态。

禁止修改六个调用点所在生产文件、`SupplementTasteMixPlanner`、Gradle/settings/version catalog、机械动作、库存扣减/持久化、MQTT/HTTP、Modbus/RS485、协议和下位机相关文件。最终用实施前后路径清单及精确 diff 机械核对本白名单；发现越界先停止并恢复本切片自身越界改动，不覆盖前序或用户已有改动。

## 9. 回滚边界

本切片不新增配置字段、持久化格式、数据库或协议。路径级回滚动作固定为：

1. 恢复 `KaoPanHelper.kt` 的两个生产方法及其原地配额计算；
2. 恢复 `KaoPanHelperRatioTest.kt` 到实施前内容；
3. 删除新增 core 主源码 `SupplementTargetAllocationStrategy.kt`；
4. 删除新增 core 测试 `SupplementTargetAllocationStrategyTest.kt`；
5. 删除新增 App 接线门禁 `SchedulerTargetAllocationWiringSourceTest.kt`；
6. 回退 `2026-08-13-target-architecture-decisions.md` 中 1C-3 已实施状态；
7. 删除尚未对外使用的 1C-3 验证记录，或若需保留审计轨迹，则在文件首部明确标记“已回滚、不可作为当前能力证据”；
8. 本计划作为历史方案保留并明确记录回滚事实，不修改前序切片文件。

所有调用方、箱位数据和下位机协议无需迁移。回滚后重新运行完整 JVM 测试；若回滚触及 App 生产源码，重新构建 Debug APK，仅作为本地构建证据。

## 10. 明确不做

- 不实现或伪造算法 B；
- 不新增 profile 选择 UI/配置；
- 不改 `computeKaoPamRatio_old`；
- 不改变补货安全过滤、库存扣减、箱位选择、批次数量、热源、返回位置、动作组或恢复；
- 不修改 MQTT/HTTP、Modbus/RS485 或下位机协议；
- 不提交、不推送、不发布、不部署、不安装、不操作设备。
