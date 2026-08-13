package cn.niuyannet.kaochang.order

enum class OrderInboxAdmissionCode {
    ACCEPT_NEW,
    DUPLICATE_SAME,
    CONFLICT,
    BUSY_OTHER_ORDER,
    DUPLICATE_TERMINAL,
    STORAGE_FAILED
}

enum class OrderInboxNextAction {
    BEGIN_PREPARATION,
    NO_ORDER_SIDE_EFFECTS
}

data class OrderInboxAdmissionPlan(
    val code: OrderInboxAdmissionCode,
    val snapshotToPersist: OrderInboxSnapshot?
) {
    val requiresPersistence: Boolean
        get() = snapshotToPersist != null
}

data class OrderInboxAdmissionOutcome(
    val code: OrderInboxAdmissionCode,
    val nextAction: OrderInboxNextAction
)

object OrderInboxAdmissionPolicy {
    fun plan(
        current: OrderInboxSnapshot,
        incoming: ActiveOrderRecord
    ): OrderInboxAdmissionPlan {
        require(incoming.admissionStage == OrderAdmissionStage.PERSISTED_PENDING_PREPARATION) {
            "only a pending record can enter inbox admission"
        }
        require(incoming.lifecycleStage == OrderLifecycleStage.ACCEPTED_DURABLE) {
            "only an accepted order can enter inbox admission"
        }

        val active = current.activeRecord
        if (active != null) {
            if (active.protocolKind == incoming.protocolKind &&
                active.stableKey() == incoming.stableKey()
            ) {
                return noPersistence(
                    if (active.canonicalPayloadSha256 == incoming.canonicalPayloadSha256) {
                        OrderInboxAdmissionCode.DUPLICATE_SAME
                    } else {
                        OrderInboxAdmissionCode.CONFLICT
                    }
                )
            }
            return noPersistence(OrderInboxAdmissionCode.BUSY_OTHER_ORDER)
        }

        val terminal = current.terminalTombstones.firstOrNull {
            it.protocolKind == incoming.protocolKind && it.stableKey == incoming.stableKey()
        }
        if (terminal != null) {
            return noPersistence(
                if (terminal.canonicalPayloadSha256 == incoming.canonicalPayloadSha256) {
                    OrderInboxAdmissionCode.DUPLICATE_TERMINAL
                } else {
                    OrderInboxAdmissionCode.CONFLICT
                }
            )
        }

        val nextRevision = Math.addExact(current.snapshotRevision, 1L)
        return OrderInboxAdmissionPlan(
            code = OrderInboxAdmissionCode.ACCEPT_NEW,
            snapshotToPersist = current.copy(
                snapshotRevision = nextRevision,
                activeRecord = incoming,
                terminalTombstones = current.terminalTombstones.toList()
            )
        )
    }

    fun afterPersistence(
        plan: OrderInboxAdmissionPlan,
        persisted: Boolean
    ): OrderInboxAdmissionOutcome {
        if (!plan.requiresPersistence) {
            return OrderInboxAdmissionOutcome(
                code = plan.code,
                nextAction = OrderInboxNextAction.NO_ORDER_SIDE_EFFECTS
            )
        }
        require(plan.code == OrderInboxAdmissionCode.ACCEPT_NEW) {
            "only ACCEPT_NEW may require persistence"
        }
        return if (persisted) {
            OrderInboxAdmissionOutcome(
                code = OrderInboxAdmissionCode.ACCEPT_NEW,
                nextAction = OrderInboxNextAction.BEGIN_PREPARATION
            )
        } else {
            OrderInboxAdmissionOutcome(
                code = OrderInboxAdmissionCode.STORAGE_FAILED,
                nextAction = OrderInboxNextAction.NO_ORDER_SIDE_EFFECTS
            )
        }
    }

    private fun noPersistence(code: OrderInboxAdmissionCode) =
        OrderInboxAdmissionPlan(code = code, snapshotToPersist = null)
}

object OrderAdmissionTransitionPolicy {
    fun ready(
        current: ActiveOrderRecord,
        revision: Long,
        updatedAtMs: Long
    ): ActiveOrderRecord = transition(
        current = current,
        admissionStage = OrderAdmissionStage.READY_FOR_EXECUTION,
        holdReason = null,
        revision = revision,
        updatedAtMs = updatedAtMs
    )

    fun hold(
        current: ActiveOrderRecord,
        holdReason: OrderInboxHoldReason,
        revision: Long,
        updatedAtMs: Long
    ): ActiveOrderRecord = transition(
        current = current,
        admissionStage = OrderAdmissionStage.HOLD_REQUIRED,
        holdReason = holdReason,
        revision = revision,
        updatedAtMs = updatedAtMs
    )

    private fun transition(
        current: ActiveOrderRecord,
        admissionStage: OrderAdmissionStage,
        holdReason: OrderInboxHoldReason?,
        revision: Long,
        updatedAtMs: Long
    ): ActiveOrderRecord {
        require(current.admissionStage == OrderAdmissionStage.PERSISTED_PENDING_PREPARATION) {
            "only a pending record can transition to READY or HOLD"
        }
        require(revision > current.recordRevision) { "record revision must increase" }
        require(updatedAtMs >= 0L) { "updatedAtMs must be non-negative" }
        return current.withAdmissionState(
            admissionStage = admissionStage,
            holdReason = holdReason,
            recordRevision = revision,
            updatedAtMs = updatedAtMs
        )
    }
}

object OrderWholeOrderTerminalPolicy {
    fun close(
        current: ActiveOrderRecord,
        outcome: WholeOrderOutcome,
        revision: Long,
        updatedAtMs: Long
    ): ActiveOrderRecord = current.withWholeOrderTerminal(
        outcome = outcome,
        recordRevision = revision,
        updatedAtMs = updatedAtMs
    )
}
