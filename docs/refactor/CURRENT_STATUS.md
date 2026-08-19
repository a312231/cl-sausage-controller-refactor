# 安卓上位机渐进重构当前状态

## 1. 状态元数据

- 更新时间（Asia/Singapore）：`2026-08-19 16:54:55 +08:00`
- 切片状态：`COMPLETED`
- 实施审核：`Approve`（重构上位机审核会话A，阶段1完整内容审核）
- 当前唯一切片：文档地基切片
- 下一唯一切片：2.5.48 基线同步切片，待另写计划并审核，尚未实施

本文件只记录当前里程碑状态，不替代源码、Git、测试报告、独立审核或现场证据。

## 2. 仓库与版本门禁

- 重构仓绝对路径：`D:\LeYe\cl-sausage-controller-refactor`
- 当前分支：`develop`
- 当前 HEAD：`8b81a47d1b94171d25095c875c62f4a56ceb1535`
- 本地 `origin/develop`：`8b81a47d1b94171d25095c875c62f4a56ceb1535`
- 实时 GitHub `develop`：`8b81a47d1b94171d25095c875c62f4a56ceb1535`
- 当前源码版本：`2.5.42/292`
- 切片开始前工作树：干净
- 当前工作树：tracked diff 为空，恰有本切片创建的两个 untracked 文档

生产仓 `D:\LeYe\cl-sausage-controller` 只读，且存在用户未提交修改；禁止覆盖、回滚或混入这些修改。

## 3. 目标基线与审计锚点

- 共同基点 A：`fad00d530bb88c0fb3ace82dcdda038f24626438`，`2.5.42/292`。
- 生产目标基线 P：`5e0d2f78d21fa3e3529c04da2cb9edd6c68a61cb`，`2.5.48/298`。
- 旧重构里程碑 R：`8b81a47d1b94171d25095c875c62f4a56ceb1535`，`2.5.42/292`。

P 尚未同步到重构仓。当前 HEAD 仍为 R，不能把目标版本写成当前重构源码版本。

只读基线差异审计结果：A→P 共 32 个提交、90 个终点差异路径；A→R 共 3 个重构提交、168 条变更记录。

## 4. 当前唯一切片

文档地基切片是非代码切片，修改白名单只有：

- `docs/refactor/REFRACTOR_CHARTER.md`
- `docs/refactor/CURRENT_STATUS.md`

禁止修改旧文档、源码、测试、Gradle、AGENTS 或其他文件。本切片不构建、不运行测试、不提交、不推送、不发布、不部署、不操作设备。

## 5. 已完成里程碑与当前模块层级

- `scheduler-core`：纯模型存在，App 有编译依赖和部分真实入口/影子接线；基于 2.5.42 的策略必须对 P 重新验证，不能称为已完成 2.5.48 迁移。
- `recovery-core`：纯决策存在，App 有编译依赖和真实接线；Android、MQTT、Modbus 副作用仍由 App 承担，接线必须对 P 复核。
- `order-core`：纯模型和测试存在，但 App 没有编译依赖和运行入口，不拥有生产订单执行权。
- 2.5.42→2.5.48 基线差异审计已完成，并经“重构上位机审核会话A”修订后最终 `Approve`。

审计过程中已纠正两项关键口径：

- 当前 4F 自清洁由常量策略强制 no-op，不会下发旧 200 自清洁命令；遗留 `moveStatus/finally` 分支当前不可达。
- 正式 OUT 的 `out2..out5` 没有耐久逐步 checkpoint；runner 的进程内成功不能替代崩溃恢复证据。

## 6. 证据分栏

- 源码与 Git：已只读核对 A/P/R、远端 SHA、差异路径、调用链、机械状态写入、热区和寄存器模型。
- JVM 测试与构建：本切片未执行；历史结果不作为本切片验证。
- 测试 MQTT/HTTP/后端：未执行。
- 真实 RS485/Modbus/机械/断电/温控/长稳：未验证。
- 独立审核：基线差异审计已 `Approve`；文档地基阶段1完整内容审核已 `Approve`。

## 7. 当前架构阻断与风险

1. P 的 32 个提交语义尚未同步到重构仓，不能直接开始机械核心接线。
2. 当前仍有 OrderDeliveryClaim、ActionGroupSession、SupplementPrefetchPipelineSession、SupplementBatchIntent、PanMoveJournal、DiscardJournal、ResetCloseoutRecord、ResourceLocks 和 `moveStatus` 等多套权威。
3. 搬盘结果不明时会保留 journal 与 `moveStatus=3`，但 `finally` 释放 ResourceLocks，资源状态与恢复责任分裂。
4. callback 和资源释放缺少统一的 `operationId + revision` 围栏。
5. OUT 的 `out2..out5` 缺少耐久逐步 checkpoint，进程崩溃或 `out5` 超时后不能精确映射到固定状态机。

上述项目是后续架构阻断，尚未修复，不得写成已完成。

## 8. 权限边界

当前授权只覆盖两份文档的创建和验证，不包含提交、推送、发布、部署或设备操作。后续任何代码同步或实现都必须另立切片并通过审核门禁。

## 9. 下一切片门禁

下一唯一切片为“2.5.48 基线同步切片”，目前待另写计划、非目标、白名单、验收和回滚边界并审核，尚未实施；不能直接实施 `mechanical-core`。

## 10. 更新纪律

本文件只在已验证里程碑开始或结束时更新。每小时巡检只检查报警，不自动写本文件。这里记录的状态必须由当前 Git、验证输出和审核证据重新核对，不能单独作为完成证明。
