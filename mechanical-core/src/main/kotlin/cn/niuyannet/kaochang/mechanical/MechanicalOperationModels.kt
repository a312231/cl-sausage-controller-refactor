package cn.niuyannet.kaochang.mechanical

enum class MechanicalOperationKind {
    SUPPLEMENT,
    ORDER_DELIVERY,
    PAN_MOVE,
    DISCARD,
    CLEANING
}

enum class MechanicalOperationState {
    REQUESTED,
    ADMITTED,
    COMMAND_PREPARED,
    COMMAND_DISPATCHED,
    RESULT_CONFIRMED,
    EFFECT_APPLIED,
    STEP_COMPLETED,
    PAUSED,
    RECOVERY_REQUIRED,
    OPERATION_COMPLETED
}

enum class MechanicalResource {
    BOTTOM,
    LIFT,
    ABOVE,
    GATE,
    STICK
}

enum class CheckpointSafety {
    UNSAFE,
    SAFE_TO_PAUSE
}

enum class MechanicalPhysicalResult {
    NONE,
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN
}

enum class MechanicalEffectState {
    NOT_APPLIED,
    APPLIED
}

enum class MechanicalAdmissionClass {
    FOOD_SAFETY,
    PAID_ORDER_DELIVERY,
    SAFE_RETURN,
    SUPPLEMENT,
    CLEANING_OR_MAINTENANCE
}

sealed interface ResumeCursor {
    data class ExecuteStep(val stepIndex: Int) : ResumeCursor {
        init {
            require(stepIndex >= 0) { "stepIndex must not be negative" }
        }
    }

    data object CompleteOperation : ResumeCursor
}

data class MechanicalStep(
    val stepId: String,
    val commandIdempotencyKey: String,
    val effectIdempotencyKey: String,
    val resources: Set<MechanicalResource>
) {
    init {
        require(stepId.isNotBlank()) { "stepId must not be blank" }
        require(commandIdempotencyKey.isNotBlank()) { "commandIdempotencyKey must not be blank" }
        require(effectIdempotencyKey.isNotBlank()) { "effectIdempotencyKey must not be blank" }
        require(resources.isNotEmpty()) { "mechanical step must claim at least one resource" }
    }
}

data class MechanicalCheckpoint(
    val stepId: String,
    val safety: CheckpointSafety
) {
    init {
        require(stepId.isNotBlank()) { "checkpoint stepId must not be blank" }
    }
}

data class CommandAttemptToken(
    val operationId: String,
    val dispatchRevision: Long,
    val stepId: String,
    val attemptId: String
) {
    init {
        require(operationId.isNotBlank()) { "operationId must not be blank" }
        require(dispatchRevision > 0L) { "dispatchRevision must be positive" }
        require(stepId.isNotBlank()) { "stepId must not be blank" }
        require(attemptId.isNotBlank()) { "attemptId must not be blank" }
    }
}

data class EffectToken(
    val operationId: String,
    val stepId: String,
    val effectIdempotencyKey: String
) {
    init {
        require(operationId.isNotBlank()) { "operationId must not be blank" }
        require(stepId.isNotBlank()) { "stepId must not be blank" }
        require(effectIdempotencyKey.isNotBlank()) { "effectIdempotencyKey must not be blank" }
    }
}

enum class AdmissionGateState {
    STARTUP_RECOVERY_PENDING,
    OPEN,
    RECOVERY_REQUIRED
}

sealed interface RecoveryAuthorization {
    val authorizationId: String
    val bootSessionId: String

    data class DispatchIssued(
        override val authorizationId: String,
        override val bootSessionId: String,
        val proofId: String,
        val commandAttempt: CommandAttemptToken
    ) : RecoveryAuthorization {
        init {
            validateIdentity(authorizationId, bootSessionId)
            require(proofId.isNotBlank()) { "proofId must not be blank" }
        }
    }

    data class EffectIssued(
        override val authorizationId: String,
        override val bootSessionId: String,
        val effectToken: EffectToken
    ) : RecoveryAuthorization {
        init {
            validateIdentity(authorizationId, bootSessionId)
        }
    }

    companion object {
        private fun validateIdentity(authorizationId: String, bootSessionId: String) {
            require(authorizationId.isNotBlank()) { "authorizationId must not be blank" }
            require(bootSessionId.isNotBlank()) { "bootSessionId must not be blank" }
        }
    }
}

data class RecoveryAuthorizationToken(
    val bootSessionId: String,
    val authorizationId: String
) {
    init {
        require(bootSessionId.isNotBlank()) { "bootSessionId must not be blank" }
        require(authorizationId.isNotBlank()) { "authorizationId must not be blank" }
    }
}

data class RecoveryObservationCursor(
    val commandAttempt: CommandAttemptToken,
    val lastAcceptedSampleSequence: Long,
    val lastAcceptedSampledAtMs: Long
) {
    init {
        require(lastAcceptedSampleSequence > 0L) { "lastAcceptedSampleSequence must be positive" }
        require(lastAcceptedSampledAtMs >= 0L) { "lastAcceptedSampledAtMs must not be negative" }
    }
}

data class MechanicalAdmissionGate(
    val bootSessionId: String,
    val state: AdmissionGateState,
    val reason: String? = null,
    val recoveryAuthorization: RecoveryAuthorization? = null
) {
    init {
        require(bootSessionId.isNotBlank()) { "bootSessionId must not be blank" }
        recoveryAuthorization?.let { authorization ->
            require(state == AdmissionGateState.RECOVERY_REQUIRED) {
                "recovery authorization requires RECOVERY_REQUIRED gate"
            }
            require(authorization.bootSessionId == bootSessionId) {
                "authorization boot session must match gate"
            }
        }
    }
}

data class MechanicalOperation(
    val operationId: String,
    val revision: Long,
    val kind: MechanicalOperationKind,
    val admissionClass: MechanicalAdmissionClass,
    val state: MechanicalOperationState,
    val steps: List<MechanicalStep>,
    val currentStepIndex: Int,
    val heldResources: Set<MechanicalResource>,
    val checkpoint: MechanicalCheckpoint?,
    val resumeCursor: ResumeCursor?,
    val currentCommandAttempt: CommandAttemptToken?,
    val lastConfirmedCommandAttempt: CommandAttemptToken?,
    val physicalResult: MechanicalPhysicalResult,
    val effectState: MechanicalEffectState,
    val recoveryReason: String? = null,
    val appliedEffectKeys: Set<String> = emptySet()
) {
    init {
        require(operationId.isNotBlank()) { "operationId must not be blank" }
        require(revision > 0L) { "revision must be positive" }
        require(steps.isNotEmpty()) { "operation must contain at least one step" }
        require(steps.map { it.stepId }.distinct().size == steps.size) { "stepId must be unique" }
        require(steps.map { it.commandIdempotencyKey }.distinct().size == steps.size) {
            "command idempotency key must be unique"
        }
        require(steps.map { it.effectIdempotencyKey }.distinct().size == steps.size) {
            "effect idempotency key must be unique"
        }
        require(currentStepIndex in steps.indices) { "currentStepIndex is out of bounds" }
        require(isAdmissionClassAllowed(kind, admissionClass)) {
            "admissionClass=$admissionClass is invalid for kind=$kind"
        }
        require(appliedEffectKeys.none { it.isBlank() }) { "applied effect key must not be blank" }
        val eligibleEffectKeys = steps
            .take(currentStepIndex + 1)
            .map { it.effectIdempotencyKey }
            .toSet()
        require(appliedEffectKeys.all(eligibleEffectKeys::contains)) {
            "applied effect key belongs to a future or unknown step"
        }
        if (state == MechanicalOperationState.REQUESTED) {
            require(appliedEffectKeys.isEmpty()) { "requested operation cannot have applied effects" }
        }
        val currentEffectApplied = currentStep.effectIdempotencyKey in appliedEffectKeys
        require((effectState == MechanicalEffectState.APPLIED) == currentEffectApplied) {
            "current step effect state must match applied effect history"
        }
        if (currentEffectApplied) {
            require(physicalResult == MechanicalPhysicalResult.SUCCEEDED) {
                "applied current effect requires successful physical result"
            }
            require(state in setOf(
                MechanicalOperationState.EFFECT_APPLIED,
                MechanicalOperationState.STEP_COMPLETED,
                MechanicalOperationState.PAUSED,
                MechanicalOperationState.RECOVERY_REQUIRED,
                MechanicalOperationState.OPERATION_COMPLETED
            )) { "applied current effect is invalid for state=$state" }
        }

        if (state == MechanicalOperationState.REQUESTED ||
            state == MechanicalOperationState.PAUSED ||
            state == MechanicalOperationState.OPERATION_COMPLETED
        ) {
            require(heldResources.isEmpty()) { "$state must not hold resources" }
        }
        if (state == MechanicalOperationState.PAUSED) {
            require(resumeCursor != null) { "paused operation requires resume cursor" }
        } else {
            require(resumeCursor == null) { "resume cursor is valid only for paused operation" }
        }
        (resumeCursor as? ResumeCursor.ExecuteStep)?.let { cursor ->
            require(cursor.stepIndex in steps.indices) { "resume step index is out of bounds" }
        }

        checkpoint?.let {
            require(it.stepId == currentStep.stepId) { "checkpoint must match current step" }
        }
        currentCommandAttempt?.let(::validateCurrentAttempt)
        lastConfirmedCommandAttempt?.let(::validateHistoricalAttempt)

        when (state) {
            MechanicalOperationState.REQUESTED -> {
                require(currentCommandAttempt == null) { "REQUESTED cannot have current command attempt" }
                require(lastConfirmedCommandAttempt == null) { "REQUESTED cannot have confirmed attempt" }
                require(physicalResult == MechanicalPhysicalResult.NONE) { "REQUESTED requires no result" }
                require(effectState == MechanicalEffectState.NOT_APPLIED) { "REQUESTED cannot have effect" }
                require(checkpoint == null) { "REQUESTED cannot have checkpoint" }
            }
            MechanicalOperationState.ADMITTED -> {
                require(currentCommandAttempt == null) { "ADMITTED cannot have current command attempt" }
                require(physicalResult == MechanicalPhysicalResult.NONE) { "ADMITTED requires no current result" }
                require(effectState == MechanicalEffectState.NOT_APPLIED) { "ADMITTED cannot have current effect" }
            }
            MechanicalOperationState.COMMAND_PREPARED,
            MechanicalOperationState.COMMAND_DISPATCHED -> {
                require(physicalResult == MechanicalPhysicalResult.NONE) { "$state requires no terminal result" }
                require(effectState == MechanicalEffectState.NOT_APPLIED) { "$state cannot have applied effect" }
            }
            MechanicalOperationState.RESULT_CONFIRMED -> {
                require(currentCommandAttempt != null) { "RESULT_CONFIRMED requires current command attempt" }
                require(lastConfirmedCommandAttempt == currentCommandAttempt) {
                    "RESULT_CONFIRMED attempt identities must match"
                }
                require(effectState == MechanicalEffectState.NOT_APPLIED) {
                    "RESULT_CONFIRMED effect must not be applied"
                }
            }
            MechanicalOperationState.EFFECT_APPLIED,
            MechanicalOperationState.STEP_COMPLETED,
            MechanicalOperationState.OPERATION_COMPLETED -> {
                require(currentCommandAttempt != null) { "$state requires current command attempt" }
                require(lastConfirmedCommandAttempt == currentCommandAttempt) {
                    "$state attempt identities must match"
                }
            }
            MechanicalOperationState.PAUSED,
            MechanicalOperationState.RECOVERY_REQUIRED -> Unit
        }

        if (state == MechanicalOperationState.COMMAND_PREPARED ||
            state == MechanicalOperationState.COMMAND_DISPATCHED
        ) {
            require(currentCommandAttempt != null) { "$state requires current command attempt" }
        }
        if (state == MechanicalOperationState.RESULT_CONFIRMED ||
            state == MechanicalOperationState.EFFECT_APPLIED ||
            state == MechanicalOperationState.STEP_COMPLETED ||
            state == MechanicalOperationState.OPERATION_COMPLETED
        ) {
            require(lastConfirmedCommandAttempt != null) { "$state requires confirmed command attempt" }
            require(physicalResult == MechanicalPhysicalResult.SUCCEEDED) { "$state requires successful result" }
        }
        if (state == MechanicalOperationState.EFFECT_APPLIED ||
            state == MechanicalOperationState.STEP_COMPLETED ||
            state == MechanicalOperationState.OPERATION_COMPLETED
        ) {
            require(effectState == MechanicalEffectState.APPLIED) { "$state requires applied effect" }
        }
    }

    val currentStep: MechanicalStep
        get() = steps[currentStepIndex]

    fun token(): OperationToken = OperationToken(operationId, revision)

    fun effectToken(): EffectToken = EffectToken(
        operationId = operationId,
        stepId = currentStep.stepId,
        effectIdempotencyKey = currentStep.effectIdempotencyKey
    )

    private fun validateCurrentAttempt(attempt: CommandAttemptToken) {
        require(attempt.operationId == operationId) { "command attempt operationId mismatch" }
        require(attempt.stepId == currentStep.stepId) { "command attempt stepId mismatch" }
        require(attempt.dispatchRevision <= revision) { "command attempt revision is ahead of operation" }
    }

    private fun validateHistoricalAttempt(attempt: CommandAttemptToken) {
        require(attempt.operationId == operationId) { "confirmed attempt operationId mismatch" }
        require(steps.any { it.stepId == attempt.stepId }) { "confirmed attempt stepId is not part of operation" }
        require(attempt.dispatchRevision <= revision) { "confirmed attempt revision is ahead of operation" }
    }

    private fun isAdmissionClassAllowed(
        operationKind: MechanicalOperationKind,
        classification: MechanicalAdmissionClass
    ): Boolean = when (operationKind) {
        MechanicalOperationKind.SUPPLEMENT -> classification == MechanicalAdmissionClass.SUPPLEMENT
        MechanicalOperationKind.ORDER_DELIVERY -> classification == MechanicalAdmissionClass.PAID_ORDER_DELIVERY
        MechanicalOperationKind.PAN_MOVE -> classification == MechanicalAdmissionClass.SAFE_RETURN ||
            classification == MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE
        MechanicalOperationKind.DISCARD -> classification == MechanicalAdmissionClass.FOOD_SAFETY ||
            classification == MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE
        MechanicalOperationKind.CLEANING -> classification == MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE
    }
}

data class OperationLedgerSnapshot(
    val ledgerRevision: Long,
    val admissionGate: MechanicalAdmissionGate,
    val pending: MechanicalOperation? = null,
    val running: MechanicalOperation? = null,
    val paused: MechanicalOperation? = null,
    val terminal: MechanicalOperation? = null,
    val recoveryObservationCursor: RecoveryObservationCursor? = null
) {
    init {
        require(ledgerRevision > 0L) { "ledgerRevision must be positive" }
        require(pending == null || pending.state == MechanicalOperationState.REQUESTED) {
            "pending slot requires REQUESTED operation"
        }
        require(paused == null || paused.state == MechanicalOperationState.PAUSED) {
            "paused slot requires PAUSED operation"
        }
        require(terminal == null || terminal.state == MechanicalOperationState.OPERATION_COMPLETED) {
            "terminal slot requires OPERATION_COMPLETED operation"
        }
        require(running == null || running.state !in setOf(
            MechanicalOperationState.REQUESTED,
            MechanicalOperationState.PAUSED,
            MechanicalOperationState.OPERATION_COMPLETED
        )) { "running slot contains a non-running state" }
        if (running?.state == MechanicalOperationState.RECOVERY_REQUIRED) {
            require(admissionGate.state == AdmissionGateState.RECOVERY_REQUIRED) {
                "recovery-required operation must close admission gate"
            }
        }

        val operationIds = listOfNotNull(pending, running, paused, terminal).map { it.operationId }
        require(operationIds.distinct().size == operationIds.size) { "ledger operation ids must be distinct" }

        recoveryObservationCursor?.let { cursor ->
            require(admissionGate.state == AdmissionGateState.RECOVERY_REQUIRED) {
                "recovery cursor requires RECOVERY_REQUIRED gate"
            }
            val current = requireNotNull(running) { "recovery cursor requires running operation" }
            require(cursor.commandAttempt == current.currentCommandAttempt ||
                cursor.commandAttempt == current.lastConfirmedCommandAttempt
            ) { "recovery cursor attempt must match running operation" }
        }
    }
}

sealed interface OperationLedgerRead {
    data object Absent : OperationLedgerRead
    data class Present(val snapshot: OperationLedgerSnapshot) : OperationLedgerRead
    data class Corrupt(val reason: String) : OperationLedgerRead {
        init {
            require(reason.isNotBlank()) { "corrupt reason must not be blank" }
        }
    }
}

interface OperationLedgerPort {
    fun load(): OperationLedgerRead
    fun compareAndSet(expectedLedgerRevision: Long?, next: OperationLedgerSnapshot): Boolean
}

data class OperationToken(
    val operationId: String,
    val revision: Long
) {
    init {
        require(operationId.isNotBlank()) { "operationId must not be blank" }
        require(revision > 0L) { "revision must be positive" }
    }
}

enum class EffectApplyResult {
    APPLIED,
    ALREADY_APPLIED,
    FAILED
}

interface MechanicalEffectPort {
    fun applyOnce(token: EffectToken): EffectApplyResult
}
