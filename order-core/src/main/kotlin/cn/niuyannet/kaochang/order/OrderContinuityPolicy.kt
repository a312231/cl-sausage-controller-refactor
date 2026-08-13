package cn.niuyannet.kaochang.order

object OrderContinuityPolicy {

    fun evaluate(snapshot: OrderContinuitySnapshot): OrderContinuityOutcome {
        validate(snapshot)

        val mechanicalDirective = when (snapshot.outActionSession) {
            OutActionSessionPresence.ABSENT -> MechanicalDirective.NO_MECHANICAL_ACTION
            OutActionSessionPresence.PRESENT -> when (snapshot.out1DispatchEvidence) {
                Out1DispatchEvidence.NOT_DISPATCHED_CONFIRMED ->
                    MechanicalDirective.CLOSE_CONFIRMED_NOT_DISPATCHED_SESSION

                Out1DispatchEvidence.PREPARED_OR_UNKNOWN,
                Out1DispatchEvidence.DISPATCHED -> MechanicalDirective.RECOVER_ACTIVE_OUT_SESSION

                Out1DispatchEvidence.NOT_APPLICABLE -> error("validated above")
            }
        }

        val orderClaimDirective = when (snapshot.orderDeliveryClaim) {
            OrderDeliveryClaimPresence.ABSENT -> OrderClaimDirective.NO_ORDER_CLAIM_ACTION
            OrderDeliveryClaimPresence.CLAIMED -> when {
                snapshot.orderClaimCloseAttempt == OrderClaimCloseAttemptState.FAILED_REQUIRES_REVIEW ->
                    OrderClaimDirective.PRESERVE_ORDER_DELIVERY_CLAIM

                snapshot.outActionSession == OutActionSessionPresence.PRESENT ->
                    OrderClaimDirective.PRESERVE_ORDER_DELIVERY_CLAIM

                snapshot.lifecycleStage == OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE &&
                    snapshot.orderClaimReleaseEligibility == OrderClaimReleaseEligibility.RELEASE_ALLOWED ->
                    OrderClaimDirective.CLOSE_ORDER_DELIVERY_CLAIM

                else -> OrderClaimDirective.PRESERVE_ORDER_DELIVERY_CLAIM
            }
        }

        val cloudDirective = when (snapshot.deliveryEventAckState) {
            DeliveryEventAckState.PENDING -> CloudDirective.RETRY_TERMINAL_EVENT
            DeliveryEventAckState.NOT_APPLICABLE,
            DeliveryEventAckState.ACKED -> CloudDirective.NO_CLOUD_ACTION
        }

        val orderDirective = when {
            snapshot.orderClaimCloseAttempt == OrderClaimCloseAttemptState.FAILED_REQUIRES_REVIEW ->
                OrderDirective.HOLD_FOR_DIAGNOSIS

            snapshot.lifecycleStage == OrderLifecycleStage.UNCONFIRMED &&
                (snapshot.orderDeliveryClaim == OrderDeliveryClaimPresence.CLAIMED ||
                    snapshot.outActionSession == OutActionSessionPresence.PRESENT) ->
                OrderDirective.HOLD_FOR_DIAGNOSIS

            snapshot.lifecycleStage == OrderLifecycleStage.UNCONFIRMED ->
                OrderDirective.REJECT_NEW

            snapshot.lifecycleStage == OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE &&
                snapshot.outActionSession == OutActionSessionPresence.PRESENT ->
                OrderDirective.HOLD_FOR_DIAGNOSIS

            snapshot.lifecycleStage == OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE &&
                snapshot.orderClaimReleaseEligibility == OrderClaimReleaseEligibility.HOLD_REQUIRED ->
                OrderDirective.HOLD_FOR_DIAGNOSIS

            snapshot.lifecycleStage == OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE ->
                OrderDirective.COMPLETE_ORDER_NO_ACTION

            snapshot.outActionSession == OutActionSessionPresence.PRESENT ->
                OrderDirective.WAIT_FOR_MECHANICAL_RECOVERY

            snapshot.authorizationFreshness != OrderAuthorizationFreshness.FRESH_CURRENT_CONTEXT ->
                OrderDirective.HOLD_FOR_REVALIDATION

            else -> OrderDirective.CONTINUE_ACCEPTED_ORDER
        }

        return OrderContinuityOutcome(
            orderDirective = orderDirective,
            orderClaimDirective = orderClaimDirective,
            mechanicalDirective = mechanicalDirective,
            cloudDirective = cloudDirective
        )
    }

    private fun validate(snapshot: OrderContinuitySnapshot) {
        require(
            (snapshot.lifecycleStage == OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE &&
                snapshot.orderClaimReleaseEligibility != OrderClaimReleaseEligibility.NOT_APPLICABLE) ||
                (snapshot.lifecycleStage != OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE &&
                    snapshot.orderClaimReleaseEligibility == OrderClaimReleaseEligibility.NOT_APPLICABLE)
        ) {
            "claim release eligibility must belong to a durable whole-order terminal state"
        }

        require(
            (snapshot.outActionSession == OutActionSessionPresence.ABSENT &&
                snapshot.out1DispatchEvidence == Out1DispatchEvidence.NOT_APPLICABLE) ||
                (snapshot.outActionSession == OutActionSessionPresence.PRESENT &&
                    snapshot.out1DispatchEvidence != Out1DispatchEvidence.NOT_APPLICABLE)
        ) {
            "out1 dispatch evidence must belong to an existing OUT action session"
        }

        if (snapshot.orderClaimCloseAttempt == OrderClaimCloseAttemptState.FAILED_REQUIRES_REVIEW) {
            require(snapshot.lifecycleStage == OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE) {
                "a failed whole-order claim close requires a durable whole-order terminal state"
            }
            require(snapshot.orderClaimReleaseEligibility == OrderClaimReleaseEligibility.RELEASE_ALLOWED) {
                "a claim close can fail only after a release-allowed whole-order outcome"
            }
            require(snapshot.orderDeliveryClaim == OrderDeliveryClaimPresence.CLAIMED) {
                "a failed whole-order claim close must preserve the claim"
            }
            require(snapshot.outActionSession == OutActionSessionPresence.ABSENT) {
                "the whole-order claim must not be closed while an OUT action session remains"
            }
        }
    }
}
