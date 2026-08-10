package cn.niuyannet.kaochang.android.services

enum class OrderExecutionOutcome {
    SUCCESS,
    NO_DISPATCH,
    EVIDENCE_UNKNOWN,
    MECHANICAL_FAILED,
    FAILED,
    UNKNOWN
}

object OrderSupplementResumePolicy {
    fun completedOutcome(deliveredTotal: Int, totalCount: Int): OrderExecutionOutcome =
        if (totalCount > 0 && deliveredTotal == totalCount) {
            OrderExecutionOutcome.SUCCESS
        } else {
            OrderExecutionOutcome.FAILED
        }

    fun shouldResume(outcome: OrderExecutionOutcome): Boolean =
        outcome == OrderExecutionOutcome.SUCCESS || outcome == OrderExecutionOutcome.NO_DISPATCH

    fun shouldReleaseClaim(outcome: OrderExecutionOutcome): Boolean = when (outcome) {
        OrderExecutionOutcome.SUCCESS,
        OrderExecutionOutcome.NO_DISPATCH,
        OrderExecutionOutcome.MECHANICAL_FAILED -> true
        OrderExecutionOutcome.EVIDENCE_UNKNOWN,
        OrderExecutionOutcome.FAILED,
        OrderExecutionOutcome.UNKNOWN -> false
    }

    fun shouldEnterMaintenance(
        outcome: OrderExecutionOutcome,
        supplementResumeSucceeded: Boolean = true
    ): Boolean = when (outcome) {
        OrderExecutionOutcome.SUCCESS,
        OrderExecutionOutcome.NO_DISPATCH -> !supplementResumeSucceeded
        OrderExecutionOutcome.EVIDENCE_UNKNOWN,
        OrderExecutionOutcome.MECHANICAL_FAILED,
        OrderExecutionOutcome.FAILED,
        OrderExecutionOutcome.UNKNOWN -> true
    }
}
