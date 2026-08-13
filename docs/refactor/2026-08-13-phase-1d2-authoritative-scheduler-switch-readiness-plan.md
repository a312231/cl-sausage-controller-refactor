# 第一阶段 1D-2：调度 Profile 权威切换就绪证据计划

日期：2026-08-13
生产源码基线：`D:\LeYe\cl-sausage-controller`，`develop`，`fad00d530bb88c0fb3ace82dcdda038f24626438`
重构仓库：`D:\LeYe\cl-sausage-controller-refactor`，`develop`，同一 HEAD
源码版本：`versionName=2.5.42`、`versionCode=292`

源码版本不代表现场 APK。本计划不授权提交、推送、发布、部署、安装或设备操作。

## 1. 目标

在不开放真实算法切换的前提下，由现有机械编排权威所有者构造一份只读、失败即阻断的 `SchedulerProfileSwitchRuntimeSnapshot`，回答“当前是否具备评估 Profile 切换的完整证据”。

本切片只建立安全证据边界：

- 状态读取必须来自现有权威对象、内存 owner 和耐久 journal，不能复制第二套业务状态；
- 任一关键证据损坏或读取异常时返回 `Unavailable`，不得按“不存在”处理；
- `Available` 只表示快照证据可供纯策略评估，不是切换授权；
- 快照读取不写配置、不清理 journal、不恢复 owner、不触发 MQTT/HTTP、不调用 Modbus/RS485、不启动或停止机械流程；
- 不解决“读取空闲后新流程立即启动”的检查—执行竞态，因此本切片禁止新增 `activate`、`switch`、setter 或持久化选择入口。

完成后仍不能宣称“算法 A/B 已可选择”。真实激活必须在后续切片建立与订单、补货、自清洁、维护和机械认领共享的原子边界，或采用经审核的冷启动激活方案。

## 2. 当前代码事实与风险

1D-1 已有纯 JVM `SchedulerProfileSwitchPolicy` 和七项 blocker，但 App 尚未构造真实快照。当前状态分散在：

- `AppConfigBean`：`moveStatus`、`onlineStatus`、`inspectionMode`、`errorStatus`、`isEnable`、流量过渡和故障恢复字段；
- `KaoChangAlgorithm.isEnabled`：独立于配置字段的进程内算法开关；关闭算法后，已经开始的烤制/搬盘/出餐/转保温仍会自然完成；
- `KaoPanHelper` 当前已初始化烤盘缓存：烤制中或有肠但尚未保温可售的批次；现有 `getKaoPanList()` 会惰性加载或创建默认盘，不能用于无副作用探测；
- `OrderStateManager`：内存 `STATE_ACCEPTED_PROCESSING`；
- `SupplementPauseController`：订单优先暂停号；
- `OrderDeliveryClaimStore`：整单出餐 claim，损坏时 `load()` 抛错；
- `SharedPreferenceActionGroupSessionStore`：当前 IN/OUT 动作会话，可区分 Absent/Valid/Corrupted；
- 同类 paused supplement store：暂停续作会话；
- `SharedPreferenceSupplementPrefetchSessionStore`：双 lane 预取会话，可区分 Absent/Valid/Corrupted；
- `SharedPreferencePanMoveJournalStore`、`SharedPreferenceDiscardJournalStore`：存在或损坏时都返回非空 sentinel；
- `SupplementBatchIntent`：补货批次和补热回搬绑定；现有 `loadActive()` 可能迁移或清理，不能用于只读就绪探测；
- `EmergencyResetRecoveryFenceStore`：紧急复位恢复栅栏，损坏时抛错；
- `ResourceLocks`：进程内机械资源 owner；
- `ManualTakeTaskCoordinator`、`ManualOutOrderRecoveryCoordinator`、`SupplementResumeSingleFlight`：未必都有耐久 journal 的进程内进行态。
- `actionGroupRecoveryVerified` 与 guarded supplement closeout residue：当前订单和机械调度已使用的恢复安全门禁。

现有 `isActionGroupIdleForMoveStatusRecovery()` 会检查并尝试恢复订单 claim，具有副作用，不能复用为调度切换快照。现有 `SupplementBatchIntentRepository.loadActive()` 也可能执行迁移或清理，不能复用。

即使一次快照显示空闲，随后订单或机械流程仍可能启动；因此“快照可用”和“允许原子激活”必须严格分开。

## 3. 设计边界

### 3.1 App 结果类型

在 `services/scheduler` 新增 App 侧不可变结果：

```text
SchedulerProfileSwitchReadinessResult
├─ Available(runtimeSnapshot)
├─ Blocked(runtimeSnapshot, diagnostics)
└─ Unavailable(issues)
```

约束：

- `issues` 使用稳定枚举，不把异常对象、Context、store 或可变集合泄漏给调用方；
- 集合必须防御性复制且不可修改；
- 结果构造受控，禁止调用方伪造“证据完整”；
- `Available` 只允许七项 blocker 全部为空；`Blocked` 表示证据完整但观察到至少一个 blocker；两者都携带纯核心已有的七项布尔快照；
- `diagnostics` 只记录证据来源的存在/占用类别，不包含订单号、owner 原文、原始 JSON 或其他敏感业务数据。

### 3.2 权威读取入口

在 `KaoChangOperate` 内新增唯一只读入口，例如：

```text
readSchedulerProfileSwitchReadiness()
```

该入口可在现有 `faultHeatTransitionLock` 的短临界区内读取受该锁保护的字段，但其他 owner 各自使用独立锁，因此整体只能形成一次**非原子、只读、尽力而为的保守采样**，不能称为跨 owner 一致快照。它不调用任何恢复、清理、保存或网络/硬件函数，只组合现有权威对象，不创建新的状态仓库。

`Available` 只说明这次顺序采样中没有读取失败且没有观察到 blocker；`Blocked` 说明证据可读但存在 blocker。二者都不能消除采样过程中的撕裂或返回后的新流程启动竞态。1D-3 必须在所有相关流程共享的启动门禁内重新读取并原子激活，或选择经审核的安全冷启动激活模型。

配置只能通过 `AppConfig` 的无初始化、无迁移、无保存窄 `peek` 读取；缓存尚未初始化时返回带稳定 `CONFIG_UNAVAILABLE` issue 的 `Unavailable`，禁止调用 `getAppConfig()`。

若任一关键读取抛错或返回 Corrupted，整体返回 `Unavailable`。不得通过 `runCatching { ... }.getOrNull()` 把损坏证据降级成 absent。

只允许把预期业务 `Exception` 转成 `Unavailable`；不得 `catch(Throwable)`，不得吞掉 `Error`、线程中断或协程取消信号。`CancellationException` 必须原样传播；`InterruptedException` 必须恢复当前线程 interrupt 标志后原样传播，不能转换成 `Unavailable`。

上述异常语义必须贯穿真实 store，而不只存在于入口外层。action/paused/prefetch evidence codec、整单 claim、搬盘/丢弃 journal 和紧急复位 fence 均使用保留控制流的严格读取：普通解析/校验 `Exception` 按损坏或 `Unavailable` 处理，`Error`/`CancellationException` 原样传播，`InterruptedException` 恢复标志后传播；不得复用会 `catch(Throwable)` 或 `runCatching` 的兼容读取结果。

当前 action-group session 只允许读取一次 `readEvidence()`。同一个 `Valid/Absent` 结果必须同时用于 action-session blocker 和 guarded supplement residue 计算；readiness 禁止调用现有无参数 `hasGuardedSupplementCloseoutResidue(config)`，因为该旧函数会再次调用兼容 `load()`。兼容 `load()` 在损坏时会记录原始 JSON 并返回 null，不符合本切片的 fail-closed 和敏感数据边界。允许新增只接收已读取 session、配置和 lock 快照的纯私有重载/辅助函数，但不得再次访问 store。

### 3.3 七项快照映射

只有所有关键证据均可读时才构造 `Available`：

1. `deviceIdle`
   - `moveStatus == 0`；
   - 配置 `isEnable == 0` 且进程内 `KaoChangAlgorithm.isEnabled == false`；
   - `onlineStatus != 4`，并且 `onlineStatus` 必须属于已知 `0..4`；
   - 当前烤盘缓存已初始化，且没有 `isHasGrilling`，也没有“有肠但尚未按 `KaoPanHelper.isSellable` 进入保温可售”的活动批次；只读探测不得调用会惰性初始化的 `getKaoPanList()`；
   - `modeType` 必须为 1 或 2；
   - `transitionMode == 0`、`highConcurrencyResidualDrain == 0`；
   - `faultHeatPauseActive == 0` 且 `faultHeatRecoveryJournalJson` 为空；
   - `pendingDiscardPositions` 为空；
   - `pendingDisableActionGroupV2 == 0`；
   - 搬盘/丢弃 journal、紧急复位 fence 均不存在；
   - `ResourceLocks` 空闲。
2. `hasActiveOrder`
   - `OrderStateManager` 当前内存 PROCESSING 存在，或 `SupplementPauseController` 仍有订单暂停号；
   - 新增只读 snapshot 查询，不触发现有 3 小时惰性清理。
3. `hasWholeOrderClaim`
   - `OrderDeliveryClaimStore.load()` 返回 claim；损坏或异常直接 `Unavailable`。
4. `hasOutActionSession`
   - 当前有效 action-group session 类型为 `OUT_SELL` 或 `OUT_MANUAL`；
   - `ManualTakeTaskCoordinator` 正在执行或手动 OUT 恢复仍有 reservation 时同样为 true。
5. `hasSupplementOwner`
   - 当前有效 session 类型为 `IN_SUPPLEMENT`；
   - paused supplement、双 lane prefetch 或补货批次/回搬绑定证据存在；
   - `SupplementResumeSingleFlight` 正在运行。
6. `hasCleaningOwner`
   - `moveStatus == 5`。当前自清洁没有独立耐久 owner，不能另造第二套状态。
7. `hasMaintenanceOwner`
   - `inspectionMode == ACTIVE`；
   - `onlineStatus == 3`、`errorStatus != NORMAL`，或动作组已关闭；
   - `actionGroupRecoveryVerified == false`；
   - 存在 guarded supplement closeout residue，包括 OPEN 且 guarded 的下平台隔离 episode、串行补肠失败 proof，或现有 session/lock 组合满足当前 guarded 判定；
   - 这些状态即使 `moveStatus == 0` 也不得被视为可安全激活新 Profile。

`deviceIdle=false` 与其他具体 blocker 可以同时出现；纯策略保留既有稳定顺序。诊断不得为了“只显示一个原因”丢弃其他证据。

### 3.4 只读证据补口

只允许给现有 owner 增加最窄查询：

- `OrderStateManager`：增加不清理、不持久化的 PROCESSING snapshot 查询；
- `ManualOutOrderRecoveryCoordinator`：增加是否存在 reservation 的只读查询；
- `SharedPreferenceSupplementBatchIntentStore`：增加只读 raw evidence presence 查询；任何非空内容都阻断，不调用 repository 的迁移/清理逻辑。
- `KaoPanHelper`：增加只读的当前缓存活动批次探测；缓存尚未初始化时返回“证据不可用”，不得读取 SharedPreferences、创建默认盘位或返回可变烤盘对象。

不修改这些 owner 的现有写入、清理、TTL、恢复或业务判定。

## 4. 明确非目标

- 不新增算法 B/C；
- 不新增 AppConfig profile 字段；
- 不做 profile 选择持久化；
- 不做冷启动或运行时激活；
- 不新增 UI、维护页按钮、MQTT/HTTP 命令或远程配置；
- 不修改订单准入、补货、自清洁、维护、故障恢复或机械认领顺序；
- 不修改 Modbus/RS485、寄存器、动作组协议或下位机；
- 不启动新线程、协程、Timer、Executor、Service 或连接；
- 不把现有 `KaoChangOperate` 全面拆分，不顺手治理无关资源生命周期；
- 不把源码/JVM/构建结果当成真实 MQTT、RS485、温控或机械证据。

## 5. TDD 测试矩阵

先写失败测试，再实现最小生产代码。

### 5.1 结果和映射行为

至少覆盖：

1. 全部安全且证据 absent 时得到 `Available`，七项值精确；
   任一 blocker 存在时得到 `Blocked` 而不是 `Available`；读取/校验失败才得到 `Unavailable`；
2. `moveStatus` 1~5、未知负值/大值均不能得到 `deviceIdle=true`；
3. 配置或进程内算法开关任一开启、`onlineStatus=4`、活动烤制批次、流量过渡、残留消化、故障停热、恢复 journal、待丢弃、pending disable 分别阻断 device idle；
4. PROCESSING 与订单暂停号分别映射 active order；
5. 整单 claim 映射 whole-order claim；
6. `OUT_SELL`、`OUT_MANUAL`、人工任务、恢复 reservation 映射 out session；
7. `IN_SUPPLEMENT`、paused、prefetch、batch intent、resume single-flight 分别映射 supplement owner；
8. 自清洁、维护/故障配置、恢复未验证和 guarded supplement closeout residue 分别映射对应 blocker；
9. 多 blocker 同时存在时全部保留，顺序与纯核心既有策略一致；
10. 任一 action session、paused session、prefetch、claim、batch intent、journal、fence 读取损坏/抛错时只返回 `Unavailable`，不得出现部分 `Available`；
11. 结果集合不可修改，结果类型不能由普通调用方伪造；
12. 同一输入重复读取不写配置、SharedPreferences、journal、网络或硬件端口。
13. 烤盘缓存未初始化时返回 `Unavailable`，且不会触发惰性加载或默认盘创建；已初始化缓存分别覆盖纯保温、烤制中、有肠未可售和待丢弃情形；
14. `onlineStatus`、`inspectionMode`、`useActionGroupV2`、`isEnable`、`modeType`、`transitionMode`、`highConcurrencyResidualDrain`、故障/恢复布尔字段的非法值一律 fail-closed；`useActionGroupV2` 只有精确等于 1 才算启用；
15. 预期业务 `Exception` 转 `Unavailable`，致命 `Error` 与 `CancellationException` 原样传播；`InterruptedException` 恢复线程 interrupt 标志后原样传播，测试结束时显式清理测试线程标志，避免污染其他用例；
16. action-group session 只调用一次 `readEvidence()`；同一 Valid session 同时参与 session 类型和 guarded residue 映射。损坏 session 不调用旧 `load()`、不记录 raw、不降级 absent。
17. 配置缓存未初始化时 `Unavailable`，且不会初始化默认配置、执行迁移或落盘；真实 claim/fence/journal/session/prefetch 读取分别注入普通异常、`Error`、取消和中断，验证端到端异常语义。

### 5.2 权威接线门禁

至少覆盖：

1. 生产入口位于 `KaoChangOperate`，复用其私有 store、locks 和 coordinator；
2. 不调用 `isActionGroupIdleForMoveStatusRecovery()`、`restoreClaimedOrderMoveStatusIfNeeded()` 或 `SupplementBatchIntentRepository.loadActive()`；
3. 不调用无参数 `hasGuardedSupplementCloseoutResidue(config)` 或 `actionGroupSessionStore.load()`；guarded 判定只能消费本次 `readEvidence()` 已取得的 session/lock 快照；
4. 不新增独立 `ResourceLocks`、session store、claim store 或状态缓存；
5. 不出现 save/clear/commit/apply、MQTT/HTTP、Modbus/RS485、机械命令调用；
6. 不向 `SchedulerProfileRuntime` 增加 setter、switch、catalog 或激活 API；
7. App 主源码仍只有固定 `legacy-scheduler-profile-a-v1`，没有真实 B/C；
8. 现有七项纯核心 switch policy 测试继续全部通过；
9. 入口和文档不得出现“全局一致快照”或“Available 可授权切换”的表述；明确它是非原子保守采样。

源码结构测试只作为接线补充，不能替代行为测试。

## 6. 修改白名单

允许新增/修改：

- `app/src/main/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerProfileSwitchReadiness.kt`（新增）；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt`（仅新增只读入口及最小接线）；
- `app/src/main/java/cn/niuyannet/kaochang/android/utils/OrderStateManager.kt`（仅新增无清理 snapshot 查询）；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/ManualOutOrderRecoveryCoordinator.kt`（仅新增 reservation snapshot）；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementBatchIntent.kt`（仅给具体 store 增加 raw evidence presence 查询）；
- `app/src/main/java/cn/niuyannet/kaochang/android/model/KaoPanHelper.kt`（仅增加不惰性初始化、不返回可变对象的活动批次只读探测）；
- `app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfig.kt`（仅增加不初始化、不迁移、不保存的当前缓存 peek）；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/recovery/DurableEvidenceReadResult.kt`（严格保留取消、中断和致命错误）；
- action/paused session 与 prefetch session 的具体 store（仅为严格读取测试注入 raw/decoder/validator，默认运行行为不变）；
- 整单 claim、紧急复位 fence、搬盘/丢弃 journal 的具体 store（仅增加或改用严格只读解析，不改变写入、清理和恢复流程）；
- `app/src/test/java/cn/niuyannet/kaochang/android/services/scheduler/SchedulerProfileSwitchReadinessTest.kt`（新增）；
- `app/src/test/java/cn/niuyannet/kaochang/android/services/SchedulerProfileSwitchReadinessWiringSourceTest.kt`（新增）；
- `app/src/test/java/cn/niuyannet/kaochang/android/model/KaoPanHelperSchedulerSwitchSnapshotTest.kt`（新增）；
- `app/src/test/java/cn/niuyannet/kaochang/android/utils/OrderStateManagerSnapshotTest.kt`（新增）；
- 与上述最窄 owner 查询直接对应的既有单元测试文件；
- `AppConfigReadinessPeekTest` 及上述严格 store/evidence codec 的既有测试；
- 本计划、后续验证记录、目标架构进度记录和 `docs/refactor/rollback/phase-1d2/` 回滚材料。

禁止修改 `scheduler-core` 已批准的 Profile/Policy 语义、AppConfig 字段、UI、MQTT/HTTP、Modbus/RS485、Manifest、资源、Gradle、版本号和生产仓库。若测试证明七项模型无法表达关键状态，必须停止实施、回到计划审核，不能在代码中临时扩 blocker。

## 7. 验证顺序

分层执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*SchedulerProfileSwitchReadiness*" --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :scheduler-core:test :app:testDebugUnitTest --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat test --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :app:assembleDebug --rerun-tasks --no-daemon --console=plain
```

同时验证：

- 新增主源码不包含新的线程、协程 scope、Timer、Executor、Service、MQTT、HTTP、Modbus 或串口依赖；
- 就绪入口不调用 `KaoPanHelper.getKaoPanList()`，烤盘缓存未初始化时 fail-closed；
- `SchedulerProfileRuntime` 仍无可变激活入口；
- App 主源码真实 B/C 数量为 0；
- `git diff --check` 通过；
- XML 分模块复算 tests/failures/errors/skipped；
- APK 仅记录大小和 SHA-256，不宣称现场可用。

## 8. 回滚与证据边界

计划审核前先保存白名单文件的存在状态、tracked 状态和 SHA-256。实施后只生成并逆向本切片紧小 hunk；禁止 `git restore`、`git checkout --`、reset、stash 或整文件覆盖。回滚不得损伤 1A~1D-1、2A~2C-1、3A~3B-1 或用户其他未提交内容。

本切片的证据等级：

- 可验证：源码结构、App/JVM 映射行为、只读接线、本地构建兼容；
- 未验证：测试 MQTT Broker、测试后端、真实 MQTT、RS485/Modbus、温控、机械动作和长时间运行；
- 快照即使 `Available` 也不是切换授权，不能在现场据此直接更换算法。

## 9. 后续切片

1. 1D-3：决定并审核原子激活模型。优先比较“只在安全冷启动激活”与“所有流程共享 profile gate”的复杂度和恢复正确性；
2. 为订单、补货、清洁、维护和恢复流程持久化开始时 pin 的完整 profile ID/版本；
3. 定义并实现真正完整的算法 B；
4. 离线回放、默认关闭影子差分、人工本地选择和测试设备灰度；
5. 远程选择与 MQTT 5 均单独评估，不作为当前前置条件。
