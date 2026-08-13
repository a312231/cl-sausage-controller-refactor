# 第一阶段 1C-5：并发需求切换纯策略计划

日期：2026-08-13
生产源码基线：`D:\LeYe\cl-sausage-controller`，`develop`，`fad00d530bb88c0fb3ace82dcdda038f24626438`
重构仓库：`D:\LeYe\cl-sausage-controller-refactor`，`develop`，同一 HEAD
源码版本：`versionName=2.5.42`、`versionCode=292`

说明：源码版本不代表现场 APK。本计划只授权重构仓库内 1C-5 切片，不授权提交、推送、发布、部署、安装或设备操作。

## 1. 目标

将“何时请求低并发转高并发、何时请求高并发转低并发”抽成纯 Kotlin/JVM 可替换策略，使未来完整 `SchedulerProfile A/B/C` 可以绑定不同的需求切换算法，同时保持 2.5.42 的机械、恢复、过渡完成和协议行为。

算法 A 稳定 ID 计划为：

```text
legacy-demand-timeout-transition-v1
```

本切片只形成第五个真实可替换调度子策略，不新增算法 B/C，不新增 UI、远端配置或持久化选择入口，也不声称整机已经可以选择完整算法 A/B/C。

## 2. 当前源码事实与调用链

### 2.1 低转高请求

当前入口：

```text
KaoChangAlgorithm.pollKaoPanStatus()（Dispatchers.IO，约 10 秒一轮）
  -> LowCurrentTrafficMode.lowTrafficMode()
  -> LowToHighDemandGate.effectiveEmptyFrontPositions()
       持久化 blocked 空位裁剪/观察到重新有肠时清除
  -> LowToHighDemandGate.canTriggerLowToHigh()
       effective empty > 7
       且 13~18 六个位置全部存在有效在烤或等待回搬批次
  -> LowCurrentTrafficMode.switchLowToHigh()
  -> resolveAutomaticTrafficTransition(current=低稳态, request=低转高)
  -> 写 modeType=2、transitionMode=1
  -> AppConfig.saveAppConfig()
  -> runtime_state 上报
```

调用方当前还要求：`schedulingEnabled=true`、烤肠箱存在自动补货库存、`modeType=1`、`transitionMode=0`。这些是 App 侧安全/运行门禁，不进入策略。

### 2.2 高转低请求

当前入口：

```text
KaoChangAlgorithm.pollKaoPanStatus()
  -> HighCurrentTrafficMode.highTrafficMode()
  -> 高并发稳态完成补货/成熟/丢弃处理
  -> checkSwitchToLow()
       优先：0 < 距离关店时间 < timeBeforeClose（非正值回退 30 分钟）
       否则：若无 lastFillPitTime，App 建立当前时钟锚点并等待
       否则：now-lastFillPitTime > timeThresholdLow 分钟
  -> switchHighToLow()
  -> resolveAutomaticTrafficTransition(current=高稳态, request=高转低)
  -> 写 modeType=2、transitionMode=2
  -> 清理未完成高热补货意图、保存配置、上报 runtime_state
```

关店优先于超时；高转低超时不依赖当前箱库存，箱库存只影响原因文本。等于阈值时不触发，严格大于才触发。

### 2.3 不属于可替换需求算法的状态机

以下逻辑继续留在 App，不能由算法插件直接改变：

- `resolveAutomaticTrafficTransition` 对合法单向迁移的约束；
- 低转高旧 13~18 批次回搬、25~33 首批启动/回搬和完成条件；
- `resolveLowToHighTransitionIdleState`、`resolveLowToHighTransitionCompletionState`、`resolveStaleLowToHighTransitionState`；
- 高转低/残留收拢、`canFinishHighToLowDrain`、未熟肠与恢复证据检查；
- 过保丢弃 `markExpiredDiscardAndFoldToLow` 的 fail-safe 收口；
- `highConcurrencyResidualDrain` 判定和清残；
- 机械 owner、订单、动作组、pending intent/checkpoint、故障、检修、营业状态和温控门禁；
- `AppConfig` 持久化、MQTT/HTTP 上报、日志和 UI。

## 3. 纯策略边界

计划在 `scheduler-core` 新增：

```kotlin
enum class TrafficTransitionRequest { NONE, LOW_TO_HIGH, HIGH_TO_LOW }

enum class TrafficTransitionReason {
    NONE,
    EFFECTIVE_LOW_FRONT_DEMAND,
    BEFORE_BUSINESS_CLOSE,
    HIGH_MODE_INACTIVITY_TIMEOUT
}

enum class TrafficTransitionOutcome {
    NONE,
    LOW_TO_HIGH_EFFECTIVE_DEMAND,
    HIGH_TO_LOW_BEFORE_CLOSE,
    HIGH_TO_LOW_INACTIVITY_TIMEOUT
}

data class LowToHighDemandInput(
    val effectiveEmptyFrontCount: Int,
    val hasCompleteActiveLowHeatBatch: Boolean
)

data class HighToLowDemandInput(
    val remainingUntilCloseMs: Long,
    val beforeCloseMinutes: Int,
    val nowMs: Long,
    val lastHighSupplementTimeMs: Long?,
    val inactivityThresholdMinutes: Int
)

interface TrafficDemandTransitionStrategy {
    val id: StrategyId
    fun planLowToHigh(input: LowToHighDemandInput): TrafficTransitionOutcome
    fun planHighToLow(input: HighToLowDemandInput): TrafficTransitionOutcome
}
```

`TrafficTransitionOutcome` 是唯一可返回结果；其枚举值天然绑定 request/reason。可提供只读派生属性把 outcome 映射到 `TrafficTransitionRequest` 和 `TrafficTransitionReason`，但不提供公开 `data class` 构造器或 `copy`，调用方无法伪造 `NONE + BEFORE_BUSINESS_CLOSE` 等矛盾组合。

算法 A 规则：

- 真实低转高入口只传入 1~9 前区，因此有效域是 `effectiveEmptyFrontCount in 0..9`；仅 `count in 8..9 && hasCompleteActiveLowHeatBatch` 返回 `LOW_TO_HIGH_EFFECTIVE_DEMAND`；`count<0` 或 `count>9` 一律 `NONE`；`trackedFrontPositions=1..21` 只服务持久化 blocked 状态跟踪，不能扩大本策略输入域；
- 高转低优先关店窗口：`remainingUntilCloseMs > 0 && remainingUntilCloseMs < beforeCloseThresholdMs`；
- 关店不成立时，只有 `lastHighSupplementTimeMs != null`、`inactivityThresholdMinutes > 0` 且原始 Long 运算 `nowMs-lastHighSupplementTimeMs > threshold` 才因超时请求高转低；
- 等于阈值不触发；不得新增独立的 `elapsed<0` 拒绝门禁，最终结果唯一由保留 JVM 回绕语义的原始 `elapsed>thresholdMs` 决定：正常正阈值下负 elapsed 不触发，但若极端分钟配置先把阈值回绕成负数，则必须保持 2.5.42 的原比较结果；无时钟锚点不触发；
- outcome 到 request/reason 的映射固定：`NONE -> NONE/NONE`、`LOW_TO_HIGH_EFFECTIVE_DEMAND -> LOW_TO_HIGH/EFFECTIVE_LOW_FRONT_DEMAND`、两个高转低 outcome 分别绑定关店或无补货超时原因。

纯核心不接收或引用 `KaoPan`、`AppConfigBean`、Context、数据库、文件、MQTT、HTTP、Modbus/RS485、协程、线程、Handler、Timer、系统时钟或随机源。

### 3.1 时间与算术口径

- App 负责采样当前时间和计算 `remainingUntilCloseMs`；把采样的 `nowMs` 与有效锚点原值交给核心，核心不读系统时钟。
- App 必须用 `lastFillPitTime.takeIf { it > 0L }` 映射策略输入；`0`、负值和 `Long.MIN_VALUE` 都必须作为 `null` 无锚点处理，核心不得把非正原值解释为有效锚点。
- `timeBeforeClose<=0` 的 30 分钟回退保持在 App，传给核心的是已经按 2.5.42 归一化的分钟数。
- 本切片明确逐运算符冻结 2.5.42 原始算术，不做饱和、`toLong()` 前移或 fail-closed 修正：关店阈值使用 `(beforeCloseMinutes * 60) * 1000L`；超时阈值使用 `(inactivityThresholdMinutes * 60) * 1000L`；elapsed 使用原始 `nowMs - lastHighSupplementTimeMs`。其中前一段 `Int * Int` 和 Long 减法均保留 JVM/Kotlin 回绕语义。
- 比较口径保持原样：关店 `remaining>0 && remaining<threshold`，超时严格 `elapsed>threshold`；非正超时分钟先返回 `NONE`。
- 配置范围归一化、饱和算术或极端时间回拨 fail-closed 属于独立安全变更，未经另行计划和授权不得混入 1C-5。

## 4. App 接线

### 4.1 低转高

`LowToHighDemandGate` 继续：

- 管理 durable blocked 空位；
- 从真实 `KaoPan` 派生有效空位数；
- 识别 13~18 是否为完整活动低热批次；
- 观察到重新有肠时清除 blocked 状态。

它将上述两个纯值交给算法 A，并只把 `outcome==LOW_TO_HIGH_EFFECTIVE_DEMAND` 转为现有布尔结果。`canTriggerLowToHigh` 只能委托核心策略，不得保留 `count>7 && activeBatch` 的第二份判断；即使测试替身错误返回任一高转低 outcome，也必须 fail-closed 为 `false` 且不产生副作用。`LowCurrentTrafficMode` 原来的 scheduling、库存、当前模式/过渡态门禁、日志和 `switchLowToHigh` 不变。

### 4.2 高转低

`HighCurrentTrafficMode.checkSwitchToLow`：

- 仍由 App 读取业务时间、配置和 `System.currentTimeMillis()`；
- 必须按 `lastFillPitTime.takeIf { it > 0L }` 把有效锚点或 `null` 交给策略；
- 用一次纯策略调用得到不可伪造的 outcome；
- 必须先处理 `HIGH_TO_LOW_BEFORE_CLOSE`：立即沿用原原因文本切换并返回；关店 outcome 成立且原锚点非正时不得建立锚点；
- 只有 outcome 为 `NONE` 且原 `lastFillPitTime<=0L` 时，App 才用本轮采样时间建立并持久化/跟踪锚点，然后立即返回；
- 只有原锚点有效时才允许消费 `HIGH_TO_LOW_INACTIVITY_TIMEOUT`，并保留原诊断日志和按箱库存区分的原因文本；
- 任一低转高 outcome 或其他不属于高转低方向的 outcome 都必须无副作用 fail-closed 忽略；
- 最终仍只能调用现有 `switchHighToLow`，由 App 状态机拒绝非法迁移。

不得把配置写入、pending intent 清理、动作组、温控或上报移入核心。

## 5. 测试与差分

先测试后实现：新增核心测试引用尚不存在的类型，确认 `compileTestKotlin FAILED` 后再实现。

纯核心测试至少覆盖：

1. 稳定策略 ID；
2. 低转高 `effectiveEmpty=0..9` 与完整批次 true/false 的全组合，和独立表达式 `empty>7 && active` 差分；
3. 低转高有效域边界 `0、7、8、9`，以及 `-1、10、21、22、Int.MIN_VALUE、Int.MAX_VALUE` 精确 fail-closed；
4. 高转低关店窗口边界：不可计算/负数/0、阈值前 1ms、恰好阈值、阈值后；
5. 无 last-fill 锚点、正常正阈值下的负 elapsed、0、阈值前 1ms、恰好阈值、阈值后；App 映射测试覆盖原锚点 `0、-1、Long.MIN_VALUE` 均作为无锚点；
6. 关店与超时同时成立时关店优先；
7. 非正 `inactivityThresholdMinutes` 不触发；
8. outcome 枚举到 request/reason 的全部映射，并用 Kotlin/Java 结构检查证明不存在公开矛盾结果构造或 `copy`；
9. 关店/超时分钟取 `Int.MAX_VALUE` 等产生阈值回绕的精确 oracle；其中超时阈值为 `-60_000ms` 时，组合验证 `elapsed=-1` 触发、`elapsed=-60_000` 不触发、`elapsed=-60_001` 不触发；`nowMs-lastHighSupplementTimeMs` 取 Long 边界产生差值回绕的精确 oracle；
10. 固定种子大样本与逐运算符复制的独立 2.5.42 oracle 差分，随机源只允许出现在本新增核心测试，种子固定。
11. 两个方向的测试替身分别返回另一方向 outcome 时，App 适配器必须无状态写入、无切换、无上报地 fail-closed；关店成立且原锚点非正时必须直接切换且不得写入新锚点。

App 回归至少覆盖：

- `LowToHighDemandGateTest` 的 blocked 空位、完整 13~18 批次与过保丢弃语义全部保留；
- 删除 App 旧 `shouldSwitchHighToLowByTimeout` 实现及其对应旧单测，等价边界全部迁到核心测试；不得保留兼容副本或第二套超时判断；
- 新增源码接线门禁：低转高只在原四个 App 门禁之后消费策略；高转低仍是关店优先、无锚点先建锚、最终走 `resolveAutomaticTrafficTransition`；
- `switchLowToHigh`、`switchHighToLow` 的配置保存、runtime_state 上报和 pending intent 清理顺序不变；
- `KaoChangAlgorithm` 的 `transitionMode != 0` 优先路由、低并发残留收口和 10 秒 IO 协程轮询不改。

切片相关 JVM 回归：

```powershell
.\gradlew.bat :scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

全仓 JVM 回归和 Debug 构建：

```powershell
.\gradlew.bat test --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :app:assembleDebug --rerun-tasks --no-daemon --console=plain
```

根 `test` 必须覆盖 `settings.gradle.kts` 当前包含的全部模块（包括四个视觉/UVC 历史模块所暴露的 JVM test 任务）；逐模块从 XML 复算测试/失败/错误/跳过，并把切片相关回归与全仓 JVM 回归分开记录。重新记录最后一次 APK 大小和 SHA-256。APK 构建和 JVM 测试不代表真实 MQTT、RS485/Modbus 或机械验证。

## 6. 修改白名单

计划只允许修改/新增以下精确路径：

- `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/TrafficDemandTransitionStrategy.kt`；
- `scheduler-core/src/test/kotlin/cn/niuyannet/kaochang/scheduler/TrafficDemandTransitionStrategyTest.kt`；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/LowToHighDemandGate.kt`；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt`；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficModeStateHelper.kt`，仅删除旧超时实现；
- `app/src/test/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficModeStateHelperTest.kt`，仅删除已迁移的旧超时测试；
- `app/src/test/java/cn/niuyannet/kaochang/android/services/LowToHighDemandGateTest.kt`；
- `app/src/test/java/cn/niuyannet/kaochang/android/services/TrafficDemandTransitionWiringSourceTest.kt`；
- `docs/refactor/2026-08-13-phase-1c5-traffic-demand-transition-strategy-plan.md`；
- `docs/refactor/2026-08-13-phase-1c5-traffic-demand-transition-strategy-verification.md`；
- `docs/refactor/2026-08-13-target-architecture-decisions.md`。

不修改 `KaoChangOperate`、动作组、Modbus/RS485、MQTT/HTTP 协议、Manifest、资源、下位机寄存器、订单、库存扣减、自清洁、UI 或版本号。

## 7. 验收与回滚边界

验收：

- 算法 A 纯策略在生产路径实际被调用，不是假接口；
- 2.5.42 合法输入范围的低转高/高转低触发结果和原因优先级一致；
- App 保持所有安全门禁、合法状态迁移、状态写入与上报所有权；
- 核心无 Android/硬件/网络/时钟/并发依赖；
- `scheduler-core/src/main` 扫描不得命中 Android/App 类型、文件/数据库/I/O、MQTT/HTTP/网络、Modbus/RS485/串口、线程/协程、Executor、Timer/Handler、系统时钟、随机源或 UUID；
- `scheduler-core/build.gradle.kts` 内容和 SHA-256 不变，无新增运行时依赖；编译产物 main class major 全部为 55（Java 11）；
- 新增核心测试以外不得新增随机源；新增测试随机必须使用固定常量种子；
- 完整 JVM、Debug 构建、静态扫描通过；
- “重构上位机审核会话A”最终 `Approve`，主会话逐项复核认可。

回滚只删除新纯策略与测试，并把两处调用恢复到原 2.5.42 表达式/兼容 helper；不得回滚或覆盖 1A~1C-4、2A~2C-1、3A~3B-1 或用户其他未提交改动。

## 8. 后续关系

完成 1C-5 后，`SchedulerProfile` 可以绑定第五类策略 ID：需求模式切换。仍需后续建立完整 profile catalog、各子策略一致版本组合、运行期完整 profile pin、空闲态原子切换、影子比对和选择 UI。机械安全状态机不作为可插拔算法的一部分。
