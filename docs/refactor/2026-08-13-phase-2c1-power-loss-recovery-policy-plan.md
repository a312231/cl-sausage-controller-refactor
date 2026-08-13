# 第二阶段 2C-1：断电恢复分支与运行心跳纯决策提取计划

日期：2026-08-13
源码基线：`fad00d530bb88c0fb3ace82dcdda038f24626438`
源码版本：`versionName=2.5.42`、`versionCode=292`

说明：源码版本不代表现场 APK。本计划不授权提交、推送、发布、部署、安装或操作设备。

## 1. 当前代码事实

`PowerLossRecoveryGuard` 当前同时拥有：

- 进程内只执行一次的启动门禁；
- 故障停热 journal 回放；
- AppConfig/KaoPan 读取与同步持久化；
- 当前墙钟读取；
- 三种冷启动分支判断；
- 烤盘时间归一化与断电间隔调整；
- 60 秒运行心跳节流；
- 本地/云日志、MQTT 状态发布和 HTTP 补偿。

当前冷启动分支顺序是：

1. `lastRuntimeAliveAtMs <= 0`：只把心跳更新为 `nowMs`，不调整烤盘；
2. `faultHeatPauseActive == 1 && faultHeatPauseStartedAtMs > 0`：已有故障停热暂停，避免重复顺延，只更新心跳；
3. 其他情况：先归一化陈旧 baking/holding 状态，再以旧心跳作为 `pauseStartedAtMs` 调用既有断电调整，最后更新心跳。

运行心跳当前规则是：旧值大于 0 且 `nowMs - lastAliveAtMs < 60_000` 时不更新，否则更新。等于 60 秒时更新；墙钟回拨时当前算式通常返回“不更新”。2C-1 只做等价迁移，不顺便修订时钟回拨/Long 溢出语义。

启动调用链仍要求：紧急复位 fence → 断电恢复 → 营业开始纠偏 → 恢复算法状态 → 启动算法。2C-1 不调整此顺序。

## 2. 目标与边界

在既有 `:recovery-core` 新增无 Android/I/O/时钟/线程依赖的 `PowerLossRecoveryPolicy`，只回答：

- 冷启动应该 `INITIALIZE_HEARTBEAT`、`SKIP_ACTIVE_FAULT_PAUSE` 还是 `APPLY_POWER_LOSS_GAP`；
- 当前运行心跳是否到期需要持久化。

App 继续拥有并执行：

- `recoveredInProcess`；
- `FaultHeatPauseManager.replayPendingRecoveryJournalIfNeeded`；
- `AppConfig`、`KaoPanHelper` 和 durable save；
- `System.currentTimeMillis()`；
- `normalizeStaleBakingHoldingTimes` 和 `adjustPansForPause`；
- 食品安全 pending discard 与 alert；
- 日志、MQTT/HTTP 和 UI。

本切片不修改断电恢复结果、不新增自动恢复、不改变食品安全门禁、不触碰订单 inbox、claim/session、Modbus/RS485、下位机协议或机械动作。

## 3. 纯核心 API

### 3.1 冷启动决策

```kotlin
enum class PowerLossColdStartAction {
    INITIALIZE_HEARTBEAT,
    SKIP_ACTIVE_FAULT_PAUSE,
    APPLY_POWER_LOSS_GAP
}

object PowerLossRecoveryPolicy {
    fun coldStartAction(
        lastAliveAtMs: Long,
        faultHeatPauseActive: Int,
        faultHeatPauseStartedAtMs: Long
    ): PowerLossColdStartAction
}
```

顺序必须完全锁定：

1. `lastAliveAtMs <= 0` 始终优先返回 `INITIALIZE_HEARTBEAT`，即使故障暂停字段同时为 active；
2. 有历史心跳且 `faultHeatPauseActive == 1 && faultHeatPauseStartedAtMs > 0` 才返回 `SKIP_ACTIVE_FAULT_PAUSE`；
3. 其余返回 `APPLY_POWER_LOSS_GAP`。

核心不接收 pans/config 对象，不修改传入状态，也不返回“机械已恢复”之类越界结论。

### 3.2 心跳节流

```kotlin
fun shouldMarkAlive(
    lastAliveAtMs: Long,
    nowMs: Long,
    intervalMs: Long = 60_000L
): Boolean
```

- 默认间隔固定为 60 秒；
- `intervalMs` 必须大于 0；App 生产调用不传自定义值，测试参数仅用于边界矩阵；
- 算式保持当前行为：`lastAliveAtMs <= 0 || nowMs - lastAliveAtMs >= intervalMs`；
- 核心只返回 Boolean，不更新时间、不持久化、不读取系统时钟。

## 4. App 等价接线

`PowerLossRecoveryGuard.recoverPansAfterColdStart` 改为先调用 `coldStartAction`：

- `INITIALIZE_HEARTBEAT`：保持原返回值、原心跳更新和无烤盘修改；
- `SKIP_ACTIVE_FAULT_PAUSE`：保持原返回值、原心跳更新和不重复顺延；
- `APPLY_POWER_LOSS_GAP`：保持原 normalize → adjust → 更新心跳顺序与参数。

`markAliveIfDue` 改为调用 `shouldMarkAlive`：false 时不改 config，true 时仍由 App 写 `lastRuntimeAliveAtMs=nowMs`。`markAliveIfDueAndPersist` 和算法循环调用点不变。

不把 `RecoveryResult`、`EndPauseResult`、`KaoPan`、`AppConfigBean`、日志或发布端口搬入 core。

## 5. 文件范围

允许修改：

- `recovery-core/src/main/kotlin/cn/niuyannet/kaochang/recovery/PowerLossRecoveryPolicy.kt`；
- `recovery-core/src/test/kotlin/cn/niuyannet/kaochang/recovery/PowerLossRecoveryPolicyTest.kt`；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/PowerLossRecoveryGuard.kt`；
- `app/src/test/java/cn/niuyannet/kaochang/android/services/PowerLossRecoveryGuardTest.kt`；
- 必要的接线源码门禁测试；
- 本计划、验证记录和目标架构决策文档。

禁止修改：

- `FaultHeatPauseManager` 的 pan/journal/食品安全规则；
- `MainActivity` 启动顺序；
- `KaoChangAlgorithm` 心跳调用位置；
- 配置字段、SharedPreferences key、MQTT/HTTP payload；
- 订单、库存、补货、自清洁、维护恢复、动作组和下位机协议；
- 生产仓库 `D:\LeYe\cl-sausage-controller`。

## 6. TDD 与等价矩阵

先增加失败测试，再加入最小实现。至少覆盖：

### 纯核心

1. 无历史心跳返回初始化；
2. 无历史心跳与 active fault pause 碰撞时仍优先初始化；
3. active=1 且 started>0 返回 skip；active 不是 1、started<=0 均返回 apply；
4. lastAlive 为负、0、正数边界；
5. 心跳旧值<=0 立即到期；
6. 间隔 59,999ms 不到期、60,000ms 到期、60,001ms 到期；
7. 普通且不发生 Long 溢出的 `nowMs < lastAliveAtMs` 保持当前“不更新”行为；
8. 锁定当前 Long 溢出语义：`lastAliveAtMs=1, nowMs=Long.MIN_VALUE` 的减法溢出结果仍判定“到期”，`lastAliveAtMs=Long.MAX_VALUE, nowMs=Long.MIN_VALUE` 的减法溢出结果仍判定“未到期”；两者必须逐项等价于当前 `nowMs - lastAliveAtMs >= intervalMs`；
9. 非法 interval 0/负数明确拒绝；
10. 无 Android/App/MQTT/HTTP/Modbus、I/O、线程和系统时钟依赖。

### App 等价接线

1. 现有 8 个 `PowerLossRecoveryGuardTest` 全部继续通过；
2. 新增分支碰撞测试，证明 App 使用 core 的相同优先级；
3. `INITIALIZE/SKIP` 不调用 normalize/adjust 的现有状态结果不变；
4. `APPLY` 仍保持 normalize → adjust → 更新心跳；
5. `markAliveIfDue` false 不改 config、true 精确写 now；
6. 源码门禁锁定 `replayPendingRecoveryJournalIfNeeded` 仍早于 core 决策，durable save、日志与 publish 仍在 App；
7. `MainActivity` 的紧急复位/断电恢复/业务纠偏/算法启动顺序测试继续通过；
8. `KaoChangAlgorithm` 的心跳仍先于 pan poll，调用频率不变。

接线源码结构门禁必须同时证明：

- `recoverPansAfterColdStart` 的分支唯一由 `PowerLossRecoveryPolicy.coldStartAction` 返回值控制，App 内不再直接比较 `lastAliveAtMs`、`faultHeatPauseActive`、`faultHeatPauseStartedAtMs` 来决定这三个分支；
- `markAliveIfDue` 只能根据 `PowerLossRecoveryPolicy.shouldMarkAlive` 返回值决定是否赋值，App 内不再保留 `60_000` 常量或 `nowMs - lastAliveAtMs` 阈值判断；
- 两个 policy 调用结果不是装饰性调用，必须实际控制对应 `when`/赋值分支，防止 App 与 core 并存第二套判断或旁路核心。

## 7. 自动化验证

```powershell
.\gradlew.bat :recovery-core:test --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --no-daemon --console=plain
.\gradlew.bat test assembleDebug --rerun-tasks --no-daemon --console=plain
git diff --check
```

同时检查：

- recovery-core main class major 全为 55；
- recovery-core 禁止依赖扫描为 0；
- App 只新增到 core 的单向依赖使用，core 不反向依赖 App；
- 当前断电恢复日志、MQTT/HTTP 调用和持久化调用仍在原 App 文件；
- 生产仓库 tracked 文件无变化。

## 8. 独立审核与回滚

实施前由“重构上位机审核会话A”只读审核本计划；实现和验证后再审核实际 diff，主会话逐项复核。

本切片没有新数据格式、配置 key 或协议。回滚只需恢复 App 内两个判断，删除 core policy/test 和对应文档，不影响 1A/1B/1C-1、2A/2B、3A/3B-1。

## 9. 证据边界

本切片可证明：当前冷启动分支和心跳节流规则已成为纯、确定、可测试的核心决策，App 接线在 JVM 测试范围内保持等价。

不能证明：真实断电时长、RK3568 墙钟可靠性、文件系统落盘、真实待丢弃烤盘、温控、MQTT、RS485/Modbus 或机械动作。APK 构建不替代断电/真机验证。
