# 第一阶段 1D-3：调度 Profile 进程级固定验证记录

日期：2026-08-13
仓库：`D:\LeYe\cl-sausage-controller-refactor`
分支：`develop`
切片基线：`2cab28607e2fbeded50b0677717b7c7a9429564b`
源码版本：`versionName=2.5.42`、`versionCode=292`

源码版本不是现场 APK 版本。本记录不代表真实 MQTT、RS485/Modbus、温控、机械动作、支付、后端或长时间运行验证。

## 1. 实施结果

- 新增纯 Kotlin、可实例化且线程安全的 `ProcessLifetimeSchedulerProfilePin`。
- 未固定时读取 Profile 立即失败，不再依靠首次类加载隐式选择 A。
- 第一次合法固定取得进程所有权；同 ID 重复固定幂等；不同已注册 ID 冲突并保留原 Profile。
- `SchedulerProfileRuntime` 仍只注册完整算法 A，没有算法 B/C、setter、运行时 switch、公开 catalog 或子策略选择入口。
- `MyApp.onCreate()` 在 `super.onCreate()` 后立即固定算法 A，早于日志、HTTP、恢复、库存同步和机械业务入口。
- 11 个直接初始化 Profile 消费单例的 JVM 测试只增加测试源集 bootstrap，没有修改既有业务断言和数据。
- 未修改 AppConfig、MainActivity、MQTT、Modbus/RS485、下位机协议、订单/补货/清洁/维护流程或 Gradle 配置；未新增线程、协程、Timer、Executor、Service 或连接。

## 2. 测试先行证据

### 核心固定器

生产类不存在时先运行：

```powershell
.\gradlew.bat :scheduler-core:test --tests "*ProcessLifetimeSchedulerProfilePinTest" --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

测试编译因 `ProcessLifetimeSchedulerProfilePin` 及结果类型不存在而失败；实现最小核心类后，同一命令成功。覆盖未固定读取、非法/未注册 ID、首次固定、同 ID 幂等、不同 ID 冲突、32 路同 ID 并发固定和 32 路 A/B 混合 ID 并发固定。

### App 启动接线

生产 bootstrap API 不存在时先运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*SchedulerProfile*" --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

结果在 `:app:compileDebugUnitTestKotlin` 按预期失败：

```text
SchedulerProfileTestBootstrap.kt:7:33 Unresolved reference: bootstrapOrThrow
```

实现 `SchedulerProfileRuntime.bootstrapOrThrow` 和 `MyApp` 最早期调用后，定向测试成功。

## 3. 自动化验证

### 强制全量 Debug JVM 回归

```powershell
.\gradlew.bat :scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

结果：`BUILD SUCCESSFUL in 6m`，`94 actionable tasks: 94 executed`。

| 测试域 | XML 文件 | tests | failures | errors | skipped | 当前结果时间 |
|---|---:|---:|---:|---:|---:|---|
| scheduler-core | 8 | 58 | 0 | 0 | 0 | 2026-08-13 15:24 |
| recovery-core | 3 | 26 | 0 | 0 | 0 | 2026-08-13 15:07 |
| order-core | 2 | 34 | 0 | 0 | 0 | 2026-08-13 15:07 |
| app Debug | 257 | 1704 | 0 | 0 | 0 | 2026-08-13 15:12 |
| 合计 | 270 | 1822 | 0 | 0 | 0 | - |

### 根项目测试与 Debug 构建

```powershell
.\gradlew.bat test :app:assembleDebug --no-daemon --max-workers=1 --console=plain
```

结果：`BUILD SUCCESSFUL in 2m 23s`，`245 actionable tasks: 15 executed, 230 up-to-date`。该命令还成功执行 App Release JVM 测试并生成 Debug APK。

Debug APK：

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：`50,188,177` 字节
- SHA-256：`CA216F07F1EEE44EBCAD4A196B74652A7B22D7E4724E937420E3326C0C859318`

APK 只证明当前源码可构建，不代表发布制品、现场版本或真实设备可用。

### 静态门禁

- `git diff --check`：通过。
- 变更仅位于 1D-3 计划白名单及本验证/回滚材料。
- 源码测试确认 bootstrap 是 `super.onCreate()` 后第一个应用自有操作，并早于日志、恢复、同步和 Profile 消费。
- 源码测试确认 App 主源码仍只创建完整算法 A；机械出餐仍由既有候选路径掌权，影子候选不接管动作。

## 4. 工具干扰记录

一次全量回归启动后收到外部 Gradle `stop` 指令，构建以 `Gradle build daemon has been stopped: stop command received` 中断。该次不计作测试失败或通过；重新运行后成功，随后又执行带 `--rerun-tasks` 的强制全量回归并成功。

独立审核指出混合 A/B ID 并发没有单独测试。主会话复核认为锁实现本身没有覆盖写入风险，但该测试与本计划验收直接相关且成本低，因此补充 32 路混合 ID 竞争测试。首次补测只按结果类型计数，审核会话复核认为没有逐条证明请求 ID 与结果的对应关系；主会话接受意见后改为逐个请求断言 owner 请求只能得到一次 `FirstPinned` 或同实例 `AlreadyPinned`，另一 ID 请求必须得到与请求 ID 对应的 `Conflict`。随后无过滤强制重跑完整 `:scheduler-core:test`，当前 8 个 XML、58 项测试、0 失败。该补测只改变测试与本记录，不改变此前已通过构建的生产源码或 APK。

## 5. 未验证边界

- 未连接测试或生产 MQTT Broker、HTTP 后端；未验证断网、重连或真实订单。
- 未打开串口，未执行 RS485/Modbus 读写，未验证寄存器或下位机兼容性。
- 未安装 APK、未操作机械、温控、补货、出餐、自清洁或维护流程。
- 未做进程被杀、掉电恢复和 RK3568 长时间运行验证。
- 本切片没有实际算法 B，因此只证明“完整 Profile 的进程级固定边界”，不证明 A/B 业务效果或切换闭环。

## 6. 当前停点与回滚

1D-3 保持未提交、未推送。精确回滚以 `docs/refactor/rollback/phase-1d3/pre-slice-manifest.md` 为准，只逆向本切片紧小 hunk，不触碰里程碑 `2cab286` 及更早已审核成果。
