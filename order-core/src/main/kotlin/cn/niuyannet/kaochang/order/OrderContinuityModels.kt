package cn.niuyannet.kaochang.order

enum class OrderLifecycleStage {
    UNCONFIRMED,
    ACCEPTED_DURABLE,
    WHOLE_ORDER_TERMINAL_DURABLE
}

enum class OrderDeliveryClaimPresence {
    ABSENT,
    CLAIMED
}

enum class WholeOrderOutcome {
    SUCCESS,
    NO_DISPATCH,
    MECHANICAL_FAILED,
    EVIDENCE_UNKNOWN,
    FAILED,
    UNKNOWN
}

enum class OrderClaimReleaseEligibility {
    NOT_APPLICABLE,
    RELEASE_ALLOWED,
    HOLD_REQUIRED
}

enum class OrderClaimCloseAttemptState {
    NOT_ATTEMPTED,
    FAILED_REQUIRES_REVIEW
}

enum class OutActionSessionPresence {
    ABSENT,
    PRESENT
}

enum class Out1DispatchEvidence {
    NOT_APPLICABLE,
    PREPARED_OR_UNKNOWN,
    NOT_DISPATCHED_CONFIRMED,
    DISPATCHED
}

enum class DeliveryEventAckState {
    NOT_APPLICABLE,
    PENDING,
    ACKED
}

enum class OrderConnectivity {
    CONNECTED,
    DISCONNECTED
}

enum class OrderAuthorizationFreshness {
    FRESH_CURRENT_CONTEXT,
    STALE,
    UNKNOWN
}

enum class OrderContinuityContext {
    SAME_PROCESS,
    COLD_START
}

enum class OrderDirective {
    REJECT_NEW,
    HOLD_FOR_REVALIDATION,
    WAIT_FOR_MECHANICAL_RECOVERY,
    HOLD_FOR_DIAGNOSIS,
    CONTINUE_ACCEPTED_ORDER,
    COMPLETE_ORDER_NO_ACTION
}

enum class OrderClaimDirective {
    NO_ORDER_CLAIM_ACTION,
    PRESERVE_ORDER_DELIVERY_CLAIM,
    CLOSE_ORDER_DELIVERY_CLAIM
}

enum class MechanicalDirective {
    NO_MECHANICAL_ACTION,
    CLOSE_CONFIRMED_NOT_DISPATCHED_SESSION,
    RECOVER_ACTIVE_OUT_SESSION
}

enum class CloudDirective {
    NO_CLOUD_ACTION,
    RETRY_TERMINAL_EVENT
}

data class OrderContinuitySnapshot(
    val lifecycleStage: OrderLifecycleStage,
    val orderDeliveryClaim: OrderDeliveryClaimPresence,
    val orderClaimReleaseEligibility: OrderClaimReleaseEligibility,
    val orderClaimCloseAttempt: OrderClaimCloseAttemptState,
    val outActionSession: OutActionSessionPresence,
    val out1DispatchEvidence: Out1DispatchEvidence,
    val deliveryEventAckState: DeliveryEventAckState,
    val connectivity: OrderConnectivity,
    val authorizationFreshness: OrderAuthorizationFreshness,
    val continuityContext: OrderContinuityContext
)

data class OrderContinuityOutcome(
    val orderDirective: OrderDirective,
    val orderClaimDirective: OrderClaimDirective,
    val mechanicalDirective: MechanicalDirective,
    val cloudDirective: CloudDirective
)
