# 第一阶段：可售烤肠选择策略解耦计划

日期：2026-08-13
基线：`develop` / `fad00d530bb88c0fb3ace82dcdda038f24626438`
源码版本：`versionName=2.5.42`、`versionCode=292`（仅表示当前源码，不代表现场 APK）

## 1. 结论

第一阶段只抽离“给定库存快照，选择哪个烤盘作为出餐候选”的纯决策。

- 不改现有出餐结果，不改 `out1~out5` 机械动作。
- 不改 Modbus/RS485、MQTT、HTTP、订单状态、库存落盘和 UI。
- 分成 1A（纯模块与差分测试）和 1B（默认关闭的影子比对）两个独立切片；每个切片分别审核、验证和回滚。
- 旧规则继续作为唯一执行依据；新逻辑不授予出餐权限，也不控制设备。
- 不在本阶段升级 MQTT 5，避免把调度解耦和通信协议迁移绑成一次高风险变更。

## 2. 当前代码事实

当前出餐入口为：

`KaoChangAlgorithm.purchase`
→ 读取 `KaoPanHelper.getKaoPanList()` 和 `AppConfig.getAppConfig()`
→ `TrafficModeHelper.purchaseSellEnd(...)` 决定扫描 `1~9` 或 `1~21`
→ `TrafficModeHelper.selectSellCandidate(...)`
→ `KaoPanHelper.isSellable(...)`
→ `KaoChangOperate.takeSausageResult(...)`
→ 取得机械 owner/资源后再次读取最新烤盘、模式范围、口味、商品和待丢弃状态
→ 最终复核通过才进入机械出餐动作。

当前可售条件以源码为准：

1. `isHasSausage == true`；
2. `status == STATUS_SELLABLE_KEEP_WARM`；
3. `holdingTime > 0`；
4. `closeTime > 0`；
5. 不在 `pendingDiscardPositions`；
6. `nowMs - holdingTime` 位于 `0..closeTime`；
7. 口味和商品 ID 匹配；
8. 候选按 `holdingTime` 升序，同时间按 `positionSn` 升序。

主要耦合点是 `TrafficModeHelper.selectSellCandidate` 直接读取 `KaoPanHelper` 全局状态和系统时间，并直接使用 App 层 `KaoPan` 对象。

安全边界：纯策略输出永远只是“候选”。`KaoChangOperate.takeSausageResult` 在 `out1` 前执行的最新状态复核是机械下发授权点，第一阶段不得迁移、弱化或删除。

## 3. 目标边界

新增纯 JVM 模块 `:scheduler-core`，只依赖 Kotlin 标准库和 JUnit，不依赖 Android、MQTT、Modbus、数据库或网络库。

核心模块采用可插拔策略：

- `SellCandidateStrategy` 定义统一输入输出；
- 每个算法有稳定、可持久化的 `StrategyId`，现有 2.5.42 规则命名为算法 A（`legacy-oldest-holding-v1`）；
- `SellCandidateStrategyCatalog` 只负责按 ID 查找策略，不读取 AppConfig；
- 未知或未注册 ID 返回“未找到”，禁止静默回退到另一算法；应用层以后必须明确决定保持当前已验证算法还是拒绝配置；
- 切片 1A 不臆造真实算法 B，只用测试策略 B 证明注册和选择机制可扩展；真实 B/C 的业务规则分别计划、测试、审核和灰度。

### 输入

`SellCandidateInput`：

- `nowMs: Long`
- `tasteCode: String`
- `productId: Int`
- `sellRange: IntRange`
- `pendingDiscardPositions: Set<Int>`
- `pans: List<PanSnapshot>`

`PanSnapshot` 只包含本次选择需要的字段：

- `positionSn: Int`
- `hasSausage: Boolean`
- `lifecycle: PanLifecycle`，取值为 `EMPTY / BAKING / SELLABLE_KEEP_WARM / WAITING_RETURN_KEEP_WARM / UNKNOWN`
- `holdingTime: Long`
- `closeTime: Long`
- `tasteCode: String?`
- `productId: Int?`

App 适配层拥有数值状态映射权：App 的 `STATUS_SELLABLE_KEEP_WARM=2` 映射为核心枚举，核心模块不得复制裸 `2` 魔法值。`KaoPan.taste == null` 映射为两个空字段，该候选不匹配但映射过程不得抛异常。

### 输出

第一阶段保持最小：输出候选 `positionSn: Int?`。不返回或修改 `KaoPan`，不触发任何副作用；该输出不是机械动作授权。

### 状态所有权

- 烤盘、待丢弃位置、订单、设备忙闲态仍由现有 App 管理。
- 核心模块不保存状态，不缓存上一次结果。
- App 适配层负责一次性复制 `nowMs`、`pendingDiscardPositions.toSet()` 和本次所需烤盘字段，形成不可变快照。
- 纯策略只读输入并返回结果。
- 机械 owner、资源锁、最新状态复核和动作结果仍归 `KaoChangOperate`，核心模块不得持有这些状态。

### 时钟、随机数和硬件端口

- 时间由调用方传入 `nowMs`；核心模块禁止直接调用 `System.currentTimeMillis()`。
- 当前选择规则没有随机数；核心模块不引入随机数接口。
- 第一阶段不定义通用硬件接口。机械动作仍在 `KaoChangOperate`，核心模块完全看不到 Modbus/RS485。
- 后续需要抽离动作编排时，再定义窄接口，例如 `MechanicalPort`；不提前设计未使用的抽象。

## 4. 实施步骤

### 步骤 A：锁定现有行为

先扩充现有 `TrafficModeHelperTest`，覆盖：

- 低并发 `1~9`、高并发/过渡/残留 `1~21`；
- 无肠、状态不对、未进入保温、保温已超时；
- 待丢弃烤盘必须排除；
- 口味和商品 ID 不匹配；
- 最早保温优先、同时间小盘位优先；
- `nowMs < holdingTime` 的异常时间必须排除；
- 明确断言 `nowMs == holdingTime` 可售、`nowMs == holdingTime + closeTime` 可售、再增加 `1ms` 不可售；
- 有效业务状态下口味对象非空；另设异常输入测试，证明新核心遇到空 `taste` 会安全排除，但不把这一行为宣称为 2.5.42 旧选择器的等价行为；
- 无候选返回 `null`。

验收：测试明确描述 2.5.42 当前行为，不以注释替代断言。

### 步骤 B：建立纯模块（切片 1A，本轮实现边界）

涉及文件：

- `settings.gradle.kts`：加入 `:scheduler-core`；
- `gradle/libs.versions.toml`、根 `build.gradle.kts`：声明 Kotlin JVM 插件；
- `app/src/test/.../TrafficModeHelperTest.kt`：只补 2.5.42 旧规则特征测试，不让 App 依赖新模块；
- `scheduler-core/build.gradle.kts`；
- `scheduler-core/src/main/.../SellCandidateStrategy.kt`、算法 A 与策略目录；
- `scheduler-core/src/test/.../SellCandidatePolicyTest.kt`。

`scheduler-core` 使用当前 AGP 所需且本机已有的 JDK 17 toolchain；Java `sourceCompatibility/targetCompatibility` 和 Kotlin `jvmTarget` 固定为 11，与当前 App 字节码目标一致，不要求额外安装 JDK 11。

验收：

- `scheduler-core` 源码中不存在 `android.*`、App 单例、MQTT、HTTP、Modbus 或数据库依赖；
- 使用固定种子的边界/组合差分测试，把同一份满足现有业务不变量的不可变输入交给测试侧 2.5.42 规则基准和新策略，至少覆盖 10,000 组，允许差异数为 0；空 `taste` 等异常输入单独验证新核心的安全行为，不混入“完全等价”统计；
- 用算法 A 和测试策略 B 验证目录可按 ID 选择不同实现；重复 ID 创建失败，未知 ID 返回未找到且不自动使用 A；
- 切片 1A 可以修改 App 的测试源码，但不修改 `app/build.gradle.kts`、不修改 App 主源码、不接入订单运行路径。

### 步骤 C：App 适配层（切片 1B，需 1A 通过后单独审核）

在 App 层新增一个很薄的映射器，把当前 `KaoPan` 和 `pendingDiscardPositions` 转成纯输入。

涉及文件必须包含：

- `app/build.gradle.kts`：加入 `implementation(project(":scheduler-core"))`；
- `AppConfigBean.java` 与 `AppConfig.kt`：加入默认关闭的 `enableSchedulerShadowMode`，仅在配置明确为 `1` 时运行影子逻辑；
- App 适配器、只存在于 App 层的 `LegacySellCandidateOracle` 和对应 JVM 测试。Oracle 按 2.5.42 规则解释不可变核心输入，只用于影子对照，不参与机械动作。

限制：

- 在一次选择中只复制一次 `nowMs`、待丢弃集合和烤盘所需字段；旧规则基准与新策略必须使用完全相同的不可变输入；
- 映射器不得写配置、库存或状态；
- 不改变 `KaoPanHelper.isSellable` 的其他调用方。
- `enableSchedulerShadowMode` 默认 `0`；若测试环境尚不能动态下发该字段，禁止进入现场影子观察，不得临时改成默认开启。
- 该字段按补丁语义合并：首次安装和旧配置缺字段时依靠 Java 字段默认值保持 `0`；设备已有本地合法值时，远端缺失、`null`、字符串、浮点、布尔或越界整数均保持当前值，且任何非法输入都绝不能把 `0` 变成 `1`；只有明确的整数 `0/1` 才能更新。不得复用会隐式类型转换的通用 `mergeInt`。
- 1B 的 App JVM 测试必须用固定种子生成满足现有业务不变量的 `KaoPan`、固定 `nowMs` 和显式配置的 `pendingDiscardPositions`，把同样本交给真实 `TrafficModeHelper.selectSellCandidate` 与 Oracle，至少 10,000 组且候选盘位差异数为 0；Oracle 未通过该门禁不得接入影子路径。
- 10,000 组旧选择器等价样本只生成旧代码可接受的非空 `taste/tasteCode`；另设异常映射矩阵覆盖 `taste == null`、`taste != null && tasteCode == null`、未知状态、空待丢弃集合和调用后原集合变化，证明核心输入是防御性快照，但不把这些异常样本宣称为旧选择器等价证据。

### 步骤 D：影子比对

在 `KaoChangAlgorithm.purchase` 现有候选选择点同步计算实际旧结果和影子结果：

1. 始终先调用现有 `TrafficModeHelper.selectSellCandidate` 取得实际旧候选；影子插入点必须位于该调用返回之后、现有 `targetKaoPan == null` 分支之前，使旧结果为 `null` 的样本也能观察；该对象继续传给 `KaoChangOperate.takeSausageResult`，影子开关关闭时执行路径与 2.5.42 相同；
2. 只有开关为 `1` 时，才在实际旧候选取得之后进入一个整体 `runCatching`；任何快照映射、Oracle、新策略、比较或日志异常都不得向订单路径传播；
3. 在影子块内只复制一次 `nowMs`、`pendingDiscardPositions.toSet()` 和烤盘字段；先把这份输入交给 `LegacySellCandidateOracle`，再把同一对象交给新核心策略；两者都只输出候选 `positionSn`；
4. Oracle 与新核心不同记为“算法差异”。实际旧候选与 Oracle 不同记为“未分类基准偏差”，可能来自采样时点、Oracle 缺陷或现有全局状态变化，不能未经调查直接归因，也不能影响实际出餐；
5. 算法差异、未分类基准偏差或影子计算异常调用 `LogUtils.dLocalOnly`；影子启用时还必须在首次比较和每累计 25 次比较时输出一条 `shadow_sample` 本地日志，包含本进程比较次数、算法差异数、基准偏差数和异常数，用来证明影子确实执行；计数器使用一个私有同步临界区原子更新并生成同一时点的不可变计数快照，计算和日志输出在临界区外执行，避免丢计数、重复采样或超过明细预算；
6. 禁止使用会写文件/上云的 W/E 或关键字日志。每个进程最多输出前 20 条逐项差异/异常，之后只累加内存计数；日志只含进程观察会话 ID、模式、候选盘位、计数和原因码，不含订单号、设备号或顾客信息；
7. 不新增线程、协程、Timer、Handler 或后台轮询；正向采样由现有订单调用同步计数，且不得影响返回结果；
8. 不发送 MQTT/HTTP，不写数据库，不改变 UI，不触发硬件；
9. `KaoChangOperate` 的 `out1` 前最新状态复核保持原样，影子候选绝不能绕过它。

生产接线采用窄门禁：

- `SchedulerShadowObserver.observe(...)` 返回 `Unit`，API 不返回 `KaoPan` 或候选盘位给调用方；
- `LegacyCandidateShadowGate.observeAndKeep(...)` 只接收真实旧候选对象和返回 `Unit` 的观察函数；无论观察结果不同、旧候选为空或观察抛异常，均原样返回同一个旧候选引用；
- `KaoChangAlgorithm.purchase` 只把该门禁返回的旧候选传入既有 `KaoChangOperate.takeSausageResult`，禁止按影子盘位反查 `KaoPan`；
- 影子运行时自身捕获映射、Oracle、策略、比较和日志 sink 的全部异常；日志 sink 可注入用于 JVM 测试，生产 sink 只调用 `LogUtils.dLocalOnly`，即使 sink 抛异常也不得越过门禁传播；
- 增加接线 JVM 测试和源码结构门禁，分别证明对象引用恒等、异常隔离，以及 `takeSausageResult` 的参数仍来自旧候选门禁而非影子结果。

并发与异常测试门禁：

- 多线程并发记录至少 1,000 次，最终比较次数必须精确一致；
- 首次及每 25 次采样不得重复，明细日志总数不得超过 20；
- 旧结果为空而 Oracle/算法非空时只记录基准偏差，调用方仍走原有无库存分支；
- 映射、Oracle、算法和日志 sink 分别抛异常时，门禁均原样返回旧候选且不向订单路径传播。

验收：无论影子结果如何，设备实际使用的仍是同一个旧候选对象。

### 步骤 E：切换与清理（需单独批准）

只有满足以下条件后，才讨论让新策略成为执行依据：

- 纯模块矩阵测试通过；
- App 现有相关 JVM 测试通过；
- 先在 1 台明确指定的测试设备观察至少 72 小时且完成至少 100 次测试订单候选选择，覆盖低并发、高并发和过渡/残留场景；算法差异允许数为 0，未分类基准偏差必须逐项查明后才能决定是否继续；
- 出现任何影子异常、任何差异、订单路径异常或明显时延回归，立即把开关下发为 `0` 并停止观察；
- 使用匹配版本 APK 完成真实订单、MQTT 和 RS485/机械验证；
- 测试电脑使用连续 `adb logcat` 采集并保存 `shadow_start`、`shadow_sample`、差异和异常日志；必须由采样计数证明比较次数达到 100，不能用“没有错误日志”代替；
- App 进程重启、logcat 采集中断、观察开关被关闭或采样序号不连续时，本轮 72 小时观察作废并从新进程会话重新计时；
- 观察设备、APK 版本、设备号、进程观察会话 ID、时间范围、订单样本和日志文件 SHA-256 全部留档；
- 已验证运行时开关确实能停用影子逻辑，并准备单提交回滚路径。

第一阶段计划本身不授权执行切换。

## 5. 验证分层

### 源码静态证据

- 依赖方向：App → `scheduler-core`，核心模块不能反向依赖 App。
- 核心策略无全局单例、无系统时钟、无 I/O、无线程。
- 旧规则候选仍是机械出餐的唯一候选输入；`out1` 前最新状态复核仍是授权点。

### 自动化测试证据

- `./gradlew :scheduler-core:test`
- `./gradlew :app:testDebugUnitTest` 或仓库现有相关测试任务
- 如修改 Gradle 配置，再运行 `./gradlew assembleDebug`

这些只能证明编译和 JVM 行为，不能证明 MQTT、RS485 或机械动作真实成功。

### 现场证据

后续切换前至少需要：

- 匹配 APK 版本、设备号和时间范围；
- 新旧候选比对日志；
- 对应订单号；
- MQTT 下发/ACK 记录；
- `out1~out5` 下位机结果寄存器和动作结果；
- 出餐后烤盘、库存和订单最终状态。

## 6. 回滚边界

- 步骤 A/B：回滚对应提交，同时撤销 `settings.gradle.kts`、版本目录、根构建配置、纯模块和测试；不涉及业务数据迁移。
- 步骤 C/D：先把 `enableSchedulerShadowMode` 下发为 `0`；需要代码回滚时撤销 App 模块依赖、配置字段、适配器和影子调用。旧规则与 `out1` 前复核从未被替换。
- 禁止在同一提交中顺便修改机械动作、MQTT 协议或库存状态。
- 每个步骤独立提交，出现问题可按提交回滚。

## 7. MQTT 5 评估

### 当前事实

- Android 使用 `org.eclipse.paho.client.mqttv3:1.2.5` 的 v3 API、`MqttAsyncClient`、QoS 1；连接参数未显式锁定 `mqttVersion`，源码只能证明使用默认版本协商，不能断言现场固定为 MQTT 3.1.1。
- Android 使用 `tcp://`、`cleanSession=false`、应用自管重连和发布队列。
- Android 使用 `MemoryPersistence`。
- 后端同样使用 Paho MQTT v3 类型和 Spring Integration MQTT，QoS 1、`tcp://`。
- 订单、运行态、可售态等已有业务层序号、ACK、重试/补偿，MQTT 版本不能替代这些业务保证。

### MQTT 5 的实际收益

- 消息过期：减少离线后收到过时控制消息的风险；
- 更明确的原因码/断开原因：改善诊断；
- Session Expiry：比 MQTT 3 的 clean session 表达更清楚；
- Response Topic/Correlation Data、User Properties：可改善协议可观测性和请求响应关联。

### 当前不升级的理由

1. 调度解耦不依赖 MQTT 5；
2. Android、后端、Broker、运维监控必须一起验证，不能只换 App 依赖；
3. 现有业务协议已有序号、ACK 和 HTTP 补偿，短期收益小于迁移风险；
4. MQTT 5 不解决机械幂等、库存一致性、订单最终态或 RS485 可靠性；
5. 当前 `tcp://` 未体现 TLS，协议升级本身也不会自动加密；
6. `cleanSession=false` 配合内存持久化的重启可靠性值得先单独评估，这比直接升 MQTT 5 更贴近当前风险。

### 建议决策

本阶段保持现有 Paho v3 API 和默认版本协商，不变更 MQTT 客户端协议栈。把 MQTT 5 作为独立 P2 技术升级，满足以下触发条件再启动：

- 已确认 Broker 版本、MQTT 5 能力和 TLS 方案；
- 有明确问题需要消息过期、原因码或响应关联解决；
- Android/后端可先做兼容性环境和灰度设备；
- 具备断网、重连、重复消息、过期消息、Broker 重启和客户端重启测试；
- 能按设备或环境回退到现有 MQTT 3 通道。

优先顺序建议：先确认 TLS 和持久化需求，再做 MQTT 5 试验；不要与调度核心切换同批发布。

## 8. 第一阶段明确不做

- 不重写 `KaoChangAlgorithm`；
- 不抽象整套通用状态机；
- 不改补货、自清洁和维护流程；
- 不改 MQTT/HTTP 消息字段；
- 不改 Modbus 寄存器、动作组、锁和 session；
- 不清理 UVC/OpenCV 历史代码；
- 不改变现场设备配置和 APK。
