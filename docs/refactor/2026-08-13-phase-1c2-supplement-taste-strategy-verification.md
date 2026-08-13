# 第一阶段 1C-2：补货口味混配纯策略验证记录

日期：2026-08-13
源码基线：`fad00d530bb88c0fb3ace82dcdda038f24626438`
源码版本：`versionName=2.5.42`、`versionCode=292`

说明：源码版本不代表现场 APK。本切片未提交、未推送、未发布、未部署、未安装或操作设备。

## 1. 实施边界

本切片只把“目标口味数、现有库存、前烤盘占用和可分配位置已经确定后，按相对缺口选择每个位置的口味”迁移到纯 Kotlin `scheduler-core`。

新增：

- `SupplementTasteMixInput`、`SupplementTasteAssignment`、`SupplementTasteMixPlan`；
- `SupplementTasteMixStrategy` 统一接口；
- 算法 A：`RelativeDeficitTasteMixStrategy`，稳定 ID 为 `relative-deficit-taste-mix-v1`；
- 纯核心场景测试、10,000 组冻结旧算法差分测试和 App 源码接线门禁。

保持不变：

- 低峰目标仍由 `KaoPanHelper.computeKaoPamRatio(listBox, 9)` 产生；
- 高峰仍经 `HighCurrentTrafficMode -> HighHeatSupplementResumePlanner`，目标仍由 `computeHighKaoPamRatio(listBox, 21)` 产生；
- 批次数量、热源、前盘返回位、可分配位置、库存读取、动作编排和提交结果过滤仍归 App；
- MQTT/HTTP、Modbus/RS485、下位机协议、订单、UI 和机械动作没有改变。

这里建立的是完整调度器的一个真实策略子能力，不把它伪装成整机“算法 A/B”开关。本切片没有虚构算法 B，也没有新增配置或 UI。

## 2. TDD 与行为等价证据

首次执行 `:scheduler-core:test` 在 `compileTestKotlin` 失败，缺少新策略及输入输出类型，符合测试先行红灯预期。实现后：

- `scheduler-core`：25 tests，0 failures，0 errors，0 skipped；
- 固定种子 10,000 组输入逐项比较目标、迁移前占用统计、位置分配和 `stockLimited`，差异为 0；
- 输入和输出集合均为防御复制、不可修改快照；
- 固定复刻空白占位仍消耗容量、位置去重但不排除与占用位重叠、迁移前占用统计、容量不足也会使旧语义 `stockLimited=true`、整数交叉相乘比较等边界。

测试过程中曾发现一条新测试错误地把目标数理解成硬上限；冻结旧算法证明确实仍会在有库存和位置时继续分配。该测试期望已纠正，生产算法未为迎合错误测试而改变。

App 定向测试：原有 `SupplementTasteMixPlannerTest` 8 项和新增源码门禁 3 项全部通过。`SupplementTasteMixPlanner.plan` 目前只构造纯核心输入、调用一次策略并映射输出；旧的排序和缺口算法已从 App 适配器删除。

## 3. 完整自动化与构建

最终执行：

```powershell
.\gradlew.bat :scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --rerun-tasks --no-daemon --console=plain
```

结果：

- scheduler-core：25；
- recovery-core：26；
- order-core：34；
- App：1650；
- 合计：`1735 tests, 0 failures, 0 errors, 0 skipped`；
- 本次 JVM 回归控制台显示 `BUILD SUCCESSFUL`，94 个 task 全部执行；
- 本次 Debug APK 构建控制台显示 `BUILD SUCCESSFUL`，122 个 task 全部执行。

测试数量可由当前 XML 复算，APK 大小和哈希可由当前制品复算；94/122 task 数只保留在本次控制台输出中，不能仅靠 XML/APK 独立复算。

当前 Debug APK：

- 路径：`app/build/outputs/apk/debug/app-debug.apk`；
- 大小：49,565,316 bytes；
- SHA-256：`045D9F6700C7F2C2ADDE9C7CB5674F051DAD7142C121F3CEFE254E190AC53D50`。

APK 只是当前工作树的构建证据，没有发布或现场版本含义。构建仍有仓库原有的 32 位 UVC native、Manifest namespace 和 deprecated API 警告，本切片未处理。

## 4. 静态边界

- `scheduler-core/src/main` 和测试对 Android/App/MQTT/Modbus/RS485/串口关键字扫描为 0；
- 低峰仍直接调用 App 适配器，高峰仍通过既有恢复规划器间接调用；两条调用链都没有直接实例化核心策略；
- `committedReturnTasteByPosition` 仍在 App 过滤 fault-timeout cancelled 和 supply-degraded 目标；
- AppConfigBean 和 MaintenanceFragment 未暴露仅代表口味混配的伪整机策略开关；
- `git diff --check` 通过；
- 生产仓库 `D:\LeYe\cl-sausage-controller` tracked 文件仍无变化，只有执行前已存在的三个 untracked 路径。

## 5. 证据边界

已证明：当前源码的口味混配算法已能通过稳定策略接口独立替换，算法 A 与迁移前实现对自动化输入等价，App 调用链和下位机协议未改变。

未证明：真实算法 B 的规则和收益；整机所有调度子能力可作为一个 profile 原子切换；真实 MQTT、HTTP、RS485/Modbus、库存现场数据、长时间运行或机械动作效果。JVM 测试和 APK 构建不能代替这些现场证据。

只有后续把售卖候选、目标比例、批次数量/热源/位置、口味混配和高低峰切换都纳入同一版本化 `SchedulerProfile`，并完成空闲门禁、运行期 pin、影子比较和现场灰度后，UI 才应显示真正的“调度算法 A/B”。

## 6. 独立审核与主会话复核

“重构上位机审核会话A”对当前源码、迁移前 HEAD、测试 XML、APK、模块依赖和生产仓库状态做了严格只读复核，最终结论为 `Approve`：P0/P1/P2 均无。

审核提出一个非阻断 P3：94/122 Gradle task 数来自本次控制台，不能由 XML/APK 事后独立复算。主会话回查本次两条 Gradle 命令的最终输出，确认 task 数和 `BUILD SUCCESSFUL` 记录真实；同时接受其“持久证据可复算性较弱”的判断，并已在第 3 节明确区分控制台证据与 XML/APK 可复算证据。

主会话还逐项复核了算法公式、旧语义边界、冻结 oracle 独立性、防御复制、App 适配器、低峰/高峰调用链和伪 A/B 开关门禁，认同审核结论。本审核仍不构成真实 MQTT、RS485/Modbus、下位机或机械补货验证。
