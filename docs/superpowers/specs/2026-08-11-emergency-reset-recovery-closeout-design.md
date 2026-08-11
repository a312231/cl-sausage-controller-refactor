# 紧急复位冷重启收口设计（临时止血版）

## 目标与范围

仅把管理员点击的“紧急复位”定义为强制中止边界。普通复位保持现状。

本次优先解决现场设备因旧 `moveStatus`、订单 claim、动作组 session、暂停 session 或损坏的双 lane session 无法恢复的问题，不把 generation/任务令牌传播到所有订单和机械 runner。

紧急复位不会自动修改烤盘、烤肠箱库存、搬盘/丢弃 journal、待丢弃事实、补肠批次或订单耐久终态。现场不需要清空整个烤盘；恢复营业前只需确认机械通道没有悬空/卡住烤肠，并核对可能受影响的位置。

## 为什么使用冷重启

当前 App Manifest 没有业务组件声明独立 `android:process`，订单页面、算法、动作 runner、Modbus 和 MQTT 均运行在同一 App 进程。`999=1` 成功后执行一次可验证的 App 冷重启，可以直接终止复位前仍存活的协程和内存 owner，替代全链路 generation 令牌改造。

持久化 fence 记录创建它的 `processInstanceId`。恢复入口只有在当前进程实例与 fence 记录不同，即已经发生过真实冷重启时才允许继续，不能用 Activity 重建冒充冷重启。

## 紧急复位流程

1. 管理员二次确认。
2. 本地同步持久化 `onlineStatus=3`、`isEnable=0`，关闭算法和接单。
3. 立即通过现有 MQTT 运行态上报并使用 HTTP 补偿通知服务端进入维护。上报失败不阻止安全复位，但设备保持维护并继续重试，不能直接恢复营业。
4. 保存 `PREPARED` fence，内容至少包括随机 `resetId`、当前 `processInstanceId` 和创建时间。
5. 在 Modbus 写入串行边界内阻止除 `999=1` 外的新机械命令，然后写入 `999=1`。
6. 写入失败：保留 `PREPARED` 供管理员重试，设备保持维护，不清协调状态、不重启。
7. 写入成功：fence 转为 `RESET_WRITTEN`，通过系统 `AlarmManager` 的精确 idle alarm 安排 `StartInitActivity` 的 `PendingIntent`；Android 12+ 先确认 `canScheduleExactAlarms()`，只有调用成功后才终止当前 App 进程。能力不可用或调用异常时不杀进程，设备继续维护并提示重试。
8. Android 系统重新启动 App；启动流程发现 fence 后必须保持维护、禁止营业时间自动纠偏、禁止算法和旧 session 恢复。

如果已有 `RESET_WRITTEN` fence，管理员仍可再次确认并执行紧急复位；本次操作创建新的 `resetId` 和新的冷重启边界，避免首次复位后机械仍不空闲时形成死路。

## 冷重启可靠性

- 使用系统 `AlarmManager` 持有的 Activity `PendingIntent`，而不是进程内 `Handler`；Android 12 声明 `SCHEDULE_EXACT_ALARM`，Android 13+ 声明 `USE_EXACT_ALARM`。
- 使用 `setExactAndAllowWhileIdle`，不使用可能被 Doze 长期延迟的非精确 `setAndAllowWhileIdle`。
- 只有系统重启任务安排成功后才调用 `Process.killProcess`。
- `PhotoCaptureService` 当前为 `START_STICKY`，可作为系统重新拉起 App 进程的辅助条件，但不作为唯一恢复依据。
- fence 中的 `processInstanceId` 必须与重启后的当前实例不同，否则“检查并恢复设备”保持阻断。

## 检查并恢复

1. fence 必须是 `RESET_WRITTEN`，且已经跨过真实 App 冷重启。
2. 设备仍是维护状态且算法关闭。
3. 管理员确认现场没有悬空/卡住的烤肠，并已核对可能受影响的烤盘和箱位；不要求清空整个烤盘。
4. Modbus 已连接，连续两次读取 `201=0`、`203=0`，全部已知 `300/400` 结果寄存器读取完整且为 `IDLE` 或 `COMPLETED`，两次间隔 500ms。`203` 读取失败、执行中或残留非空闲值均保持维护。
5. 一次 SharedPreferences `commit()` 清理：
   - `moveStatus`
   - 订单 claim
   - 普通动作组 session
   - 暂停补肠 session
   - 双 lane session，包括损坏原始值
   - 紧急复位 fence
6. 清理成功后再走现有恢复营业流程；任一步失败都保持维护。

## 并发边界

临时版不再维护全局 mechanical generation，也不修改所有 store 的通用语义。

需要保留两个最小门禁：

1. fence 存在时，高层订单准入、算法启动、动作恢复入口全部拒绝启动新机械任务。
2. 紧急复位写入与其他机械命令共用 Modbus 写入串行边界。边界关闭后，只允许本次 `999=1`，防止旧协程在 999 之后补发机械命令。

启动期和首页后台刷新同样受 fence 约束：远端设备配置、运行态和库存请求在发起前检查 fence；异步响应的“最终检查 + 本地落盘 + 状态副作用”与建立 fence 共用同一状态转换锁，关闭检查通过后再建立 fence 的 TOCTOU 窗口。MQTT `runtime_control` 和 `concurrency_control` 也在同一锁内拒绝 pending fence，并返回明确未应用 ack。fence 存在时仅允许上传本地维护运行态，不允许云端旧值覆盖 `onlineStatus=3/isEnable=0`、并发状态或待现场核对的库存。

旧协程的最终取消由冷重启完成；重启后的 fence 阻止它们从持久化 session 自动续跑。

## 明确保留的数据

- 烤盘位置、烤制/保温计时
- 烤肠箱库存
- 搬盘 journal
- 丢弃 journal 和待丢弃位置
- 补肠批次剩余目标
- 订单耐久终态和云端订单数据

完全无人到现场时，App 可以远程完成紧急复位和软件协调态清理，但不能仅凭下位机空闲证明烤肠物理位置与库存一致；未完成人工现场确认时只能保持维护，不能自动恢复营业。

## 测试和发布门禁

- Alarm 安排失败不得杀进程。
- 未跨进程实例不得执行强制收口。
- fence 存在时启动流程不得自动恢复算法、营业态或旧 session。
- 999 之后到进程退出之间，其他机械命令必须被拒绝。
- 旧订单/动作协程在冷重启后不存在，不能重新认领。
- 重复紧急复位生成新边界并可再次冷重启。
- 维护态立即上报，失败时保持维护并可重试。
- 两次空闲证据、原子多键清理、普通复位回归和保留数据均有行为测试。
- 完整单测、Release 构建和独立 Codex 只读复审无 P0/P1 后，才允许升级版本、提交、合入、推送和发布蒲公英。
