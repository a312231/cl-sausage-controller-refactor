# 第一阶段 1B：调度影子接入验证记录

日期：2026-08-13
基线：`develop` / `fad00d530bb88c0fb3ace82dcdda038f24626438`
源码版本：`versionName=2.5.42`、`versionCode=292`（不代表现场 APK）

## 实施边界

- App 依赖纯 JVM `:scheduler-core`；
- 新增默认关闭的 `enableSchedulerShadowMode`；只有明确整数 `0/1` 更新，缺失、空值、非法类型和越界值保持当前合法值；
- 新增 App 烤盘到不可变核心输入的映射器、独立旧规则 Oracle、线程安全观察器和影子运行入口；
- 真实旧选择器仍先执行，影子门禁只返回同一个旧候选引用；影子策略结果不会反查烤盘或传入机械入口；
- 影子关闭时不映射、不比较、不记录影子日志；
- 影子日志只调用 `LogUtils.dLocalOnly`，不写文件、不发送 MQTT/HTTP；
- 未修改 Modbus/RS485、动作组、订单协议、库存落盘、UI 和 `out1~out5`；
- 未新增线程、协程、Timer、Handler 或后台轮询。

## 测试先行证据

首次运行定向测试时，`:app:compileDebugUnitTestKotlin` 按预期失败，错误均为尚未实现的字段和类型：

- `enableSchedulerShadowMode`；
- `applyRemoteSchedulerShadowModeConfig`；
- `SchedulerShadowInputMapper`；
- `LegacySellCandidateOracle`；
- `LegacyCandidateShadowGate`；
- `SchedulerShadowObserver` / `SchedulerShadowRunner`。

补入最小生产实现后，同一组定向测试通过。

## 自动化验证

执行命令：

```text
.\gradlew.bat :app:testDebugUnitTest --tests "*AppConfigSchedulerShadowTest" --tests "*SchedulerShadow*Test" --no-daemon
.\gradlew.bat :scheduler-core:test :app:testDebugUnitTest --no-daemon
.\gradlew.bat test assembleDebug --no-daemon
```

结果：全部 `BUILD SUCCESSFUL`。

最终 XML 汇总：

- 测试报告：493；
- tests：3,302；
- failures：0；
- errors：0；
- skipped：0。

新增门禁覆盖：

- 10,000 组有效 Java `KaoPan` 输入，Oracle 与真实 2.5.42 旧选择器候选盘位完全一致；
- Java 平台空值、未知状态、待丢弃集合和烤盘集合防御复制；
- 影子开关默认关闭、合法更新、缺失/空值/非法值保持；
- 开关关闭不执行观察；
- 旧候选存在、为空或影子抛异常时，返回引用与旧候选恒等；
- 映射器、策略及日志 sink 异常不传播；
- 1,000 次并发观察精确计数，`shadow_start` 仅一次，`shadow_sample` 首次及每 25 次一次，逐项明细最多 20 条；
- 源码结构门禁确认影子位于旧选择器后、空候选分支前，机械入口仍接收门禁返回的旧候选变量。

## 构建证据

Debug APK：

- 路径：`app/build/outputs/apk/debug/app-debug.apk`；
- 大小：49,727,128 bytes；
- SHA-256：`1923976105B15814C8F3ED31E7D8702F9C00C2CACBFC8B3259892E22C154D9FF`。

构建仍存在仓库原有的 32 位 UVC/native 警告和 Gradle 弃用提示，本切片未处理。

## 证据边界

- 已证明：源码接线、JVM 行为、并发计数门禁和 Debug 可构建；
- 未证明：真实 MQTT Broker、HTTP 后端、RS485/Modbus、传感器、下位机寄存器和机械动作；
- APK 未安装、未发布；影子开关未在任何设备开启；
- 未提交、未推送；
- 原生产仓库未修改，仍仅保留用户原有未跟踪文件。
