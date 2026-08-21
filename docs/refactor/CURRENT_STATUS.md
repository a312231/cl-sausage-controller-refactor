# 安卓上位机渐进重构当前状态

## 1. 状态元数据

- 更新时间（Asia/Singapore）：`2026-08-21 15:53:23 +08:00`
- 切片状态：`BASELINE_2_5_51_SYNC_PLAN_APPROVED`（以本文件和同步计划最终cached diff经审核会话A `Approve` 为生效条件）
- 增量审计：`Approve审计结论 / Reject继续旧基线实现`；同步计划技术问题已闭环，最终cached diff以审核会话A `Approve` 为批准生效门禁
- 当前唯一切片：2.5.48→2.5.51生产基线同步与partial core安全保全
- 下一唯一切片：未激活；同步完成后才允许2.5.51 mechanical-core重新基线化

本文件只记录当前里程碑状态，不替代源码、Git、测试报告、独立审核或现场证据。

## 2. 仓库与版本门禁

- 重构仓绝对路径：`D:\LeYe\cl-sausage-controller-refactor`
- 当前分支：`fix/mechanical-core-foundation`
- 当前 HEAD：`f96b08594cff8bfd31998b7413e3120eba426ae3`
- 当前分支远端：`origin/fix/mechanical-core-foundation=f96b08594cff8bfd31998b7413e3120eba426ae3`
- 已审核merge里程碑 M：`92eeb8b1252908c3de8dab34814126517d7e308f`
- M 第一父：`812abf5af5f7e8b60bbbc445250e718576f6c70f`
- M 第二父：`5e0d2f78d21fa3e3529c04da2cb9edd6c68a61cb`
- 状态收口提交成功后：fix分支HEAD为状态提交 S，且 `S^=M`；S的精确SHA以提交后的当前Git为准，不在S自身内容中自引用
- 已审核状态收口提交 S：`49e25177f8ee203a9d44528d80dd032db1801368`，且 `S^=M`
- 本次集成状态提交成功后：fix分支HEAD为T，且 `T^=S`；T的精确SHA以提交后的当前Git为准，不在T自身内容中自引用
- 本地 `develop` 快进成功后：`develop=T`
- 本地fix清理成功后：`fix/baseline-2.5.48-sync` 不再存在
- 本地 `develop`：`49f9ce431b38f35d561ab8098d0a118fb556c439`
- 本地 `origin/develop`：`49f9ce431b38f35d561ab8098d0a118fb556c439`
- 实时 GitHub `develop`：`49f9ce431b38f35d561ab8098d0a118fb556c439`
- 重构仓本地 `production/develop` 跟踪引用（尚未fetch Q）：`5e0d2f78d21fa3e3529c04da2cb9edd6c68a61cb`
- 实时生产 Android `develop`：`2f0b36e08cedb8f4c06cd4d2550e2cec08558bd7`（`2.5.51/301`）
- 当前 `MERGE_HEAD`：不存在，尚未开始Q merge
- 当前重构仓源码版本：`2.5.48/298`（尚未同步Q）
- 当前同步计划起点：`f96b085`；其下partial core工作树尚未保全
- 当前partial core现场：`settings.gradle.kts`已修改；`mechanical-core`模块构建文件、5个main源码和5个测试未跟踪；Coordinator测试存在但Coordinator实现不存在。
- 当前文档修改：章程、状态台账、原mechanical-core计划暂停标记和本次2.5.51同步计划。

生产仓 `D:\LeYe\cl-sausage-controller` 只读，且存在用户未提交修改；禁止覆盖、回滚或混入这些修改。

## 3. 目标基线与审计锚点

- 共同基点 A：`fad00d530bb88c0fb3ace82dcdda038f24626438`，`2.5.42/292`。
- 生产目标基线 P：`5e0d2f78d21fa3e3529c04da2cb9edd6c68a61cb`，`2.5.48/298`。
- 当前生产同步目标 Q：`2f0b36e08cedb8f4c06cd4d2550e2cec08558bd7`，`2.5.51/301`。P是Q祖先，P→Q恰3提交、37路径。
- 旧重构里程碑 R：`8b81a47d1b94171d25095c875c62f4a56ceb1535`，`2.5.42/292`。
- 文档里程碑 D：`812abf5af5f7e8b60bbbc445250e718576f6c70f`，第一父为 R，仅增加两份权威文档。

P 已作为第二父合入本地merge commit M，并成为M、S及后续T的祖先。本地与 GitHub `develop` 均已到达T。

只读基线差异审计结果：A→P 共 32 个提交、90 个终点差异路径；A→R 共 3 个重构提交、168 条变更记录。

## 4. 当前唯一切片

生产 Android 已由P前进到Q，旧mechanical-core计划暂停。当前只允许执行 `docs/refactor/2026-08-21-baseline-2.5.51-sync-plan.md` 的文档审核和安全保全门禁；在计划审核通过前，不fetch、不merge、不切换分支、不提交partial core。

partial core原样保留：模型、状态机、资源、仲裁、恢复策略已有定向GREEN历史证据；Coordinator测试为RED且实现不存在。本状态不把它写成完成或可编译里程碑。

## 5. 已完成里程碑与当前模块层级

- `scheduler-core`：保留模块和 App 编译依赖；在 P 上重跑 58 项测试。高转低保留 P 的 durable 安全 arm/disarm 门禁，并以已做差分证明的 core 策略作为唯一 live 超时公式。
- `recovery-core`：保留纯决策、App 编译依赖和真实接线；在 P 邻接调用链上重跑 36 项测试。Android、MQTT、Modbus 副作用仍由 App 承担。
- `order-core`：保留纯模型和 34 项测试；App 仍没有编译依赖和运行入口，不拥有生产订单执行权。
- 文档地基提交 D 已随 `develop` 推送到 GitHub。
- 2.5.42→2.5.48 基线差异审计已完成，并经“重构上位机审核会话A”修订后最终 `Approve`。
- 2.5.48 merge代码、测试、构建、提交前最终diff及fresh APK证据均已由“重构上位机审核会话A”审核 `Approve`。
- 本地merge commit `92eeb8b` 已建立并验证父提交顺序。
- 状态收口提交 S=`49e2517` 已建立并验证 `S^=M`；本地和 GitHub `develop` 已到T=`51d4150`。
- 远端集成状态提交 `49f9ce4` 已推送，本地与 GitHub `develop` 均为 `49f9ce4`。
- `mechanical-core` 地基计划曾审核 `Approve`，现因Q基线变化暂停；已有partial源码但未提交、未完成。
- P→Q只读增量审计完成：2.5.49动作恢复门禁、2.5.50 out4终态单调、2.5.51派单ACK均与机械重构热区直接相关。

审计过程中已纠正两项关键口径：

- 当前 4F 自清洁由常量策略强制 no-op，不会下发旧 200 自清洁命令；遗留 `moveStatus/finally` 分支当前不可达。
- 正式 OUT 的 `out2..out5` 没有耐久逐步 checkpoint；runner 的进程内成功不能替代崩溃恢复证据。

## 6. 证据分栏

- 源码与 Git：重构fix=`f96b085`、重构GitHub develop=`49f9ce4`、生产Android live develop=Q=`2f0b36e0`。Q尚未导入重构仓；生产仓保持只读。
- 历史P基线 JVM 测试：曾强制运行 `:scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks` 和聚合 `test --rerun-tasks`；XML 为 scheduler 58、recovery 36、order 34、App Debug 1851、App Release 1851，failures/errors/skipped 均为 0。该证据不证明当前partial core或Q同步。
- 历史P基线构建：曾fresh运行 `:app:assembleDebug --rerun-tasks`，139个任务全部执行；Debug APK 49,696,377 bytes，SHA-256=`4B6EC5BC90AAC57F6E1CAF3858B5B6543D218442B7C76C966D27504EF1D84A4C`。该制品不证明Q、当前partial core或现场运行。
- 历史P本地集成：`develop` 快进到T后聚合测试曾成功，200个任务全部执行；旧fix分支已删除。该证据不证明Q同步。
- 历史P推送前复验：`2026-08-20` 聚合测试曾 `BUILD SUCCESSFUL in 3m 49s`，200个任务全部执行，并核对当时GitHub SHA。该证据不证明当前Q同步。
- 测试 MQTT/HTTP/后端：未执行；本地 JVM 测试未连接真实 broker 或后端。
- 真实 RS485/Modbus/机械/断电/温控/长稳：未验证。
- 独立审核：P→Q增量审计确认主会话A/B/C/D判断成立并`Reject`继续旧Coordinator实现；同步计划技术问题已闭环。本文件最终cached diff一旦获`Approve`，计划批准状态生效，下一动作仅为partial core独立分支保全。

## 7. 当前架构阻断与风险

1. 当前生产基线已从P前进到Q；重构仓仍是2.5.48，禁止继续宣称已对齐当前生产。
2. 当前仍有 OrderDeliveryClaim、ActionGroupSession、SupplementPrefetchPipelineSession、SupplementBatchIntent、PanMoveJournal、DiscardJournal、ResetCloseoutRecord、ResourceLocks 和 `moveStatus` 等多套权威。
3. 搬盘结果不明时会保留 journal 与 `moveStatus=3`，但 `finally` 释放 ResourceLocks，资源状态与恢复责任分裂。
4. callback 和资源释放缺少统一的 `operationId + revision` 围栏。
5. OUT 的 `out2..out5` 缺少耐久逐步 checkpoint，进程崩溃或 `out5` 超时后不能精确映射到固定状态机。
6. 当前partial core没有out4成功终态单调门禁；需在Q同步后的下一切片补充。
7. V2 Inbox与MechanicalOperation跨SharedPreferences耐久交接尚未设计；属于同步后的独立adapter问题，不塞入pure core协议。

上述项目是后续架构阻断，尚未修复，不得写成已完成。

## 8. 权限边界

用户已授权主任务在既定目标、单切片、白名单、验证和审核门禁内持续实施，并已授权本次提交与推送，不再要求每一步重复确认。该授权不包含发布、部署、真实 MQTT、RS485/Modbus、机械或设备操作，也不允许触碰生产仓用户未提交修改。

## 9. 下一切片门禁

当前只允许先审核并执行 `2026-08-21-baseline-2.5.51-sync-plan.md`：文档提交→partial core独立远端分支保全→干净fix导入Q→自动化/构建/审核→合入develop。计划审核前不执行Git保全或同步动作。Q同步完成后再新建2.5.51 mechanical-core重新基线化切片。

## 10. 更新纪律

本文件只在已验证里程碑开始或结束时更新。每小时巡检只检查报警，不自动写本文件。这里记录的状态必须由当前 Git、验证输出和审核证据重新核对，不能单独作为完成证明。
