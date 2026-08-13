package cn.niuyannet.kaochang.order

object OrderClaimReleasePolicy {

    fun forWholeOrderOutcome(outcome: WholeOrderOutcome): OrderClaimReleaseEligibility = when (outcome) {
        WholeOrderOutcome.SUCCESS,
        WholeOrderOutcome.NO_DISPATCH,
        WholeOrderOutcome.MECHANICAL_FAILED -> OrderClaimReleaseEligibility.RELEASE_ALLOWED

        WholeOrderOutcome.EVIDENCE_UNKNOWN,
        WholeOrderOutcome.FAILED,
        WholeOrderOutcome.UNKNOWN -> OrderClaimReleaseEligibility.HOLD_REQUIRED
    }
}
