# 第一阶段 1C-4：补货批次、热区与位置纯策略计划

日期：2026-08-13
源码基线：`fad00d530bb88c0fb3ace82dcdda038f24626438`
源码版本：`versionName=2.5.42`、`versionCode=292`

说明：源码版本不代表现场 APK。本计划只描述下一绞杀切片；不授权提交、推送、发布、部署、安装或设备操作。

## 1. 目的

当前已有售卖候选、口味混配、目标口味配额三个可替换子策略。本切片继续抽出会真正影响整机调度 profile 的第四个子策略：

1. 低并发何时补 13~15、何时补 16~18、何时建议关闭 2 区加热；
2. 高并发稳态是否达到 25~33 新批次门槛及请求批次数；
3. 给定 App 已安全过滤且已排序的候选位置后，选择本批目标位置和回搬位置并一一配对。

目标不是新增一个孤立开关，而是为未来完整 `SchedulerProfile A/B/C` 建立 `batch/heat/position strategy ID`。本切片只有兼容旧行为的算法 A，不提供虚假的算法 B。

## 2. 当前源码事实与必须冻结的行为

### 2.1 低并发批次与热区

`LowCurrentTrafficMode.lowConcurrencyIntestinalSupplementation` 当前只读三个空位数并返回魔法数字：

- 前区 1~9 空位不少于 3，13~15 与 16~18 都全空：选择 13~15，批次数 3；
- 13~15 全空、16~18 全满且前区空位不少于 6：选择 13~15，批次数 3；
- 16~18 全空、13~15 全满且前区空位不少于 6：选择 16~18，批次数 3；
- 前区空位少于 3 且 13~18 全空：建议关闭 2 区加热；
- 其他情况不启动新批次。

决定之后，App 仍会检查调度开关、库存可用、错误状态、停业前窗口、pending/manual-confirm 批次；真正执行前还会排除 `isHasGrilling` 和已承诺回搬位置，并确认补热区为空。策略不能绕过这些门禁。

低并发执行顺序必须精确保持：

1. 入口先计算纯决策；
2. pending 批次处理可能提前返回，提前返回时本轮不执行 `STOP_ZONE_2`；
3. `START_13_15`/`START_16_18` 继续受调度启用、库存可用、`errorStatus==0`、非停业前窗口及 manual-confirm 等现有门禁约束；
4. 未提前返回时，`STOP_ZONE_2` 不受调度开关、库存、错误状态或停业前窗口限制，只受本次 action 与 `TEMPERATURE_OFF!=1` 控制；
5. 执行 STOP 时必须先写 `TEMPERATURE_OFF=1`，再调用一次 `stopHeating(2)`；后续相同快照不得重复调用。

### 2.2 高并发批次

高并发稳态当前只在 1~21 表面空位不少于 9 且 25~33 九个位置全空时尝试新批次，请求数固定为 9。`HighHeatSupplementResumePlanner.planInitialTargets` 随后还会排除已保护/已承诺回搬位置，因此最终计划可能少于 9；现有 `isValidInitialPlan` 接受 1~9 个连续源位和等量唯一回搬位，本切片必须原样冻结，不能擅自强制“必须正好 9 个”。

低转高过渡的进入条件包含旧 13~18 批次、持久化 intent、恢复可信度、未来回搬位、订单暂停和 transition 收口，这些属于应用编排/恢复安全，不迁入可替换算法。过渡路径仍请求最多 9 个，并只把已经安全筛出的候选交给位置策略。

### 2.3 位置次序

- 低并发：前区候选沿 `TrafficModeHelper` 当前次序取前 N 个，13~15 或 16~18 目标也按当前次序取前 N 个，再按索引配对；
- 高并发：当前空回搬位按位置号升序；承诺的未来回搬位保持承诺列表次序；25~33 空目标按位置号升序；取 `min(requestedCount, returnCandidates, targetCandidates)` 后按索引配对；
- 位置策略只接收整数位置快照，不接收或修改 `KaoPan`、`KaoPanBox`、`AppConfig`、intent、单例或硬件状态。

## 3. 目标边界

在 `scheduler-core` 新增 `SupplementBatchHeatPositionStrategy`，算法 A 稳定 ID：

```text
legacy-fixed-batch-heat-position-v1
```

### 3.1 纯输入与输出

```kotlin
enum class LowHeatAction(val requestedCount: Int) {
    NONE(0), START_13_15(3), START_16_18(3), STOP_ZONE_2(0)
}

data class LowHeatBatchInput(
    val frontEmptyCount: Int,
    val firstHeatEmptyCount: Int,
    val secondHeatEmptyCount: Int
)

data class LowHeatBatchPlan(
    val action: LowHeatAction
) {
    val requestedCount: Int get() = action.requestedCount
}

data class HighHeatSteadyInput(
    val frontEmptyCount: Int,
    val heatEmptyCount: Int
)

enum class HighHeatAction(val requestedCount: Int) {
    NONE(0), START_25_33(9)
}

data class HighHeatBatchPlan(
    val action: HighHeatAction
) {
    val shouldStart: Boolean get() = action == HighHeatAction.START_25_33
    val requestedCount: Int get() = action.requestedCount
}

class SupplementPositionInput(
    val requestedCount: Int,
    targetCandidates: List<Int>,
    returnCandidates: List<Int>
)

data class SupplementPositionPair(
    val targetPosition: Int,
    val returnPosition: Int
)

class SupplementPositionPlan(
    pairs: List<SupplementPositionPair>
) {
    val pairs: List<SupplementPositionPair>
}

interface SupplementBatchHeatPositionStrategy {
    val id: StrategyId
    fun planLowHeat(input: LowHeatBatchInput): LowHeatBatchPlan
    fun planHighHeatSteady(input: HighHeatSteadyInput): HighHeatBatchPlan
    fun planPositions(input: SupplementPositionInput): SupplementPositionPlan
}
```

`SupplementPositionInput` 构造时对两个候选列表防御复制并只读暴露；`SupplementPositionPlan` 对结果防御复制并只读暴露。批次数从枚举 action 派生，不提供独立可写构造参数：低热 `NONE`/`STOP_ZONE_2 -> 0`、两个 START -> 3；高热 `NONE -> 0`、`START_25_33 -> 9`。因此公开构造与 `copy()` 都不能产生 action/count 矛盾结果。

接口提供低并发决策、高并发稳态决策和位置配对三个纯函数。重复位置、非正位置、非正请求数 fail-closed 返回空位置计划，不能抛出后让 App 猜测机械目标。App 传入核心前继续按旧逻辑过滤 future return positions，并以 `.distinct()` 保留首次出现顺序；核心的重复 fail-closed 只用于拦截去重后仍异常的安全快照，不能改变原始承诺列表含重复值时的旧行为。

算法 A 固定低并发容量 3、高并发容量 9，只重现 2.5.42 行为。容量参数不开放为远程配置，避免未经现场验证改变物理批次语义。

### 3.2 App 安全层继续拥有

以下内容明确不可替换、不可由策略授权：

- 调度启停、营业时间、错误状态、库存/箱位/IR 错误过滤；
- pending batch、manual confirmation、batch intent、checkpoint、恢复可信度；
- `isHasSausage`、`isHasGrilling`、过保丢弃、活动订单和资源 owner 的实时复核；
- 候选位置的合法物理范围、连续性、目标盘/回搬盘对象解析；
- 口味配额与混配、箱位选择、库存扣减；
- 加热启停、机械动作组、串口/Modbus、MQTT/HTTP、状态持久化和日志。

App 在使用核心结果后必须继续执行现有 `isValidInitialPlan` 或等价执行前校验；任何位置无法映射或状态已变化都跳过本轮，不做位置猜测。数量规则按 2.5.42 基线冻结：低热 action 请求上限为 3，但口味库存只覆盖 1~2 根时允许形成连续的 1~2 个部分 target 并启动部分批次；只有 0 target 才跳过。高热允许非空的 1~9 对，仍必须满足目标从 25 开始连续、回搬位在 1~21 内且唯一，少于请求数 9 仍按旧行为允许。低热“强制整批 3 根”属于另一个业务安全变更，未经单独授权不得混入本切片。

高热路径的 fail-closed 顺序必须比旧实现更严格但不改变合法计划结果：先在 App 不可变快照上计算旧 `fillMissingTasteReservations` 的预填结果，再构造整数位置结果，完成去重、范围/连续性/数量校验、对象映射和实时状态复核，并形成不修改对象的口味候选。只有整份计划合法后，才按基线顺序一次提交“预填全部原空盘 taste -> planner 覆盖选中回搬位和目标位 taste”。在此之前禁止修改任何 `KaoPan` 字段、intent/checkpoint、温控或机械状态。核心输出重复、越界、无法映射、数量非法、口味缺失或状态漂移时返回空计划，所有相关对象保持调用前快照。

## 4. 接线范围

1. `LowCurrentTrafficMode`：用算法 A 的枚举结果替代魔法数字 0/1/2/3；日志文本与分支语义保持。两条低热补货路径把安全过滤后的前区候选与补热候选交给位置策略，再按结果解析回现有 `KaoPan` 对象；机械调用不变。
2. `HighCurrentTrafficMode`：高并发稳态门槛由算法 A 输出，结果只能在当前 `p1_21>=9` 分支的原位置消费，不能提前计算后改变分支优先级。必须保持 pending/恢复优先，随后是残缺批次、已启动批次、全空初始化；进入稳态坑位分支后仍先检查过保阻断空位并优先高转低，最后才能根据策略结果尝试 25~33 新批次。将请求数显式传给 planner。
3. `HighHeatSupplementResumePlanner`：继续构造当前/未来安全回搬候选、保护位置及目标候选；只把位置号交给核心配对。`TrafficModeHelper` 新增不修改烤盘的预填计划函数，原有 `fillMissingTasteReservations` 复用同一计算；每条延迟写入同时保存预期烤盘对象身份、位置和目标口味。planner 先解析/校验/映射/计算，并确认预填位置仍对应同一对象、位置未变、仍未占用且口味仍为空白；全部合法后按旧顺序提交预填和目标覆盖，合法计划全部烤盘的最终口味不变。
4. 低转高过渡不把 transition/recovery 条件迁入核心，只复用位置配对和固定上限 9。

不新增 App 配置、UI、MQTT 字段或持久化 profile；不改变现有调用频率、线程、协程、定时器或连接。

## 5. 测试先行与冻结差分

### 5.1 纯核心测试

先新增测试并记录生产类型缺失的预期红灯，再实现：

1. 稳定策略 ID；
2. 穷举低并发合理物理范围 `front=0..9`、两个热区 `0..3`，与独立旧 oracle 完全一致；
3. 负数和超物理计数保持旧决策结果，避免迁移时静默加新校验改变行为；显式覆盖 `p13_15=Int.MIN_VALUE, p16_18=Int.MIN_VALUE+6` 的 Int 加法回绕为 6，以及 `Int.MIN_VALUE + Int.MIN_VALUE` 回绕为 0；
4. 高并发稳态 `front=0..21`、`heat=0..9` 穷举，与 `front>=9 && heat==9` 一致，请求数为 9 或 0；
5. 位置数量不足、请求小于/等于/大于候选数；候选次序精确保留；低热口味库存只覆盖 1~2 根时保持连续部分批次，高热 1~9 对按旧校验接受；
6. 重复、非正位置和非正请求 fail-closed；action/count 只能由枚举产生合法组合；
7. 输入修改不影响快照，输出集合不可修改；
8. 固定种子至少 10,000 组位置差分，与独立 `take(min)+zip` oracle 比较值和顺序。

### 5.2 App 兼容测试

- 保留现有 `LowHeatSupplementReservationResultTest`、`LowHeatSupplementResumePlannerTest`、`LowHeatSupplementSafetySourceTest`、`HighHeatSupplementResumePlannerTest`、`HighCurrentTrafficModeStateHelperTest` 全部场景；
- 新增源码接线门禁，确认旧魔法决策体已移除、低/高入口各只通过核心策略、App 安全过滤和 `isValidInitialPlan` 仍存在；同时锁定分支相对顺序：pending/恢复、残缺/已启动批次、全空初始化、过保空位高转低，最后才是高并发稳态新批次；
- 冻结低热库存只够 1 根时生成 1 个连续 target 的基线行为；增加高热当前/未来回搬顺序、受保护/已承诺位置、少于 9 个有效回搬位仍合法、原始 future return 重复值按首次出现去重、去重后异常/非法核心结果 fail-closed 的行为测试；对非法源位和规划后目标/回搬状态漂移断言相关 `KaoPan` 字段不被写入；
- 增加“超过 9 个空盘 + 混合已有口味”的合法路径差分：基线先调用 `fillMissingTasteReservations` 再 planner，与不可变预填计划延迟提交后的 1~21/25~33 全部 taste、target 和 return 顺序完全一致；
- 对延迟预填覆盖的非选中前区盘增加对象替换、位置变化、口味从空白变为非空三种状态漂移测试；任何一项发生时整份计划返回空且不改其他相关对象；
- 增加低并发 STOP 顺序源码门禁：pending 提前返回在 STOP 之前；STOP 不套用 START 门禁；仍先写 `TEMPERATURE_OFF=1` 再 `stopHeating(2)`，且保留防重复条件；
- 核对机械方法 `moveSausagesToKaoPansByTaste`、`heating/stopHeating` 的调用点和参数未被核心直接引用。

## 6. 验证

定向、完整 JVM 与 App 构建：

```powershell
.\gradlew.bat :scheduler-core:test --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*LowHeatSupplementReservationResultTest" --tests "*LowHeatSupplementResumePlannerTest" --tests "*LowHeatSupplementSafetySourceTest" --tests "*HighHeatSupplementResumePlannerTest" --tests "*HighCurrentTrafficModeStateHelperTest" --tests "*SupplementBatchHeatPositionWiringSourceTest" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --rerun-tasks --no-daemon --console=plain
```

同时执行 XML 测试计数、APK 大小/SHA-256、`git diff --check`、核心依赖/禁止引用扫描、class major 55、源码版本和生产仓库 tracked 状态核对。

禁止依赖扫描的范围固定为：

- `scheduler-core/src/main` 禁止 Android/AndroidX、App 包、MQTT、Modbus/RS485/串口、`java.io`、`java.nio`、`java.net`、文件、线程/Executor、协程、Timer/Handler、系统时钟、随机源和 UUID；
- `scheduler-core/src/test` 只允许既有 `SellCandidatePolicyTest`、`SupplementTasteMixStrategyTest`、`SupplementTargetAllocationStrategyTest` 及新增 `SupplementBatchHeatPositionStrategyTest` 的冻结差分使用固定常量种子的 PRNG；前序 1C-1 的 `StrategySwitchPolicyTest` 保留只读当前模块源码的 `java.io.File` 结构门禁，本切片不修改也不扩大该例外；全部测试禁止时间种子、无固定种子随机源、UUID、网络、线程/协程或系统时钟，1C-4 新增测试禁止任何文件 I/O；
- 检查 `scheduler-core` Gradle 依赖清单没有新增运行时库，并复核全部 main class 的 class major 为 55（Java 11）。

这些只构成源码、JVM 测试和本地构建证据，不能替代真实 MQTT、RS485/Modbus、库存、加热或机械联调。

## 7. 验收与回滚边界

验收：旧决策/位置次序差分为 0；现有安全门禁、恢复和机械调用不变；完整 JVM 与 Debug 构建通过；独立审核无未处理 P0/P1/P2；生产仓库 tracked 文件无变化。

回滚按路径逐项执行：

1. 恢复 `LowCurrentTrafficMode.kt`、`HighCurrentTrafficMode.kt`、`HighHeatSupplementResumePlanner.kt`、`TrafficModeHelper.kt` 到本切片实施前内容；
2. 恢复白名单内五个既有 App 测试到本切片实施前内容；
3. 删除新增 core 主源码、core 测试和 App 接线门禁；
4. 回退 `2026-08-13-target-architecture-decisions.md` 中 1C-4 状态；
5. 删除尚未对外使用的 1C-4 验证记录，或在必须保留审计轨迹时在文件首部标记“已回滚、不可作为当前能力证据”；
6. 保留本计划作为历史方案并记录回滚事实；
7. 重新运行完整 JVM 回归；因回滚触及 App 生产源码，重新构建 Debug APK并记录哈希，仅作为本地构建证据。

回滚不涉及 1A~1C-3、2A~3B-1，不触碰用户其他未提交改动。

## 8. 允许变更文件白名单

1. 新增 `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/SupplementBatchHeatPositionStrategy.kt`；
2. 新增 `scheduler-core/src/test/kotlin/cn/niuyannet/kaochang/scheduler/SupplementBatchHeatPositionStrategyTest.kt`；
3. 修改 `app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt`；
4. 修改 `app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt`；
5. 修改 `app/src/main/java/cn/niuyannet/kaochang/android/services/HighHeatSupplementResumePlanner.kt`；
6. 修改 `app/src/main/java/cn/niuyannet/kaochang/android/services/TrafficModeHelper.kt`；
7. 修改 `app/src/test/java/cn/niuyannet/kaochang/android/services/LowHeatSupplementReservationResultTest.kt`；
8. 修改 `app/src/test/java/cn/niuyannet/kaochang/android/services/LowHeatSupplementResumePlannerTest.kt`；
9. 修改 `app/src/test/java/cn/niuyannet/kaochang/android/services/LowHeatSupplementSafetySourceTest.kt`；
10. 修改 `app/src/test/java/cn/niuyannet/kaochang/android/services/HighHeatSupplementResumePlannerTest.kt`；
11. 修改 `app/src/test/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficModeStateHelperTest.kt`；
12. 新增 `app/src/test/java/cn/niuyannet/kaochang/android/services/SupplementBatchHeatPositionWiringSourceTest.kt`；
13. 本计划 `docs/refactor/2026-08-13-phase-1c4-supplement-batch-heat-position-strategy-plan.md`；
14. 新增 `docs/refactor/2026-08-13-phase-1c4-supplement-batch-heat-position-strategy-verification.md`；
15. 修改 `docs/refactor/2026-08-13-target-architecture-decisions.md`。

禁止修改 Gradle/settings/version catalog、配置持久化、UI、MQTT/HTTP、Modbus/RS485、库存扣减、动作组、下位机协议和版本号。

## 9. 与完整算法 A/B/C 的关系

完成 1C-4 后，未来 profile 可以原子绑定：售卖候选、目标口味配额、批次/热区/位置、口味混配、模式切换等策略 ID。只有全部子能力迁完、完成回放/影子比对并通过空闲门禁，才允许 UI 显示“调度算法 A/B/C”。算法切换必须 pin 完整 profile；运行中不能只换一个子策略。

本切片不实现算法 B/C，也不把低并发/高并发安全门禁、恢复或机械执行塞进算法插件。
