# 第一阶段 1C-5：并发需求切换纯策略验证记录

日期：2026-08-13
生产源码基线：`D:\LeYe\cl-sausage-controller`，`develop`，`fad00d530bb88c0fb3ace82dcdda038f24626438`
重构仓库：`D:\LeYe\cl-sausage-controller-refactor`，`develop`，同一 HEAD
源码版本：`versionName=2.5.42`、`versionCode=292`

源码版本不代表现场 APK。本切片未提交、未推送、未发布、未部署、未安装或操作设备。

## 实施结果

- `scheduler-core` 新增 `TrafficDemandTransitionStrategy`，算法 A ID 为 `legacy-demand-timeout-transition-v1`；
- 低转高只接收有效空位数和完整活动低热批次布尔值，有效输入域固定为 0..9，域外 fail-closed；
- 高转低只接收关店剩余时间、归一化关店分钟、外部时钟、可空补货锚点和超时分钟；关店优先于超时；
- 2.5.42 的 `(minutes * 60) * 1000L` Int 回绕、`now-last` Long 回绕和严格比较语义保持不变；
- outcome 枚举固定绑定 request/reason，不存在公开构造或 `copy`；两方向 App 适配器拒绝错误方向 outcome；
- `LowToHighDemandGate.canTriggerLowToHigh` 已删除旧 `count>7 && activeBatch` 副本，真实调用委托算法 A；
- `HighCurrentTrafficMode.checkSwitchToLow` 保持关店、无锚点建锚、有效锚点超时的顺序；非正锚点映射为 `null`；
- 旧 `shouldSwitchHighToLowByTimeout` 实现及四项旧 App 单测已删除，等价与极值测试迁入纯核心；
- App 继续拥有调度/库存/稳态门禁、合法过渡状态机、pending intent、配置持久化、runtime_state 上报、恢复、温控和机械动作。

## TDD 证据

先只新增核心测试并运行：

```powershell
.\gradlew.bat :scheduler-core:test --tests "cn.niuyannet.kaochang.scheduler.TrafficDemandTransitionStrategyTest" --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

预期红灯成立：`:scheduler-core:compileTestKotlin FAILED`，未解析 `LegacyDemandTimeoutTransitionStrategy`、`TrafficTransitionOutcome`、输入类型等；失败来自实现尚不存在，不是环境或旧回归失败。

实现后同一命令 `BUILD SUCCESSFUL`；核心新增测试 11 项，0 失败、0 错误、0 跳过。

App 定向回归：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.LowToHighDemandGateTest" --tests "cn.niuyannet.kaochang.android.services.TrafficDemandTransitionWiringSourceTest" --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

结果：`BUILD SUCCESSFUL`；XML 复算 `LowToHighDemandGateTest` 20 项、接线测试 6 项，全部 0 失败、0 错误、0 跳过。

## JVM 回归

切片相关四模块：

```powershell
.\gradlew.bat :scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

结果：`BUILD SUCCESSFUL`，94 个 Gradle 任务。XML 复算：

- `scheduler-core`：50；
- `recovery-core`：26；
- `order-core`：34；
- `app` Debug：1668；
- 合计：1778，失败 0、错误 0、跳过 0。

全仓 JVM：

```powershell
.\gradlew.bat test --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

结果：`BUILD SUCCESSFUL`，200 个 Gradle 任务。XML 复算 7 个有测试结果的任务：

- App Debug 1668、App Release 1668；
- libnative Debug 1、Release 1；
- scheduler 50、recovery 26、order 34；
- 合计 3448，失败 0、错误 0、跳过 0。

`libausbc`、`libuvc`、`libuvccommon` 当前测试任务为 `NO-SOURCE`；Gradle 已执行任务图，不能表述为存在而通过了测试用例。

## 构建与静态门禁

```powershell
.\gradlew.bat :app:assembleDebug --rerun-tasks --no-daemon --console=plain
```

- 结果：`BUILD SUCCESSFUL`，122 个 Gradle 任务；
- APK：`app/build/outputs/apk/debug/app-debug.apk`；
- 大小：49,581,701 bytes；
- SHA-256：`91FDABDD316001FD8CEAD7E7F906760249B9C767403FF004D4111830E2E8EE35`。

静态门禁：

- `scheduler-core/src/main` 未命中 Android/App、I/O、网络、MQTT/HTTP、Modbus/RS485/串口、线程/协程、Executor、Timer/Handler、系统时钟、随机源或 UUID；
- `scheduler-core/build.gradle.kts` SHA-256 保持 `3E439CDD3843C75F9E097607C52ADD37C6BBC68D1453F2D10D1DEB45D048D878`，未新增依赖；
- `scheduler-core` 69 个 main class 的 class major 全部为 55（Java 11）；
- App 主源码与测试均不再存在 `shouldSwitchHighToLowByTimeout`；
- `git diff --check` 通过。

构建中仍有历史 UVC 32 位、Manifest package、弃用 API 和既有未使用参数警告；本切片未修改相关历史模块，也未把这些警告误报为本切片新增故障。

## 审核与证据边界

- 实施前计划经“重构上位机审核会话A”四轮只读审核，最终 `Approve`，P0/P1/P2/P3 均无；主会话逐项核实并修订了有效输入域、锚点顺序、错误方向 outcome、全仓测试口径和算术回绕矛盾；
- 最终实施结果仍需独立只读审核；
- 未进行测试 MQTT Broker/后端联调；
- 未进行真实 RS485/Modbus、温控或机械动作验证；
- JVM、源码静态门禁与 Debug APK 只证明本地规则、接线和构建链路，不证明现场设备行为；
- 仍未实现完整 `SchedulerProfile A/B/C` 目录、UI 选择、空闲原子切换或算法 B/C；1C-5 是第五类真实可替换子策略，不是完整算法切换功能。
