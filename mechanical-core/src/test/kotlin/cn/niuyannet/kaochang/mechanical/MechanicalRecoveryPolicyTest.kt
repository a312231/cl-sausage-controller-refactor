package cn.niuyannet.kaochang.mechanical

import org.junit.Assert.assertEquals
import org.junit.Test

class MechanicalRecoveryPolicyTest {

    @Test
    fun `startup requires Modbus connection and two stable snapshots`() {
        assertEquals(
            RecoveryDecision.BLOCK_NEW_OPERATIONS,
            decide(OperationLedgerRead.Absent, connected = false, snapshots = stableIdle())
        )
        assertEquals(
            RecoveryDecision.BLOCK_NEW_OPERATIONS,
            decide(OperationLedgerRead.Absent, connected = true, snapshots = stableIdle().take(1))
        )
        assertEquals(
            RecoveryDecision.RECOVERY_REQUIRED,
            decide(OperationLedgerRead.Absent, connected = true, snapshots = unstable())
        )
        assertEquals(
            RecoveryDecision.SAFE_IDLE,
            decide(OperationLedgerRead.Absent, connected = true, snapshots = stableIdle())
        )
    }

    @Test
    fun `corrupt ledger never becomes idle`() {
        assertEquals(
            RecoveryDecision.RECOVERY_REQUIRED,
            decide(OperationLedgerRead.Corrupt("bad ledger"), snapshots = stableIdle())
        )
    }

    @Test
    fun `prepared command needs matching durable not-dispatched proof`() {
        val prepared = prepared()
        val attempt = requireNotNull(prepared.currentCommandAttempt)
        assertEquals(
            RecoveryDecision.RECOVERY_REQUIRED,
            decide(present(prepared), snapshots = stableIdle(), proof = null)
        )
        assertEquals(
            RecoveryDecision.RECOVERY_REQUIRED,
            decide(
                present(prepared),
                snapshots = stableIdle(),
                proof = notDispatchedProof(attempt.copy(attemptId = "old"))
            )
        )
        assertEquals(
            RecoveryDecision.DISPATCH_CONFIRMED_NOT_SENT,
            decide(
                present(prepared),
                snapshots = stableIdle(),
                proof = notDispatchedProof(attempt)
            )
        )
    }

    @Test
    fun `prepared command with old dispatch authorization and matched activity records observed dispatch`() {
        val prepared = prepared()
        val attempt = requireNotNull(prepared.currentCommandAttempt)
        val authorization = RecoveryAuthorization.DispatchIssued(
            authorizationId = "old-authorization",
            bootSessionId = "old-boot",
            proofId = "proof-1",
            commandAttempt = attempt
        )
        val ledger = present(
            prepared,
            gate = MechanicalAdmissionGate(
                bootSessionId = "old-boot",
                state = AdmissionGateState.RECOVERY_REQUIRED,
                recoveryAuthorization = authorization
            )
        )
        assertEquals(
            RecoveryDecision.RECORD_OBSERVED_DISPATCHED,
            decide(ledger, snapshots = stable(attempt, RegisterActivity.RUNNING))
        )
        assertEquals(
            RecoveryDecision.RECORD_OBSERVED_DISPATCHED,
            decide(ledger, snapshots = stable(attempt, RegisterActivity.COMPLETED, resultCode = 2))
        )
    }

    @Test
    fun `dispatched command distinguishes running completed and unavailable evidence`() {
        val dispatched = dispatched()
        val attempt = requireNotNull(dispatched.currentCommandAttempt)
        assertEquals(
            RecoveryDecision.WAIT_FOR_RUNNING,
            decide(present(dispatched), snapshots = stable(attempt, RegisterActivity.RUNNING))
        )
        assertEquals(
            RecoveryDecision.CONFIRM_RESULT_AND_APPLY_EFFECT,
            decide(present(dispatched), snapshots = stable(attempt, RegisterActivity.COMPLETED, 2))
        )
        assertEquals(
            RecoveryDecision.RECOVERY_REQUIRED,
            decide(
                present(dispatched),
                snapshots = stable(
                    attempt = null,
                    activity = RegisterActivity.COMPLETED,
                    resultCode = 2,
                    correlation = SnapshotCommandCorrelation.UNAVAILABLE
                )
            )
        )
    }

    @Test
    fun `cursor rejects replay and accepts strictly newer matched evidence`() {
        val dispatched = dispatched()
        val attempt = requireNotNull(dispatched.currentCommandAttempt)
        val cursor = RecoveryObservationCursor(attempt, 2, 20)
        val ledger = present(dispatched, cursor = cursor)
        assertEquals(
            RecoveryDecision.RECOVERY_REQUIRED,
            decide(
                ledger,
                snapshots = stable(attempt, RegisterActivity.COMPLETED, 2, sequenceStart = 1)
            )
        )
        assertEquals(
            RecoveryDecision.CONFIRM_RESULT_AND_APPLY_EFFECT,
            decide(
                ledger,
                snapshots = stable(attempt, RegisterActivity.COMPLETED, 2, sequenceStart = 3)
            )
        )
    }

    @Test
    fun `confirmed result applies effect only without redispatch`() {
        val confirmed = confirmed()
        val attempt = requireNotNull(confirmed.lastConfirmedCommandAttempt)
        assertEquals(
            RecoveryDecision.APPLY_EFFECT_ONLY,
            decide(present(confirmed), snapshots = stable(attempt, RegisterActivity.COMPLETED, 2))
        )
    }

    @Test
    fun `move status is derived and recovery stays busy`() {
        assertEquals(0, MoveStatusProjection.from(null))
        assertEquals(0, MoveStatusProjection.from(requested()))
        assertEquals(0, MoveStatusProjection.from(paused()))
        assertEquals(1, MoveStatusProjection.from(admitted(MechanicalOperationKind.SUPPLEMENT)))
        assertEquals(2, MoveStatusProjection.from(admitted(MechanicalOperationKind.ORDER_DELIVERY)))
        assertEquals(3, MoveStatusProjection.from(admitted(MechanicalOperationKind.PAN_MOVE)))
        assertEquals(4, MoveStatusProjection.from(admitted(MechanicalOperationKind.DISCARD)))
        assertEquals(5, MoveStatusProjection.from(admitted(MechanicalOperationKind.CLEANING)))

        val delivery = admitted(MechanicalOperationKind.ORDER_DELIVERY)
        val recovery = applied(
            MechanicalOperationStateMachine.requireRecovery(delivery, delivery.token(), "unknown")
        )
        assertEquals(2, MoveStatusProjection.from(recovery))
    }

    private fun decide(
        ledger: OperationLedgerRead,
        connected: Boolean = true,
        snapshots: List<MechanicalRegisterSnapshot>,
        proof: DispatchRecoveryProof? = null
    ): RecoveryDecision = MechanicalRecoveryPolicy.decide(ledger, connected, snapshots, proof)

    private fun present(
        running: MechanicalOperation,
        gate: MechanicalAdmissionGate = recoveryGate(),
        cursor: RecoveryObservationCursor? = null
    ): OperationLedgerRead.Present = OperationLedgerRead.Present(
        OperationLedgerSnapshot(
            ledgerRevision = 1,
            admissionGate = gate,
            running = running,
            recoveryObservationCursor = cursor
        )
    )

    private fun stableIdle(sequenceStart: Long = 1) = listOf(
        MechanicalRegisterSnapshot(sequenceStart, 10, null, SnapshotCommandCorrelation.UNAVAILABLE, RegisterActivity.IDLE, 0),
        MechanicalRegisterSnapshot(sequenceStart + 1, 20, null, SnapshotCommandCorrelation.UNAVAILABLE, RegisterActivity.IDLE, 0)
    )

    private fun unstable() = listOf(
        MechanicalRegisterSnapshot(1, 10, null, SnapshotCommandCorrelation.UNAVAILABLE, RegisterActivity.IDLE, 0),
        MechanicalRegisterSnapshot(2, 20, null, SnapshotCommandCorrelation.UNAVAILABLE, RegisterActivity.RUNNING, 0)
    )

    private fun stable(
        attempt: CommandAttemptToken?,
        activity: RegisterActivity,
        resultCode: Int? = 0,
        sequenceStart: Long = 1,
        correlation: SnapshotCommandCorrelation = SnapshotCommandCorrelation.MATCHED_CURRENT_ATTEMPT
    ) = listOf(
        MechanicalRegisterSnapshot(sequenceStart, sequenceStart * 10, attempt, correlation, activity, resultCode),
        MechanicalRegisterSnapshot(sequenceStart + 1, (sequenceStart + 1) * 10, attempt, correlation, activity, resultCode)
    )

    private fun notDispatchedProof(attempt: CommandAttemptToken) = DispatchRecoveryProof(
        commandAttempt = attempt,
        proofId = "proof-not-dispatched",
        outcome = DispatchProofOutcome.CONFIRMED_NOT_DISPATCHED
    )

    private fun requested(kind: MechanicalOperationKind = MechanicalOperationKind.SUPPLEMENT): MechanicalOperation {
        val admission = when (kind) {
            MechanicalOperationKind.SUPPLEMENT -> MechanicalAdmissionClass.SUPPLEMENT
            MechanicalOperationKind.ORDER_DELIVERY -> MechanicalAdmissionClass.PAID_ORDER_DELIVERY
            MechanicalOperationKind.PAN_MOVE -> MechanicalAdmissionClass.SAFE_RETURN
            MechanicalOperationKind.DISCARD -> MechanicalAdmissionClass.FOOD_SAFETY
            MechanicalOperationKind.CLEANING -> MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE
        }
        return MechanicalOperation(
            operationId = "operation-${kind.name}",
            revision = 1,
            kind = kind,
            admissionClass = admission,
            state = MechanicalOperationState.REQUESTED,
            steps = listOf(
                MechanicalStep(
                    stepId = "step",
                    commandIdempotencyKey = "command-${kind.name}",
                    effectIdempotencyKey = "effect-${kind.name}",
                    resources = setOf(MechanicalResource.LIFT)
                )
            ),
            currentStepIndex = 0,
            heldResources = emptySet(),
            checkpoint = null,
            resumeCursor = null,
            currentCommandAttempt = null,
            lastConfirmedCommandAttempt = null,
            physicalResult = MechanicalPhysicalResult.NONE,
            effectState = MechanicalEffectState.NOT_APPLIED
        )
    }

    private fun admitted(kind: MechanicalOperationKind = MechanicalOperationKind.SUPPLEMENT): MechanicalOperation {
        val requested = requested(kind)
        return applied(MechanicalOperationStateMachine.admit(requested, requested.token()))
    }

    private fun prepared(): MechanicalOperation {
        val admitted = admitted()
        return applied(MechanicalOperationStateMachine.prepareCommand(admitted, admitted.token(), "attempt-1"))
    }

    private fun dispatched(): MechanicalOperation {
        val prepared = prepared()
        return applied(
            MechanicalOperationStateMachine.markDispatched(
                prepared,
                requireNotNull(prepared.currentCommandAttempt)
            )
        )
    }

    private fun confirmed(): MechanicalOperation {
        val dispatched = dispatched()
        return applied(
            MechanicalOperationStateMachine.confirmResult(
                dispatched,
                requireNotNull(dispatched.currentCommandAttempt),
                MechanicalPhysicalResult.SUCCEEDED
            )
        )
    }

    private fun paused(): MechanicalOperation {
        val admitted = admitted()
        return applied(MechanicalResourcePolicy.pauseAtCheckpoint(admitted, admitted.token()))
    }

    private fun recoveryGate() = MechanicalAdmissionGate("boot", AdmissionGateState.RECOVERY_REQUIRED)

    private fun applied(result: TransitionResult): MechanicalOperation =
        (result as TransitionResult.Applied).updated
}
