# 安卓上位机渐进重构当前状态

## 1. 状态元数据

- 更新时间（Asia/Singapore）：`2026-08-19 19:37:31 +08:00`
- 切片状态：`READY_FOR_COMMIT_AUTHORIZATION`（以本文件及最终完整 merge diff 终审 `Approve` 为生效条件）
- 实施审核：`Approve`（重构上位机审核会话A，未提交 merge 代码、测试与构建审核）
- 当前唯一切片：2.5.48 基线同步切片
- 下一切片：未激活

本文件只记录当前里程碑状态，不替代源码、Git、测试报告、独立审核或现场证据。

## 2. 仓库与版本门禁

- 重构仓绝对路径：`D:\LeYe\cl-sausage-controller-refactor`
- 当前分支：`fix/baseline-2.5.48-sync`
- 当前 HEAD：`812abf5af5f7e8b60bbbc445250e718576f6c70f`
- 本地 `develop`：`812abf5af5f7e8b60bbbc445250e718576f6c70f`
- 本地 `origin/develop`：`8b81a47d1b94171d25095c875c62f4a56ceb1535`
- 实时 GitHub `develop`：`8b81a47d1b94171d25095c875c62f4a56ceb1535`
- 本地 `production/develop`：`5e0d2f78d21fa3e3529c04da2cb9edd6c68a61cb`
- 当前 `MERGE_HEAD`：`5e0d2f78d21fa3e3529c04da2cb9edd6c68a61cb`
- 当前工作树源码版本：`2.5.48/298`
- 切片起点：文档里程碑 D，工作树干净
- 当前工作树：相对 D 有 93 个 staged 路径，unmerged、unstaged、untracked 均为 0

生产仓 `D:\LeYe\cl-sausage-controller` 只读，且存在用户未提交修改；禁止覆盖、回滚或混入这些修改。

## 3. 目标基线与审计锚点

- 共同基点 A：`fad00d530bb88c0fb3ace82dcdda038f24626438`，`2.5.42/292`。
- 生产目标基线 P：`5e0d2f78d21fa3e3529c04da2cb9edd6c68a61cb`，`2.5.48/298`。
- 旧重构里程碑 R：`8b81a47d1b94171d25095c875c62f4a56ceb1535`，`2.5.42/292`。
- 文档里程碑 D：`812abf5af5f7e8b60bbbc445250e718576f6c70f`，第一父为 R，仅增加两份权威文档。

P 已 fetch 并以 `merge --no-ff --no-commit` 合入当前 index/worktree，但尚未形成 merge commit。当前 HEAD 仍为 D，P 目前不是 HEAD 的祖先；不得把未提交 merge 写成已提交或已集成。

只读基线差异审计结果：A→P 共 32 个提交、90 个终点差异路径；A→R 共 3 个重构提交、168 条变更记录。

## 4. 当前唯一切片

2.5.48 基线同步切片是代码同步切片。确定性 merge 允许集合为 A→P 与 A→R 旧/新端点并集，加 `CURRENT_STATUS.md`，共 243 个路径；当前相对 D 实际变化 93 个 staged 路径。

相对自动 merge 基准只人工修改 6 个已审核路径：3 个冲突文件、1 个 P 公式与 core 差分测试、2 个过期 R-only source gate。`REFRACTOR_CHARTER.md` 未修改。本切片未提交 merge、未推送、未发布、未部署、未操作设备。

## 5. 已完成里程碑与当前模块层级

- `scheduler-core`：保留模块和 App 编译依赖；在 P 上重跑 58 项测试。高转低保留 P 的 durable 安全 arm/disarm 门禁，并以已做差分证明的 core 策略作为唯一 live 超时公式。
- `recovery-core`：保留纯决策、App 编译依赖和真实接线；在 P 邻接调用链上重跑 36 项测试。Android、MQTT、Modbus 副作用仍由 App 承担。
- `order-core`：保留纯模型和 34 项测试；App 仍没有编译依赖和运行入口，不拥有生产订单执行权。
- 文档地基已形成独立本地提交 D，未推送。
- 2.5.42→2.5.48 基线差异审计已完成，并经“重构上位机审核会话A”修订后最终 `Approve`。
- 2.5.48 未提交 merge 的代码、测试和构建已由“重构上位机审核会话A”审核 `Approve`；最终状态回填后的完整 diff 仍待终审。

审计过程中已纠正两项关键口径：

- 当前 4F 自清洁由常量策略强制 no-op，不会下发旧 200 自清洁命令；遗留 `moveStatus/finally` 分支当前不可达。
- 正式 OUT 的 `out2..out5` 没有耐久逐步 checkpoint；runner 的进程内成功不能替代崩溃恢复证据。

## 6. 证据分栏

- 源码与 Git：HEAD=D、MERGE_HEAD=P；无 unmerged 或冲突标记；相对自动 merge 的 6 个人工变化均在审核白名单内。MqttProtocol、VMModbus、AtomicActionCore 和 ProtocolStatusPolicies blob 与 P 一致。
- JVM 测试：最终强制运行 `:scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks`，并运行聚合 `test --rerun-tasks`。最终 XML 为 scheduler 58、recovery 36、order 34、App Debug 1851、App Release 1851，failures/errors/skipped 均为 0。
- 构建：提交前 fresh 强制命令包含 `:app:assembleDebug --rerun-tasks`，139 个 actionable tasks 全部 executed。最终 Debug APK 为 49,696,377 bytes，SHA-256=`4B6EC5BC90AAC57F6E1CAF3858B5B6543D218442B7C76C966D27504EF1D84A4C`。APK 每次重打包的字节结果不作为可复现构建保证，仅记录本次最终制品。
- 测试 MQTT/HTTP/后端：未执行；本地 JVM 测试未连接真实 broker 或后端。
- 真实 RS485/Modbus/机械/断电/温控/长稳：未验证。
- 独立审核：基线差异审计、文档地基和未提交 merge 实施审核均已 `Approve`；本次状态回填后的最终完整 diff 终审待完成。

## 7. 当前架构阻断与风险

1. P 的 32 个提交语义已进入未提交 merge 工作树，但尚未形成提交、集成到本地 `develop` 或推送；不能直接开始机械核心接线。
2. 当前仍有 OrderDeliveryClaim、ActionGroupSession、SupplementPrefetchPipelineSession、SupplementBatchIntent、PanMoveJournal、DiscardJournal、ResetCloseoutRecord、ResourceLocks 和 `moveStatus` 等多套权威。
3. 搬盘结果不明时会保留 journal 与 `moveStatus=3`，但 `finally` 释放 ResourceLocks，资源状态与恢复责任分裂。
4. callback 和资源释放缺少统一的 `operationId + revision` 围栏。
5. OUT 的 `out2..out5` 缺少耐久逐步 checkpoint，进程崩溃或 `out5` 超时后不能精确映射到固定状态机。

上述项目是后续架构阻断，尚未修复，不得写成已完成。

## 8. 权限边界

当前授权已覆盖文档本地提交 D，以及 fetch P、临时 fix 分支、未提交 merge、冲突适配、JVM 测试和 Debug 构建。授权不包含当前 merge 结果提交、集成本地 `develop`、推送、发布、部署或设备操作。

## 9. 下一切片门禁

当前没有激活下一实施切片。只有本文件及最终完整 merge diff 终审 `Approve` 后，才进入 `READY_FOR_COMMIT_AUTHORIZATION` 并向用户申请 merge commit 授权；提交、集成、推送和分支清理仍分别受权限门禁。不能直接实施 `mechanical-core`。

## 10. 更新纪律

本文件只在已验证里程碑开始或结束时更新。每小时巡检只检查报警，不自动写本文件。这里记录的状态必须由当前 Git、验证输出和审核证据重新核对，不能单独作为完成证明。
