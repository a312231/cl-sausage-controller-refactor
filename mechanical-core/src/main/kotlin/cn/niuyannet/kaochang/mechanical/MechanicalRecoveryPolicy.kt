package cn.niuyannet.kaochang.mechanical

enum class RegisterActivity {
    IDLE,
    RUNNING,
    COMPLETED,
    FAILED,
    UNKNOWN
}

enum class SnapshotCommandCorrelation {
    MATCHED_CURRENT_ATTEMPT,
    MISMATCHED,
    UNAVAILABLE
}

data class MechanicalRegisterSnapshot(
    val sampleSequence: Long,
    val sampledAtMs: Long,
    val commandAttempt: CommandAttemptToken?,
    val correlation: SnapshotCommandCorrelation,
    val activity: RegisterActivity,
    val resultCode: Int?
) {
    init {
        require(sampleSequence > 0L) { "sampleSequence must be positive" }
        require(sampledAtMs >= 0L) { "sampledAtMs must not be negative" }
        if (correlation == SnapshotCommandCorrelation.MATCHED_CURRENT_ATTEMPT) {
            require(commandAttempt != null) { "matched snapshot requires command attempt" }
        }
    }
}

enum class DispatchProofOutcome {
    CONFIRMED_NOT_DISPATCHED,
    DISPATCHED_OR_MAYBE_DISPATCHED
}

data class DispatchRecoveryProof(
    val commandAttempt: CommandAttemptToken,
    val proofId: String,
    val outcome: DispatchProofOutcome
) {
    init {
        require(proofId.isNotBlank()) { "proofId must not be blank" }
    }
}

enum class RecoveryDecision {
    BLOCK_NEW_OPERATIONS,
    SAFE_IDLE,
    DISPATCH_CONFIRMED_NOT_SENT,
    RECORD_OBSERVED_DISPATCHED,
    WAIT_FOR_RUNNING,
    CONFIRM_RESULT_AND_APPLY_EFFECT,
    APPLY_EFFECT_ONLY,
    CONTINUE_OR_COMPLETE,
    CLOSE_SAFE_TERMINAL,
    RECOVERY_REQUIRED
}

object MechanicalRecoveryPolicy {

    fun decide(
        ledger: OperationLedgerRead,
        modbusConnected: Boolean,
        snapshots: List<MechanicalRegisterSnapshot>,
        dispatchProof: DispatchRecoveryProof?
    ): RecoveryDecision {
        if (!modbusConnected || snapshots.size < 2) {
            return RecoveryDecision.BLOCK_NEW_OPERATIONS
        }
        val evidence = stableEvidence(snapshots) ?: return RecoveryDecision.RECOVERY_REQUIRED
        if (ledger is OperationLedgerRead.Corrupt) return RecoveryDecision.RECOVERY_REQUIRED
        if (ledger is OperationLedgerRead.Absent) {
            return if (evidence.activity == RegisterActivity.IDLE) {
                RecoveryDecision.SAFE_IDLE
            } else {
                RecoveryDecision.RECOVERY_REQUIRED
            }
        }

        val snapshot = (ledger as OperationLedgerRead.Present).snapshot
        if (!isNewerThanCursor(snapshot.recoveryObservationCursor, evidence)) {
            return RecoveryDecision.RECOVERY_REQUIRED
        }
        val running = snapshot.running
        if (running == null) {
            return when {
                evidence.activity != RegisterActivity.IDLE -> RecoveryDecision.RECOVERY_REQUIRED
                snapshot.terminal != null -> RecoveryDecision.CLOSE_SAFE_TERMINAL
                else -> RecoveryDecision.SAFE_IDLE
            }
        }

        return when (running.state) {
            MechanicalOperationState.ADMITTED -> if (evidence.activity == RegisterActivity.IDLE) {
                RecoveryDecision.CONTINUE_OR_COMPLETE
            } else {
                RecoveryDecision.RECOVERY_REQUIRED
            }

            MechanicalOperationState.COMMAND_PREPARED -> decidePrepared(
                running,
                snapshot.admissionGate.recoveryAuthorization,
                evidence,
                dispatchProof
            )

            MechanicalOperationState.COMMAND_DISPATCHED -> decideDispatched(running, evidence)

            MechanicalOperationState.RESULT_CONFIRMED -> if (
                evidence.activity == RegisterActivity.COMPLETED &&
                matches(evidence, running.lastConfirmedCommandAttempt)
            ) {
                RecoveryDecision.APPLY_EFFECT_ONLY
            } else {
                RecoveryDecision.RECOVERY_REQUIRED
            }

            MechanicalOperationState.EFFECT_APPLIED,
            MechanicalOperationState.STEP_COMPLETED -> if (
                evidence.activity == RegisterActivity.COMPLETED &&
                matches(evidence, running.lastConfirmedCommandAttempt)
            ) {
                RecoveryDecision.CONTINUE_OR_COMPLETE
            } else {
                RecoveryDecision.RECOVERY_REQUIRED
            }

            MechanicalOperationState.REQUESTED,
            MechanicalOperationState.PAUSED,
            MechanicalOperationState.OPERATION_COMPLETED,
            MechanicalOperationState.RECOVERY_REQUIRED -> RecoveryDecision.RECOVERY_REQUIRED
        }
    }

    private fun decidePrepared(
        operation: MechanicalOperation,
        authorization: RecoveryAuthorization?,
        evidence: StableEvidence,
        dispatchProof: DispatchRecoveryProof?
    ): RecoveryDecision {
        val attempt = operation.currentCommandAttempt
            ?: return RecoveryDecision.RECOVERY_REQUIRED
        val oldIssued = authorization as? RecoveryAuthorization.DispatchIssued
        if (oldIssued?.commandAttempt == attempt &&
            evidence.activity in setOf(RegisterActivity.RUNNING, RegisterActivity.COMPLETED) &&
            matches(evidence, attempt)
        ) {
            return RecoveryDecision.RECORD_OBSERVED_DISPATCHED
        }
        return if (
            evidence.activity == RegisterActivity.IDLE &&
            dispatchProof?.outcome == DispatchProofOutcome.CONFIRMED_NOT_DISPATCHED &&
            dispatchProof.commandAttempt == attempt
        ) {
            RecoveryDecision.DISPATCH_CONFIRMED_NOT_SENT
        } else {
            RecoveryDecision.RECOVERY_REQUIRED
        }
    }

    private fun decideDispatched(
        operation: MechanicalOperation,
        evidence: StableEvidence
    ): RecoveryDecision {
        if (!matches(evidence, operation.currentCommandAttempt)) {
            return RecoveryDecision.RECOVERY_REQUIRED
        }
        return when (evidence.activity) {
            RegisterActivity.RUNNING -> RecoveryDecision.WAIT_FOR_RUNNING
            RegisterActivity.COMPLETED -> if (operation.effectState == MechanicalEffectState.NOT_APPLIED) {
                RecoveryDecision.CONFIRM_RESULT_AND_APPLY_EFFECT
            } else {
                RecoveryDecision.CONTINUE_OR_COMPLETE
            }
            RegisterActivity.IDLE,
            RegisterActivity.FAILED,
            RegisterActivity.UNKNOWN -> RecoveryDecision.RECOVERY_REQUIRED
        }
    }

    private fun matches(
        evidence: StableEvidence,
        expected: CommandAttemptToken?
    ): Boolean = expected != null &&
        evidence.correlation == SnapshotCommandCorrelation.MATCHED_CURRENT_ATTEMPT &&
        evidence.commandAttempt == expected

    private fun isNewerThanCursor(
        cursor: RecoveryObservationCursor?,
        evidence: StableEvidence
    ): Boolean {
        if (cursor == null) return true
        return evidence.commandAttempt == cursor.commandAttempt &&
            evidence.firstSequence > cursor.lastAcceptedSampleSequence &&
            evidence.firstSampledAtMs >= cursor.lastAcceptedSampledAtMs
    }

    private fun stableEvidence(
        snapshots: List<MechanicalRegisterSnapshot>
    ): StableEvidence? {
        val first = snapshots[snapshots.lastIndex - 1]
        val second = snapshots.last()
        if (second.sampleSequence <= first.sampleSequence || second.sampledAtMs < first.sampledAtMs) {
            return null
        }
        if (first.commandAttempt != second.commandAttempt ||
            first.correlation != second.correlation ||
            first.activity != second.activity ||
            first.resultCode != second.resultCode
        ) {
            return null
        }
        return StableEvidence(
            firstSequence = first.sampleSequence,
            lastSequence = second.sampleSequence,
            firstSampledAtMs = first.sampledAtMs,
            lastSampledAtMs = second.sampledAtMs,
            commandAttempt = second.commandAttempt,
            correlation = second.correlation,
            activity = second.activity,
            resultCode = second.resultCode
        )
    }

    private data class StableEvidence(
        val firstSequence: Long,
        val lastSequence: Long,
        val firstSampledAtMs: Long,
        val lastSampledAtMs: Long,
        val commandAttempt: CommandAttemptToken?,
        val correlation: SnapshotCommandCorrelation,
        val activity: RegisterActivity,
        val resultCode: Int?
    )
}

object MoveStatusProjection {
    fun from(operation: MechanicalOperation?): Int {
        if (operation == null || operation.state in setOf(
                MechanicalOperationState.REQUESTED,
                MechanicalOperationState.PAUSED,
                MechanicalOperationState.OPERATION_COMPLETED
            )
        ) {
            return 0
        }
        return when (operation.kind) {
            MechanicalOperationKind.SUPPLEMENT -> 1
            MechanicalOperationKind.ORDER_DELIVERY -> 2
            MechanicalOperationKind.PAN_MOVE -> 3
            MechanicalOperationKind.DISCARD -> 4
            MechanicalOperationKind.CLEANING -> 5
        }
    }
}
