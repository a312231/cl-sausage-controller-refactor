# 安卓上位机渐进重构当前状态

## 1. 状态元数据

- 更新时间（Asia/Singapore）：`2026-08-21 16:32:36 +08:00`
- 切片状态：`MECHANICAL_CORE_2_5_51_REBASELINE_PLAN_APPROVED`（以本文件和新计划最终cached diff经审核会话A`Approve`为生效条件）
- 前一同步里程碑：`BASELINE_2_5_51_SYNC_COMPLETED`，develop/GitHub=`b41f8dab6176d3856d6b8cfc8a7ccee40b16866f`
- 当前唯一切片：2.5.51 mechanical-core重新基线化
- 下一唯一切片：未激活；审核阶段不应用partial WIP，W成功并push后进入implementation阶段

本文件只记录当前里程碑状态，不替代源码、Git、测试报告、独立审核或现场证据。

## 2. 仓库与版本门禁

- 重构仓绝对路径：`D:\LeYe\cl-sausage-controller-refactor`
- 当前分支：`fix/mechanical-core-foundation-2.5.51`
- W提交前当前 HEAD / 新切片起点：`b41f8dab6176d3856d6b8cfc8a7ccee40b16866f`
- 本计划状态提交成功后：新fix HEAD为W且`W^=b41f8da`；W精确SHA以提交后的Git为准，不在W自身内容中自引用
- W普通push成功前，新fix没有远端分支
- 已审核Q同步merge commit：`676bf0d2a1bd37ad4bec6c26fa97ed09500915ed`
- Q同步merge第一父：`524027e34d61fcacbfde5bac3200ef0329e2f423`
- Q同步merge第二父：`2f0b36e08cedb8f4c06cd4d2550e2cec08558bd7`
- 本状态提交成功后：fix分支HEAD为U，且`U^=676bf0d`；U精确SHA以提交后的Git为准，不在U自身内容中自引用
- 已提交状态收口U：`0a065ea832909b1c42e15ee82311ca47edb6c020`，且`U^=676bf0d`
- 本次最终状态提交成功后：develop HEAD为V，且`V^=U`；V精确SHA以提交后的Git为准，不在V自身内容中自引用
- 已完成最终状态提交V：`b41f8dab6176d3856d6b8cfc8a7ccee40b16866f`，且`V^=U`
- 已合入的原`fix/mechanical-core-foundation`本地/远端均已删除
- 已审核merge里程碑 M：`92eeb8b1252908c3de8dab34814126517d7e308f`
- M 第一父：`812abf5af5f7e8b60bbbc445250e718576f6c70f`
- M 第二父：`5e0d2f78d21fa3e3529c04da2cb9edd6c68a61cb`
- 状态收口提交成功后：fix分支HEAD为状态提交 S，且 `S^=M`；S的精确SHA以提交后的当前Git为准，不在S自身内容中自引用
- 已审核状态收口提交 S：`49e25177f8ee203a9d44528d80dd032db1801368`，且 `S^=M`
- 本次集成状态提交成功后：fix分支HEAD为T，且 `T^=S`；T的精确SHA以提交后的当前Git为准，不在T自身内容中自引用
- 本地 `develop` 快进成功后：`develop=T`
- 本地fix清理成功后：`fix/baseline-2.5.48-sync` 不再存在
- 本地 `develop`：`b41f8dab6176d3856d6b8cfc8a7ccee40b16866f`
- 本地 `origin/develop`：`b41f8dab6176d3856d6b8cfc8a7ccee40b16866f`
- 实时 GitHub `develop`：`b41f8dab6176d3856d6b8cfc8a7ccee40b16866f`
- 重构仓本地 `production/develop`：`2f0b36e08cedb8f4c06cd4d2550e2cec08558bd7`
- 实时生产 Android `develop`：`2f0b36e08cedb8f4c06cd4d2550e2cec08558bd7`（`2.5.51/301`）
- 当前 `MERGE_HEAD`：不存在，merge已形成提交
- 当前重构仓源码版本：`2.5.51/301`
- partial core保全分支本地/远端SHA：`3d5918aa19944b6b4765532f2977c5db43253a3d`；第一父=`524027e`
- partial core仍为WIP/RED：Coordinator测试存在、Coordinator实现不存在；未混入Q同步merge。
- 本文件审核时cached只允许本次新计划和`CURRENT_STATUS.md`；W提交后工作树应干净，尚未cherry-pick WIP。

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

Q同步里程碑已完成并进入本地/远端develop。新计划最终cached diff获`Approve`并形成W前，不cherry-pick保全WIP、不创建core修改；W成功并push后，下一动作仅为`cherry-pick --no-commit`恢复partial现场。

partial core已独立远端保全，不在本同步里程碑中；旧mechanical-core计划继续暂停。

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
- Q同步merge commit `676bf0d` 已建立并验证第一父=`524027e`、第二父=Q。
- scheduler/recovery/order core成果已在Q邻接调用链重新验证；order-core仍未接入App生产入口。

审计过程中已纠正两项关键口径：

- 当前 4F 自清洁由常量策略强制 no-op，不会下发旧 200 自清洁命令；遗留 `moveStatus/finally` 分支当前不可达。
- 正式 OUT 的 `out2..out5` 没有耐久逐步 checkpoint；runner 的进程内成功不能替代崩溃恢复证据。

## 6. 证据分栏

- 源码与 Git：Q merge=`676bf0d`且Q为第二父；本地/GitHub develop=V=`b41f8da`，原fix已删除。W push前新fix从V创建且无远端；W成功后新fix=`W`、`W^=V`且远端SHA应等于W。生产Android live develop=Q。生产仓保持只读。
- Q同步定向JVM：`BUILD SUCCESSFUL in 3m 2s`，94 tasks全部executed。
- Q同步聚合JVM：`BUILD SUCCESSFUL in 2m 9s`，200 tasks全部executed；fresh XML scheduler58、recovery36、order34、App Debug1930、App Release1930，failures/errors/skipped均为0。
- Q同步Debug构建：`BUILD SUCCESSFUL in 1m 15s`，122 tasks全部executed；APK 49,745,545 bytes，SHA-256=`1697AF5E11D9EDE011603BAAE83D1B6ED952D8D8D14C0BE6FE2DC4C2EB0600D3`。
- develop集成后聚合JVM：`BUILD SUCCESSFUL in 2m 23s`，200 tasks全部executed。
- 历史P基线 JVM 测试：曾强制运行 `:scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks` 和聚合 `test --rerun-tasks`；XML 为 scheduler 58、recovery 36、order 34、App Debug 1851、App Release 1851，failures/errors/skipped 均为 0。该证据不证明当前partial core或Q同步。
- 历史P基线构建：曾fresh运行 `:app:assembleDebug --rerun-tasks`，139个任务全部执行；Debug APK 49,696,377 bytes，SHA-256=`4B6EC5BC90AAC57F6E1CAF3858B5B6543D218442B7C76C966D27504EF1D84A4C`。该制品不证明Q、当前partial core或现场运行。
- 历史P本地集成：`develop` 快进到T后聚合测试曾成功，200个任务全部执行；旧fix分支已删除。该证据不证明Q同步。
- 历史P推送前复验：`2026-08-20` 聚合测试曾 `BUILD SUCCESSFUL in 3m 49s`，200个任务全部执行，并核对当时GitHub SHA。该证据不证明当前Q同步。
- 测试 MQTT/HTTP/后端：未执行；本地 JVM 测试未连接真实 broker 或后端。
- 真实 RS485/Modbus/机械/断电/温控/长稳：未验证。
- 独立审核：前一Q同步的增量审计、计划、WIP保全、merge diff和fresh验证均已`Approve`；本次rebaseline计划技术问题已闭环，最终文档diff以审核会话A`Approve`为W批准生效门禁。

## 7. 当前架构阻断与风险

1. Q同步已完成；当前风险转为partial core重新应用后必须补out4不可逆effect单调性，并重新验证全部TDD。
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

本文件审核阶段只允许审核并提交W。W成功、工作树干净且普通push核对SHA后，才允许核对并执行`cherry-pick --no-commit 3d5918a`，先完成Coordinator RED/GREEN，再补out4单调性。partial保全分支在新里程碑完成前继续保留。

## 10. 更新纪律

本文件只在已验证里程碑开始或结束时更新。每小时巡检只检查报警，不自动写本文件。这里记录的状态必须由当前 Git、验证输出和审核证据重新核对，不能单独作为完成证明。
