# 第三阶段 3A：order-core 验证记录

日期：2026-08-13
上位机源码基线：`fad00d530bb88c0fb3ace82dcdda038f24626438`
当前源码版本：`versionName=2.5.42`、`versionCode=292`
后端只读审计基线：`bfaebdad8ad7553d0d254069aa0cd686eeb6b570`

版本号仅代表当前源码，不代表现场 APK。后端工作树原有一处已修改文档和一处未跟踪文档，本切片未修改、覆盖或回滚。

## 1. 实施范围

3A 只增加：

- `settings.gradle.kts` 中的 `:order-core` 模块声明；
- 纯 Kotlin/JVM `order-core` 模块；
- 订单连续性状态、四轴指令和纯策略；
- 决策矩阵与安全不变量测试；
- 第三阶段方案及本验证记录。

3A 没有：

- 给 App 增加 `order-core` 依赖；
- 修改 App 源码、App 测试、`app/build.gradle.kts`、Manifest 或资源；
- 接线 `MainActivity`、MQTT、HTTP、Modbus、RS485、数据库或 UI；
- 修改后端或下位机协议；
- 增加调度算法 B/C 的生产实现；调度 A/B/C 仍只负责候选选择。

当前工作树中的 App、`scheduler-core` 和 `recovery-core` 差异均来自进入 3A 前已经完成并审核通过的第一、二阶段；本切片未覆盖或回滚它们。

## 2. 关键安全边界

- `OrderLifecycleStage` 只表示整单生命周期；单根 out4 事件不能生成 `WHOLE_ORDER_TERMINAL_DURABLE`；
- 整单终态与 claim 释放资格分开；当前 App 的 `SUCCESS/NO_DISPATCH/MECHANICAL_FAILED` 映射为 `RELEASE_ALLOWED`，`EVIDENCE_UNKNOWN/FAILED/UNKNOWN` 映射为 `HOLD_REQUIRED`；
- 整单 `OrderDeliveryClaim` 与单根 `OUT_SELL` action session/move claim 分开建模；
- `PREPARED_OR_UNKNOWN` 永远只进入单根机械恢复，不能重新下发 out1；
- `NOT_DISPATCHED_CONFIRMED` 要先精确关闭单根 session，再用更新后的快照重新决策；
- 单根事件 ACK 只停止该事件重发，不能压过 out5、单根 session 或整单 claim 收口；
- 多根订单两根之间保留整单 claim；只有整单终态已耐久且单根 session 已无，才允许精确关闭整单 claim；
- 整单 claim 关闭失败重新输入 `FAILED_REQUIRES_REVIEW`，结果必须是 HOLD/诊断并保留 claim；
- 网络断开不撤销已在当前连续性上下文确认的新鲜授权，也不是机械急停信号；
- 未确认、过期或未知授权不能启动新机械出餐。

## 3. 自动化验证

### 3.1 order-core 独立测试

命令：

```powershell
.\gradlew.bat :order-core:test --rerun-tasks --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`。JUnit XML：16 tests、0 failures、0 errors、0 skipped。

穷举测试实际覆盖 10,368 种输入组合：

- 1,188 种合法组合：每次重复求值结果一致，并满足安全不变量；
- 9,180 种矛盾组合：由输入校验明确拒绝。

### 3.2 前序核心与 App 测试

命令：

```powershell
.\gradlew.bat :scheduler-core:test :recovery-core:test :app:testDebugUnitTest --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`。

### 3.3 全量 JVM 测试与 Debug APK 构建

命令：

```powershell
.\gradlew.bat test assembleDebug --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL`。当前全部 JUnit XML 汇总：

- 3,330 tests；
- 0 failures；
- 0 errors；
- 0 skipped。

Debug APK：

- 大小：50,387,175 bytes；
- SHA-256：`ACA767880C9B1C38D6E11A8F06C49B5812627976508196E38FBD5B9505E911FB`。

APK 与 3A 前已验证制品的大小和 SHA-256 相同，符合“3A 不接入 App”的预期。

### 3.4 静态边界检查

- `git diff --check`：通过；
- `order-core` 主代码禁用 Android、AndroidX、MQTT、Modbus、SharedPreferences、HTTP、数据库和 scheduler 引用扫描：无命中；
- `:order-core:dependencies --configuration runtimeClasspath`：只有 Kotlin 标准库；
- `app/build.gradle.kts` 搜索 `order-core`/项目依赖：无命中；
- `order-core` 主 class 全部为 major version 55，即 Java 11 字节码；
- JDK toolchain：17；Kotlin/Java target：11。

构建仍会输出仓库原有的 `armeabi-v7a` 单 ABI 警告和 Gradle 9 兼容性弃用警告；本切片没有新增或处理这些既有问题。

## 4. 证据边界

已验证：源码结构、纯函数决策、JVM 测试、依赖边界、Java 11 字节码、Debug APK 构建和制品哈希。

未验证：

- 现场 APK/后端版本；
- 真实 MQTT Broker 的离线会话、重投、积压和业务 ACK；
- 真实支付、退款、取消和版本冲突；
- 真实 RS485、Modbus、out1~out5、温控、库存和机械动作；
- 杀进程、整机断电和网络抖动恢复。

因此 3A 通过不代表真实断网订单或机械安全已经完成验证，也不授权发布、部署或操作设备。

## 5. 独立审核

“重构上位机审核会话A”对方案和实施分别执行多轮只读审核。实施首轮指出“整单终态不一定允许释放 claim”的 P1；主会话对照 `OrderSupplementResumePolicy.shouldReleaseClaim` 确认问题成立，并增加 `OrderClaimReleaseEligibility` 与六种结果的显式映射。

修订后的最终门禁：`Approve`，P0/P1/P2/P3 均无。主会话再次核对映射、策略条件、笛卡尔积计算和本地报告，审核结论可靠。
