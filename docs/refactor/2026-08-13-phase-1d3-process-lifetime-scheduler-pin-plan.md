# 第一阶段 1D-3：调度 Profile 进程级固定与冷启动边界计划

日期：2026-08-13
生产源码基线：`D:\LeYe\cl-sausage-controller`，`develop`，`fad00d530bb88c0fb3ace82dcdda038f24626438`
重构仓库：`D:\LeYe\cl-sausage-controller-refactor`，`develop`，同一 HEAD
源码版本：`versionName=2.5.42`、`versionCode=292`

源码版本不代表现场 APK。本计划只形成实施边界，不授权提交、推送、发布、部署、安装或设备操作。

## 1. 当前代码事实

1. `MyApp.onCreate()` 在日志和 HTTP 初始化后立即调用 `KaoChangOperate.enforceEmergencyResetRecoveryOnStartup()`，随后可能调用 `DataManagementAPI.dataSyncServer()`。
2. `dataSyncServer()` 先拉库存；库存路径会访问 `KaoPanHelper`。`KaoPanHelper` 在对象初始化时立即读取 `SchedulerProfileRuntime.activeProfile.targetAllocationStrategy`。
3. `SchedulerProfileRuntime` 当前在首次类加载时固定创建完整算法 A。固定时机依赖 Kotlin/JVM 类加载顺序，不是显式启动协议。
4. `KaoPanHelper`、高/低并发、补热恢复、口味混合、流量切换和影子候选等多个对象会在自身初始化时缓存 Profile 内策略。进程启动后只替换 `activeProfile` 无法保证这些缓存一起变化。
5. `MainActivity` 在有效配置后才启动算法轮询，之后连接状态回调才注册 MQTT 订单监听；但 `Application` 阶段已可能执行恢复、云端配置和库存同步。
6. `AppConfigBean` 当前没有正式 Profile 选择字段，只有默认关闭的影子模式字段；App 主源码只有完整算法 A，没有真实 B/C。
7. 1D-2 的就绪采样是非原子只读证据，不是切换授权；冷启动时烤盘缓存未初始化还会返回证据不可用，不能直接拿它当启动激活器。

结论：当前固定算法 A 的行为没有被破坏，但 Profile 固定点不显式。若直接增加 setter、远程字段或 UI 下拉框，会产生“部分对象仍使用旧策略”的混合算法风险。

## 2. 架构决定

采用**进程生命周期内一次性固定完整 Profile**，不采用运行时热切换：

```text
受控切换请求（后续切片）
  -> 阻止新订单/补货/清洁/维护任务进入
  -> 在共享排他边界内复核 owner、journal、烤盘和下位机空闲
  -> 持久化 pendingProfileId
  -> 受控重启 App
  -> Application 最早期读取并校验完整 Profile
  -> 在恢复、库存同步、算法、MQTT 和机械入口前一次性 pin
  -> 启动自检通过后才允许业务运行
```

理由：

- 所有策略消费者天然共享同一个进程级 Profile，不会出现 A/B 子策略混搭；
- 不需要给订单、补货、清洁、维护和十余个机械入口长期增加一把全局热切换锁；
- 进程崩溃恢复只需恢复“本次启动固定的 Profile ID/版本”，状态所有权清晰；
- 代价是切换需要受控重启，不能即时生效。对机械设备而言这是可接受且更安全的取舍。

## 3. 本切片目标

1D-3 只把“隐式固定 A”改为“显式、可证明早于所有 Profile 消费者的进程级 bootstrap”，仍只注册算法 A：

- `SchedulerProfileRuntime` 只有 `UNINITIALIZED -> PINNED` 一次状态转换；
- bootstrap 接收稳定 Profile ID，经 `SchedulerProfileCatalog` 校验后固定完整 Profile；
- 同 ID 重复 bootstrap 可幂等，异 ID重复 bootstrap 必须拒绝；
- 未 bootstrap 就读取策略必须失败并给出稳定诊断，不能静默创建默认 Profile；
- `MyApp` 把 `bootstrapOrThrow(A)` 作为 `super.onCreate()` 后第一个应用自有操作；只有首次固定成功或同 ID 幂等成功才继续，非法、未注册或冲突一律在任何日志、恢复、同步、机械和 Profile 消费入口前终止进程初始化；
- 所有策略消费者继续只读同一完整 Profile，不新增 setter、子策略选择或运行时切换入口。

本切片不会持久化选择，也不会让用户实际选择 B。它只建立之后接入安全选择所必需的唯一启动所有者和时序门禁。

## 4. 明确非目标

- 不新增算法 B/C，不用测试假实现冒充生产算法；
- 不新增 Profile 选择 UI、AppConfig 字段、SharedPreferences 选择 store、MQTT/HTTP 远程命令；
- 不实现切换申请、停机、重启、回滚或自动降级；
- 不修改订单、补货、自清洁、维护、恢复和机械动作业务顺序；
- 不修改 Modbus/RS485、寄存器、下位机协议、MQTT 版本或服务端协议；
- 不新增线程、协程、Timer、Executor、Service 或连接；
- 不把 1D-2 `Available` 当作激活授权；
- 不在当前未提交里程碑获得处理授权前实施本计划的生产代码。

## 5. 建议实现边界

允许修改的生产文件仅为：

- `app/.../MyApp.kt`：增加最早期显式 bootstrap，并保持在恢复/同步调用之前；
- `app/.../services/scheduler/SchedulerProfileRuntime.kt`：一次性固定状态机和只读访问；
- `scheduler-core/.../ProcessLifetimeSchedulerProfilePin.kt`（新增）：可实例化、线程安全的一次固定协调器，复用现有 `PinnedSchedulerProfile`，不复制 catalog/policy；
- `scheduler-core/.../ProcessLifetimeSchedulerProfilePinTest.kt`（新增）：未初始化、并发、同 ID 幂等和异 ID 冲突行为；
- `app/src/test/.../services/scheduler/SchedulerProfileTestBootstrap.kt`（新增、仅测试源集）：只调用生产 bootstrap API 幂等固定 A，不提供 reset、不自动在生产 Runtime 内回退；
- `app/src/test/.../services/scheduler/SchedulerProfileRuntimeTest.kt` 与 `SchedulerProfileWiringSourceTest.kt`：Runtime 行为和启动接线门禁；
- 下列 11 个会直接初始化 Profile 消费单例的现有测试文件，只允许在类初始化/测试前入口增加 `SchedulerProfileTestBootstrap.pinLegacyA()`，不得改变断言或业务数据：
  - `model/KaoPanHelperBakingTimeTest.kt`
  - `model/KaoPanHelperRatioTest.kt`
  - `model/KaoPanHelperSellableTest.kt`
  - `model/KaoPanHelperTasteMetadataTest.kt`
  - `services/HighHeatSupplementResumePlannerTest.kt`
  - `services/LowHeatSupplementReservationResultTest.kt`
  - `services/LowHeatSupplementResumePlannerTest.kt`
  - `services/LowToHighDemandGateTest.kt`
  - `services/SupplementPanStateWriterTest.kt`
  - `services/SupplementSuccessPanStateTest.kt`
  - `services/SupplementTasteMixPlannerTest.kt`
- 本计划、验证记录和紧小回滚材料。

若实现需要修改任一策略消费者、`AppConfig`、`MainActivity`、MQTT、恢复 owner 或 Gradle，说明当前最小模型不成立，必须停止并重新审核，不能临时扩大白名单。

## 6. TDD 与接线门禁

先写失败测试，至少覆盖：

1. 合法 A 可固定，所有访问返回同一个完整 Profile 实例；
2. 空白、非法和未注册 ID 被拒绝；
3. 同 ID 二次 bootstrap 幂等，异 ID二次 bootstrap 拒绝且原 Profile 不变；
4. bootstrap 前读取失败，不能隐式回退 A；
5. 并发 bootstrap 只有一个结果成为进程所有者，不出现混合 Profile；
6. 生产 Runtime 无 setter、switch、activate、公开 catalog 或子策略选择 API；
7. `MyApp` 中 `bootstrapOrThrow(A)` 是 `super.onCreate()` 后第一项应用自有可执行语句；源码门禁验证返回结果不可能被忽略，拒绝结果会在 `KaoChangOperate.enforceEmergencyResetRecoveryOnStartup()`、`DataManagementAPI.dataSyncServer()`、日志、网络和任何 Profile 消费之前终止启动；扫描排除 import、注释和字符串；
8. App 主源码仍只有完整算法 A，B/C 数量为 0；
9. 未新增线程、协程、网络、串口、Modbus、Service 或持久化依赖；
10. 上述 11 个测试显式、幂等固定 A，不依赖测试顺序；其他源码结构测试不得为了方便普遍引入 bootstrap；
11. 既有 Profile 接线与全部核心测试继续通过。

测试应通过 `ProcessLifetimeSchedulerProfilePin` 的独立实例验证并发和重复固定；不得为了测试给生产单例增加 `resetForTest()`。不得保留 Runtime 隐式默认 A，也不得通过 Gradle 测试排序或某个 Runtime 测试先运行来初始化全局状态。

## 7. 分层验证

```powershell
.\gradlew.bat :scheduler-core:test :app:testDebugUnitTest --tests "*SchedulerProfile*" --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat test :app:assembleDebug --no-daemon --max-workers=1 --console=plain
```

同时执行 `git diff --check`、白名单检查、源码时序检查和 XML 测试计数。Debug APK 只记录大小和 SHA-256，不代表现场可用。

## 8. 后续真实选择切片

1. 实现并验证完整算法 B，使用离线回放和默认关闭的影子差分，不接生产选择入口；
2. 设计持久化 `pending/applied Profile ID + version`，状态归本地启动协调器所有，远端只能申请不能直接激活；
3. 建立共享准入关闭与受控停机边界，在其中重新读取 1D-2 证据并增加真实下位机空闲确认；
4. 先提供本地维护页选择，明确“重启后生效”，再在测试设备灰度；
5. 失败时进入维护并保留诊断，不自动在未知现场状态下回退另一个算法；
6. MQTT 5 单独评估，不作为算法切换前置条件。

## 9. 证据边界与当前停点

本计划基于源码静态调用链。1D-2 已有 JVM/构建证据，但没有测试 MQTT Broker、测试后端、真实 RS485/Modbus、温控、机械或长时间运行证据。

当前重构工作树已有多个审核通过但未提交的切片，实际脏文件数量以实施前 manifest 为准。为避免回滚边界继续变大，1D-3 计划审核通过后仍停在实施前，先由用户决定是否授权形成当前里程碑提交；未授权前不新增 1D-3 生产代码。

## 10. 逐文件回滚边界

实施前在 `docs/refactor/rollback/phase-1d3/pre-slice-manifest.md` 记录所有白名单文件的存在状态、tracked 状态和 SHA-256，并记录完整 `git status --short`，但不复制敏感配置内容。

- `MyApp.kt`：只逆向删除 `super.onCreate()` 后的单行 `bootstrapOrThrow(A)` 及对应 import，恢复既有启动顺序；
- `SchedulerProfileRuntime.kt`：只逆向本切片的显式协调器包装 hunk，恢复 1D-2 已批准的固定 A 实现；
- `ProcessLifetimeSchedulerProfilePin.kt` 及其新测试：仅在确认实施前不存在后删除本切片新增文件；
- `SchedulerProfileTestBootstrap.kt`：仅删除本切片新增测试 helper；
- 11 个既有测试：只逆向删除各自增加的 test bootstrap import 和类级/测试前调用，不改任何原断言；
- `SchedulerProfileRuntimeTest.kt`、`SchedulerProfileWiringSourceTest.kt`：只逆向新增测试 hunk；
- 文档和 `phase-1d3` 回滚材料按本切片新增/修改 hunk处理。

禁止使用 `git restore`、`git checkout --`、reset、stash 或整文件覆盖。回滚不得触碰 1A~1D-2、2A~2C-1、3A~3B-1 和用户其他未提交内容。
