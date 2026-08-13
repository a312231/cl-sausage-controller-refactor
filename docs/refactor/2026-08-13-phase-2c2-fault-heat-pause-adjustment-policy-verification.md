# 第二阶段 2C-2：故障停热烤盘时间补偿纯决策验证

日期：2026-08-13
仓库：`D:\LeYe\cl-sausage-controller-refactor`
分支：`develop`
实施基线：`8b1868d6b1eedd4590fa22a0e7cad09bf9e8b636`
源码版本：`versionName=2.5.42`、`versionCode=292`

源码版本不代表现场 APK。本文只记录本地源码、JVM 测试和 Debug 构建证据；未安装、未发布、未连接设备。

## 1. 实施结果

- 在 `:recovery-core` 新增 `FaultHeatPauseAdjustmentPolicy`，集中拥有故障停热后的 baking 顺延/待丢弃、holding 到期、时钟异常和全局 fail-closed 候选判断。
- 核心输入是调用方提供的 `nowMs`、暂停起点、已归一化阈值和不可变烤盘快照；核心不读取 Android、AppConfig、KaoPan、MQTT、Modbus、文件或系统时钟。
- `FaultHeatPauseManager` 的私有适配器只构造快照、调用一次核心策略并映射原告警码；preview、journal prepare/finalize、普通调整和断电恢复仍在各自调用时获取当前时间与快照。
- App 继续拥有 pan/config 修改、journal、durable save、日志和状态同步。`actualPauseOverlapMs` 只保留为 pending 日志兼容计算，不参与任何决定或状态修改。
- 保留重复 position 的当前异常兼容行为：核心逐输入保留列表语义、汇总 target Map 后项覆盖；App 原有 `firstOrNull` 仍只改第一个匹配对象。

## 2. TDD 证据

### 2.1 纯核心红绿

先新增 `FaultHeatPauseAdjustmentPolicyTest`，在生产模型尚不存在时执行：

```powershell
.\gradlew.bat :recovery-core:test --tests "*FaultHeatPauseAdjustmentPolicyTest" --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

红灯：测试源码因策略/模型不存在而编译失败。加入最小核心实现后，同一命令通过。

当前 XML：10 项、0 failure、0 error、0 skipped。测试包含具名边界、`closeTime<=0`、holding Long 回绕、不可变集合、JVM private 构造器、重复 position、Long 边界和固定种子 10,000 组独立旧公式 oracle 对照。

### 2.2 App 接线红绿

接线前执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*FaultHeatPauseAdjustmentCoreWiringSourceTest" --tests "*FaultHeatPauseManagerTest" --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

红灯：接线守卫 4 项中 3 项失败，证据为核心调用次数仍为 0、App 仍保留旧决定公式；既有 `FaultHeatPauseManagerTest` 20 项全部通过。接线后先发现两次测试守卫自身的作用域/重复字符串搜索错误，只修正测试解析方式，没有改变生产恢复流程。

最终同组测试：24 项、0 failure、0 error、0 skipped。

## 3. 全域测试与构建

强制重跑四个域：

```powershell
.\gradlew.bat :scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest :app:assembleDebug --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

结果：`BUILD SUCCESSFUL in 5m 17s`，139 个 task 全部 executed，并用最终 recovery-core 重打 Debug APK。当前 XML 统计：

| 域 | XML | tests | failure | error | skipped |
|---|---:|---:|---:|---:|---:|
| scheduler-core | 8 | 58 | 0 | 0 | 0 |
| recovery-core | 4 | 36 | 0 | 0 | 0 |
| order-core | 2 | 34 | 0 | 0 | 0 |
| app Debug | 258 | 1709 | 0 | 0 | 0 |
| 合计 | 272 | 1837 | 0 | 0 | 0 |

仓库测试与 Debug APK：

```powershell
.\gradlew.bat test --no-daemon --max-workers=1 --console=plain
```

结果：`BUILD SUCCESSFUL in 1m 6s`，200 个 task 中 3 executed、197 up-to-date；该命令按最终源码重新完成 App Release JVM 测试 1709 项、0 failure/error/skipped。Debug APK 已由上一条强制命令生成。

Debug APK（仅构建证据）：

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：49,614,463 bytes
- SHA-256：`1E50FACA58117FC66B206A49500D9A58E7B1C75B7A8117BD492D23A4D1D99F9A`

历史构建警告仍包括 32 位 `armeabi-v7a` 原生库、旧 Manifest namespace 和弃用 API；本切片没有修改对应文件，也没有把这些警告误记为 2C-2 新问题。

## 4. 边界与结构校验

- 新策略相关 9 个 class 的 class major 均为 55（Java 11）。
- 对这 9 个 class 的 `javap -verbose` 禁止依赖扫描为 0：无 Android/App、MQTT、Modbus、Fastjson、文件 I/O、系统时钟、Thread、协程或 UUID 依赖。
- `FaultHeatPauseAdjustment` 唯一非 synthetic 构造器在最终 jar 中为 JVM `private`；模块内部 factory 标记 `@JvmSynthetic`，App/Java 不能通过普通 API 自行拼装矛盾结果。
- `git diff --check` 通过。
- 修改路径只在 2C-2 白名单内。
- 实施前 manifest 的 7 个禁止修改文件 SHA-256 全部一致：`KaoChangOperate`、`PowerLossRecoveryGuard`、`AppConfigBean`、`KaoPan`、`app/build.gradle.kts`、`MqttProtocol`、`VMModbusHelper`。
- 未新增 Handler、线程、协程、Timer、Executor、Service、连接、监听器或静态 Context。

## 5. 尚未验证

以下均未执行，不能由上述测试或 APK 替代：

- 真实 MQTT Broker/后端联调；
- 真实 RS485/Modbus、下位机寄存器和响应；
- RK3568 真实断电、时钟回拨、文件落盘和重启恢复；
- 真实温控恢复、烤盘温度和食品安全现场口径；
- 机械动作、补货、出餐、自清洁、维护和长时间稳定性；
- 现场 APK 版本匹配、安装、灰度、发布或部署。

## 6. 回滚边界

按 `docs/refactor/rollback/phase-2c2/pre-slice-manifest.md` 精确逆向本切片：删除新增核心/测试/验证文件，只逆向 `FaultHeatPauseManager` 的核心适配 hunk、既有测试新增 hunk及目标架构单一状态段落。禁止 reset、stash、整文件覆盖或影响 2C-2 前里程碑。

## 7. 独立审核闭环

审核会话 A 首轮结论为无 P0/P1、两个 P2：

1. Kotlin `internal constructor` 在 JVM 字节码中为 public，App/Java 存在绕过 policy 伪造结果的 API 边界；
2. 缺少 `closeTime<=0` 和 holding Long 回绕的具名固定预期测试。

主会话逐项核验后确认两项准确，改为 JVM private 主构造器和 `@JvmSynthetic internal` 受控 factory，并新增 3 个具名/字节码测试。修正后重新执行最终强制回归、APK 构建、仓库测试和制品扫描。

审核会话 A 复审结论：`Approve`，P0/P1/P2/P3 均无；明确保留真实 MQTT、RS485/Modbus、断电、温控、机械和现场食品安全未验证边界。主会话再次核对最终 jar、XML、APK 和代码后，同意该结论。

## 8. 当前 Git 状态

2C-2 保持未提交、未推送，等待独立审核会话 A 复核和用户后续提交授权。远端未因本切片改变。
