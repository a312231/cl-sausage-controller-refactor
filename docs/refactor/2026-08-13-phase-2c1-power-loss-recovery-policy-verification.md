# 第二阶段 2C-1：断电恢复分支与运行心跳纯决策验证记录

日期：2026-08-13
源码基线：`fad00d530bb88c0fb3ace82dcdda038f24626438`
源码版本：`versionName=2.5.42`、`versionCode=292`

说明：源码版本不是现场 APK 版本。本记录不代表发布、部署或真实设备验证。

## 1. 完成内容

- 在 `:recovery-core` 新增 `PowerLossRecoveryPolicy`：
  - 按既有优先级返回 `INITIALIZE_HEARTBEAT`、`SKIP_ACTIVE_FAULT_PAUSE` 或 `APPLY_POWER_LOSS_GAP`；
  - 纯函数判断运行心跳是否达到既有 60 秒间隔；
  - 保持既有墙钟回拨和 Long 减法溢出语义。
- `PowerLossRecoveryGuard` 只依据 core 返回值分派冷启动分支和心跳赋值，不再保留 App 内第二套谓词或 60 秒阈值。
- App 继续拥有：进程门禁、`System.currentTimeMillis()`、故障停热 journal、烤盘/config 变更、durable save、日志、MQTT/HTTP 和实际发布。
- `MainActivity` 启动顺序和 `KaoChangAlgorithm` 心跳调用位置未修改。
- 未触碰订单 3B、调度 1C-1、Modbus/RS485、下位机协议或机械动作。

## 2. TDD 证据

### 2.1 recovery-core 红灯

先只加入 `PowerLossRecoveryPolicyTest`，执行：

```powershell
.\gradlew.bat :recovery-core:test --rerun-tasks --no-daemon --console=plain
```

结果按预期失败：`PowerLossColdStartAction`、`PowerLossRecoveryPolicy` 均为 `Unresolved reference`。加入最小 core 实现后同一命令通过，3 个 Gradle task 全部执行。

### 2.2 App 接线红灯

先加入 `PowerLossRecoveryCoreWiringSourceTest` 和分支碰撞测试。第一次全量重编译尝试在 124 秒命令时限内未形成测试结论；随后不使用 `--rerun-tasks` 重跑，源码门禁按预期在第 17 行失败，因为 App 尚未调用 core。完成等价接线后定向命令通过。

## 3. 定向验证

```powershell
.\gradlew.bat :recovery-core:test --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.PowerLossRecoveryGuardTest" --tests "cn.niuyannet.kaochang.android.services.PowerLossRecoveryCoreWiringSourceTest" --rerun-tasks --no-daemon --console=plain
```

结果：

- `PowerLossRecoveryPolicyTest`：8 项，0 失败；
- `PowerLossRecoveryGuardTest`：9 项，0 失败；
- `PowerLossRecoveryCoreWiringSourceTest`：3 项，0 失败；
- App 定向命令：87 个 Gradle task 全部执行，`BUILD SUCCESSFUL`。

覆盖的关键边界：

- 无历史心跳优先于 active fault pause；
- active 必须精确为 1 且 started 大于 0 才跳过重复顺延；
- 59,999/60,000/60,001ms 边界；
- 普通时钟回拨；
- 两个既有 Long 溢出结果；
- App 无第二套冷启动谓词、心跳差值或 60 秒常量；
- journal 在恢复调用前，durable save、日志和 publish 仍在 App；
- 算法循环仍先写到期心跳再 poll 烤盘。

## 4. 全量验证

### 4.1 App JVM 回归

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --no-daemon --console=plain
```

结果：87 个 Gradle task 全部执行，`BUILD SUCCESSFUL`。

### 4.2 全工程测试与 Debug 构建

```powershell
.\gradlew.bat test assembleDebug --rerun-tasks --no-daemon --console=plain
```

结果：

- 本次命令控制台输出记录 287 个 Gradle task 全部执行（该数字不能仅由 XML/APK 独立复算）；
- `BUILD SUCCESSFUL`；
- 汇总 499 个 testsuite、3374 项测试；
- failures=0、errors=0、skipped=0。

既有警告仍包括：仅 32 位原生库、旧 Manifest `package` namespace、弃用 API、少量未使用参数和 unchecked 操作。本切片未修改这些历史问题。

## 5. 静态边界与制品

- `git diff --check`：通过；
- `recovery-core` 主代码 `.class`：17 个，major 全为 55；
- `recovery-core/src/main` 禁止依赖扫描：0 命中；
- Debug APK：`D:\LeYe\cl-sausage-controller-refactor\app\build\outputs\apk\debug\app-debug.apk`；
- 最终全量复跑后的 APK 大小：49,565,302 bytes；
- 最终全量复跑后的 APK SHA-256：`DD422D49B394A99AC38F848B1949967D0274450B635598AB3CE1C852E889BD00`。

说明：实施后第一次全量构建的 APK 为 49,565,312 bytes、SHA-256 `7F2F05AABFF05993C856805FF0842EFE3C8C311A41516C3065AEC8031D897DE6`；审核后只强化测试和文档、未修改生产代码，但重新打包的 APK 字节/hash 发生变化。以上最终值只标识最后一次本地 Debug 构建，不据此宣称构建可复现或制品可发布。

生产仓库 `D:\LeYe\cl-sausage-controller` tracked 文件无变化；仅保留此前已有的 3 个 untracked 路径。本切片没有提交、推送、发布、部署、安装或设备操作。

## 6. 审核

- 实施前第一轮：`Request Changes`，指出 App 双实现门禁和 Long 溢出测试缺口；主会话复核后接受并修订计划。
- 实施前第二轮：`Approve`，P0/P1/P2 无；P3 措辞建议已采纳。
- 实施后第一轮：`Request Changes`，指出源码门禁可被换写法绕过、心跳位置测试未限定算法循环、287 tasks 不能由 XML/APK 复算；主会话复核后接受。
- 已强化为：policy 调用次数唯一、冷启动唯一 `return when(action)`、心跳唯一 core 控制分支、算法 `runServer` 的 `while (isRunning)` 块内调用次数和顺序门禁；并明确 287 tasks 来自本次控制台输出。
- 实施后第二轮：`Approve`，P0/P1/P2/P3 均无；审核复算测试、字节码、禁止依赖和最终 APK 指纹一致。主会话复核后接受该结论。

## 7. 证据边界

已证明：当前源码范围内，断电冷启动分支和运行心跳到期判断已成为纯、确定、可单测的 core 决策；App JVM 测试和源码结构门禁证明接线保持既有分支结果、状态修改位置及调用顺序；当前工程可测试并构建 Debug APK。

未证明：真实断电时长、RK3568 墙钟和文件系统落盘可靠性、真实烤盘状态、温控、MQTT、HTTP、RS485/Modbus、下位机响应或机械动作。JVM 测试和 APK 构建不能替代测试机断电/联调验证。
