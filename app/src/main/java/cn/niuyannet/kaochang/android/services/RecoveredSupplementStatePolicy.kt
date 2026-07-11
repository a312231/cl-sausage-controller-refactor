package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.KaoPanBox
import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSession

internal object RecoveredSupplementStatePolicy {
    enum class Decision {
        ALREADY_APPLIED,
        CORRECT_BOX_ONLY,
        APPLY_PAN_ONLY,
        APPLY_PAN_AND_CORRECT_BOX,
        APPLY_PAN_AND_DEDUCT_BOX,
        INCONSISTENT
    }

    fun decide(session: ActionGroupSession, pan: KaoPan, box: KaoPanBox): Decision {
        val expectedStockAfter = session.expectedStockAfter
        val stockBefore = session.stockBefore
        if (expectedStockAfter != null && box.num !in setOfNotNull(stockBefore, expectedStockAfter)) {
            return Decision.INCONSISTENT
        }
        val deductionProven = session.boxDeductionApplied || session.lastConfirmedStage in PICKED_OR_LATER_STAGES
        if (pan.isHasSausage) {
            if (pan.startTime <= 0L) return Decision.INCONSISTENT
            return when {
                expectedStockAfter == null || box.num == expectedStockAfter -> Decision.ALREADY_APPLIED
                box.num == stockBefore && deductionProven -> Decision.CORRECT_BOX_ONLY
                else -> Decision.INCONSISTENT
            }
        }
        val deductionApplied = session.boxDeductionApplied ||
            (expectedStockAfter != null && box.num == expectedStockAfter)
        if (expectedStockAfter != null && box.num == stockBefore && deductionProven) {
            return Decision.APPLY_PAN_AND_CORRECT_BOX
        }
        if (deductionApplied || session.lastConfirmedStage in PICKED_OR_LATER_STAGES && stockBefore == null) {
            return Decision.APPLY_PAN_ONLY
        }
        return if (box.num > 0) Decision.APPLY_PAN_AND_DEDUCT_BOX else Decision.INCONSISTENT
    }

    private val PICKED_OR_LATER_STAGES = setOf("in2", "in3", "in4", "in5", "in6", "in7")
}
