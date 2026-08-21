# 安卓上位机渐进重构章程

## 1. 文档身份与权威边界

本文件是安卓上位机渐进重构的长期北极星，记录稳定目标、范围、设计决策、不变量和治理门禁。它不记录会频繁变化的当前分支、HEAD、工作树或切片进度；这些信息只记录在 `CURRENT_STATUS.md`。

权威顺序固定为：当前代码与 Git 事实、当前测试或构建报告、独立审核、真实现场证据，高于本文件和其他设计文档。本文件不能替代源码、Git、测试、审核或现场验证。

`docs/refactor` 下既有日期计划、验证和回滚资料不再拥有跨切片架构权威，但继续保留其对应历史切片范围内的证据价值。历史材料不能覆盖当前代码、Git、测试、审核或现场事实。

## 2. 不可变起源与审计锚点

- 共同基点 A：`fad00d530bb88c0fb3ace82dcdda038f24626438`，源码版本 `2.5.42/292`。
- 初始生产目标基线 P：`5e0d2f78d21fa3e3529c04da2cb9edd6c68a61cb`，源码版本 `2.5.48/298`。
- 当前生产同步目标 Q：`2f0b36e08cedb8f4c06cd4d2550e2cec08558bd7`，源码版本 `2.5.51/301`。Q 是 P 的严格后继，P→Q 共 3 个提交、37 个路径。
- 审计时旧重构里程碑 R：`8b81a47d1b94171d25095c875c62f4a56ceb1535`，源码版本 `2.5.42/292`。

A、P、R 继续作为不可变起源和历史审计锚点；Q 是生产 Android `develop` / source 同步目标前进后新增的当前同步目标，不覆盖 P。用户报告生产环境 APK 已为2.5.51，但安装、运行和现场效果仍需独立证据。实时远端、当前 HEAD 和 Q 的同步进度只以 `CURRENT_STATUS.md` 及当前 Git 证据为准。

## 3. 范围与非目标

本阶段只渐进重构 Android 12 上位机 App，不从头重写整个 App。保持下位机协议、Modbus 寄存器及既有 MQTT/HTTP 语义兼容。

当前非目标：

- 不修改后端、支付、订单数据库或下位机协议。
- 不开发算法 B、新 UI、MQTT 5 或运行中算法热切换。
- 不启用或实现双 lane 真并行；双 lane 仅保留以后独立评估的模型扩展点。

## 4. 机械协调地基的稳定设计决策

1. 渐进迁移：不重写整个 App；以当前经审计生产同步目标为渐进迁移基线（现为Q），采用绞杀式迁移；P仅保留历史审计锚点。
2. 唯一协调中心：只能存在一个 `MechanicalOperationCoordinator`，所有机械准入、仲裁、暂停、恢复和收口最终归口。
3. 单一权威记录：单一 `MechanicalOperation` / `OperationLedger` 是 owner、session 身份、claim 身份、资源占用、checkpoint、命令阶段、物理结果、库存/烤盘效果和恢复状态的第一权威；`operationId` 统一所有身份，`revision` 围栏每次状态推进。
4. `moveStatus` 仅为派生投影：无活动=`0`、补肠=`1`、出餐=`2`、搬盘=`3`、丢弃=`4`、清洁=`5`。迁移完成后，业务代码禁止直接写 `moveStatus`。
5. 固定状态机：`REQUESTED → ADMITTED → COMMAND_PREPARED → COMMAND_DISPATCHED → RESULT_CONFIRMED → EFFECT_APPLIED → STEP_COMPLETED → OPERATION_COMPLETED`。任一证据不明进入 `RECOVERY_REQUIRED`，禁止猜测完成或盲目重发。
6. 耐久顺序：Modbus 下发前先耐久记录命令意图；确认下位机结果后幂等更新库存/烤盘；效果落盘后才进入下一步；仅确认安全终态才完成 operation ownership 收口。
7. 资源与 ownership 释放必须遵守本章程的十条硬规则，并严格区分物理 resource lease 与 operation/ledger ownership。
8. 固定安全优先级：紧急停机/食品安全 ＞ 不可中断的当前原子动作 ＞ 已付款订单出餐 ＞ 已物理放置需安全回搬 ＞ 补肠 ＞ 自动清洁/普通维护。优先级不得任意热配置。
9. 订单打断补肠：只能在安全 checkpoint 暂停；释放物理 resource lease 后才能执行 `out1..out5`；订单完整收口并重新验证库存和机械后才能续补；机械运动中不得硬抢占。
10. 崩溃/断电启动恢复：先阻止新机械任务；读取并校验 Ledger；连接 Modbus；获取至少两次稳定寄存器快照；再分类为明确未下发、已完成但效果未提交、仍运行、结果不明或明确安全终态。不得以内存锁为空推断机械空闲。
11. 第一版并发边界：最多一个真正运行的 `MechanicalOperation`；可保留暂停 operation，但暂停 operation 不占物理资源；双 lane 保持关闭，仅在模型中预留资源集合能力。
12. 固定迁移顺序：同步当前生产基线（初始为 2.5.48，现更新为 Q=2.5.51）→ 纯 `mechanical-core` 模型、状态机、资源、仲裁、checkpoint 和恢复决策 → 旧状态影子观察 → 正式订单 `out1..out5` 所有权 → 单 lane 补肠及订单暂停/续作 → 故障补肠恢复和高低并发补热 → 搬盘、丢弃、下平台、维护、自清洁；双 lane 最后另行评估。
13. 每个状态转换必须覆盖：落盘失败、崩溃/断电、协程取消、Modbus 超时、重复 MQTT/下位机结果、迟到回调、owner/revision 变化、库存/会话/资源部分提交、`out5` 超时、`in2` 后重启及订单打断续补。真实 RS485/Modbus/机械效果必须另行验证。
14. 固定范围：当前只改 Android App；保持下位机协议、寄存器及既有 MQTT/HTTP 语义；不改后端、支付或订单数据库；不做算法 B、新 UI、MQTT 5、运行中热切换或双 lane。

## 5. 资源与 ownership 释放十条硬规则

1. 只有匹配 `operationId + revision` 的主体才能释放其物理 resource lease，或推进、收口 operation。
2. 暂停必须先到经状态机批准的安全 checkpoint。
3. 安全暂停可以释放物理 resource lease，但必须保留 operation ownership、Ledger、`operationId/revision` 和恢复责任；暂停 operation 不得继续占物理资源。
4. 超时只记录告警并进入恢复判断，不能按经过时间释放 resource lease 或 operation ownership。
5. 协程取消只表示上位机控制流取消，不等于下位机机械取消或 operation 终止。
6. `finally` 不得盲目释放 resource lease，更不得清除 operation/ledger ownership；只能执行具备精确 owner/revision 和批准状态证明的幂等收口。
7. 物理结果或证据未知时保持机械忙态，并进入 `RECOVERY_REQUIRED`。
8. 迟到或重复回调必须校验 `operationId + revision`；不匹配时只记录隔离证据，不得修改状态、库存、盘位或资源。
9. 禁止任何路径通过强制 `moveStatus=0` 模拟资源或 operation 释放。
10. operation/ledger ownership 只能在 `OPERATION_COMPLETED` 或明确批准的等价安全终态，以 `operationId + revision` 精确释放；不得因内存锁为空、超时、取消、异常、`finally` 或证据不明而释放。

## 6. 状态与恢复不变量

- owner、session、claim、resource、checkpoint、command、result、effect 和 recovery 必须由同一 Operation/Ledger 权威描述。
- 命令意图、命令下发、物理结果和本地效果必须分别建模，不得把寄存器写成功当成物理成功。
- 只有明确安全终态才能完成 operation ownership 收口；未知结果保持忙态。
- 迟到回调必须通过 `operationId + revision` 围栏隔离。
- 暂停 operation 保留恢复责任，但不占物理资源。
- 第一版只允许一个运行 operation；双 lane 保持关闭。

## 7. 切片、权限与证据门禁

同一时间只允许一个可回滚切片。每个切片开始前必须明确目标、非目标、修改白名单、状态不变量、故障场景、测试或静态证据、验收和逐文件回滚边界。超出白名单或关键假设不成立时立即停止并重新审核。

每个实施切片必须经过相称的自动化验证、“重构上位机审核会话A”独立只读审核和主会话复核。P0/P1 未关闭时不得进入下一切片。

未经用户单独明确授权，不提交、不推送、不发布、不部署、不操作设备。

交付证据必须分栏：

- 源码与 Git 静态证据；
- JVM 测试与构建证据；
- 测试 MQTT/HTTP/后端证据；
- 真实 RS485/Modbus/机械/断电/温控/长稳证据。

缺失的证据必须明确写“未执行”或“未验证”，不同证据层级不得互相替代。

## 8. 文档治理

- 本文件只记录稳定决策；`CURRENT_STATUS.md` 只记录当前里程碑状态。
- 每个后续切片最多保留一份计划和一份验证记录。
- `CURRENT_STATUS.md` 只在已验证里程碑开始或结束时更新。
- 每小时巡检只检查报警，不自动写文档。
- 文档不能替代当前代码、Git、测试报告、审核或现场证据。

## 9. 目标变更流程

任何稳定目标或设计决策变更必须依次经过：用户明确授权、影响分析、审核会话A独立审核、主会话复核、更新本章程。实现代码、旧文档、历史提交或运行时配置均不得静默改变本章程目标。
