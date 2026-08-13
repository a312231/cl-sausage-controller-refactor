# 第一阶段 1D-1：完整 SchedulerProfile 目录与单一固定 A 验证

日期：2026-08-13
生产基线：`D:\LeYe\cl-sausage-controller`，`develop`，`fad00d530bb88c0fb3ace82dcdda038f24626438`
重构仓库：`D:\LeYe\cl-sausage-controller-refactor`，`develop`，同一 HEAD
源码版本：`versionName=2.5.42`、`versionCode=292`

源码版本不代表现场 APK。本切片未提交、未推送、未发布、未部署、未安装、未操作设备。

## 结果

- 新增完整 `SchedulerProfile A`，稳定 ID 为 `legacy-scheduler-profile-a-v1`；一次性绑定可售候选、补货目标、口味混配、批次/热区/位置、流量模式切换五类策略。
- Profile 是 private constructor 的 final 非 data class，没有 `copy`；描述从实际组件实例派生。
- 正式 A 只能由固定工厂创建；任意组件组装仅接受 `test-` ID，App 无法构造混搭正式 Profile。未来 B/C 必须新增各自受控工厂。
- 完整 Profile 目录精确查找、重复 ID 拒绝、未知 ID 不回退；pin 保存同一个完整实例；切换计划保留七项安全 blocker 和 fail-closed 优先级。
- App 的 8 个 A 组件直接构造点已全部改为读取同一个 `SchedulerProfileRuntime.activeProfile`；静态扫描直接构造数为 0。
- 可售候选仍只在 `SchedulerShadowRuntime` 使用；`KaoChangAlgorithm.purchase` 仍由 legacy 候选进入机械执行，没有改变机械权威。
- 未新增 AppConfig、UI、MQTT/HTTP、持久化或运行时切换；本切片不能声称已支持用户选择算法 A/B。

## TDD 证据

1. 先增加纯核心 Profile 与切换测试，首次运行在缺少 Profile 类型时 `compileTestKotlin` 失败；实现后转绿。
2. 先增加 App Runtime 与 8 点接线测试，首次运行在缺少 Runtime/接线时未形成通过结果；实现后定向测试转绿。
3. 测试 Profile B 的五个组件 ID、实例和行为均可区分；逐项验证 getter、descriptor 和被 pin Profile 的引用一致性，并实际调用五类可区分行为。
4. 独立审核后补充：`PinnedSchedulerProfile` 私有构造反射门禁、六个 required binding 的 Java 平台 null 矩阵，以及“先 pin A、再规划 B，A profile 与五个组件引用全部不变”。补测后 `:scheduler-core:test --rerun-tasks` 通过。

## 自动化验证

已通过：

```text
:scheduler-core:test --rerun-tasks
:app:testDebugUnitTest --tests "*SchedulerProfile*" --rerun-tasks
:scheduler-core:test :recovery-core:test :order-core:test :app:testDebugUnitTest --rerun-tasks
test --rerun-tasks
:app:assembleDebug --rerun-tasks
```

首次完整全仓测试 XML 复算：

| 模块/变体 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| App Debug | 1674 | 0 | 0 | 0 |
| App Release | 1674 | 0 | 0 | 0 |
| scheduler-core | 52 | 0 | 0 | 0 |
| recovery-core | 26 | 0 | 0 | 0 |
| order-core | 34 | 0 | 0 | 0 |
| libnative Debug/Release | 2 | 0 | 0 | 0 |
| 合计 | 3462 | 0 | 0 | 0 |

实施后主会话进一步把任意组装工厂限制为 `test-` ID，并再次通过 `:scheduler-core:test --rerun-tasks`。随后最终源状态执行根 `test`，结果 `BUILD SUCCESSFUL`（200 tasks：7 executed、193 up-to-date），再执行 `:app:assembleDebug`，结果 `BUILD SUCCESSFUL`（122 tasks：7 executed、115 up-to-date）。

静态门禁：

```text
scheduler-core 主源码禁止依赖命中：0
App 主源码五种 A 组件直接构造：0
旧 SellCandidate 部分目录/pin/switch API 命中：0
scheduler-core/build.gradle.kts SHA-256：3E439CDD3843C75F9E097607C52ADD37C6BBC68D1453F2D10D1DEB45D048D878
scheduler-core main class：75 个，major=55
git diff --check：通过
```

最终 Debug 构建产物：

```text
app-debug.apk：49,739,194 bytes
SHA-256：B66D5FF894A21891C084089D251B5CF9AE57FF45FF394B90BEB2F480FB4CD86D
```

## 证据边界

- 已验证：当前源码静态结构、纯 JVM 策略/Profile 行为、App JVM 接线、Debug 打包链路。
- 未验证：测试 MQTT Broker、测试后端、真实 RS485/Modbus、温控和机械动作。
- APK/JVM 结果不能替代上述现场证据；本切片没有开放算法切换，也没有改变下位机协议。

## 回滚边界

仅精确逆向本切片 hunk：删除新增完整 Profile/Runtime/测试，恢复旧三个可售候选部分目录/pin/switch 文件和 8 个直接构造点。禁止使用 `git restore`、`git checkout --`、reset、stash 或整文件覆盖，避免误伤 1A~1C-5、2A~2C-1、3A~3B-1 及用户其他未提交内容。

实施前哈希原本只存在主会话命令输出，未提前保存为仓库文件；独立审核准确指出这是证据缺口。审核后已如实补存可复核材料到 `docs/refactor/rollback/phase-1d1/README.md`：其中明确标注为事后根据实施前输出补存，不冒充实施前已落盘；四个被删除未跟踪文件的完整 preimage 哈希已逐项复算匹配。其他文件按精确逆向 hunk 和实施前 SHA 复核，不能整文件覆盖。
