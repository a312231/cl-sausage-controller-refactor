# 第一阶段 1C-3：补货目标配额纯策略验证记录

日期：2026-08-13
源码基线：`fad00d530bb88c0fb3ace82dcdda038f24626438`
源码版本：`versionName=2.5.42`、`versionCode=292`

说明：源码版本不代表现场 APK。本切片未提交、未推送、未发布、未部署、未安装或操作设备。

## 1. 实施边界

新增纯 Kotlin `SupplementTargetAllocationStrategy`，算法 A 为 `InventoryProportionalTargetStrategy`，稳定 ID 是 `inventory-proportional-target-v1`。它只接收目标烤盘数、App 已过滤并聚合的按口味库存快照，以及 `STANDARD/HIGH_HEAT` 场景，返回配额和被丢弃库存诊断。

App `KaoPanHelper` 继续拥有并执行：

- 全局 `supplementEnabled`；
- `num<=0`、空口味；
- `IR_PICK_FAILED`；
- 合法源箱位；
- 多箱同口味 Int 聚合；
- 日志及现有 `MutableMap` 返回兼容。

因此可替换算法不能把硬件安全过滤排除的库存重新纳入调度。六个现有调用点、库存扣减、批次/热源/位置、口味混配、动作组、MQTT/HTTP 和 Modbus/RS485 均未修改。

## 2. 等价语义

固定保留两种旧模式：

- `STANDARD`：库存降序/口味码升序选口味，所有有效库存仍作 Float 分母；每个选中口味至少 1，按稳定余数顺序补齐，超额时按稳定分配数顺序单遍减一且不低于 1；
- `HIGH_HEAT`：先算 Float 比例整数部分，再循环分配未取整余量，最后合并整数部分；保留零值键和首次插入顺序。

输入/输出集合均防御复制并不可修改；App 返回新的可变副本。`computeKaoPamRatio_old` 未迁移、未删除，也未作为算法 B。

## 3. TDD 证据

纯核心先新增测试，首次运行在 `compileTestKotlin` 因 `InventoryProportionalTargetStrategy`、输入/输出和场景类型不存在而失败；最小实现后转绿。

纯核心新增 7 项测试，包含固定种子 10,000 组冻结旧算法差分。oracle 在测试内独立复制迁移前算法，不调用生产策略、App 适配器或共享 helper；逐项比较目标 map 和丢弃 map 的键值及迭代顺序。

App 测试新增两类跨边界门禁：

- 两个入口的同口味 `Int.MAX_VALUE + 1` 聚合回绕；
- 不同口味 `values.sum()` 负、零、正回绕，并锁定空结果、极值结果和顺序。

第一次执行极值测试时误用了禁用源箱位 1，四条测试按硬件安全规则被过滤，说明测试前提错误。修正为合法源箱位 2~4 后，旧 App 实现上的 19 项 ratio 测试全部通过；随后新增源码接线门禁按预期因尚未委托核心而红灯。完成适配后，19 项 ratio 测试和 3 项接线门禁全部通过。

## 4. 完整验证

最终执行：

```powershell
.\gradlew.bat :scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --rerun-tasks --no-daemon --console=plain
```

当前 XML 可复算结果：

- scheduler-core：32；
- recovery-core：26；
- order-core：34；
- App：1657；
- 合计：`1749 tests, 0 failures, 0 errors, 0 skipped`。

本次控制台显示 JVM 回归 `BUILD SUCCESSFUL`、94 个 task 全部执行；Debug 构建 `BUILD SUCCESSFUL`、122 个 task 全部执行。task 数只能由本次控制台记录，不能仅靠 XML/APK 事后复算。

当前 Debug APK：

- 路径：`app/build/outputs/apk/debug/app-debug.apk`；
- 大小：49,565,311 bytes；
- SHA-256：`415C954CC21F8B9E60B7F3A15B9453D24C6DC399056A959475F6E50AE6DAE2A7`。

APK 只是当前工作树构建证据，没有发布或现场版本含义。构建仍有历史 UVC 32 位 native、Manifest namespace 和 deprecated API 警告，本切片未处理。

## 5. 静态与白名单门禁

- `scheduler-core/src/main` 对 Android/App/MQTT/Modbus/RS485/串口、I/O、网络、线程/Executor、协程、Timer/Handler、系统时钟、随机数和 UUID 扫描为 0；
- 1C-3 差分测试只使用固定常量种子；测试目录另有前序 1C-1 的 `java.io.File` 源码结构测试，不属于本切片新增；
- `scheduler-core` 依赖清单未改，49 个 main class 全部 class major 55；
- 六个调用点、`SupplementTasteMixPlanner`、Gradle/settings/version catalog 文件实施前后 SHA-256 全部一致；
- `git diff --check` 通过；
- 生产仓库 `D:\LeYe\cl-sausage-controller` tracked 文件变化为 0。

## 6. 证据边界

已证明：当前源码的两种补货目标配额规则已通过稳定策略接口迁入纯 Kotlin 核心；对自动化覆盖输入与冻结旧实现等价；App 硬件安全过滤和六个调用点保持。

未证明：真实算法 B 的业务规则、收益或安全性；完整 SchedulerProfile 已可切换；真实库存、MQTT/HTTP、RS485/Modbus、下位机响应、长时间运行或机械补货效果。JVM 测试和 APK 构建不能替代这些证据。

本切片只是完整 profile 的第三个真实子策略。后续仍需抽取批次/热源/位置和高低峰模式决策，再以版本化 profile 原子组合、空闲切换并对已开始流程整体 pin，才能向用户暴露真正的“调度算法 A/B”。

## 7. 独立审核与主会话复核

“重构上位机审核会话A”先对计划做了三轮严格只读审核。前两轮指出并由主会话确认、修订：精确文件白名单、App 两层 Int 回绕门禁、核心 I/O/线程/时钟/随机数与 Java 11 门禁、固定种子测试范围、精确验证路径和逐项回滚。最终计划审核为 `Approve`，P0/P1/P2/P3 均无。

实现完成后的最终只读审核结论同样为 `Approve`，P0/P1/P2/P3 均无。审核独立核对了：

- 新旧 Int/Float、排序、补余/减一及 map 顺序；
- 10,000 组独立 oracle 和可终止范围；
- 合法源箱位 2~4 上的 App 聚合回绕测试；
- App 安全过滤、日志和 MutableMap 兼容；
- 六个调用点、旧算法定义、XML、APK、class major、禁止依赖、白名单和生产仓库状态。

主会话再次按当前源码与持久证据逐项复核，认同审核结论。审核及主会话均明确：本切片没有真实 MQTT、RS485/Modbus、库存现场、下位机或机械补货证据。
