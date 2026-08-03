package cn.niuyannet.kaochang.android.services

enum class OrderExecutionOutcome {
    SUCCESS,
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
        outcome == OrderExecutionOutcome.SUCCESS

    fun shouldEnterMaintenance(
        outcome: OrderExecutionOutcome,
        supplementResumeSucceeded: Boolean = true
    ): Boolean = outcome != OrderExecutionOutcome.SUCCESS || !supplementResumeSucceeded
}
