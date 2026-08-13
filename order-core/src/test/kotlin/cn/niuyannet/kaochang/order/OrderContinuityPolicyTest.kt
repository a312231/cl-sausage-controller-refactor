package cn.niuyannet.kaochang.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OrderContinuityPolicyTest {

    @Test
    fun `unconfirmed order never starts even when connected`() {
        val outcome = evaluate(
            lifecycle = OrderLifecycleStage.UNCONFIRMED,
            connectivity = OrderConnectivity.CONNECTED,
            freshness = OrderAuthorizationFreshness.FRESH_CURRENT_CONTEXT
        )

        assertEquals(OrderDirective.REJECT_NEW, outcome.orderDirective)
        assertEquals(MechanicalDirective.NO_MECHANICAL_ACTION, outcome.mechanicalDirective)
    }

    @Test
    fun `same-process durable accepted order continues after network loss`() {
        val outcome = evaluate(
            lifecycle = OrderLifecycleStage.ACCEPTED_DURABLE,
            connectivity = OrderConnectivity.DISCONNECTED,
            freshness = OrderAuthorizationFreshness.FRESH_CURRENT_CONTEXT,
            context = OrderContinuityContext.SAME_PROCESS
        )

        assertEquals(OrderDirective.CONTINUE_ACCEPTED_ORDER, outcome.orderDirective)
    }

    @Test
    fun `cold start accepted order waits until authorization is revalidated`() {
        for (freshness in listOf(
            OrderAuthorizationFreshness.STALE,
            OrderAuthorizationFreshness.UNKNOWN
        )) {
            val outcome = evaluate(
                lifecycle = OrderLifecycleStage.ACCEPTED_DURABLE,
                connectivity = OrderConnectivity.CONNECTED,
                freshness = freshness,
                context = OrderContinuityContext.COLD_START
            )

            assertEquals(OrderDirective.HOLD_FOR_REVALIDATION, outcome.orderDirective)
        }
    }

    @Test
    fun `fresh means revalidated in current context including after cold start`() {
        val outcome = evaluate(
            lifecycle = OrderLifecycleStage.ACCEPTED_DURABLE,
            connectivity = OrderConnectivity.DISCONNECTED,
            freshness = OrderAuthorizationFreshness.FRESH_CURRENT_CONTEXT,
            context = OrderContinuityContext.COLD_START
        )

        assertEquals(OrderDirective.CONTINUE_ACCEPTED_ORDER, outcome.orderDirective)
    }

    @Test
    fun `prepared or unknown out1 evidence never permits redispatch`() {
        for (connectivity in OrderConnectivity.values()) {
            for (freshness in OrderAuthorizationFreshness.values()) {
                val outcome = evaluate(
                    lifecycle = OrderLifecycleStage.ACCEPTED_DURABLE,
                    orderClaim = OrderDeliveryClaimPresence.CLAIMED,
                    session = OutActionSessionPresence.PRESENT,
                    evidence = Out1DispatchEvidence.PREPARED_OR_UNKNOWN,
                    connectivity = connectivity,
                    freshness = freshness
                )

                assertEquals(OrderDirective.WAIT_FOR_MECHANICAL_RECOVERY, outcome.orderDirective)
                assertEquals(
                    MechanicalDirective.RECOVER_ACTIVE_OUT_SESSION,
                    outcome.mechanicalDirective
                )
                assertEquals(
                    OrderClaimDirective.PRESERVE_ORDER_DELIVERY_CLAIM,
                    outcome.orderClaimDirective
                )
            }
        }
    }

    @Test
    fun `confirmed not-dispatched session is closed before reevaluation`() {
        val outcome = evaluate(
            lifecycle = OrderLifecycleStage.ACCEPTED_DURABLE,
            orderClaim = OrderDeliveryClaimPresence.CLAIMED,
            session = OutActionSessionPresence.PRESENT,
            evidence = Out1DispatchEvidence.NOT_DISPATCHED_CONFIRMED,
            freshness = OrderAuthorizationFreshness.UNKNOWN,
            context = OrderContinuityContext.COLD_START
        )

        assertEquals(
            MechanicalDirective.CLOSE_CONFIRMED_NOT_DISPATCHED_SESSION,
            outcome.mechanicalDirective
        )
        assertEquals(OrderDirective.WAIT_FOR_MECHANICAL_RECOVERY, outcome.orderDirective)
        assertEquals(
            OrderClaimDirective.PRESERVE_ORDER_DELIVERY_CLAIM,
            outcome.orderClaimDirective
        )
    }

    @Test
    fun `cloud ack never suppresses active mechanical recovery`() {
        for (ack in listOf(DeliveryEventAckState.PENDING, DeliveryEventAckState.ACKED)) {
            for (evidence in listOf(
                Out1DispatchEvidence.PREPARED_OR_UNKNOWN,
                Out1DispatchEvidence.DISPATCHED
            )) {
                val outcome = evaluate(
                    lifecycle = OrderLifecycleStage.ACCEPTED_DURABLE,
                    orderClaim = OrderDeliveryClaimPresence.CLAIMED,
                    session = OutActionSessionPresence.PRESENT,
                    evidence = evidence,
                    ack = ack
                )

                assertEquals(
                    MechanicalDirective.RECOVER_ACTIVE_OUT_SESSION,
                    outcome.mechanicalDirective
                )
                assertEquals(
                    if (ack == DeliveryEventAckState.PENDING) {
                        CloudDirective.RETRY_TERMINAL_EVENT
                    } else {
                        CloudDirective.NO_CLOUD_ACTION
                    },
                    outcome.cloudDirective
                )
            }
        }
    }

    @Test
    fun `middle delivery event preserves whole-order claim`() {
        for (ack in listOf(DeliveryEventAckState.PENDING, DeliveryEventAckState.ACKED)) {
            val outcome = evaluate(
                lifecycle = OrderLifecycleStage.ACCEPTED_DURABLE,
                orderClaim = OrderDeliveryClaimPresence.CLAIMED,
                session = OutActionSessionPresence.ABSENT,
                evidence = Out1DispatchEvidence.NOT_APPLICABLE,
                ack = ack
            )

            assertEquals(
                OrderClaimDirective.PRESERVE_ORDER_DELIVERY_CLAIM,
                outcome.orderClaimDirective
            )
        }
    }

    @Test
    fun `durable whole-order terminal closes claim only after OUT session is absent`() {
        for (ack in listOf(DeliveryEventAckState.PENDING, DeliveryEventAckState.ACKED)) {
            val outcome = evaluate(
                lifecycle = OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE,
                orderClaim = OrderDeliveryClaimPresence.CLAIMED,
                releaseEligibility = OrderClaimReleaseEligibility.RELEASE_ALLOWED,
                session = OutActionSessionPresence.ABSENT,
                evidence = Out1DispatchEvidence.NOT_APPLICABLE,
                ack = ack
            )

            assertEquals(OrderDirective.COMPLETE_ORDER_NO_ACTION, outcome.orderDirective)
            assertEquals(
                OrderClaimDirective.CLOSE_ORDER_DELIVERY_CLAIM,
                outcome.orderClaimDirective
            )
        }
    }

    @Test
    fun `whole-order terminal with residual OUT session recovers and preserves claim`() {
        val outcome = evaluate(
            lifecycle = OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE,
            orderClaim = OrderDeliveryClaimPresence.CLAIMED,
            releaseEligibility = OrderClaimReleaseEligibility.RELEASE_ALLOWED,
            session = OutActionSessionPresence.PRESENT,
            evidence = Out1DispatchEvidence.DISPATCHED,
            ack = DeliveryEventAckState.ACKED
        )

        assertEquals(OrderDirective.HOLD_FOR_DIAGNOSIS, outcome.orderDirective)
        assertEquals(
            OrderClaimDirective.PRESERVE_ORDER_DELIVERY_CLAIM,
            outcome.orderClaimDirective
        )
        assertEquals(
            MechanicalDirective.RECOVER_ACTIVE_OUT_SESSION,
            outcome.mechanicalDirective
        )
        assertEquals(CloudDirective.NO_CLOUD_ACTION, outcome.cloudDirective)
    }

    @Test
    fun `failed whole-order claim close remains held and preserves evidence`() {
        val outcome = evaluate(
            lifecycle = OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE,
            orderClaim = OrderDeliveryClaimPresence.CLAIMED,
            releaseEligibility = OrderClaimReleaseEligibility.RELEASE_ALLOWED,
            claimCloseAttempt = OrderClaimCloseAttemptState.FAILED_REQUIRES_REVIEW,
            session = OutActionSessionPresence.ABSENT,
            evidence = Out1DispatchEvidence.NOT_APPLICABLE,
            ack = DeliveryEventAckState.ACKED
        )

        assertEquals(OrderDirective.HOLD_FOR_DIAGNOSIS, outcome.orderDirective)
        assertEquals(
            OrderClaimDirective.PRESERVE_ORDER_DELIVERY_CLAIM,
            outcome.orderClaimDirective
        )
        assertEquals(MechanicalDirective.NO_MECHANICAL_ACTION, outcome.mechanicalDirective)
    }

    @Test
    fun `current release-allowed whole-order outcomes map explicitly`() {
        for (outcome in listOf(
            WholeOrderOutcome.SUCCESS,
            WholeOrderOutcome.NO_DISPATCH,
            WholeOrderOutcome.MECHANICAL_FAILED
        )) {
            assertEquals(
                OrderClaimReleaseEligibility.RELEASE_ALLOWED,
                OrderClaimReleasePolicy.forWholeOrderOutcome(outcome)
            )
        }
    }

    @Test
    fun `uncertain whole-order outcomes require claim hold`() {
        for (outcome in listOf(
            WholeOrderOutcome.EVIDENCE_UNKNOWN,
            WholeOrderOutcome.FAILED,
            WholeOrderOutcome.UNKNOWN
        )) {
            assertEquals(
                OrderClaimReleaseEligibility.HOLD_REQUIRED,
                OrderClaimReleasePolicy.forWholeOrderOutcome(outcome)
            )
        }

        val outcome = evaluate(
            lifecycle = OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE,
            orderClaim = OrderDeliveryClaimPresence.CLAIMED,
            releaseEligibility = OrderClaimReleaseEligibility.HOLD_REQUIRED,
            session = OutActionSessionPresence.ABSENT,
            evidence = Out1DispatchEvidence.NOT_APPLICABLE
        )
        assertEquals(OrderDirective.HOLD_FOR_DIAGNOSIS, outcome.orderDirective)
        assertEquals(
            OrderClaimDirective.PRESERVE_ORDER_DELIVERY_CLAIM,
            outcome.orderClaimDirective
        )
    }

    @Test
    fun `contradictory session and out1 evidence is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(
                session = OutActionSessionPresence.ABSENT,
                evidence = Out1DispatchEvidence.DISPATCHED
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(
                session = OutActionSessionPresence.PRESENT,
                evidence = Out1DispatchEvidence.NOT_APPLICABLE
            )
        }
    }

    @Test
    fun `claim-close failure evidence is accepted only for its exact safe state`() {
        val invalidSnapshots = listOf(
            snapshot(
                lifecycle = OrderLifecycleStage.ACCEPTED_DURABLE,
                orderClaimCloseAttempt = OrderClaimCloseAttemptState.FAILED_REQUIRES_REVIEW
            ),
            snapshot(
                lifecycle = OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE,
                orderClaim = OrderDeliveryClaimPresence.ABSENT,
                releaseEligibility = OrderClaimReleaseEligibility.RELEASE_ALLOWED,
                orderClaimCloseAttempt = OrderClaimCloseAttemptState.FAILED_REQUIRES_REVIEW
            ),
            snapshot(
                lifecycle = OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE,
                orderClaim = OrderDeliveryClaimPresence.CLAIMED,
                releaseEligibility = OrderClaimReleaseEligibility.RELEASE_ALLOWED,
                orderClaimCloseAttempt = OrderClaimCloseAttemptState.FAILED_REQUIRES_REVIEW,
                session = OutActionSessionPresence.PRESENT,
                evidence = Out1DispatchEvidence.DISPATCHED
            )
        )

        invalidSnapshots.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                OrderContinuityPolicy.evaluate(invalid)
            }
        }
    }

    @Test
    fun `complete Cartesian product has one outcome or an explicit contradiction`() {
        var validCount = 0
        var rejectedCount = 0

        for (lifecycle in OrderLifecycleStage.values()) {
            for (orderClaim in OrderDeliveryClaimPresence.values()) {
                for (releaseEligibility in OrderClaimReleaseEligibility.values()) {
                    for (closeAttempt in OrderClaimCloseAttemptState.values()) {
                        for (session in OutActionSessionPresence.values()) {
                            for (evidence in Out1DispatchEvidence.values()) {
                                for (ack in DeliveryEventAckState.values()) {
                                    for (connectivity in OrderConnectivity.values()) {
                                        for (freshness in OrderAuthorizationFreshness.values()) {
                                            for (context in OrderContinuityContext.values()) {
                                                val input = snapshot(
                                                    lifecycle = lifecycle,
                                                    orderClaim = orderClaim,
                                                    releaseEligibility = releaseEligibility,
                                                    orderClaimCloseAttempt = closeAttempt,
                                                    session = session,
                                                    evidence = evidence,
                                                    ack = ack,
                                                    connectivity = connectivity,
                                                    freshness = freshness,
                                                    context = context
                                                )
                                                try {
                                                    val first = OrderContinuityPolicy.evaluate(input)
                                                    val second = OrderContinuityPolicy.evaluate(input)
                                                    assertEquals(first, second)
                                                    assertSafetyInvariants(input, first)
                                                    validCount++
                                                } catch (_: IllegalArgumentException) {
                                                    assertEquals(false, isValid(input))
                                                    rejectedCount++
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        assertEquals(1_188, validCount)
        assertEquals(9_180, rejectedCount)
    }

    private fun assertSafetyInvariants(
        input: OrderContinuitySnapshot,
        outcome: OrderContinuityOutcome
    ) {
        if (input.lifecycleStage == OrderLifecycleStage.UNCONFIRMED) {
            assertEquals(
                false,
                outcome.orderDirective == OrderDirective.CONTINUE_ACCEPTED_ORDER
            )
        }
        if (input.out1DispatchEvidence == Out1DispatchEvidence.PREPARED_OR_UNKNOWN ||
            input.out1DispatchEvidence == Out1DispatchEvidence.DISPATCHED
        ) {
            assertEquals(
                MechanicalDirective.RECOVER_ACTIVE_OUT_SESSION,
                outcome.mechanicalDirective
            )
            assertEquals(
                false,
                outcome.orderDirective == OrderDirective.CONTINUE_ACCEPTED_ORDER
            )
        }
        if (input.deliveryEventAckState == DeliveryEventAckState.ACKED) {
            assertEquals(CloudDirective.NO_CLOUD_ACTION, outcome.cloudDirective)
        }
        if (input.deliveryEventAckState == DeliveryEventAckState.PENDING) {
            assertEquals(CloudDirective.RETRY_TERMINAL_EVENT, outcome.cloudDirective)
        }
        if (input.lifecycleStage == OrderLifecycleStage.ACCEPTED_DURABLE &&
            input.orderDeliveryClaim == OrderDeliveryClaimPresence.CLAIMED &&
            input.outActionSession == OutActionSessionPresence.ABSENT
        ) {
            assertEquals(
                OrderClaimDirective.PRESERVE_ORDER_DELIVERY_CLAIM,
                outcome.orderClaimDirective
            )
        }
        if (input.lifecycleStage == OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE &&
            input.orderClaimReleaseEligibility == OrderClaimReleaseEligibility.HOLD_REQUIRED
        ) {
            assertEquals(OrderDirective.HOLD_FOR_DIAGNOSIS, outcome.orderDirective)
            if (input.orderDeliveryClaim == OrderDeliveryClaimPresence.CLAIMED) {
                assertEquals(
                    OrderClaimDirective.PRESERVE_ORDER_DELIVERY_CLAIM,
                    outcome.orderClaimDirective
                )
            }
        }
    }

    private fun isValid(input: OrderContinuitySnapshot): Boolean {
        val releaseEligibilityValid =
            (input.lifecycleStage == OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE &&
                input.orderClaimReleaseEligibility != OrderClaimReleaseEligibility.NOT_APPLICABLE) ||
                (input.lifecycleStage != OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE &&
                    input.orderClaimReleaseEligibility == OrderClaimReleaseEligibility.NOT_APPLICABLE)
        if (!releaseEligibilityValid) return false

        val sessionEvidenceValid =
            (input.outActionSession == OutActionSessionPresence.ABSENT &&
                input.out1DispatchEvidence == Out1DispatchEvidence.NOT_APPLICABLE) ||
                (input.outActionSession == OutActionSessionPresence.PRESENT &&
                    input.out1DispatchEvidence != Out1DispatchEvidence.NOT_APPLICABLE)
        if (!sessionEvidenceValid) return false

        if (input.orderClaimCloseAttempt == OrderClaimCloseAttemptState.FAILED_REQUIRES_REVIEW) {
            return input.lifecycleStage == OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE &&
                input.orderClaimReleaseEligibility == OrderClaimReleaseEligibility.RELEASE_ALLOWED &&
                input.orderDeliveryClaim == OrderDeliveryClaimPresence.CLAIMED &&
                input.outActionSession == OutActionSessionPresence.ABSENT
        }
        return true
    }

    private fun evaluate(
        lifecycle: OrderLifecycleStage = OrderLifecycleStage.ACCEPTED_DURABLE,
        orderClaim: OrderDeliveryClaimPresence = OrderDeliveryClaimPresence.ABSENT,
        releaseEligibility: OrderClaimReleaseEligibility = if (
            lifecycle == OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE
        ) OrderClaimReleaseEligibility.RELEASE_ALLOWED else OrderClaimReleaseEligibility.NOT_APPLICABLE,
        claimCloseAttempt: OrderClaimCloseAttemptState = OrderClaimCloseAttemptState.NOT_ATTEMPTED,
        session: OutActionSessionPresence = OutActionSessionPresence.ABSENT,
        evidence: Out1DispatchEvidence = Out1DispatchEvidence.NOT_APPLICABLE,
        ack: DeliveryEventAckState = DeliveryEventAckState.NOT_APPLICABLE,
        connectivity: OrderConnectivity = OrderConnectivity.CONNECTED,
        freshness: OrderAuthorizationFreshness = OrderAuthorizationFreshness.FRESH_CURRENT_CONTEXT,
        context: OrderContinuityContext = OrderContinuityContext.SAME_PROCESS
    ): OrderContinuityOutcome = OrderContinuityPolicy.evaluate(
        snapshot(
            lifecycle = lifecycle,
            orderClaim = orderClaim,
            releaseEligibility = releaseEligibility,
            orderClaimCloseAttempt = claimCloseAttempt,
            session = session,
            evidence = evidence,
            ack = ack,
            connectivity = connectivity,
            freshness = freshness,
            context = context
        )
    )

    private fun snapshot(
        lifecycle: OrderLifecycleStage = OrderLifecycleStage.ACCEPTED_DURABLE,
        orderClaim: OrderDeliveryClaimPresence = OrderDeliveryClaimPresence.ABSENT,
        releaseEligibility: OrderClaimReleaseEligibility = if (
            lifecycle == OrderLifecycleStage.WHOLE_ORDER_TERMINAL_DURABLE
        ) OrderClaimReleaseEligibility.RELEASE_ALLOWED else OrderClaimReleaseEligibility.NOT_APPLICABLE,
        orderClaimCloseAttempt: OrderClaimCloseAttemptState = OrderClaimCloseAttemptState.NOT_ATTEMPTED,
        session: OutActionSessionPresence = OutActionSessionPresence.ABSENT,
        evidence: Out1DispatchEvidence = Out1DispatchEvidence.NOT_APPLICABLE,
        ack: DeliveryEventAckState = DeliveryEventAckState.NOT_APPLICABLE,
        connectivity: OrderConnectivity = OrderConnectivity.CONNECTED,
        freshness: OrderAuthorizationFreshness = OrderAuthorizationFreshness.FRESH_CURRENT_CONTEXT,
        context: OrderContinuityContext = OrderContinuityContext.SAME_PROCESS
    ) = OrderContinuitySnapshot(
        lifecycleStage = lifecycle,
        orderDeliveryClaim = orderClaim,
        orderClaimReleaseEligibility = releaseEligibility,
        orderClaimCloseAttempt = orderClaimCloseAttempt,
        outActionSession = session,
        out1DispatchEvidence = evidence,
        deliveryEventAckState = ack,
        connectivity = connectivity,
        authorizationFreshness = freshness,
        continuityContext = context
    )
}
