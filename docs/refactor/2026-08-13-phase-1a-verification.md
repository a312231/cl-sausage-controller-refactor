# 切片 1A 验证记录

日期：2026-08-13
基线：`fad00d530bb88c0fb3ace82dcdda038f24626438`
源码版本：`2.5.42 (292)`，不代表现场 APK 版本

## 实施范围

- 新增纯 JVM 模块 `:scheduler-core`；
- 新增不可变调度输入、策略接口、稳定策略 ID 和策略目录；
- 现有 2.5.42“最早进入保温优先、同时间小盘位优先”规则作为算法 A：`legacy-oldest-holding-v1`；
- 测试策略 B 仅存在于测试源码，用于证明算法可以按 ID 选择；
- App 仅补充 `TrafficModeHelperTest`，App 主源码和 `app/build.gradle.kts` 未修改；
- 未接入订单、库存、MQTT、HTTP、Modbus、机械动作或 UI。

## 测试先行证据

1. 首次执行因本机没有 JDK 11 toolchain 而在编译前失败；方案改为使用现有 JDK 17 toolchain、Java/Kotlin 字节码目标 11。
2. 核心实现尚不存在时，`:scheduler-core:compileTestKotlin` 因类型和策略未定义失败，形成预期红灯。
3. 增加集合防御复制测试后，该测试先失败；实现输入集合复制后转绿。

## 自动化验证

### 纯模块

命令：

`./gradlew :scheduler-core:test --no-daemon`

结果：8 tests，0 failures，0 errors。

覆盖：

- 可售过滤、范围、待丢弃、口味和商品；
- 时间窗口起点、终点和 `+1ms`；
- `holdingTime`、盘位排序；
- 固定种子 10,000 组算法 A 规则差分；
- 空口味异常输入安全排除；
- 调用方集合修改不影响已创建输入；
- 算法 A/测试策略 B 按 ID 选择；
- 未知 ID 不回退、重复 ID 拒绝。

### App 旧规则特征测试

命令：

`./gradlew :app:testDebugUnitTest --tests "cn.niuyannet.kaochang.android.services.TrafficModeHelperTest" --no-daemon`

结果：15 tests，0 failures，0 errors。

### 全量 JVM 与构建

命令：

- `./gradlew test --no-daemon`
- `./gradlew assembleDebug --no-daemon`
- 最终复核：`./gradlew test assembleDebug --no-daemon`

结果：成功。当前 `build/test-results` 共 485 个 XML 报告，汇总 3,280 tests，0 failures，0 errors，0 skipped。

核心 class `major version=55`，即 Java 11 字节码。

本地 Debug APK：

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- SHA-256：`85C758BBCF4A637D732F893B11C5038B672EFC569E1536E25B3D530781A265D2`

该 APK 仅用于证明构建链路成功，未安装、未发布、未操作设备。

## 独立审核与主会话复核

- 审核任务：`重构上位机审核会话A`，任务 ID `019ff6e5-8975-7461-97c5-0a8d306cbd51`；
- 结论：P0/P1/P2/P3 均无，`Approve`；
- 主会话重新核对了 3,280 项报告汇总、Java 11 class 版本、App 无运行时依赖和生产仓库状态，审核结论可靠。

## 证据边界

- 已证明：源码边界、纯 JVM 行为、现有 App 测试兼容、Debug 构建成功；
- 未证明：现场 APK 行为、真实 MQTT Broker、HTTP、RS485、Modbus、红外/光电、下位机寄存器和机械动作；
- 生产仓库 `D:\LeYe\cl-sausage-controller` 未被修改，用户原有未跟踪文件保留；
- 当前切片未提交、未推送、未发布。
