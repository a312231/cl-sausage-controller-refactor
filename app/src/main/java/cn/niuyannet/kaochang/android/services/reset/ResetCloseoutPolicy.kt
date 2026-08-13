package cn.niuyannet.kaochang.android.services.reset

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetry
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionExecutor
import com.alibaba.fastjson.JSON

object ResetCloseoutPolicy {
    private const val MIN_RESET_GATE_MS = 10_000L
    fun blocksOrders(record: ResetCloseoutRecord?): Boolean =
        record?.phase == ResetCloseoutPhase.PREPARED ||
            record?.phase == ResetCloseoutPhase.RESET_WRITTEN

    fun confirmOut4(record: ResetCloseoutRecord, itemId: Long): ResetCloseoutRecord {
        var matched = false
        val updatedItems = record.items.map { item ->
            if (item.itemId != itemId) {
                item
            } else {
                matched = true
                item.copy(confirmedOut4 = (item.confirmedOut4 + 1).coerceAtMost(item.quantity))
            }
        }
        check(matched) { "复位订单证据不存在订单项：$itemId" }
        return record.copy(items = updatedItems).requireValid()
    }

    fun isConfirmedOut4Delivery(telemetry: ActionGroupTelemetry): Boolean =
        telemetry.actionName == "out4" &&
            telemetry.eventPhase == AtomicActionExecutor.ACTION_EVENT_PHASE_FINISHED &&
            telemetry.resultCode == AtomicActionCodes.COMPLETED &&
            telemetry.errorCode == null

    fun isServerBlockConfirmed(transportCode: Int, content: String?): Boolean =
        transportCode == 0 && runCatching {
            JSON.parseObject(content).getIntValue("code") == 200
        }.getOrDefault(false)

    fun remainingCount(item: ResetOrderItem): Int =
        (item.quantity - item.confirmedOut4).coerceAtLeast(0)

    fun resolveStableOnlineStatus(kind: ResetKind, previousOnlineStatus: Int, errorStatus: Int): Int {
        if (kind == ResetKind.EMERGENCY) return 3
        if (previousOnlineStatus != 4) return previousOnlineStatus
        return if (errorStatus == 0) 1 else 3
    }

    fun remainingResetGateMs(record: ResetCloseoutRecord, nowMs: Long): Long {
        val writtenAt = requireNotNull(record.resetWrittenAtMs)
        return (MIN_RESET_GATE_MS - (nowMs - writtenAt).coerceAtLeast(0L)).coerceAtLeast(0L)
    }
}
