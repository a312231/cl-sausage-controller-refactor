# 第一阶段 1D-1：完整 SchedulerProfile 目录与单一固定 A 计划

日期：2026-08-13
生产源码基线：`D:\LeYe\cl-sausage-controller`，`develop`，`fad00d530bb88c0fb3ace82dcdda038f24626438`
重构仓库：`D:\LeYe\cl-sausage-controller-refactor`，`develop`，同一 HEAD
源码版本：`versionName=2.5.42`、`versionCode=292`

源码版本不代表现场 APK。本计划不授权提交、推送、发布、部署、安装或设备操作。

## 1. 目标与非目标

目标：把已经迁移的五类调度子策略原子绑定成一个不可缺项的完整 `SchedulerProfile A`，并让 App 中所有这些策略的真实入口从同一个进程期固定 pin 取得，消除“各处直接 new 策略”和“只切可售候选却冒充整套算法切换”的结构风险。

完整 A 的稳定 ID：

```text
legacy-scheduler-profile-a-v1
```

本切片不新增算法 B/C，不新增 App 配置字段、UI、MQTT/HTTP 选择、持久化或运行期 setter，也不把可售候选纯策略从影子观察改成机械执行权威。完成后仍不能对用户声称“已经可以切换 A/B”；但以后任何 B 必须作为完整 Profile 注册，不能只替换其中一个子策略。

## 2. 当前代码事实

当前已存在五类策略及 A 组件 ID：

1. 可售候选：`legacy-oldest-holding-v1`；
2. 补货目标分配：`inventory-proportional-target-v1`；
3. 补货口味混配：`relative-deficit-taste-mix-v1`；
4. 批次/热区/位置：`legacy-fixed-batch-heat-position-v1`；
5. 流量模式切换：`legacy-demand-timeout-transition-v1`。

App 主源码仍有 8 个直接构造点：

- `KaoPanHelper`：目标分配；
- `SupplementTasteMixPlanner`：口味混配；
- `LowCurrentTrafficMode`、`HighCurrentTrafficMode`、`HighHeatSupplementResumePlanner`：批次/热区/位置；
- `LowToHighDemandGate`、`HighCurrentTrafficMode`：流量切换；
- `SchedulerShadowRuntime`：可售候选影子策略。

1C-1 的 `SellCandidateStrategyCatalog`、`PinnedSellCandidateStrategy` 和 `StrategySwitchPolicy` 只证明“可售候选策略”能选择和固定，App 没有调用它们。继续以这些类型作为未来算法选择会形成半套切换，因此本切片将其替换为完整 Profile 级目录、pin 和切换门禁。

## 3. 纯核心模型

### 3.1 Profile 身份和描述

新增严格、独立的 `SchedulerProfileId`，格式约束与现有稳定组件 ID 相同，但类型上不能和 `StrategyId` 混用。Profile ID 的不兼容行为变更必须升级 ID，不能原地替换。

`SchedulerProfile` 是非 `data class` 的 final class，主构造器 private、没有 `copy`，必须一次性、非空绑定：

- `SellCandidateStrategy`；
- `SupplementTargetAllocationStrategy`；
- `SupplementTasteMixStrategy`；
- `SupplementBatchHeatPositionStrategy`；
- `TrafficDemandTransitionStrategy`。

它暴露不可变 `SchedulerProfileDescriptor`，包含 profile ID 和五个组件 ID，用于测试、诊断和未来上报；描述只能从实际绑定实例派生，调用方不能分别传入一套虚假的 ID。App 不能直接构造 Profile；`scheduler-core` 只通过受控的 internal、`@JvmSynthetic` 工厂创建。A 工厂内部固定构造五个精确组件；任意组件组装工厂只接受 `test-` 前缀的测试 profile ID，拒绝用 A 或其他正式 ID 构造混搭 Profile。未来真实 B/C 必须新增各自受控工厂和完整组件约束，不能复用测试组装工厂。

`LegacySchedulerProfileA` 只在 `scheduler-core` 内创建上述五个 2.5.42 A 组件，不读取 Android、配置、时钟或硬件。测试可通过仅对 Kotlin friend test 可见的 internal 工厂创建测试 Profile B；该工厂不得进入 App API，并且不能以 A 的保留 ID 搭配任意组件。

### 3.2 完整目录与 pin

`SchedulerProfileCatalog`：

- 以完整 profile ID 注册；
- 拒绝重复 profile ID；
- 精确查找，未知 ID 不回退 A；
- 返回注册 ID 的不可修改副本。

`PinnedSchedulerProfile`：

- 主构造器 private；
- 只能由 catalog 按原始 `String?` ID 严格解析创建；
- 明确区分 `INVALID_ID`、`NOT_REGISTERED` 和 `PINNED`；
- pin 保存 catalog 返回的同一个完整 profile 实例；
- 五个组件 getter 始终来自该固定 profile，不再次读取 catalog 或配置；
- descriptor/profile ID 只能从实际 profile 派生。

### 3.3 Profile 级切换门禁

删除只接受 `SellCandidateStrategyCatalog` 的旧部分切换模型，替换为 `SchedulerProfileSwitchPolicy`：

- 接收 `SchedulerProfileCatalog`、原始 current/requested profile ID 和现有七项一致性快照；
- 保持 1C-1 已审核通过的严格解析、校验优先级、七项 blocker、私有不可伪造结果和未知 ID fail-closed；
- 结果字段命名明确为 current/requested profile ID；
- 测试 Profile B 必须绑定全部五类组件，只用于证明完整目录可扩展，不进入 App 或生产代码。

本切片不在 App 构造真实安全快照、不执行切换、不写持久化。

## 4. App 单一固定 A 接线

新增 `SchedulerProfileRuntime`：

- 内部目录只注册 `LegacySchedulerProfileA`；
- 进程初始化时按 A 的稳定 profile ID 创建唯一 `PinnedSchedulerProfile`；
- 对 App 只暴露只读 `activeProfile`；不得暴露 catalog、可变 current ID、setter、switch 或 fallback API；
- 初始化若 A 无法注册或 pin，直接抛出本地编程错误，不能悄悄构造另一套组件。

8 个 App 直接构造点全部改为从同一个 `SchedulerProfileRuntime.activeProfile` 获取对应组件。接线后：

- 目标分配、口味混配、批次/热区/位置、模式切换仍在原生产调用点执行；
- 可售候选组件仍只供现有默认关闭影子观察，legacy 候选仍是唯一机械执行依据；
- 不改变任何算法输入、输出消费、门禁、日志、持久化、MQTT/HTTP、Modbus/RS485 或机械调用顺序；
- A 组件实现必须保持无可变运行状态，多个 App 调用方共享同一实例不能改变行为。

## 5. TDD 与验收

先新增失败测试，确认缺少 Profile 类型和 Runtime 后再实现。

纯核心至少覆盖：

1. A profile ID 和五个组件 ID 精确匹配；
2. descriptor 从实际实例派生，集合/描述不可修改；
3. profile 不允许任何组件为 null（Kotlin/Java 结构门禁）；
4. catalog 精确查询、重复 ID 拒绝、未知 ID 不回退；
5. pin 私有构造、精确实例恒等、非法/未知 ID fail-closed；
6. 完整测试 Profile B 使用五个 ID、行为和实例都可区分的测试组件；逐项断言五个 getter 与 descriptor 全部来自同一个被 pin 的 B，再覆盖 A/B 双向空闲切换和同 ID no-op，禁止“一项 B、四项 A”的伪完整测试；
7. 七项 blocker、碰撞优先级和不可伪造结果完整保留；
8. 已创建 A pin 不随外部请求 B 改变，五个组件仍来自同一 A；
9. 五个 A 组件实现无非 final 可变实例字段；
10. 五类既有策略测试和固定种子差分全部继续通过。

App 至少覆盖：

1. `SchedulerProfileRuntime.activeProfile` 的 ID/descriptor 是完整 A；
2. App 主源码除 `SchedulerProfileRuntime` 外不再直接构造五个 A 组件；
3. Runtime 只创建一个完整 A 并无 setter/switch/catalog 暴露；
4. 8 个原入口均引用同一 runtime pin 的正确组件；
5. `KaoChangAlgorithm.purchase` 仍把 legacy 候选交给机械，影子只读取 profile 的 sell candidate；
6. 各入口现有安全门禁、状态机、持久化与上报顺序测试继续通过。

## 6. 修改白名单

纯核心允许新增/修改/删除：

- `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/SchedulerProfile.kt`（新增）；
- `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/SchedulerProfileCatalog.kt`（新增）；
- `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/PinnedSchedulerProfile.kt`（新增）；
- `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/SchedulerProfileSwitchPolicy.kt`（新增）；
- `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/SellCandidateStrategyCatalog.kt`（删除）；
- `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/PinnedSellCandidateStrategy.kt`（删除）；
- `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/StrategySwitchPolicy.kt`（删除）；
- `scheduler-core/src/test/kotlin/cn/niuyannet/kaochang/scheduler/SchedulerProfileTest.kt`（新增）；
- `scheduler-core/src/test/kotlin/cn/niuyannet/kaochang/scheduler/SchedulerProfileSwitchPolicyTest.kt`（新增）；
- `scheduler-core/src/test/kotlin/cn/niuyannet/kaochang/scheduler/StrategySwitchPolicyTest.kt`（删除）；
- `scheduler-core/src/test/kotlin/cn/niuyannet/kaochang/scheduler/SellCandidatePolicyTest.kt`（仅删除旧组件 catalog 测试，算法行为测试保留）。

App 允许新增/修改：

- `app/src/main/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerProfileRuntime.kt`（新增）；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerShadowRuntime.kt`；
- `app/src/main/java/cn/niuyannet/kaochang/android/model/KaoPanHelper.kt`；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementTasteMixPlanner.kt`；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/LowCurrentTrafficMode.kt`；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/HighCurrentTrafficMode.kt`；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/HighHeatSupplementResumePlanner.kt`；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/LowToHighDemandGate.kt`；
- `app/src/test/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerProfileRuntimeTest.kt`（新增）；
- `app/src/test/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerProfileWiringSourceTest.kt`（新增）。

文档：

- 本计划；
- `docs/refactor/2026-08-13-phase-1d1-complete-scheduler-profile-verification.md`；
- `docs/refactor/2026-08-13-target-architecture-decisions.md`。

禁止修改 AppConfig、UI、MQTT/HTTP、订单、库存数据结构、动作组、Modbus/RS485、下位机协议、Manifest、资源、Gradle、版本号或生产仓库。

## 7. 验证与证据边界

分层执行：

```powershell
.\gradlew.bat :scheduler-core:test --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "*SchedulerProfile*" --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat test --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :app:assembleDebug --rerun-tasks --no-daemon --console=plain
```

同时验证：

- `scheduler-core/src/main` 禁止依赖扫描为 0；
- `scheduler-core/build.gradle.kts` SHA-256 和依赖不变；
- main class major 全部 55；
- App 主源码不存在五个 A 组件直接构造，只有完整 profile 工厂可以构造；
- `git diff --check` 通过；
- XML 复算测试/失败/错误/跳过，记录 APK 大小和 SHA-256。

JVM/构建只能证明 Profile 完整绑定、接线和本地兼容，不能证明真实 MQTT、RS485/Modbus、温控或机械动作。本切片不开放算法切换，也不能替代后续安全快照、原子持久化、真实 B、影子/回放和现场灰度。

## 8. 回滚边界与后续

实施前先记录白名单内每个文件的存在状态、SHA-256 和当前差异；所有修改只用紧小 hunk。回滚只对本切片新增 hunk 做精确逆向补丁，恢复旧三个部分策略目录/pin/门禁文件和 8 个直接构造点，删除本切片新增完整 Profile 文件/测试；禁止使用 `git restore`、`git checkout --`、reset、stash 或整文件覆盖，不得回滚 1A~1C-5、2A~2C-1、3A~3B-1 或用户其他脏工作树内容。回滚后复核实施前 SHA/差异，确认前序未提交内容逐字保留。

后续独立切片顺序：

1. App 权威所有者下构造一致性安全快照和原子持久化激活；
2. 为新流程建立完整 profile pin 生命周期，运行中不跟随配置变化；
3. 明确算法 B 的业务目标并实现完整 B；
4. 离线回放和默认关闭的完整 Profile 影子比对；
5. 人工本地选择 UI、测试设备灰度；远程选择最后评估。
