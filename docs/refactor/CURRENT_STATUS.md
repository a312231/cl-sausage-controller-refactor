# 安卓上位机渐进重构当前状态

## 1. 状态元数据

- 更新时间（Asia/Singapore）：`2026-08-20 00:43:02 +08:00`
- 切片状态：`REMOTE_DEVELOP_INTEGRATION_COMPLETED`
- 实施审核：`Approve`（重构上位机审核会话A，merge实现、测试、构建、最终diff及状态收口审核）
- 当前唯一切片：2.5.48 基线同步远端状态收口
- 下一唯一切片：纯 `mechanical-core` 模型、状态机、资源、仲裁、checkpoint 与恢复决策；须先完成切片计划和独立审核

本文件只记录当前里程碑状态，不替代源码、Git、测试报告、独立审核或现场证据。

## 2. 仓库与版本门禁

- 重构仓绝对路径：`D:\LeYe\cl-sausage-controller-refactor`
- 当前分支：`develop`
- 已审核merge里程碑 M：`92eeb8b1252908c3de8dab34814126517d7e308f`
- M 第一父：`812abf5af5f7e8b60bbbc445250e718576f6c70f`
- M 第二父：`5e0d2f78d21fa3e3529c04da2cb9edd6c68a61cb`
- 状态收口提交成功后：fix分支HEAD为状态提交 S，且 `S^=M`；S的精确SHA以提交后的当前Git为准，不在S自身内容中自引用
- 已审核状态收口提交 S：`49e25177f8ee203a9d44528d80dd032db1801368`，且 `S^=M`
- 本次集成状态提交成功后：fix分支HEAD为T，且 `T^=S`；T的精确SHA以提交后的当前Git为准，不在T自身内容中自引用
- 本地 `develop` 快进成功后：`develop=T`
- 本地fix清理成功后：`fix/baseline-2.5.48-sync` 不再存在
- 本地 `develop`：`51d4150dddbda0ffab7e0d0eac520c3fc5910b9a`
- 本地 `origin/develop`：`51d4150dddbda0ffab7e0d0eac520c3fc5910b9a`
- 推送后实时 GitHub `develop`：`51d4150dddbda0ffab7e0d0eac520c3fc5910b9a`
- 本地 `production/develop`：`5e0d2f78d21fa3e3529c04da2cb9edd6c68a61cb`
- 当前 `MERGE_HEAD`：不存在，merge已形成提交
- 当前源码版本：`2.5.48/298`
- 切片起点：文档里程碑 D，工作树干净
- 本文件审核时：相对S仅 `CURRENT_STATUS.md` 一个修改路径
- T提交、`develop`快进和fix清理成功后：当前分支为 `develop`，工作树应保持干净

生产仓 `D:\LeYe\cl-sausage-controller` 只读，且存在用户未提交修改；禁止覆盖、回滚或混入这些修改。

## 3. 目标基线与审计锚点

- 共同基点 A：`fad00d530bb88c0fb3ace82dcdda038f24626438`，`2.5.42/292`。
- 生产目标基线 P：`5e0d2f78d21fa3e3529c04da2cb9edd6c68a61cb`，`2.5.48/298`。
- 旧重构里程碑 R：`8b81a47d1b94171d25095c875c62f4a56ceb1535`，`2.5.42/292`。
- 文档里程碑 D：`812abf5af5f7e8b60bbbc445250e718576f6c70f`，第一父为 R，仅增加两份权威文档。

P 已作为第二父合入本地merge commit M，并成为M、S及后续T的祖先。本地与 GitHub `develop` 均已到达T。

只读基线差异审计结果：A→P 共 32 个提交、90 个终点差异路径；A→R 共 3 个重构提交、168 条变更记录。

## 4. 当前唯一切片

2.5.48 基线同步代码、状态提交、本地 `develop` 集成和远端推送均已完成。本次远端状态收口只允许修改 `docs/refactor/CURRENT_STATUS.md`；状态收口提交的精确 SHA 以提交后的 Git 为准，不在提交自身内容中自引用。

相对自动 merge 基准只人工修改 6 个已审核路径：3 个冲突文件、1 个 P 公式与 core 差分测试、2 个过期 R-only source gate。`REFRACTOR_CHARTER.md` 未修改。本次仍不发布、不部署、不操作设备。

## 5. 已完成里程碑与当前模块层级

- `scheduler-core`：保留模块和 App 编译依赖；在 P 上重跑 58 项测试。高转低保留 P 的 durable 安全 arm/disarm 门禁，并以已做差分证明的 core 策略作为唯一 live 超时公式。
- `recovery-core`：保留纯决策、App 编译依赖和真实接线；在 P 邻接调用链上重跑 36 项测试。Android、MQTT、Modbus 副作用仍由 App 承担。
- `order-core`：保留纯模型和 34 项测试；App 仍没有编译依赖和运行入口，不拥有生产订单执行权。
- 文档地基提交 D 已随 `develop` 推送到 GitHub。
- 2.5.42→2.5.48 基线差异审计已完成，并经“重构上位机审核会话A”修订后最终 `Approve`。
- 2.5.48 merge代码、测试、构建、提交前最终diff及fresh APK证据均已由“重构上位机审核会话A”审核 `Approve`。
- 本地merge commit `92eeb8b` 已建立并验证父提交顺序。
- 状态收口提交 S=`49e2517` 已建立并验证 `S^=M`；本地和 GitHub `develop` 已到T=`51d4150`。

审计过程中已纠正两项关键口径：

- 当前 4F 自清洁由常量策略强制 no-op，不会下发旧 200 自清洁命令；遗留 `moveStatus/finally` 分支当前不可达。
- 正式 OUT 的 `out2..out5` 没有耐久逐步 checkpoint；runner 的进程内成功不能替代崩溃恢复证据。

## 6. 证据分栏

- 源码与 Git：M=`92eeb8b`，M第一父=D、第二父=P；S=`49e2517` 且 `S^=M`；T=`51d4150` 且 `T^=S`。本地和 GitHub `develop` 均为T，生产远端 `develop` 仍为P。MqttProtocol、VMModbus、AtomicActionCore 和 ProtocolStatusPolicies blob 与 P 一致。
- JVM 测试：最终强制运行 `:scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks`，并运行聚合 `test --rerun-tasks`。最终 XML 为 scheduler 58、recovery 36、order 34、App Debug 1851、App Release 1851，failures/errors/skipped 均为 0。
- 构建：提交前 fresh 强制命令包含 `:app:assembleDebug --rerun-tasks`，139 个 actionable tasks 全部 executed。最终 Debug APK 为 49,696,377 bytes，SHA-256=`4B6EC5BC90AAC57F6E1CAF3858B5B6543D218442B7C76C966D27504EF1D84A4C`。APK 每次重打包的字节结果不作为可复现构建保证，仅记录本次最终制品。
- 本地集成后验证：`develop` 快进到T后聚合 `test --rerun-tasks` 成功，200 个任务全部执行；本地fix分支已删除。
- 推送前复验：`2026-08-20` 再次运行聚合 `test --rerun-tasks`，`BUILD SUCCESSFUL in 3m 49s`，200 个任务全部执行；随后以非强制方式将 GitHub `develop` 从R推进到T，并核对远端SHA等于本地SHA。
- 测试 MQTT/HTTP/后端：未执行；本地 JVM 测试未连接真实 broker 或后端。
- 真实 RS485/Modbus/机械/断电/温控/长稳：未验证。
- 独立审核：基线差异审计、文档地基、merge实施审核、最终diff终审、fresh APK证据和状态提交S均已 `Approve`。本次本地集成状态只在审核会话A `Approve` 后允许提交T；审核和集成后验证结论保留在会话证据中，不在T内再次回填。

## 7. 当前架构阻断与风险

1. P 的 32 个提交语义已进入 merge commit，并随本地 `develop` 推送到 GitHub；纯 `mechanical-core` 仍未开始，必须先完成下一切片计划及审核。
2. 当前仍有 OrderDeliveryClaim、ActionGroupSession、SupplementPrefetchPipelineSession、SupplementBatchIntent、PanMoveJournal、DiscardJournal、ResetCloseoutRecord、ResourceLocks 和 `moveStatus` 等多套权威。
3. 搬盘结果不明时会保留 journal 与 `moveStatus=3`，但 `finally` 释放 ResourceLocks，资源状态与恢复责任分裂。
4. callback 和资源释放缺少统一的 `operationId + revision` 围栏。
5. OUT 的 `out2..out5` 缺少耐久逐步 checkpoint，进程崩溃或 `out5` 超时后不能精确映射到固定状态机。

上述项目是后续架构阻断，尚未修复，不得写成已完成。

## 8. 权限边界

用户已授权主任务在既定目标、单切片、白名单、验证和审核门禁内持续实施，并已授权本次提交与推送，不再要求每一步重复确认。该授权不包含发布、部署、真实 MQTT、RS485/Modbus、机械或设备操作，也不允许触碰生产仓用户未提交修改。

## 9. 下一切片门禁

本状态收口提交并推送后，下一唯一切片为纯 `mechanical-core` 地基。开始实施前必须先形成一份切片计划，明确目标、非目标、白名单、不变量、故障注入测试、验收和逐文件回滚边界，并经审核会话A只读审核及主会话复核。计划审核完成前不修改生产代码。

## 10. 更新纪律

本文件只在已验证里程碑开始或结束时更新。每小时巡检只检查报警，不自动写本文件。这里记录的状态必须由当前 Git、验证输出和审核证据重新核对，不能单独作为完成证明。
