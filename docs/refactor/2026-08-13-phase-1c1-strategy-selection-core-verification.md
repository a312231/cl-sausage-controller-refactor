# 第一阶段 1C-1：调度策略选择、切换门禁与运行期固定验证记录

日期：2026-08-13
源码基线：`fad00d530bb88c0fb3ace82dcdda038f24626438`
源码版本：`versionName=2.5.42`、`versionCode=292`

说明：源码版本不代表现场 APK。本切片未提交、未推送、未发布、未部署、未安装或操作设备。

## 1. 实施范围

新增：

- `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/StrategySwitchPolicy.kt`；
- `scheduler-core/src/main/kotlin/cn/niuyannet/kaochang/scheduler/PinnedSellCandidateStrategy.kt`；
- `scheduler-core/src/test/kotlin/cn/niuyannet/kaochang/scheduler/StrategySwitchPolicyTest.kt`；
- 1C-1 计划、验证记录和目标架构状态说明。

没有修改 App 主源码、配置、UI、订单、库存、补货、自清洁、维护、MQTT/HTTP、Modbus/RS485、动作组或生产算法 A。生产仓库 `D:\LeYe\cl-sausage-controller` 的 tracked 文件没有变化。

## 2. 已实现的纯核心能力

- `StrategySwitchPolicy` 直接接收原始 `currentId/requestedId: String?`，不在门禁外构造 `StrategyId`；
- null、空串、前后空白、大写、过短、超长和非法字符稳定返回 `INVALID`，不 trim、纠正、抛出或回退到 A；
- 合法但未注册的请求/当前 ID 分别返回明确 `NOT_REGISTERED`；
- 判定顺序固定为请求格式、请求注册、当前格式、当前注册、同 ID、runtime busy；
- 真正 A→B/B→A 只有在 `deviceIdle` 且没有 active order、whole-order claim、out session、补货/清洁/维护 owner 时才输出 `SWITCH_ALLOWED`；
- 同一个已注册 ID 返回 `NO_CHANGE`，即使设备忙也不产生持久化请求；
- 多阻断原因按 enum 固定顺序完整返回，并防御复制为不可修改集合；
- `StrategySwitchPlan` 主构造器 private，唯一 `internal + @JvmSynthetic` 入口自身执行原始 ID 解析、catalog 查询与完整决策；不存在接收任意 code/ID/blocker 的通用工厂；
- `PinnedSellCandidateStrategy` 主构造器 private，唯一工厂严格解析原始 ID 并从 catalog 取得精确策略实例；pin 的 ID 只来自该实例；
- 已创建 pin 不再次读取配置或 catalog，外部期望 ID 改变不影响已开始流程；
- 未注册或非法 ID 不能创建 pin，也不能回退到算法 A。

本切片没有实现真实算法 B。测试策略 B 只用于证明目录、切换和 pin 机制可承载不同实现。

## 3. TDD 证据

先新增 `StrategySwitchPolicyTest`，首次执行：

```powershell
.\gradlew.bat :scheduler-core:test --rerun-tasks --no-daemon --console=plain
```

在 `compileTestKotlin` 按预期失败，缺少 `StrategySwitchRuntimeSnapshot`、`StrategySwitchPolicy`、`PinnedSellCandidateStrategy` 等生产类型。加入最小实现后转绿。

定向最终结果：`18 tests, 0 failures, 0 errors, 0 skipped`，其中原 1A 的 8 个测试继续通过，1C-1 新增 10 个测试。既有算法 A 固定种子的 10,000 组差分仍为 0。

覆盖：

- A↔B 空闲切换与同 ID no-op；
- 原始非法 ID、未注册 ID和判定优先级碰撞；
- 七个阻断条件逐项与组合；
- blockers 不可修改；
- plan/pin 私有主构造器和 synthetic 工厂；
- 两个格式合法但未注册的 ID 仍经 catalog 返回明确拒绝，不能通过通用工厂伪造 `SWITCH_ALLOWED`；
- pin ID/实例恒等、A/B 不同输出、外部 ID 变化不影响既有 pin；
- 非法/未注册 pin 不回退。

## 4. 全量验证

在最终源码和测试上执行：

```powershell
.\gradlew.bat test assembleDebug --rerun-tasks --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`，287 个 Gradle task 全部执行。重新统计当前 XML：

- App：3286；
- libnative：2；
- order-core：34；
- recovery-core：18；
- scheduler-core：18；
- 合计：`3358 tests, 0 failures, 0 errors, 0 skipped`。

当前 Debug APK：

- 路径：`app/build/outputs/apk/debug/app-debug.apk`；
- 大小：49,565,314 bytes；
- SHA-256：`5B5BFD6D62488DCBBA0ECC096397FC6E7EED868400D50861076362447D895034`。

APK 只是当前工作树构建证据，没有发布或现场版本含义。构建仍存在历史 UVC 32 位库、Manifest namespace 和 deprecated API 警告，本切片未处理这些遗留项。

## 5. 结构门禁

- `scheduler-core` 当前 27 个 main class，class major 全部为 55（Java 11）；
- Android/AndroidX/App/Paho/Modbus/并发线程/协程/系统时钟禁止引用扫描为 0；
- `StrategySwitchPlan` 与 `PinnedSellCandidateStrategy` 非 synthetic 主构造器字节码均为 `ACC_PRIVATE`；
- App 主源码对 `StrategySwitchPolicy`、`PinnedSellCandidateStrategy`、`StrategySwitchRuntimeSnapshot` 的引用为 0；
- 生产 `SellCandidateStrategy` 实现仍只有 `OldestHoldingFirstStrategy`（算法 A）；
- `KaoChangAlgorithm.purchase` 仍先由 `TrafficModeHelper.selectSellCandidate` 取得 legacy 候选，经 `LegacyCandidateShadowGate.observeAndKeep` 原样返回后交给 `takeSausageResult`；
- `git diff --check` 通过。

## 6. 方案审核与主会话复核

实施前首轮审核为 `Request Changes`：

- P1：如果纯核心只接 `StrategyId`，原始配置的 null/非法格式会在门禁外抛异常，无法稳定 fail-closed；
- P2：缺少判定优先级碰撞测试；pin 构造器和 ID/实例绑定缺少结构门禁。

主会话回到当前 `StrategyId`、catalog 与计划逐项复核，确认 findings 准确，随后把 API 改为接原始 `String?`、补齐 INVALID/NOT_REGISTERED、固定优先级、私有 pin 构造器、实例派生 ID 以及对应测试。修订方案复核结果：`Approve`，P0/P1/P2/P3 均无。

实现阶段主会话又主动发现 `StrategySwitchPlan internal constructor` 在 JVM 字节码中为 public，可能被 Java 调用方伪造允许结果，因此进一步改成 private 主构造器、自校验 synthetic 入口并补充负向测试。

最终 diff 审核为 `Request Changes`：P1 指出通用内部工厂仍可由同模块 Kotlin 用两个未注册 ID 伪造 `SWITCH_ALLOWED`；P2 指出 `runCatching` 会误吞 `VirtualMachineError/ThreadDeath` 等非业务错误。主会话确认两项准确并整改：

- 删除接收任意 code/ID/blocker 的通用工厂，唯一内部入口直接接收 catalog、原始 ID和 runtime snapshot并完成完整决策；
- 严格解析只捕获 `StrategyId` 校验实际产生的 `IllegalArgumentException`，其他 Error 原样传播；
- 增加两个未注册 ID 的精确反例，以及不存在通用决策工厂/不使用 `runCatching` 的结构门禁。

整改后重新执行定向测试和 `test assembleDebug --rerun-tasks`：scheduler-core 18 个测试、全量 3358 个测试均为 0 失败；287 个 Gradle task 全部执行，最终 APK 数据已更新为本记录第 4 节所列值。最终审核结论以下一轮只读复核为准。

最终只读复核结论：`Approve`；P0/P1/P2/P3 均无。主会话再次按当前源码、测试 XML、class 字节码、App 依赖扫描和 APK 哈希独立复核，认同该结论。

## 7. 证据边界与后续切片

已证明：纯 JVM 下可精确选择已注册策略、非法/未知 ID fail-closed、只有完整空闲快照才允许真正切换、已开始流程可固定精确策略实例。

未证明且尚未实现：

- App 的策略配置字段、SharedPreferences 同步持久化和 UI；
- App 在订单/机械过渡锁内构造一致性快照；
- 订单或机械流程真正持有 pin；
- 真实算法 B/C 的业务规则与正确性；
- 1A/1B 计划要求的 72 小时、100 次测试订单影子证据；
- 真实 MQTT 下发、RS485/Modbus 或机械动作。

因此本记录不能作为可以在设备上切换算法、可以上线算法 B或可以操作机械的证据。未来 App 接线必须另立切片、自动化验证、独立审核和现场灰度。
