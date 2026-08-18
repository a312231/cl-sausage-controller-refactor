# 补热回搬目标所有权与旧冲突安全收口设计

## 目标

修复低并发/高并发残缺补热恢复把其他源盘的前区预留按位置下标复用，进而持久化多个源盘指向同一 `returnPositionSn` 的问题；同时对旧版本已经落盘的重复绑定 fail-closed，不新增人工搬盘或新的 202/203 寄存器入口。

## 核心不变量

- 任一活动补肠意图中，每个有效 `returnPositionSn` 最多有一个源盘所有者。
- pending 批次仅由 `handlePendingSupplementBatchIfAny` 使用原 `existingBatchId` 恢复。
- generic 残缺组恢复仅在 `activeIntent == null` 时兼容旧状态；存在活动意图时不得根据物理空洞重建源盘。
- 成熟回搬只认精确绑定；不得遇到占用后动态改绑任意空盘。

## 新冲突三层防线

1. 低/高并发 resume planner 保持纯函数，存在活动意图时拒绝 fallback。
2. `SupplementBatchIntentRepository.begin` 在落盘前拒绝 new 内部及 retained-new 重复目标。
3. 自动补肠 admission 与 `moveKaoPanToKaoPanInternal` 中央机械入口发现旧重复绑定时拒绝执行。

## 旧重复意图

`SupplementBatchIntent` 增加独立、可序列化的冲突证据字段，不覆盖原动作失败字段。fingerprint 由重复目标对应的有效绑定按 `returnPositionSn/sourcePositionSn/originBatchId/resolvedOriginBatchType` 排序生成；集合未变化时不得重复保存、刷新时间或刷日志。

检测到冲突后，设备持久化进入 `isEnable=0 / inspectionMode=1 / onlineStatus=3`，自动补肠、自动搬盘、普通确认和普通清 intent 全部拒绝。状态漂移时重新锁回维护，但稳定状态不重复发布。

## 无机械收口

本次不接入 `moveKaoPanToKaoPanManual`。维护页只展示冲突和处理说明。维护人员通过现有清空/丢弃流程处理现场后，专用 resolve 在统一状态锁内重新核对：

- `batchId + updatedAtMs + fingerprint` 未变化；
- 13..18、25..33 及全部冲突源盘本地状态为空；
- 无订单、pan move journal、机械 claim 和资源锁；
- `moveStatus=0`。

全部通过后才按精确 origin/source/return 删除冲突成员绑定，并仅清除专用冲突证据。其他人工确认或故障状态原样保留；不自动恢复营业或退出检修。

## 验证边界

JVM 测试只能证明状态门禁、绑定唯一性和无机械收口逻辑，不能证明真实 RS485、寄存器、机械盘面、MQTT Broker 或现场升级。现有“丢弃全部并重置”有原机械动作证据；“清空烤盘”仍依赖维护人员现场确认。

## 审核记录

独立任务《审核者B》在第四轮给出 Approve，P0/P1 均为 0；实施须遵守其 fingerprint、聚合门禁、乐观并发、状态漂移重锁和现场证据边界要求。
