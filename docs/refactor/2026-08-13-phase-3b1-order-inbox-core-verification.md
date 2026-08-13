# 第三阶段 3B-1：耐久订单 Inbox 纯核心验证记录

日期：2026-08-13
源码基线：`fad00d530bb88c0fb3ace82dcdda038f24626438`
源码版本：`versionName=2.5.42`、`versionCode=292`

说明：源码版本不代表现场 APK。本切片未提交、未推送、未发布、未部署、未安装或操作设备。

## 1. 实施边界

只新增/修改：

- `order-core/src/main/kotlin/cn/niuyannet/kaochang/order/OrderInboxModels.kt`；
- `order-core/src/main/kotlin/cn/niuyannet/kaochang/order/OrderInboxValidator.kt`；
- `order-core/src/main/kotlin/cn/niuyannet/kaochang/order/OrderInboxAdmissionPolicy.kt`；
- `order-core/src/test/kotlin/cn/niuyannet/kaochang/order/OrderInboxPolicyTest.kt`；
- 3B 方案和本验证记录。

没有修改 App 接单、MQTT、SharedPreferences、库存、补肠、Modbus、RS485、动作组、UI 或调度算法。`app/build.gradle.kts` 当前只依赖 `scheduler-core` 和 `recovery-core`，没有依赖 `order-core`；因此 3B-1 没有运行时入口。

## 2. 已实现的纯核心能力

- `LEGACY_V1` / `DISPATCH_V2` typed draft 与耐久记录模型；
- Long 范围业务 ID 的十进制规范化，不先经过 App 现有 Int DTO；
- 每项数量和整单 `1..4` 上限，使用 `BigInteger` 解析与求和，超大输入不会整数溢出；
- V2 dispatch/version/expiry 结构完整性校验，但不在本切片擅自定义服务端时钟偏差或授权新鲜度；
- SHA-256 canonical hash，只覆盖协议、订单/设备、V2 授权元数据和有序商品执行字段；
- `ACCEPT_NEW / DUPLICATE_SAME / CONFLICT / BUSY_OTHER_ORDER / DUPLICATE_TERMINAL / STORAGE_FAILED`；
- 只有 pending 快照同步落盘成功才输出 `BEGIN_PREPARATION`；所有其他结果输出 `NO_ORDER_SIDE_EFFECTS`；
- `PERSISTED_PENDING_PREPARATION -> READY_FOR_EXECUTION/HOLD_REQUIRED` 单向状态迁移；
- 整单终态与 `OrderClaimReleasePolicy` 的释放资格保持一致；
- tombstone 只能由 `RELEASE_ALLOWED` 的耐久整单终态生成；`HOLD_REQUIRED`、证据未知或失败终态不能转换成 tombstone；活动记录会重算 canonical hash，并校验商品 ID 唯一、总数、连续 delivery ordinal；
- items/tombstones 防御复制并对外不可修改；快照拒绝 active/tombstone 同 key、重复或互相冲突的 tombstone key；
- inbox 模型不包含 scheduler/strategy、MQTT、Modbus、claim owner、单根 session 或 outbox ACK 所有权。

## 3. TDD 与自动化结果

先新增 `OrderInboxPolicyTest`，首次执行因生产类型尚不存在而在 `compileTestKotlin` 失败；随后加入最小实现并转绿。

### 3.1 定向与模块测试

```powershell
.\gradlew.bat :order-core:test --rerun-tasks --no-daemon --console=plain
```

结果：`34 tests, 0 failures, 0 errors, 0 skipped`。其中 3A 既有 16 个测试保持通过，3B-1 新增 18 个测试。

覆盖：

- 缺失字段、设备不匹配、空商品；
- 数量 0、负数、单项/整单大于 4、超过 4 个商品项、10 万位十进制输入的长度前置拒绝；
- `Long.MAX_VALUE` 和大于 Int 的业务 ID 不截断；
- legacy/V2 元数据一致性；
- 易变时间、名称和图片变化时 hash 不变；
- 稳定 ID、口味、数量、商品顺序、dispatch/version/expiry 变化时 hash 改变；
- 新单、重复、冲突、忙态、终态重复和存储失败；
- 非接受结果无订单副作用；
- pending 的 READY/HOLD 合法迁移及非法二次迁移；
- 非终态 tombstone 拒绝、集合防御复制、hash 自校验、重复商品 ID/不连续 ordinal、矛盾快照拒绝；
- V2 version/expiry 的非数字、非正数、超 Long，dispatch/order/device/taste 文本上下限、负时间和 length-prefix 歧义边界；
- inbox 不包含调度策略字段。

### 3.2 全量测试与构建

```powershell
.\gradlew.bat test assembleDebug --rerun-tasks --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`，287 个 Gradle task 执行。重新统计当前 XML：

- App：3286；
- scheduler-core：8；
- recovery-core：18；
- order-core：34；
- libnative：2；
- 合计：`3348 tests, 0 failures, 0 errors, 0 skipped`。

最终收口活动记录构造入口与 tombstone 释放门禁后，重新以 `--rerun-tasks` 完整执行上述全量测试和构建；当前 XML 时间戳均来自这次最终执行。

当前 Debug APK：

- 路径：`app/build/outputs/apk/debug/app-debug.apk`；
- 大小：49,548,913 bytes；
- SHA-256：`E0C5154A3FB173630EEF23566C58973D201F0A2157234665A8664F99D78F5B14`。

APK 仅是当前工作树构建证据，没有发布含义。构建仍报告历史 UVC 32 位库、Manifest namespace 和 deprecated API 警告，本切片未改这些遗留项。

### 3.3 结构门禁

- `order-core` 当前 51 个 main class，class major 全部为 55（Java 11）；
- `order-core/src/main` 禁止 Android/AndroidX/Paho/App/Modbus import 的扫描结果为 0；
- 新增文件尾随空白为 0，`git diff --check` 通过；
- App 未增加 `implementation(project(":order-core"))`。

## 4. 独立审核整改

“重构上位机审核会话A”首轮结论为 `Request Changes`：P0 无，P1 两项、P2 三项。主会话逐项回到源码复核后确认均成立，并完成：

- 禁止从非终态 active record 生成 tombstone；
- items 最多 4 项，quantity/Long 数字在 `BigInteger` 前做固定字符长度门禁；
- 记录及快照集合防御复制、不可修改，执行记录自校验 hash/商品字段/总数/连续 ordinal；
- 拒绝 active/tombstone 重叠及重复 stable key，使损坏证据不能依赖列表顺序降级成普通重复；
- 补齐 V2 错误分支、长度上下限、负时间和 length-prefix 边界测试。

第二轮审核继续给出 `Request Changes`：公共变换 helper 可绕过状态/revision policy，tombstone 公共构造器可绕过终态工厂。主会话确认后进一步收口：

- admission/terminal 内部变换同时使用 `internal + @JvmSynthetic`，并在方法内部重复强制当前状态、目标状态、revision 严格递增和时间不倒退；字节码门禁确认两方法为 `ACC_SYNTHETIC`；
- 新增公共 `OrderWholeOrderTerminalPolicy.close` 作为整单终态唯一业务入口；
- tombstone 主构造器改为 private，字节码只保留 private 主构造器和编译器 synthetic bridge；当前切片不提前提供 persistence restore 绕过入口；
- 新增同 revision、低 revision、反向 admission、重复 terminal 和时间倒退的直接 helper 测试。

第三轮审核仍给出 `Request Changes`：活动记录公共构造器可伪造终态、`HOLD_REQUIRED` 终态仍可生成 tombstone、tombstone 时间可由调用方写得早于真实终态。主会话确认三项均准确，并完成最终收口：

- `ActiveOrderRecord` 主构造器改为 private，仅保留 `internal + @JvmSynthetic` 的 accepted factory；字节码确认非 synthetic 主构造器为 `ACC_PRIVATE`；
- tombstone 工厂只接受 `claimReleaseEligibility=RELEASE_ALLOWED` 的耐久终态，明确拒绝 `EVIDENCE_UNKNOWN`、`FAILED`、`UNKNOWN` 等保持 `HOLD_REQUIRED` 的终态；
- `terminalAtMs` 固定取自活动记录的 `updatedAtMs`，调用方不能自行指定；
- 最终重新执行 `:order-core:test` 和 `test assembleDebug --rerun-tasks`，34 个模块测试及 3348 个全量测试均为 0 失败。

最终只读审核结论：`Approve`；P0/P1/P2/P3 均无。主会话再次按当前源码、测试 XML、class 字节码和 APK 哈希独立复核，认同该门禁结论。审核同时明确：这不代表 3B-2/3B-3 已实施，不代表算法 B/C 或运行时切换已实现，也不构成真实 MQTT、RS485/Modbus 或机械验证。

## 5. 证据边界与剩余风险

已证明：纯 JVM 模型、校验、canonical hash、准入决策和状态迁移在当前测试空间内确定且可构建。

未证明：

- App 原始 JSON 解析和不同 JSON key 顺序的等价性；这必须由 3B-2b 的真实 App 适配器测试证明；
- SharedPreferences `commit()`、进程死亡/断电、冷启动 fence；
- legacy 消息新鲜度、服务端重验证、dispatch ACK/outbox；
- 真实 MQTT Broker 重投、RS485、Modbus、out1~out5 或机械动作。

因此本记录不能作为真实设备可上线、可回滚到旧 APK或可以断点续出餐的证据。
