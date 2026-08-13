# 第一阶段 1C-1：调度策略选择、切换门禁与运行期固定计划

日期：2026-08-13
源码基线：`fad00d530bb88c0fb3ace82dcdda038f24626438`
源码版本：`versionName=2.5.42`、`versionCode=292`

说明：源码版本不代表现场 APK。本计划不授权提交、推送、发布、部署、安装或操作设备。

## 1. 背景与当前事实

用户要求调度算法必须解耦，并可选择“算法 A”“算法 B”等实现。当前工作树已经具备：

- 纯 JVM `:scheduler-core`；
- 稳定 `StrategyId`、统一 `SellCandidateStrategy` 和 `SellCandidateStrategyCatalog`；
- 唯一生产策略 A：`legacy-oldest-holding-v1`；
- App 内默认关闭的影子比对；旧 `TrafficModeHelper` 结果仍是唯一机械执行依据。

当前尚不具备：

- 生产算法 B/C；
- 可持久化的策略选择与运行时 UI/config 切换；
- “只有完全空闲才允许切换”的可测试核心决策；
- 一个已经开始的流程固定使用原策略、不会随配置变化而重新解析的运行期 pin。

3B-2a 是首个订单启动行为切片，既有方案明确要求先确认六项断网/恢复业务口径。本切片不绕过该门槛，先实现不改变 App 行为的纯 JVM 策略选择边界。

## 2. 本切片目标

在 `scheduler-core` 增加最小、确定、无副作用的策略切换决策和运行期固定能力：

1. 请求算法 A/B/C 时必须按稳定 ID 精确解析；
2. 未注册 ID 明确拒绝，禁止静默回退到算法 A；
3. 只有设备完全空闲且没有订单/机械所有权证据时才允许 A→B 或 B→A；
4. 已开始的流程固定持有开始时解析出的策略实例和 ID，后续配置变化不影响该流程；
5. 核心只返回决定，不读写配置、不操作库存、不触发 MQTT/HTTP/Modbus/RS485/UI；
6. 不臆造真实算法 B，只使用测试策略 B 证明扩展和切换机制。

## 3. 目标模型

### 3.1 策略身份

- `StrategyId` 继续作为持久化和运行期身份；当前 ID 已带实现版本后缀，例如 `legacy-oldest-holding-v1`。
- 同一 ID 的行为必须保持兼容；算法规则发生不兼容变化时必须使用新 ID，不能原地替换后仍称同一版本。
- “算法 A/B”是 UI/产品显示名，不作为核心身份。显示名以后由 App 层映射，不能靠本地化文字查策略。

### 3.2 一致性切换快照

新增不可变 `StrategySwitchRuntimeSnapshot`，只表达切换时必须同时观察的事实：

- `deviceIdle`；
- `hasActiveOrder`；
- `hasWholeOrderClaim`；
- `hasOutActionSession`；
- `hasSupplementOwner`；
- `hasCleaningOwner`；
- `hasMaintenanceOwner`。

纯核心不解释 Android 字段。后续 App 接线必须在现有订单/机械过渡锁内一次性构造快照：

- `deviceIdle` 不能只看 UI 或 `onlineStatus`，至少要求 `moveStatus=0` 且没有正在建立/释放的机械过渡；
- `hasActiveOrder` 必须覆盖 legacy processing 与新 inbox accepted/pending/READY/HOLD；
- claim、单根 session、补货、自清洁和维护 owner 必须从各自权威所有者读取；
- 不能在锁外逐项读取后拼成可能跨时点的“空闲”快照。

### 3.3 决策结果

新增纯决策 `StrategySwitchPolicy.plan(...)`。它必须直接接收持久化/配置边界的原始 `currentId: String?` 与 `requestedId: String?`，在核心内严格解析；不得要求调用方先构造 `StrategyId`，也不得 `trim`、改大小写或补默认值后偷偷接受损坏输入。结果只能是：

- `NO_CHANGE`：当前和请求 ID 相同且该 ID 已注册；
- `SWITCH_ALLOWED`：当前/目标均已注册，且所有门禁为空；
- `REQUESTED_STRATEGY_ID_INVALID`：请求 ID 为 `null`、空串或不满足 `StrategyId` 格式；
- `REQUESTED_STRATEGY_NOT_REGISTERED`：请求 ID 未注册；
- `CURRENT_STRATEGY_ID_INVALID`：当前 ID 为 `null`、空串或不满足 `StrategyId` 格式；
- `CURRENT_STRATEGY_NOT_REGISTERED`：当前持久化 ID 已损坏或与当前包不兼容；
- `RUNTIME_BUSY`：返回确定顺序、不可修改的阻断原因集合。

校验顺序固定：请求 ID 格式、请求 ID 注册、当前 ID 格式、当前 ID 注册、同 ID、运行时门禁。未知或非法 ID 不能因“当前等于请求”而被当作合法 no-op；任一损坏均返回稳定拒绝码，不能向调用方抛解析异常或回退到 A。

`StrategySwitchBlocker` 至少包含：

- `DEVICE_NOT_IDLE`；
- `ACTIVE_ORDER`；
- `WHOLE_ORDER_CLAIM`；
- `OUT_ACTION_SESSION`；
- `SUPPLEMENT_OWNER`；
- `CLEANING_OWNER`；
- `MAINTENANCE_OWNER`。

策略核心只返回决定，不修改当前 ID。后续 App 层只有在 `SWITCH_ALLOWED` 时才可同步持久化新 ID；持久化失败必须保持原策略，不能只改内存。

`StrategySwitchPlan` 主构造器必须是 private；唯一内部创建入口使用 `internal + @JvmSynthetic`，入口自身接收 catalog、原始 ID和 runtime snapshot，并完成格式解析、注册查询与完整决策。不得暴露接收任意 code/ID/blocker 的通用工厂。结果构造仍再次校验每种 code 对 ID、阻断原因和 `requiresPersistence` 的组合不变量，防止 Java/Kotlin 调用方伪造 `SWITCH_ALLOWED`。

### 3.4 运行期固定

新增 `PinnedSellCandidateStrategy`，只能通过 catalog 按原始 `String?` ID 创建：

- 主构造器必须是 private，唯一公共工厂负责严格解析原始 ID并通过 catalog 查找实例；
- 工厂明确区分 `INVALID_ID`、`NOT_REGISTERED` 与成功 `PINNED`，解析失败不抛出到调用方；
- pin 的 `StrategyId` 只能读取 catalog 返回实例自身的 `id`，不能让调用方分别传 ID 与实例；
- pin 保存 catalog 返回的同一个精确策略实例和其 `StrategyId`；
- 选择候选时只调用被固定的实例，不再次读取配置或 catalog；
- 未注册 ID 返回明确失败，不回退；
- 已开始订单/流程必须持有自己的 pin，直到流程结束；配置随后从 A 改成 B，只影响以后新建的流程。

本切片只证明纯核心 pin 语义，不把 pin 接入订单或机械路径。

## 4. 文件范围

允许修改：

- `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/StrategySwitchPolicy.kt`；
- `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/PinnedSellCandidateStrategy.kt`；
- `scheduler-core/src/test/kotlin/cn/niuyannet/kaochang/scheduler/StrategySwitchPolicyTest.kt`；
- 本计划、对应验证记录和目标架构决策文档。

禁止修改：

- App 主源码、配置、UI 和影子接线；
- 订单、库存、补货、自清洁、维护和动作组；
- MQTT/HTTP、Modbus/RS485 与下位机协议；
- 生产算法 A 的候选规则；
- 生产仓库 `D:\LeYe\cl-sausage-controller`。

## 5. TDD 与验收矩阵

先新增失败测试，再加入最小实现。至少覆盖：

1. A/B 都注册且快照完全空闲时允许 A→B、B→A；
2. 同一已注册 ID 返回 `NO_CHANGE`，不要求切换；
3. current/requested 的 `null`、空串、大写、过短、超长、非法字符均返回各自明确 `INVALID`，不抛异常、不 trim/纠正、不回退；
4. 未注册请求 ID明确拒绝且目标不变；
5. 当前 ID 未注册时明确拒绝，不借机切到请求算法；
6. 表驱动覆盖判定碰撞：同一个未注册 ID 不得 `NO_CHANGE`；两者均未注册时请求优先；请求未注册与 busy 碰撞时请求优先；当前未注册与 busy 碰撞时当前优先；同一已注册 ID 与 busy 碰撞时为 `NO_CHANGE` 且没有持久化动作；
7. 七个阻断条件分别单独阻断；
8. 多个阻断条件同时存在时全部返回，顺序确定，调用方不能修改集合；
9. 允许结果只包含 from/to ID，不包含配置写入或机械动作；plan 非 synthetic 主构造器必须 private，唯一内部 synthetic 入口必须自行查询 catalog，且不存在可接收任意 code/ID/blocker 的通用工厂；
10. pin 主构造器不可公开，唯一工厂精确绑定 catalog 返回的同一策略实例，pin ID 必须等于实例 ID；
11. A/B 测试策略对同一输入返回不同结果时，pin 调用的是创建时解析出的实例；外部“期望 ID”变量随后改变不影响既有 pin；
12. pin 的非法原始 ID 返回 `INVALID_ID`，合法但未注册 ID 返回 `NOT_REGISTERED`，两者都不能创建 pin或回退到 A；
13. `scheduler-core/src/main` 仍无 Android、App、MQTT、HTTP、Modbus、数据库、线程和系统时钟依赖；
14. 既有算法 A 的 10,000 组确定性差分测试继续为 0 差异。

## 6. 自动化验证

```powershell
.\gradlew.bat :scheduler-core:test --rerun-tasks --no-daemon --console=plain
.\gradlew.bat test assembleDebug --rerun-tasks --no-daemon --console=plain
git diff --check
```

同时检查：

- `scheduler-core` main class 全部为 Java 11 class major 55；
- 禁止依赖/import 扫描为 0；
- `KaoChangAlgorithm.purchase` 仍只把 legacy 候选交给机械动作；
- App 没有策略选择持久化或执行切换入口；
- 当前生产策略仍只有算法 A。

## 7. 独立审核门禁

实现前先由“重构上位机审核会话A”只读审核本计划。实现和自动化通过后再审核 diff；主会话逐条复核审核结论。

任何 P1 必须整改并重新验证，不能因为代码未接运行路径就忽略核心安全缺陷。

## 8. 回滚边界

删除新增纯核心文件、测试和文档即可回滚。本切片不写运行数据、不修改 App 配置、不产生设备侧兼容问题。

未来 App 接线必须是新的独立切片，并满足：

- 策略选择同步持久化与一致性锁；
- 当前流程 pin 的生命周期所有权；
- 1A/1B 既定的 72 小时、100 次测试订单影子证据；
- 匹配 APK 的真实 MQTT、RS485/Modbus 和机械验证；
- 明确的开关回滚路径。

## 9. 证据边界

本切片可证明：纯 JVM 策略选择门禁、未知 ID fail-closed、运行期固定和现有算法 A 回归行为。

本切片不能证明：真实算法 B/C 的业务正确性、App 运行时切换、SharedPreferences 原子性、MQTT 配置下发、RS485/Modbus 或机械动作。APK 构建也不能替代现场验证。
