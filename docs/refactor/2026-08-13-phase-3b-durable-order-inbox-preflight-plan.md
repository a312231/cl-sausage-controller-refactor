# 第三阶段 3B：耐久订单 Inbox 预研与实施方案

日期：2026-08-13
上位机源码基线：`fad00d530bb88c0fb3ace82dcdda038f24626438`、`versionName=2.5.42`、`versionCode=292`
后端只读审计基线：`bfaebdad8ad7553d0d254069aa0cd686eeb6b570`

说明：源码版本不代表现场 APK。本文是 3B 预研和实施门禁，不授权发布、部署或设备操作。

## 1. 本阶段目标与边界

3B 的目标不是立刻“断点续出餐”，而是先确保一张订单只有在完整执行快照同步落盘成功后，才能进入任何订单副作用和机械链路。

必须满足：

1. 完整订单执行快照先同步落盘，再暂停补肠、切工作态、取得整单 claim 或下发 out1；
2. 重复消息、冲突消息、损坏记录和落盘失败均 fail closed；
3. 冷启动只恢复为 HOLD/待复核，不在 3B 自动继续整单；
4. 整单 inbox、整单 claim、单根动作 session/out1 证据、单根事件 outbox 仍保持独立所有权；
5. 调度算法 A/B/C 只选择可售候选，不参与订单授权、inbox 状态、网络判断或恢复；
6. 不改下位机、Modbus 寄存器或 out1~out5；不升级 MQTT 5。

3B 不实现：

- 服务端订单重验证接口、`dispatch_id/order_version/expires_at_ms` 强制协议；
- 多根订单冷启动自动续作；
- 多事件 durable outbox 与服务端业务 ACK；
- 自动退款、取消或本地推断支付事实；
- 真实 MQTT/RS485/机械验证。

## 2. 当前源码事实

### 2.1 QoS 1 回调与业务接单不是同一个提交点

Paho `messageArrived` 内同步调用业务监听器，但 `MainActivity` 监听器只调用 `runOnUiThread` 投递后立即返回。真正的订单校验、准入和出餐在主线程稍后执行。

因此当前 MQTT 客户端回调完成不等于：

- 订单已被业务校验；
- 完整订单已落盘；
- 设备已接受；
- 机械动作已开始或完成。

证据：

- `app/src/main/java/cn/niuyannet/kaochang/android/mqtt/VMMqttHelper.kt:406-448`；
- `app/src/main/java/cn/niuyannet/kaochang/android/ui/MainActivity.kt:230-315`。

### 2.2 当前 ACCEPTED_PROCESSING 只在内存

`OrderStateManager.checkAndAcceptOrder` 只把 `STATE_ACCEPTED_PROCESSING` 放入 `ConcurrentHashMap`。只有 FINISHED/FAILED 终态使用 `SharedPreferences.commit()` 同步落盘。

进程退出后：

- `ACCEPTED_PROCESSING` 消失；
- 完整 `goods_items` 不存在于 `OrderStateManager`；
- 是否还能依赖 Broker 重投不由 App 保证。

证据：`app/src/main/java/cn/niuyannet/kaochang/android/utils/OrderStateManager.kt:30-70,147-245`。

### 2.3 完整订单只存在于 Activity 协程参数

`MainActivity` 准入后调用 `purchaseOrder(orderMessage)`；`OrderActivity` 在 IO 协程里重新解析原始字符串，再按商品项和数量串行执行。当前没有统一耐久记录保存：

- 原始授权元数据；
- 全部商品项；
- 稳定的每根序号；
- 已完成数量；
- 当前项/下一根位置。

证据：`app/src/main/java/cn/niuyannet/kaochang/android/ui/OrderActivity.kt:182-336,504-757`。

### 2.4 整单 claim 与单根动作 session 已耐久，但不能替代 inbox

- `OrderDeliveryClaimStore` 同步保存 `orderNo/owner/state/revision/createdAtMs`；
- `SharedPreferenceActionGroupSessionStore` 同步保存单根动作上下文、当前动作和 out1 证据；
- 两者都不保存整单商品列表和剩余数量。

证据：

- `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/OrderDeliveryClaim.kt:8-107`；
- `app/src/main/java/cn/niuyannet/kaochang/android/services/actiongroup/SharedPreferenceActionGroupSessionStore.kt:10-55`。

### 2.5 旧残留 claim 自动收口会绕过新 inbox，必须同步治理

冷启动后内存 processing 集合为空时，`staleOrderClaimProcessingCanClose` 当前直接允许进入残留 claim 收口。后续会：

1. 读取两次下位机空闲证据；
2. 必要时同步写入 FAILED 终态；
3. 原子清除整单 claim 并把 `moveStatus` 收为 0。

这是当前没有耐久 accepted inbox 时的安全止损，但 3B 接线后如果不改这条判定，会把“已耐久接受、等待服务端复核”的订单误当孤儿失败单。

证据：

- `OrderStateManager.kt:94-112,182-194,314-323`；
- `KaoChangOperate.kt:905-1014,1031-1053,3340-3360`。

### 2.6 补肠暂停也是进程内状态

`SupplementPauseController.pauseOrderNo` 只在内存。冷启动若存在 accepted inbox，不能依赖旧 pause 自动恢复；启动门禁必须在算法启动前识别 inbox HOLD，否则补肠调度可能先恢复运行。

证据：

- `app/src/main/java/cn/niuyannet/kaochang/android/services/SupplementPauseController.kt`；
- `MainActivity.kt:125-203`；
- `MyApp.kt:18-43`。

### 2.7 当前新旧两侧协议缺少可重验证字段

后端下发内容只有 `device_id/order_no/goods_items/server_dispatched_at_ms/pay_time_ms`，没有：

- `dispatch_id`；
- `order_version`；
- `expires_at_ms`；
- 设备业务 accepted/rejected ACK。

`mqttOutputChannel.send=true` 只证明消息被发送通道接受，不证明设备业务已耐久接受。

证据：

- 后端 `MqttHelper.java:176-207,2514-2533`；
- 后端 `MpOrderService.java:791-853`。

### 2.8 App 与后端订单 ID 类型已经不一致

后端 `KcOrderItem` 的 `id/orderId/productId/quantity` 是 `Long`；App 的 `KcOrderItem` 对应字段是 `int`。3B 新耐久模型不得继续截断标识符，建议把业务 ID 规范化为非空十进制字符串，数量单独使用受限 Int。

在旧执行/上报链仍要求 Int 的兼容期，超出 Int 范围必须 HOLD/拒绝并诊断，不能静默截断。

证据：

- 后端 `ruoyi-admin/src/main/java/com/ruoyi/cl/domain/KcOrderItem.java`；
- App `app/src/main/java/cn/niuyannet/kaochang/android/mqtt/KcOrderItem.java`。

## 3. 当前崩溃窗口

| 窗口 | 当前结果 | 3B 目标 |
|---|---|---|
| MQTT 回调返回后、主线程处理前崩溃 | 没有业务记录，依赖不确定重投 | 未提交 inbox，不得产生订单副作用 |
| 内存 ACCEPTED 后、暂停补肠前崩溃 | processing 消失 | 先提交 inbox；冷启动 HOLD |
| 暂停补肠后、`onlineStatus=4` 前崩溃 | pause 也随进程消失 | inbox 成为启动门禁唯一业务依据 |
| `onlineStatus=4` 后、整单 claim 前崩溃 | 完整订单仍未落盘 | inbox 可解释“已接受、机械未开始” |
| 整单 claim 后、单根 session 前崩溃 | 只有 orderNo/owner | inbox + claim 联合判定，禁止旧自动失败收口 |
| out1 PREPARED/未知时崩溃 | 有单根 session 证据 | 只机械恢复，绝不重发 out1 |
| 某根完成后、下一根开始前崩溃 | 没有完整剩余进度 | 3B 只 HOLD；3E 前不得自动继续 |
| 整单终态后、claim 清理前崩溃 | 终态与 claim 分库存储 | 后续以整单终态、释放资格和精确 revision 收口 |
| claim 已清、云端事件未确认时崩溃 | 现有单槽补偿可能丢 | 由 3D durable outbox 解决，不伪装成 3B 已解决 |

## 4. 目标数据模型

### 4.1 一个权威快照，而不是继续堆散落字段

建议 `OrderInboxSnapshot` 使用单个同步提交键，包含：

- `schemaVersion`；
- `snapshotRevision`；
- `activeRecord`：最多一张活动订单；
- `terminalTombstones`：V2 可按确定门禁清理；legacy 在没有服务端重验证或不可删除已见索引前不得清理的防重记录。

`ActiveOrderRecord` 最少包含：

- `protocolKind`：`LEGACY_V1` / `DISPATCH_V2`；
- `orderNo`；
- `deviceId`；
- `dispatchId`、`orderVersion`、`expiresAtMs`（旧协议可空）；
- `receivedAtMs/serverDispatchedAtMs/payTimeMs`；
- `canonicalPayloadSha256`；
- 有序 `items`；
- `admissionStage`：`PERSISTED_PENDING_PREPARATION`、`READY_FOR_EXECUTION`、`HOLD_REQUIRED`；
- `holdReason`：仅保存稳定原因码，不把异常长文本作为状态；
- `lifecycleStage`；
- `wholeOrderOutcome` 与 `claimReleaseEligibility`；

只有 `claimReleaseEligibility=RELEASE_ALLOWED` 的耐久整单终态才允许转换为 terminal tombstone，且 `terminalAtMs` 必须直接继承终态记录的更新时间。`HOLD_REQUIRED`、证据未知或失败终态必须继续保留 active/HOLD 证据，不能通过生成 tombstone 提前释放或掩盖未闭环状态；3B-3 接线还必须同时满足 claim、session 与 outbox 的安全关闭门禁。
- `recordRevision/createdAtMs/updatedAtMs`。

`OrderItemSnapshot`：

- `itemId/productId`：规范化十进制字符串；
- `tasteCode`：非空、长度受限；
- `quantity`：正数；
- `firstDeliveryOrdinal`：整单稳定起始序号。

不把以下状态复制进 inbox：

- 整单 claim owner/revision；
- 单根动作 session、out1 证据；
- Modbus 寄存器；
- MQTT 连接状态；
- 调度策略 ID；
- 单根事件 outbox ACK。

这些状态仍由各自所有者提供，决策时组成一致性快照。

### 4.2 校验规则

首次接受前至少校验：

- `device_id` 与当前设备一致；
- `order_no` 非空且长度受限；
- 商品项非空；
- 每项 ID 为正十进制数，口味非空；
- 每项数量为正，整单总数量为 `1..4`（与当前后端上限一致）；
- 求和使用防溢出逻辑；
- V2 字段存在时格式、版本和有效期一致；
- canonical hash 只覆盖执行必需字段，不能受 JSON key 顺序影响。

checksum/hash 只能检测重复和意外损坏，不宣称防恶意篡改。

canonical hash 必须只覆盖稳定执行字段：协议种类、order/device、V2 dispatch/version/expiry 和有序 itemId/productId/tasteCode/quantity。不得包含 `receivedAtMs/serverDispatchedAtMs/payTimeMs`、商品名称/图片或 JSON key 顺序；这些字段变化不能把同一执行授权误判为另一张订单。

### 4.3 去重与冲突

- 同 `dispatch_id`（V2）或同 `orderNo`（legacy）且 canonical hash 相同：`DUPLICATE_SAME`，不重复暂停、切状态或启动协程；
- 同稳定键但 hash 不同：`CONFLICT`，HOLD/诊断，绝不执行；
- 已有另一张 active order：`BUSY_OTHER_ORDER`；
- terminal tombstone 命中：`DUPLICATE_TERMINAL`；
- 同步提交失败：`STORAGE_FAILED`，不得继续任何订单副作用。

V2 tombstone 只有在授权已过期、相关 outbox 已确认且不存在 claim/session 后才能清理，并按 `terminalAtMs + stable key` 确定性排序。Active、Corrupted 以及仍有关联 claim/session/outbox 的证据永不参与容量清理；设备时钟回拨时暂停基于时间的清理。

legacy 没有协议有效期，本地删除 tombstone 后无法再判断同一 `orderNo` 是否执行过。因此在 3C 服务端重验证或不可删除的已见订单索引落地前，legacy tombstone **不得清理**，也不使用旧实现的 24 小时 TTL。以后若启用清理，必须同时启用“所有 legacy 消息先服务端复核”的全局能力模式，不能根据已经删除的本地记录推断这是一张新订单。

## 5. 存储与原子性

### 5.1 3B 最小实现选择

推荐继续使用 App 已有的 app-private SharedPreferences，但必须：

- 与 `OrderDeliveryClaim` 使用同一 `AppConfig.Sp_File_URL`；
- 使用 `commit()`，不得用 `apply()`；
- 单次提交完整 `OrderInboxSnapshot`；
- 读取必须区分 `Absent/Valid/Corrupted`；
- 所有 read-check-write 在现有单进程订单/机械过渡锁内完成；
- 明确这是单进程 CAS，不宣称跨进程数据库事务。

当前设备一次只允许一张活动订单、单订单最多 4 根，不需要为 3B 引入 Room/SQLite 和新的数据库迁移。若以后要求多进程或多活动订单，再单独评估数据库。

### 5.2 必须原子提交的组合

后续接线至少需要两个组合提交入口：

1. `accept inbox`：先提交 `PERSISTED_PENDING_PREPARATION`；成功后才允许取得手动 OUT 恢复预留、暂停补肠/写 barrier；这些准备都成功并再次同步提交 `READY_FOR_EXECUTION` 后，才允许设置内存 processing、切工作态或启动订单协程；
2. `whole-order closeout`：位于 `AppConfig.Sp_File_URL` 同一 SharedPreferences 文件内的整单终态、释放资格、claim 和 `moveStatus/onlineStatus` 字段必须由单个 editor/commit 提交；`order_states_sp` 只能是迁移兼容镜像。

如果 inbox 首次提交失败，必须没有 manual-OUT reservation、pause、barrier、工作态或 claim 副作用。如果首次提交成功但后续 reservation/pause/barrier/READY 提交失败，必须把记录保持或迁移为 `HOLD_REQUIRED`、释放只存在于内存的 reservation、进入维护并停止启动 `purchaseOrder`；不得删除已接受证据后退回旧内存流程。

若写入 `HOLD_REQUIRED` 的第二次 commit 自身失败，磁盘上的 `PERSISTED_PENDING_PREPARATION` 仍是有效的 fail-closed 证据；进程内 maintenance/inbox fence 必须继续生效，禁止启动订单、禁止删除 pending、禁止回退旧内存接单。重新启动后 pending 一律按 HOLD 处理。

当前 `requestSupplementPauseForOrder` 返回 `Unit`，barrier 写失败时只进入维护但调用方仍继续。3B 接线必须把它重构为显式准备结果，准入协调器根据结果决定 READY 或 HOLD，不能只看日志/设备状态猜测成功。

现有 `order_states_sp` 可在迁移期作为旧终态防重只读 fallback 或 best-effort 镜像，但不能继续作为新订单 accepted/整单终态的第一权威，否则跨两个 SP 无法原子提交。上述原子性只覆盖同一 SharedPreferences 文件的一次 editor/commit；不声称与 `order_states_sp`、内存 processing/pause、MQTT、Modbus、RS485、机械动作或突然断电构成事务。`commit()==true` 也不代表服务端、寄存器或机械已经确认。

## 6. 冷启动门禁

同步读取并建立进程内 inbox fence，必须早于 `enforceEmergencyResetRecoveryOnStartup`、`DataManagementAPI.dataSyncServer()`、`PowerLossRecoveryGuard`、业务启动纠偏、算法启动和新单 MQTT listener。inbox fence 与 emergency-reset fence 组合使用，任一要求 HOLD 都不得被另一条恢复路径解除：

1. 读取 inbox，区分 absent/valid/corrupted；
2. 读取整单 claim；
3. 读取单根 session/out1 证据；
4. 组合为 3A `OrderContinuitySnapshot`；
5. 在 3C 有服务端重验证前，任何 accepted active record 冷启动都进入 HOLD，不自动继续；
6. inbox 损坏时进入维护、算法关闭、保留原始证据，不自动清空；
7. 修改旧残留 claim 自动收口门禁：accepted inbox 存在时不得自动写 FAILED 或清 claim；
8. 防止 `recoverBusinessStartIfNeeded` 或远端配置在 HOLD 未处理时重新开启调度；
9. `DataManagementAPI` 的库存/配置等请求必须在“发起前”和“响应应用前”分别检查 fence：HOLD 建立后不再发出会改变业务运行态的请求，已经在途的迟到响应也不得覆盖库存安全快照、营业态或算法开关；只允许保存诊断所需的只读网络证据。

网络连接不是机械急停信号；如果已有 `PREPARED_OR_UNKNOWN/DISPATCHED` 单根 session，仍按机械证据恢复/安全收口。

## 7. 绞杀式实施切片

### 3B-1：纯 JVM inbox 模型和校验器

只修改：

- `order-core/src/main/kotlin/**`；
- `order-core/src/test/kotlin/**`；
- 本方案/验证文档。

内容：纯模型、规范化、校验、hash、去重/冲突和状态迁移。无 Android、MQTT、HTTP、Modbus、数据库依赖，不接入 App。

### 3B-2a：启动读取、HOLD fence 与 stale-claim 阻断

这是首个行为切片，必须在用户确认业务口径后单独实施：

- Android store/codec 与首个真实启动读取点同切片引入；
- 在所有恢复、云同步、算法和 MQTT listener 之前同步读取 inbox 并建立 fence；
- 请求发起和迟到响应应用都受 fence 约束；
- `Absent` 才允许进入旧兼容判断；Valid accepted、pending、HOLD 或 Corrupted 都必须阻断 stale-claim 自动失败收口；
- 仍不自动冷启动续作，也不接在线订单执行。

### 3B-2b：在线准入耐久提交与执行接线

在 3B-2a 门禁通过后再单独实施：

- 严格解析并构造 typed envelope；
- 在当前机械过渡锁内先同步提交 inbox pending，再执行 manual-OUT reservation 与 pause/barrier 准备，最后提交 READY；
- 任一准备失败都持久化 HOLD、释放内存 reservation、禁止设置 processing/工作态或启动协程；
- `purchaseOrder` 从已提交记录取执行快照，不再依赖原始 MQTT 闭包；
- 仍不自动冷启动续作。

不先加入“主链路永远不调用”的生产 store/codec，以免制造未使用抽象。Android store、App 对 `order-core` 的依赖和第一处实际启动读取调用在 3B-2a 同一切片引入。

由于 legacy payload 没有协议有效期，3B-2 只有在以下任一前提成立后才能启用 legacy 行为接线：

- 用户明确批准可执行的新鲜度窗口和时钟偏差规则；或
- 3C 已提供服务端重验证/能力协商。

否则只允许 V2/受能力开关保护的新路径，不能把“MQTT 当前已连接”当作旧积压消息仍有效的证明。

### 3B-3：整单终态与 claim 原子收口迁移

- inbox 写入整单终态和释放资格；
- release-allowed 才精确清 claim；
- hold-required 保留 claim 并进入维护；
- legacy `OrderStateManager` 只保留迁移兼容，不再拥有 accepted 状态。

3C 提供服务端重验证/dispatch ACK，3D 提供 durable outbox，3E 才允许从每根合法检查点恢复剩余整单。

## 8. 自动化门禁

### 3B-1

```powershell
.\gradlew.bat :order-core:test --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :scheduler-core:test :recovery-core:test :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat test assembleDebug --no-daemon --console=plain
git diff --check
```

必须测试：

- 字段缺失、空 items、数量 0/负数/>4、求和溢出；
- Long 业务 ID 不截断；
- 3B-1 的 typed canonicalizer 不接收原始 JSON，证明 hash 不受接收/派单/支付时间、商品名称和图片变化影响；稳定 ID、口味、数量、商品顺序以及 V2 dispatch/version/expiry 任一变化时 hash 必须变化；
- same/conflict/busy/terminal/storage failure；
- 所有失败均无“开始机械”输出；
- 调度策略 ID 不出现在 inbox 输入、存储或决策中；
- Java 11 class major 55、零 Android/通信依赖。

### 3B-2a/3B-2b/3B-3

除全量测试/构建外，增加源码和故障注入门禁：

- commit 失败后 processing/pause/onlineStatus/claim 全不改变；
- manual-OUT reservation 不得早于 inbox pending 提交，后续失败必须精确释放；
- pause/barrier 失败后 inbox 仍在且为 HOLD，`purchaseOrder` 未启动；
- HOLD commit 自身失败时保留 pending，进程内 fence/维护态继续生效且 `purchaseOrder` 未启动；
- pending 提交后崩溃、pause 后 READY 提交前崩溃均在冷启动进入 HOLD；
- commit 成功后立即模拟进程死亡，冷启动读到 valid accepted record 并 HOLD；
- corrupted record 不按 absent 处理；`Corrupted inbox + stale claim` 必须保留 claim、不得写 FAILED、不得清 `moveStatus`；
- duplicate same 不重复启动协程；
- conflict 不覆盖原记录；
- accepted inbox 阻断旧 stale-claim 自动失败收口；
- `MyApp` 云同步、库存响应、配置响应、`PowerLossRecoveryGuard`、业务启动纠偏、算法或 MQTT listener 早于 inbox fence 的源码路径测试必须失败；
- HOLD 建立后的新请求不得发起，在途库存/配置迟到响应不得应用业务状态；
- 3B-2b App 适配器必须用不同 JSON key 顺序解析出相同 typed record/hash；这项证据不能由不解析 JSON 的 3B-1 测试替代；
- App 仍不把 MQTT 断开作为 active mechanical stop。

## 9. 回滚边界

- 3B-1：删除纯模型/测试即可，无运行数据；
- 3B-2a 的回滚包必须保留 inbox 兼容 reader 和 fail-closed fence；
- 3B-2b 开始写入 inbox 后，禁止直接降级到不识别该格式的 2.5.42。只能回滚到包含兼容 reader/fence 的桥接包；若确需降级到旧 APK，必须先在受控维护流程中证明不存在 Active/Corrupted/pending/HOLD、claim/session/outbox 未收口证据，并完成人工安全收口，不能靠删除 key 伪造完成；
- 禁止自动清理 corrupted/unknown inbox；
- 混合版本期间后端继续支持旧 payload，不可先强制新字段。

## 10. 实施前需要用户确认

1. 已在正常连接时完整校验并同步落盘的付款订单，随后断网是否继续同进程履约？推荐：继续，网络不是机械急停。
2. 冷启动读到 accepted inbox、但机械尚未开始：推荐联网重验证，离线 HOLD；是否同意？
3. 冷启动读到 `PREPARED_OR_UNKNOWN/DISPATCHED`：推荐只机械恢复/安全收口，禁止重发 out1；是否同意？
4. inbox 同步落盘失败或损坏：推荐拒绝新机械动作并进入维护/诊断，不降级回内存接单；是否同意？
5. 旧 payload 在混合版本期没有有效期：推荐 3C 服务端重验证完成前不启用 legacy 冷启动/积压单自动执行；若希望 3B 先接 legacy，必须另行确定最大消息年龄和允许时钟偏差；是否同意优先走服务端重验证？
6. 推荐保持 MQTT 3，后续增加应用层 `dispatch_id + accepted/rejected ACK + durable outbox`；是否同意？

## 11. 证据边界

本方案已验证当前上位机/后端静态源码、Git 基线和现有持久化调用方式。

未验证：现场 APK/后端/Broker 配置、Paho 实际重投时序、真实支付与退款、断电文件系统行为、真实 MQTT/RS485/Modbus/out1~out5/机械动作。

因此方案审核、JVM 测试或 APK 构建均不能替代真实断网、Broker 和机械联调。
