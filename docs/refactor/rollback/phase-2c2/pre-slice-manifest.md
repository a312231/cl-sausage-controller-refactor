# 2C-2 实施前文件清单

记录时间：2026-08-13
仓库：`D:\LeYe\cl-sausage-controller-refactor`
分支：`develop`
实施前 HEAD：`8b1868d6b1eedd4590fa22a0e7cad09bf9e8b636`
源码版本：`versionName=2.5.42`、`versionCode=292`
实施前工作树：仅有已审核通过、尚未提交的 2C-2 计划文件。

本清单只记录路径、存在状态、tracked 状态和 SHA-256，不复制配置内容。回滚只允许逆向 2C-2 紧小 hunk，禁止 reset、stash、整文件覆盖或回滚既有里程碑。

## 允许修改文件

| 路径 | 存在 | tracked | 实施前 SHA-256 |
|---|---:|---:|---|
| `recovery-core/src/main/kotlin/cn/niuyannet/kaochang/recovery/FaultHeatPauseAdjustmentPolicy.kt` | 否 | 否 | `ABSENT` |
| `recovery-core/src/test/kotlin/cn/niuyannet/kaochang/recovery/FaultHeatPauseAdjustmentPolicyTest.kt` | 否 | 否 | `ABSENT` |
| `app/src/main/java/cn/niuyannet/kaochang/android/services/FaultHeatPauseManager.kt` | 是 | 是 | `8252CCB69C100A9BACC3B3B78725D36A000289A0E14D59D1DA2D49050D2B2520` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/FaultHeatPauseManagerTest.kt` | 是 | 是 | `E7EA465DD5EFC32968502318BD59250C1494F3BAE10F5628C447D3FEE58958CF` |
| `app/src/test/java/cn/niuyannet/kaochang/android/services/FaultHeatPauseAdjustmentCoreWiringSourceTest.kt` | 否 | 否 | `ABSENT` |
| `docs/refactor/2026-08-13-phase-2c2-fault-heat-pause-adjustment-policy-plan.md` | 是 | 否 | `28FE22AD9013EEBBE70BB7038B80C18FAC0BF7B70CE6632197897BD73C81D0A4` |
| `docs/refactor/2026-08-13-phase-2c2-fault-heat-pause-adjustment-policy-verification.md` | 否 | 否 | `ABSENT` |
| `docs/refactor/rollback/phase-2c2/pre-slice-manifest.md` | 否 | 否 | `ABSENT` |
| `docs/refactor/2026-08-13-target-architecture-decisions.md` | 是 | 是 | `233E84245FED51C840018150D0D37C374E92A1C1A9F93BC7753D3AB795405387` |

## 关键禁止修改文件

| 路径 | SHA-256 |
|---|---|
| `app/src/main/java/cn/niuyannet/kaochang/android/services/KaoChangOperate.kt` | `865B6CDC9432311AA3D25E182B86679EB54226DD24F591F104B5BF8DB512B6CD` |
| `app/src/main/java/cn/niuyannet/kaochang/android/services/PowerLossRecoveryGuard.kt` | `061A3FC6C6B0A1947FD60C08241423661020D5560736DAC112E23B8BB683C73B` |
| `app/src/main/java/cn/niuyannet/kaochang/android/init/AppConfigBean.java` | `35F2C42AA8866484878F97C72F6AC159E8F00757AB9A3CFEA7E1605B9D1A3EE3` |
| `app/src/main/java/cn/niuyannet/kaochang/android/model/bean/KaoPan.java` | `54745275EED5775D4E5256E390FCF7D84F344FA2B2A0B5EEFF5483B9AFC1750E` |
| `app/build.gradle.kts` | `3301E1D89C1C99D82168C81808732F4FD665414197303AD2AB98ED5125A23B16` |
| `app/src/main/java/cn/niuyannet/kaochang/android/mqtt/MqttProtocol.kt` | `D43A3257C5466AEE015FDA7DB32079F48EC25C79E620B7CAB24C7752B0A90DEA` |
| `app/src/main/java/cn/niuyannet/kaochang/android/modbus/VMModbusHelper.kt` | `7774C4003E71B82A198309F72B272CF6D94CCC3B38EE8806B84455A86277CAE8` |

## 精确回滚边界

- 新增 core policy/test、App 源码门禁测试、验证记录和本 manifest：仅删除 2C-2 新文件。
- `FaultHeatPauseManager.kt`：只逆向快照映射、core 调用、结果映射和日志专用 overlap 例外 hunk，恢复实施前私有计算。
- `FaultHeatPauseManagerTest.kt`：只逆向 2C-2 新增的等价/重复 position 适配断言，不改既有测试数据。
- 目标架构文档：只逆向实施成功后新增的 2C-2 阶段状态段落。
- 禁止触碰 `8b1868d` 及更早的 1A~1D-3、2A~2C-1、3A~3B-1 成果。
