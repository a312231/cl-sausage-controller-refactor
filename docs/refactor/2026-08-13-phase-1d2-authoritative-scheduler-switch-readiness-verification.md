# 第一阶段 1D-2：调度 Profile 权威切换就绪证据验证记录

日期：2026-08-13
生产基线：`D:\LeYe\cl-sausage-controller`，`develop`，`fad00d530bb88c0fb3ace82dcdda038f24626438`
重构仓库：`D:\LeYe\cl-sausage-controller-refactor`，`develop`，同一 HEAD
源码版本：`versionName=2.5.42`、`versionCode=292`

源码版本不代表现场 APK。本记录不授权提交、推送、发布、部署、安装或设备操作。

## 结果

1D-2 已通过源码检查、JVM 自动化、Debug 构建和独立只读审核。它只建立 App 侧只读、失败即阻断的 Profile 切换就绪证据：

- `AVAILABLE`：证据完整且七项 blocker 为空；
- `BLOCKED`：证据完整但至少存在一个 blocker；
- `UNAVAILABLE`：配置未初始化、证据损坏、非法或读取失败。

该结果不是切换授权。当前仍只有固定 `legacy-scheduler-profile-a-v1`，没有真实算法 B/C、配置选择、持久化、UI、MQTT/HTTP 命令或运行时激活入口。

## 实现证据

- `KaoChangOperate.readSchedulerProfileSwitchReadiness()` 是唯一权威组合入口；只消费现有配置缓存、owner、session、journal、claim、fence 和锁。
- 配置只使用 `AppConfig.peekInitializedAppConfig()`；缓存未初始化时返回 `CONFIG_UNAVAILABLE`，不读取旧配置、不生成默认配置、不迁移、不保存。
- action session 只调用一次 `readEvidence()`；同一读取结果同时计算动作 session blocker 和 guarded supplement residue。
- 烤盘缓存未初始化时 fail-closed；活动批次判断一次捕获 `nowMs`，并复用现有 `KaoPanHelper.isSellable`。
- claim、fence、pan/discard journal、action/paused session、prefetch session 的严格读取均保留 `Error`、取消和线程中断；普通解析/校验异常按损坏或不可用处理。
- 未新增线程、协程、Timer、Executor、Service、MQTT/HTTP、Modbus/RS485 或机械动作调用。

## TDD 与验证

初始失败证据：

- `:app:compileDebugUnitTestKotlin --rerun-tasks` 因 readiness 类型和最窄 owner 查询尚不存在而失败；
- 第一轮定向测试 57 项中 1 项失败，定位为测试夹具未设置 `closeTime`，不满足生产 `isSellable` 的保温期限条件；修正夹具后通过；
- 独立实现审核发现三态语义、`AppConfig.getAppConfig()` 传递写副作用和真实 store 控制流缺口，修正前的绿色结果未作为最终证据；
- 后续审核发现 Error 断言可能由测试自身 `fail()` 形成假阳性；改为捕获真实异常并 `assertSame` 原始对象后重新执行。

最终命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*StrictReadinessStoreControlFlowTest' --tests '*DurableEvidenceCodecTest' --tests '*SchedulerProfileSwitchReadinessTest' --no-daemon --max-workers=1 --console=plain
.\gradlew.bat test :app:assembleDebug --no-daemon --max-workers=1 --console=plain
git diff --check
```

最终结果：

| 模块 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `scheduler-core` | 52 | 0 | 0 | 0 |
| `recovery-core` | 26 | 0 | 0 | 0 |
| `order-core` | 34 | 0 | 0 | 0 |
| `app:testDebugUnitTest` | 1703 | 0 | 0 | 0 |
| 合计 | 1815 | 0 | 0 | 0 |

`test :app:assembleDebug`：`BUILD SUCCESSFUL`。
`git diff --check`：通过。

Debug APK（仅构建证据）：

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：`50,480,423` bytes
- SHA-256：`7DE6B43A37B527CB8BD7B216912C4E24FE46200409EEDE19340BEAC37CB6B555`

## 独立审核

审核会话：`重构上位机审核会话A`（`019ff6e5-8975-7461-97c5-0a8d306cbd51`）。

- 初审：`Request Changes`，三项 P1、一项 P2、一项 P3；主会话逐项复核后接受；
- 二审：剩余 store validator/journal 边界和测试矩阵问题；主会话复核后接受；
- 三审：发现 Error 测试假阳性；主会话复核后接受；
- 最终复审：P0/P1/P2/P3 均无，`Approve`。

## 回滚边界

- 实施前文件存在状态和 SHA-256 见 `docs/refactor/rollback/phase-1d2/pre-slice-manifest.md`；
- 1D-2 新增类型和查询均有唯一名称，可按本记录的实现证据精确删除；
- 重叠脏文件只能逆向 1D-2 的紧小 hunk，禁止 `restore`、`checkout`、`reset`、`stash` 或整文件覆盖；
- 回滚不得损伤 1A~1D-1、2A~2C-1、3A~3B-1 及用户其他未提交内容。

## 未验证证据

以下均未执行，不能由 JVM 或 APK 构建替代：

- 测试 MQTT Broker、测试后端；
- 真实 MQTT；
- RS485/Modbus；
- 温控、动作组和机械动作；
- 长时间运行与断电重启现场行为。

## 后续边界

1D-3 才能选择激活模型。优先审核“安全冷启动激活”，不要直接建设跨全部流程的运行时热切换；无论选择哪种模型，都必须在共享启动门禁内重新读取权威证据，不能直接复用 1D-2 的非原子采样结果。
