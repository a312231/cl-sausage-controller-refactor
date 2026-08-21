# Mechanical Core 2.5.51 Rebaseline Verification

## 1. 范围与基线

- 仓库：`D:\LeYe\cl-sausage-controller-refactor`
- 实施分支：`fix/mechanical-core-foundation-2.5.51`
- 已审核计划提交 W：`dee797ec26b22e719969b7ec25eed47a1b21564a`
- W 第一父 / 起点 develop：`b41f8dab6176d3856d6b8cfc8a7ccee40b16866f`
- 生产同步目标 Q：`2f0b36e08cedb8f4c06cd4d2550e2cec08558bd7`，源码 `2.5.51/301`
- 本记录覆盖 pure `mechanical-core`、模块注册、测试和状态台账；App 未接入该模块。

## 2. TDD证据

- 恢复 partial 后：`:mechanical-core:compileKotlin` 成功；`:mechanical-core:compileTestKotlin` 仅因 Coordinator API 缺失而失败。
- 创建无行为 API 壳后测试可编译，`MechanicalOperationCoordinatorTest` 11 个场景中 10 个按预期失败；实现 boot gate、Ledger CAS、迟到证据、effect 幂等及启动恢复后转绿。
- `appliedEffectKeys` 测试先因字段不存在而 RED；模型、不变量和 `markEffectApplied` 实现后转绿。
- 正常生命周期、下一 step、安全暂停/续作和超时入口测试先因 Coordinator 方法不存在而 RED；实现后 `MechanicalOperationCoordinatorTest` 转绿。
- 终审初轮发现的失败/不明结果gate、过早effect、RUNNING游标、typed emergency、跨owner authorization、phase字段组合和typed stale operation均先补失败测试，再作最小实现转绿。
- 关键覆盖：固定状态机、operation/revision/attempt 围栏、单 running、paused 不占资源、订单打断补肠原子 handoff、失败/不明结果同CAS关闭gate、超时/取消不释放、迟到/重复回调、effect 外部成功而 Ledger CAS 失败、双稳定快照、跨 boot proof/cursor、旧许可只能由新proof替换、防重恢复、逐 CAS 安全收口，以及 out4 effect 在 out5 FAILED/UNKNOWN/取消/超时后保持单调。

## 3. 源码与纯度门禁

- 最终候选只包含白名单中的 13 个模块/源码/测试路径，加本验证记录和 `CURRENT_STATUS.md`；未修改 App 生产代码。
- `app/build.gradle.kts` 无 `mechanical-core` 依赖，`app/src` 无 `MechanicalOperationCoordinator` 或 mechanical 包引用。
- `mechanical-core/build.gradle.kts` 仅 Kotlin/JVM 与 JUnit 测试依赖，目标 Java 11 bytecode。
- main 源码无 Android、MQTT、Modbus实现、协程、文件、系统时钟依赖；`modbusConnected` 只是恢复决策的布尔证据入参。
- `mechanical-core.jar` 为 156,318 bytes；条目仅含 `cn.niuyannet.kaochang.mechanical` 与 Kotlin module 元数据。
- `jdeps --multi-release 11 --ignore-missing-deps -summary mechanical-core.jar` 只报告 `java.base` 与未随 jar 打包的 Kotlin stdlib，不含 Android/业务模块依赖。
- `git diff --check` 与最终 cached diff 必须为 0，且所有路径必须重新核对白名单后才能提交。

## 4. 新鲜自动化验证

执行时间：`2026-08-21`，Asia/Singapore。

1. `./gradlew.bat :mechanical-core:test :mechanical-core:jar --rerun-tasks`
   - `BUILD SUCCESSFUL in 4s`
   - 4 tasks executed
   - fresh XML：61 tests，failures/errors/skipped 均为 0
2. `./gradlew.bat test --rerun-tasks`
   - `BUILD SUCCESSFUL in 2m 13s`
   - 203 actionable tasks，203 executed
   - fresh XML：scheduler 58、recovery 36、order 34、mechanical 61、App Debug 1930、App Release 1930；failures/errors/skipped 均为 0
3. `./gradlew.bat :app:assembleDebug --rerun-tasks`
   - `BUILD SUCCESSFUL in 57s`
   - 122 actionable tasks，122 executed
   - APK：`app/build/outputs/apk/debug/app-debug.apk`
   - 大小：49,745,545 bytes
   - SHA-256：`C83321837C23DD6BDF46309384217E78F604E6AC477E8D49FB4EBF9EB5CD1182`
   - mtime：`2026-08-21T17:31:48.9076786+08:00`

构建仍有既有 32 位 native、旧 Manifest package 和 deprecated API 警告；本切片没有修改这些区域，且没有把警告记成成功证据。

## 5. 证据边界

- 已验证：当前源码、纯 JVM 状态机/资源/仲裁/恢复/Coordinator 单元测试、全仓 JVM 回归、Debug 构建与本地 APK 哈希。
- 未执行：测试 MQTT、测试后端、后端部署、SQL/Redis、支付/退款联调。
- 未验证：真实 RS485/Modbus、下位机寄存器、机械动作、传感器、断电、温控、长稳及生产设备所安装 APK。
- 本切片没有接入 App，不能声称已替换旧 session/claim/resource owner/`moveStatus`，也不能声称已解决生产机械竞态。

## 6. 审核、提交与回滚门禁

- 完整 diff、测试、jar 纯度、out4 单调性及证据边界须经审核会话 A 独立只读审核；P0/P1 未清零立即停止。
- 审核通过后才可精确暂存 15 个白名单路径并形成单一绿色里程碑 F；`F^=W`，F 精确 SHA 以提交后 Git 为准，不在提交自身内容中自引用。
- F 普通 push、远端 SHA/tree/blob 核对、本地 develop `--ff-only`、集成后复验和 develop 普通 push 均成功后，才算本切片完成。
- 回滚边界为单次 revert F；因为 App 未依赖该模块，回滚会同时移除模块注册、pure core、测试和本记录。
- 发布、部署、真实 MQTT、RS485/Modbus、机械和设备操作均不在授权范围。
