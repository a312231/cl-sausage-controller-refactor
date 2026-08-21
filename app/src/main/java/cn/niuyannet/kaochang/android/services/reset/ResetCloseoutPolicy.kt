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

enum class Out4CurrentCallEvidenceState {
    UNSEEN,
    CONFIRMED_DURABLE_PENDING,
    CONFIRMED_PENDING_MISSING,
    UNKNOWN
}

class Out4CurrentCallEvidence {
    var state: Out4CurrentCallEvidenceState = Out4CurrentCallEvidenceState.UNSEEN
        private set

    var pendingSaveFailure: Boolean = false
        private set

    val advancedThisCall: Boolean
        get() = state == Out4CurrentCallEvidenceState.CONFIRMED_DURABLE_PENDING ||
            state == Out4CurrentCallEvidenceState.CONFIRMED_PENDING_MISSING

    fun observeConfirmedOut4(
        confirmDurably: () -> Out4ConfirmResult,
        savePendingDurably: () -> Boolean
    ): Out4CurrentCallEvidenceState {
        if (state != Out4CurrentCallEvidenceState.UNSEEN) return state
        state = Out4CurrentCallEvidenceState.UNKNOWN
        if (runCatching(confirmDurably).getOrDefault(Out4ConfirmResult.CORRUPTED) != Out4ConfirmResult.ADVANCED_THIS_CALL) {
            return state
        }
        val pendingSaved = runCatching(savePendingDurably).getOrDefault(false)
        if (pendingSaved) {
            state = Out4CurrentCallEvidenceState.CONFIRMED_DURABLE_PENDING
        } else {
            pendingSaveFailure = true
            state = Out4CurrentCallEvidenceState.CONFIRMED_PENDING_MISSING
        }
        return state
    }
}

enum class Out4DeliveryCloseoutResult {
    DELIVERED,
    DELIVERED_CLOSEOUT_UNKNOWN,
    EVIDENCE_UNKNOWN
}

object Out4DeliveryCloseoutPolicy {
    fun resolveAfterActionGroupFailure(
        evidence: Out4CurrentCallEvidence,
        enterMaintenance: () -> Unit
    ): Out4DeliveryCloseoutResult? {
        if (!evidence.advancedThisCall) return null
        runCatching(enterMaintenance)
        return Out4DeliveryCloseoutResult.DELIVERED_CLOSEOUT_UNKNOWN
    }

    fun resolve(
        evidence: Out4CurrentCallEvidence,
        releaseMechanical: () -> Boolean,
        enterMaintenance: () -> Unit
    ): Out4DeliveryCloseoutResult {
        val released = runCatching(releaseMechanical).getOrDefault(false)
        val requiresMaintenance = !evidence.advancedThisCall || evidence.pendingSaveFailure || !released
        if (requiresMaintenance) {
            runCatching(enterMaintenance)
        }
        if (!evidence.advancedThisCall) {
            return Out4DeliveryCloseoutResult.EVIDENCE_UNKNOWN
        }
        return if (evidence.pendingSaveFailure || !released) {
            Out4DeliveryCloseoutResult.DELIVERED_CLOSEOUT_UNKNOWN
        } else {
            Out4DeliveryCloseoutResult.DELIVERED
        }
    }
}
