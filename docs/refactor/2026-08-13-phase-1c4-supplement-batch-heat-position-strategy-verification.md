# 第一阶段 1C-4：补货批次、热区与位置纯策略验证记录

日期：2026-08-13
源码基线：`fad00d530bb88c0fb3ace82dcdda038f24626438`
源码版本：`versionName=2.5.42`、`versionCode=292`

说明：源码版本不代表现场 APK。本切片未提交、未推送、未发布、未部署、未安装或操作设备。

## 1. 实施结果

新增纯 JVM `SupplementBatchHeatPositionStrategy`，算法 A 稳定 ID：

```text
legacy-fixed-batch-heat-position-v1
```

它只负责：

- 低并发 `NONE / START_13_15 / START_16_18 / STOP_ZONE_2` 及固定请求数；
- 高并发稳态 `front>=9 && heat==9` 的新批次决策及请求数 9；
- 对 App 已安全过滤、已排序的位置号按请求上限有序配对。

App 继续拥有并实际执行：pending/manual-confirm、恢复可信度、batch intent/checkpoint、订单/owner、营业时间、错误状态、库存/IR/箱位过滤、实时烤盘状态、位置物理范围与连续性校验、温控、机械动作、持久化、MQTT/HTTP 和 Modbus/RS485。

低并发保留原执行顺序：pending 可先返回；START 继续受原门禁控制；未提前返回时 STOP 不套用 START 门禁，仍先写 `TEMPERATURE_OFF=1` 再下发一次 `stopHeating(2)`。

高并发新批次策略只在旧分支原位置消费，保留 `pending/恢复 -> 残缺/已有批次 -> 全空初始化 -> 过保空位高转低 -> 稳态新批次` 的优先级。

高热调用方先用 `TrafficModeHelper.planMissingTasteReservations` 计算不修改烤盘的基线预填结果，每条延迟写入保存预期烤盘对象、位置和目标口味；`HighHeatSupplementResumePlanner` 对位置、对象映射、数量、连续性、实时占用、原空白口味和口味计划全量校验通过后，才按“预填原空盘 -> planner 覆盖选中回搬位/目标位”的基线顺序统一写 `KaoPan.taste`。对象替换、位置/占用/口味漂移或非法目标均返回空，相关烤盘字段保持进入 planner 时状态。

## 2. TDD 与定向验证

先新增纯核心测试，在生产类型尚不存在时运行定向测试，得到预期 `compileTestKotlin FAILED`，错误为 `LegacyFixedBatchHeatPositionStrategy`、输入/输出类型 unresolved reference。随后实现算法 A，同一测试通过。

纯核心覆盖：

- 稳定 ID 与 action/count 不变量；
- 低并发物理范围穷举、负数/超物理值及 Int 回绕；
- 高并发物理范围穷举；
- 位置不足、部分高热计划、顺序保持、重复/非正位置 fail-closed；
- 输入防御复制、输出不可修改；
- 固定常量种子 10,000 组独立 `take(min)+zip` 差分。

App 定向回归覆盖五个既有低/高热测试类与新增接线门禁，`BUILD SUCCESSFUL`。新增行为证据包括：

- future return 重复值按首次出现去重；
- 高热源位不从 25 连续开始时返回空；
- 上述非法计划前后前区与补热区全部 taste 快照一致；
- 低热库存只覆盖 1 根时仍生成 1 个连续 target，保持 2.5.42 部分批次语义；
- 12 个空盘且已有口味混合时，“基线先预填再 planner”与“不可变预填计划延迟提交”的 1~21/25~33 全部 taste、target、return 完全一致；
- 同一固定输入另外断言 2.5.42 预填分配的独立 oracle：位置 1~8 为 A、9~12 为 B，避免只让新旧接线路径共用同一实现后互相比对；
- 提交前目标盘变为占用、当前回搬盘变为占用、位置进入排除集或已承诺集时，最终复核拒绝写入；
- 延迟预填覆盖的非选中前区盘发生对象替换、位置变化或口味从空白变为非空时，整份计划拒绝且不改其他相关对象；
- STOP、pending、高并发初始化/恢复/过保分支的源码相对顺序门禁。

## 3. 完整自动化证据

命令：

```powershell
.\gradlew.bat :scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

结果：`BUILD SUCCESSFUL`，94 个 task 全部执行。XML 复算：

| 模块 | 测试 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| scheduler-core | 39 | 0 | 0 | 0 |
| recovery-core | 26 | 0 | 0 | 0 |
| order-core | 34 | 0 | 0 | 0 |
| app testDebugUnitTest | 1666 | 0 | 0 | 0 |
| 合计 | 1765 | 0 | 0 | 0 |

Debug 构建：

```powershell
.\gradlew.bat :app:assembleDebug --rerun-tasks --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`，122 个 task 全部执行。

当前 Debug APK：

- 路径：`app/build/outputs/apk/debug/app-debug.apk`；
- 大小：49,581,686 bytes；
- SHA-256：`558A424CDAB9AD382770DD4A7997998CA9FE2B13610E9AC828B85CEA7ACA4F4D`。

APK 只是当前工作树本地构建证据。构建仍有历史 UVC 仅 32 位、Manifest namespace 与 deprecated API 警告，本切片未处理。

验证期间曾有两个并行残留 Gradle 进程分别占用 `libausbc` 和 App 的生成 `R.jar`，对应命令因无法删除生成文件而失败；确认残留构建自然结束后，同一全量 JVM 命令成功，随后无并发单独执行上述完整 Debug 重建并成功。该过程属于本地构建环境文件锁，不是测试断言或源码编译失败，最终证据只采用最后一次成功结果与最后一次 APK。

增加固定预填 oracle 后，第一次定向运行的 Gradle Test Executor / Kotlin daemon 回环连接被重置，报 `127.0.0.1 connection refused`，没有测试断言失败；使用 `--max-workers=1` 重跑同一测试成功。最终全量 JVM 证据同样使用单 worker 重跑，避免把进程通信故障当作业务结果。

## 4. 静态与边界门禁

- `scheduler-core/src/main` 禁止项扫描无 Android/App、MQTT、Modbus/RS485/串口、I/O/网络、线程/协程、Timer/Handler、系统时钟、随机源或 UUID 命中；
- 新测试只使用固定常量种子 PRNG；既有三个差分测试仍使用各自固定种子；前序 `StrategySwitchPolicyTest` 保留只读源码结构测试，本切片未修改；
- `scheduler-core/build.gradle.kts` 未改，SHA-256 为 `3E439CDD3843C75F9E097607C52ADD37C6BBC68D1453F2D10D1DEB45D048D878`，没有新增运行时依赖；
- `scheduler-core` 当前 61 个 main class，class major 全部为 55（Java 11）；
- 本切片路径 `git diff --check` 通过；
- 生产仓库 `D:\LeYe\cl-sausage-controller` tracked 文件变化为 0；
- 重构仓库仍在 `develop`，HEAD 未变：`fad00d530bb88c0fb3ace82dcdda038f24626438`。

## 5. 独立审核与主会话复核

计划先后经过四轮独立只读审核。前三轮当时发现并闭环：

- STOP 门禁/顺序未冻结；
- 低热/高热部分计划数量规则当时未写清；
- 高并发新批次可能抢占恢复/初始化/过保收口；
- 接口不可编译、action/count 可伪造；
- future return 去重、测试命令、白名单、回滚与依赖扫描不完整；
- 高热先写 taste 后校验可能造成非法计划状态污染。

最终计划审核为 `Approve`，P0/P1/P2/P3 均无。主会话逐项反查源码后认可这些意见并按修订计划实施。

第一次实施审核为 `Request Changes`：

- P1：实现未满足当时计划所写的“低热恰好 3 对”；
- P1：高热调用方仍在 planner 前补写空盘口味，planner 最终写入前也没有重新复核占用/排除/承诺状态；
- P2：已有测试和验证记录不足以证明上述门禁。

主会话第一次复核后按当时计划实施了“低热恰好 3 对”并将高热 `fillMissingTasteReservations` 简单后移，同时增加最终状态复核。

第二次实施审核仍为 `Request Changes`，并进一步反查基线确认：

- 2.5.42 明确允许低热库存只覆盖 1~2 根时启动连续部分批次，因此“强制 3 根”是未经授权的业务变化；
- 简单后移 `fillMissingTasteReservations` 会改变超过 9 个空盘时未选中位置的最终预留口味；
- 缺少合法路径全盘 taste 等价测试和实际状态漂移复核测试。

主会话再次复核后确认第二次审核准确，恢复低热 1~3 根基线语义；将高热改为不可变预填计划，成功时按旧“预填 -> planner 覆盖”顺序提交，失败时不提交；增加全盘最终 taste 差分和最终状态复核行为测试。此修正需要重新执行完整 JVM 与 Debug 构建，第 3 节只应记录最后一次修正后的证据。

第三次实施审核为 `Request Changes`：延迟预填只保存 `position -> taste`，提交前没有确认非选中预填盘仍是形成计划时的同一对象、位置未变且口味仍为空白；现有状态漂移测试也未覆盖这类预填盘。主会话反查可变 `KaoPan` 和提交代码后确认意见准确，将每条延迟写入改为保存预期对象身份、位置和目标口味，并在任何写入前统一复核对象、位置、占用和空白口味；新增对象替换、位置变化和口味漂移行为测试。

该轮审核还指出合法路径对比两侧都复用了新的预填计算，不能单独充当 2.5.42 预填算法 oracle。主会话认可并增加固定输入的明确位置/口味预期，再保留最终全盘状态差分作为提交顺序证据。

最终修正版再次交由“重构上位机审核会话A”严格只读审核，结论为 `Approve`，P0/P1/P2/P3 均无。审核确认：

- 延迟写入已携带对象引用、预期位置和目标口味，所有 taste 写入前统一复核位置不重复、对象恒等、位置未变、无肠、口味仍空白且目标口味非空；
- 两个生产入口都传递完整 writes，全部复核完成后才按“预填 -> 回搬/目标覆盖”顺序提交；
- 非选中预填盘的口味变化、位置变化和同位置对象替换均有实际行为测试；
- 固定 2.5.42 预填 oracle 与最终全盘差分共同覆盖计算规则和提交顺序；
- 现有 XML、APK 大小/SHA、生产源码与产物时间边界、文件锁/worker 连接故障记录均一致，没有把失败过程冒充成功证据。

主会话逐项反查当前实现、测试和证据后认可该最终审核结论。审核也再次限定：结论不代表真实 MQTT、RS485/Modbus、温控或机械验证，也不代表已实现真实算法 B/C、完整 `SchedulerProfile` 或对外切换 UI。

## 6. 证据边界与后续

已证明：当前源码可编译、纯策略差分一致、指定 JVM 测试与完整 JVM 回归通过、Debug APK 可构建、非法高热位置计划不写 taste、App 安全编排边界仍存在。

未证明：现场 APK 使用本源码；真实 MQTT/HTTP、RS485/Modbus、下位机响应、温控、库存扣减、长时间运行或机械补货效果；也未实现真实算法 B/C 或完整 SchedulerProfile 选择 UI。JVM 测试与 APK 构建不能替代现场证据。

完成 1C-4 后已有四个真实可替换调度子策略，但仍不是可对外选择的完整算法 A/B/C。后续至少还需迁移高低并发模式/过渡决策，并建立版本化 profile 目录、原子空闲切换、完整流程 pin、离线回放与影子比对。
