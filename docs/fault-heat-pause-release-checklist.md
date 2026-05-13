# 烤肠故障停热 Phase 1 发布与灰度说明

## 本轮上位机已完成

- 故障停热计时：新增 `FaultHeatPauseManager`，记录故障停热开始时间；恢复非 0 温度前补偿烤制中烤盘 `startTime`，保温中烤盘 `holdingTime` 按自然时间继续计算。
- 食品安全护栏：烤制停热超过阈值、暂停异常过长、系统时间倒退、保温过期都会写入 `pendingDiscardPositions`，并通过 `foodSafetyAlertCode` / `foodSafetyAlertReason` 走软告警，不直接拉高 `errorStatus`。
- 温度写入收口：`heating()`、`keepWarm()`、`applyConfiguredTemperatureByPanState()` 的非 0 温度写入统一经过 `setAreaTargetTemperature`；故障停热写 0 走 raw 入口。
- 可售口径统一：新增 `KaoPanHelper.isSellable`，订单选肠、云端快照、温控策略、补热恢复、低/高并发模式等入口统一排除待丢弃烤盘。
- 过保扫描统一：新增 `OverKeepWarmDiscardScanner`，标记阶段不依赖算法开关或机械空闲，机械丢弃阶段再受自动调度门控约束。
- 恢复顺序：`enableRoastAlgorithm` 先结束故障暂停并补偿计时，再恢复温度，再触发待丢弃优先扫描。
- 通信同步：`runtime_state` MQTT payload 和 HTTP `updateDeviceInfo` 增加 `foodSafetyAlertCode`、`foodSafetyAlertReason`、`pendingDiscardPositions`；runtime 签名纳入这些字段并对待丢弃列表排序去重。
- ACK 兜底：`runtime_state` ACK 8 秒超时后按 runtimeSeq 单次、设备级最小间隔兜底调用 HTTP 上传。
- 续补确认：维护页新增“确认续补安全”按钮，仅 active 批次存在 `requiresManualConfirm=true` 时显示。
- 版本号：`versionCode` 从 `75` 升到 `76`，`versionName` 从 `2.1.05` 升到 `2.1.06`。
- 项目知识同步：外层 `D:\LeYe\CLAUDE.md` 版本号已同步为 `76 / 2.1.06`。

## 已补测试

- `FaultHeatPauseManagerTest`：覆盖暂停顺延、超阈值待丢弃、异常暂停、时钟倒退、重复 begin、clear 后 end 等分支。
- `KaoPanHelperSellableTest`：验证待丢弃烤盘即使保温可售状态也返回不可售。
- `OverKeepWarmDiscardScannerTest`：验证待丢弃优先、过保候选、算法关闭、硬故障、机械忙、检修模式阻断。
- `AppConfigLocalRuntimeFieldsTest`：验证 7 个食品安全本地运行态字段不会被远端覆盖，阈值字段仍允许远端合法值下发。
- `SendServerHelperSignatureTest`：验证待丢弃列表排序去重后签名一致，内容变化和软告警变化会触发新签名。
- `TemperatureRegisterWriteGuardTest`：扫描生产源码，防止新增代码绕过温度写入收口直接写 503/504/505 非 0 目标温度寄存器。

## 本地验证

已通过：

```bash
.\gradlew.bat :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.init.AppConfigLocalRuntimeFieldsTest" --tests "cn.niuyannet.kaochang.android.mqtt.SendServerHelperSignatureTest" --tests "cn.niuyannet.kaochang.android.services.OverKeepWarmDiscardScannerTest"
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

说明：构建日志中仍有项目既有的 32 位 native library、Android manifest package、deprecated API 等 warning，不是本次食品安全改造引入的失败项。

## 真机灰度验收清单

| 场景 | 验收内容 | 必要证据 |
| --- | --- | --- |
| 场景 0 | 温度寄存器穿透检查，完整低并发和高并发流程中所有非 0 目标温度写入都经过 `setAreaTargetTemperature` | logcat 或文件日志 grep `写入温区目标温度` / `writeAreaTargetTemperatureRaw`，确认非 0 写入来源为受控入口 |
| 场景 1 | 烤制第 10 分钟故障停热 40 分钟，恢复后仍需约 30 分钟，不应立即转保温 | 视频 + `【故障停热】` 开始/结束日志，包含 `pauseDurationMs≈40min` 和 `shiftedPositions` |
| 场景 2 | 保温第 5 小时 50 分钟故障停热 30 分钟，恢复后进入待丢弃并立即不可售 | 视频 + `pendingDiscardPositions` 日志 + 小程序/二维码/订单侧不可售证据 |
| 场景 3 | 烤制第 5 分钟故障停热 3 小时，恢复后进入待丢弃，不继续烤 | 视频或 logcat，包含 `BAKING_PAUSE_ABORT` |
| 场景 4 | 故障中 App 强杀重启，暂停超过 4 小时，不平移时间，相关烤盘进入待丢弃 | logcat，包含 `ANOMALY_TOO_LONG` |
| 场景 5 | 手动把系统时间调早，恢复触发时钟倒退保护 | logcat，包含 `CLOCK_REGRESSED` |
| 场景 6 | 25~33 补到 25/26/27 后故障 40 分钟，恢复后已补烤盘顺延并继续补 28~33 | 视频 + `shiftedPositions` + 续补动作日志 |
| 场景 7 | `requiresManualConfirm=true` 时维护页按钮可见，点击确认后才允许续补 | 视频或截图 + 确认日志 |
| 场景 8 | 维护页只点“恢复正常状态”但不开算法，机械不动，待丢弃烤盘不可售 | 视频 + `isEnable=0` 下可售阻断证据 |
| 场景 9 | MQTT `runtime_state` ACK 超时，8 秒后触发 HTTP 兜底，30 秒内不重复刷 | 弱网/拔网线 logcat，包含 ACK 超时、HTTP 兜底、限频跳过日志 |
| 场景 10 | 等待机械动作超过 2 分钟不 2 秒刷屏 | logcat，确认日志节流效果；如果 Phase 2 未合入，此项作为后续 PR 验收 |

## 后端联动要求

- `cl-sausage-server` 必须消费 MQTT `runtime_state` 的 `foodSafetyAlertCode`、`foodSafetyAlertReason`、`pendingDiscardPositions`。
- HTTP `/api/app/device/updateDeviceInfo` 必须消费同名三个字段。
- 飞书告警需要新增“食品安全软告警”分类，区分硬故障 `errorStatus != 0` 与软告警 `foodSafetyAlertCode` 非空。
- 发布顺序建议：后端先上线字段兼容和入库，上位机随后灰度；避免上位机先发后云端丢字段。

## 建议提交拆分

1. 温度写入收口：`setAreaTargetTemperature`、`heating()`、`keepWarm()`、`writeAreaTargetTemperatureRaw()`。
2. 可售口径统一：`KaoPanHelper.isSellable` 与可售入口排除 `pendingDiscardPositions`。
3. 配置与本地运行态：`AppConfigBean` 字段、阈值兜底、远端合并本地运行态保护。
4. 故障暂停管理：`FaultHeatPauseManager` 与计时平移/待丢弃标记。
5. 过保扫描与调度顺序：`OverKeepWarmDiscardScanner`、低/高并发替换、`pollKaoPanStatus` 和 `enableRoastAlgorithm` 顺序约束。
6. MQTT/HTTP 同步：软告警字段、runtime 签名、ACK 超时 HTTP 兜底。
7. 维护页续补确认：按钮、可见性、二次确认和确认调用。
8. 测试、版本号和发布文档：新增单测、源码扫描测试、版本 `76 / 2.1.06`、本说明文档。

## 暂不包含

- Phase 2 日志治理尚未合入：等待循环 `logEvery(intervalMs)`、203 轮询进度日志、`markActionGroupFailure` 上下文补全、残留消化态日志去重、`schedulingEnabled=false` 五要素 once-on-change 日志。
- 本地测试不能替代 Modbus、真实温度寄存器、机械丢弃、弱网 MQTT ACK、设备时间倒退等真机场景。
