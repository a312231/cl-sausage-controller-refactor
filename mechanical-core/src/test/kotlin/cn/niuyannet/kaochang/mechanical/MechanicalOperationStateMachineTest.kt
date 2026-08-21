package cn.niuyannet.kaochang.mechanical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MechanicalOperationStateMachineTest {

    @Test
    fun `out4 effect remains confirmed when out5 becomes unknown`() {
        var current = operation(
            kind = MechanicalOperationKind.ORDER_DELIVERY,
            admissionClass = MechanicalAdmissionClass.PAID_ORDER_DELIVERY,
            steps = listOf(step("out4"), step("out5"))
        )
        current = applied(MechanicalOperationStateMachine.admit(current, current.token()))
        current = applied(MechanicalOperationStateMachine.prepareCommand(current, current.token(), "out4-attempt"))
        var attempt = requireNotNull(current.currentCommandAttempt)
        current = applied(MechanicalOperationStateMachine.markDispatched(current, attempt))
        current = applied(
            MechanicalOperationStateMachine.confirmResult(
                current,
                attempt,
                MechanicalPhysicalResult.SUCCEEDED
            )
        )
        current = applied(MechanicalOperationStateMachine.markEffectApplied(current, current.token()))
        current = applied(MechanicalOperationStateMachine.completeStep(current, current.token()))
        assertTrue("effect-out4" in current.appliedEffectKeys)

        current = applied(MechanicalOperationStateMachine.startNextStep(current, current.token()))
        current = applied(MechanicalOperationStateMachine.prepareCommand(current, current.token(), "out5-attempt"))
        attempt = requireNotNull(current.currentCommandAttempt)
        current = applied(MechanicalOperationStateMachine.markDispatched(current, attempt))
        current = applied(
            MechanicalOperationStateMachine.confirmResult(
                current,
                attempt,
                MechanicalPhysicalResult.UNKNOWN
            )
        )

        assertEquals(MechanicalOperationState.RECOVERY_REQUIRED, current.state)
        assertTrue("effect-out4" in current.appliedEffectKeys)
        assertTrue("effect-out5" !in current.appliedEffectKeys)
    }

    @Test
    fun `out4 effect survives out5 failed cancelled and timed out recovery`() {
        val dispatched = out4CompletedOut5Dispatched()
        listOf(MechanicalPhysicalResult.FAILED, MechanicalPhysicalResult.UNKNOWN).forEach { result ->
            val recovered = applied(
                MechanicalOperationStateMachine.confirmResult(
                    dispatched,
                    requireNotNull(dispatched.currentCommandAttempt),
                    result
                )
            )
            assertEquals(MechanicalOperationState.RECOVERY_REQUIRED, recovered.state)
            assertTrue("effect-out4" in recovered.appliedEffectKeys)
            assertTrue("effect-out5" !in recovered.appliedEffectKeys)
        }
        listOf("cancelled", "timed out").forEach { reason ->
            val recovered = applied(
                MechanicalResourcePolicy.requireRecovery(dispatched, dispatched.token(), reason)
            )
            assertEquals(MechanicalOperationState.RECOVERY_REQUIRED, recovered.state)
            assertTrue("effect-out4" in recovered.appliedEffectKeys)
            assertTrue("effect-out5" !in recovered.appliedEffectKeys)
        }
    }

    @Test
    fun `effect history rejects future keys and current effect inconsistencies`() {
        val twoSteps = listOf(step("out4"), step("out5"))
        assertThrows(IllegalArgumentException::class.java) {
            operation(steps = twoSteps, appliedEffectKeys = setOf("effect-out5"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            operation(steps = twoSteps, appliedEffectKeys = setOf("effect-out4"))
        }

        val confirmed = confirmedSingleStep()
        assertThrows(IllegalArgumentException::class.java) {
            confirmed.copy(
                state = MechanicalOperationState.EFFECT_APPLIED,
                effectState = MechanicalEffectState.APPLIED,
                appliedEffectKeys = emptySet()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            confirmed.copy(
                effectState = MechanicalEffectState.NOT_APPLIED,
                appliedEffectKeys = setOf(confirmed.currentStep.effectIdempotencyKey)
            )
        }
        val effectApplied = applied(
            MechanicalOperationStateMachine.markEffectApplied(confirmed, confirmed.token())
        )
        assertThrows(IllegalArgumentException::class.java) {
            effectApplied.copy(physicalResult = MechanicalPhysicalResult.UNKNOWN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            effectApplied.copy(state = MechanicalOperationState.COMMAND_DISPATCHED)
        }
    }

    @Test
    fun `single step follows the exact durable phase chain`() {
        val states = mutableListOf(MechanicalOperationState.REQUESTED)
        var current = operation()

        current = applied(MechanicalOperationStateMachine.admit(current, current.token())).also { states += it.state }
        current = applied(
            MechanicalOperationStateMachine.prepareCommand(current, current.token(), "attempt-1")
        ).also { states += it.state }
        val commandAttempt = requireNotNull(current.currentCommandAttempt)
        current = applied(
            MechanicalOperationStateMachine.markDispatched(current, commandAttempt)
        ).also { states += it.state }
        current = applied(
            MechanicalOperationStateMachine.confirmResult(
                current,
                commandAttempt,
                MechanicalPhysicalResult.SUCCEEDED
            )
        ).also { states += it.state }
        current = applied(
            MechanicalOperationStateMachine.markEffectApplied(current, current.token())
        ).also { states += it.state }
        current = applied(
            MechanicalOperationStateMachine.completeStep(current, current.token())
        ).also { states += it.state }
        current = applied(
            MechanicalOperationStateMachine.completeOperation(current, current.token())
        ).also { states += it.state }

        assertEquals(
            listOf(
                MechanicalOperationState.REQUESTED,
                MechanicalOperationState.ADMITTED,
                MechanicalOperationState.COMMAND_PREPARED,
                MechanicalOperationState.COMMAND_DISPATCHED,
                MechanicalOperationState.RESULT_CONFIRMED,
                MechanicalOperationState.EFFECT_APPLIED,
                MechanicalOperationState.STEP_COMPLETED,
                MechanicalOperationState.OPERATION_COMPLETED
            ),
            states
        )
        assertEquals((1L..8L).last, current.revision)
        assertTrue(current.heldResources.isEmpty())
    }

    @Test
    fun `next step resumes at admitted without replaying completed step`() {
        val firstCompleted = completedFirstStepOfTwo()
        val oldAttempt = requireNotNull(firstCompleted.lastConfirmedCommandAttempt)

        val next = applied(
            MechanicalOperationStateMachine.startNextStep(firstCompleted, firstCompleted.token())
        )

        assertEquals(MechanicalOperationState.ADMITTED, next.state)
        assertEquals(1, next.currentStepIndex)
        assertEquals(next.currentStep.resources, next.heldResources)
        assertEquals(MechanicalPhysicalResult.NONE, next.physicalResult)
        assertEquals(MechanicalEffectState.NOT_APPLIED, next.effectState)
        assertTrue(
            MechanicalOperationStateMachine.confirmResult(
                next,
                oldAttempt,
                MechanicalPhysicalResult.SUCCEEDED
            ) is TransitionResult.DuplicateEvidence
        )
    }

    @Test
    fun `same confirmed attempt is duplicate throughout later states`() {
        val confirmed = confirmedSingleStep()
        val attempt = requireNotNull(confirmed.lastConfirmedCommandAttempt)
        val laterStates = buildList {
            add(confirmed)
            val effectApplied = applied(
                MechanicalOperationStateMachine.markEffectApplied(confirmed, confirmed.token())
            )
            add(effectApplied)
            val stepCompleted = applied(
                MechanicalOperationStateMachine.completeStep(effectApplied, effectApplied.token())
            )
            add(stepCompleted)
            add(applied(MechanicalOperationStateMachine.completeOperation(stepCompleted, stepCompleted.token())))
        }

        laterStates.forEach { later ->
            assertTrue(
                "state=${later.state}",
                MechanicalOperationStateMachine.confirmResult(
                    later,
                    attempt,
                    MechanicalPhysicalResult.SUCCEEDED
                ) is TransitionResult.DuplicateEvidence
            )
        }
    }

    @Test
    fun `wrong command attempt is stale and never mutates current operation`() {
        val current = dispatched()
        val stale = requireNotNull(current.currentCommandAttempt).copy(attemptId = "old-attempt")

        val result = MechanicalOperationStateMachine.confirmResult(
            current,
            stale,
            MechanicalPhysicalResult.SUCCEEDED
        )

        assertTrue(result is TransitionResult.StaleCommandEvidence)
        assertEquals(MechanicalOperationState.COMMAND_DISPATCHED, current.state)
    }

    @Test
    fun `failed or unknown result enters recovery and preserves resources`() {
        listOf(MechanicalPhysicalResult.FAILED, MechanicalPhysicalResult.UNKNOWN).forEach { result ->
            val current = dispatched()
            val updated = applied(
                MechanicalOperationStateMachine.confirmResult(
                    current,
                    requireNotNull(current.currentCommandAttempt),
                    result
                )
            )
            assertEquals(MechanicalOperationState.RECOVERY_REQUIRED, updated.state)
            assertEquals(current.heldResources, updated.heldResources)
            assertEquals(result, updated.physicalResult)
        }
    }

    @Test
    fun `state machine rejects skipped phases and stale operation revision`() {
        val requested = operation()
        assertTrue(
            MechanicalOperationStateMachine.prepareCommand(requested, requested.token(), "attempt")
                is TransitionResult.Rejected
        )
        val admitted = applied(MechanicalOperationStateMachine.admit(requested, requested.token()))
        assertTrue(
            MechanicalOperationStateMachine.admit(admitted, requested.token())
                is TransitionResult.StaleOperationEvidence
        )
        assertTrue(
            MechanicalOperationStateMachine.completeOperation(
                completedFirstStepOfTwo(),
                completedFirstStepOfTwo().token()
            ) is TransitionResult.Rejected
        )
    }

    @Test
    fun `operation rejects blank id invalid revision empty steps and duplicate step ids`() {
        assertThrows(IllegalArgumentException::class.java) { operation(operationId = "") }
        assertThrows(IllegalArgumentException::class.java) { operation(revision = 0) }
        assertThrows(IllegalArgumentException::class.java) { operation(steps = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            operation(steps = listOf(step("out1"), step("out1")))
        }
    }

    @Test
    fun `operation kind constrains fixed admission class`() {
        assertThrows(IllegalArgumentException::class.java) {
            operation(
                kind = MechanicalOperationKind.SUPPLEMENT,
                admissionClass = MechanicalAdmissionClass.PAID_ORDER_DELIVERY
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            operation(
                kind = MechanicalOperationKind.ORDER_DELIVERY,
                admissionClass = MechanicalAdmissionClass.SUPPLEMENT
            )
        }
        assertEquals(
            MechanicalAdmissionClass.SAFE_RETURN,
            operation(
                kind = MechanicalOperationKind.PAN_MOVE,
                admissionClass = MechanicalAdmissionClass.SAFE_RETURN
            ).admissionClass
        )
    }

    @Test
    fun `operation rejects impossible durable phase field combinations`() {
        val requested = operation()
        assertThrows(IllegalArgumentException::class.java) {
            requested.copy(
                currentCommandAttempt = CommandAttemptToken(
                    operationId = requested.operationId,
                    dispatchRevision = requested.revision,
                    stepId = requested.currentStep.stepId,
                    attemptId = "impossible-requested-attempt"
                )
            )
        }

        val admitted = admitted()
        assertThrows(IllegalArgumentException::class.java) {
            admitted.copy(physicalResult = MechanicalPhysicalResult.RUNNING)
        }

        val prepared = applied(
            MechanicalOperationStateMachine.prepareCommand(admitted, admitted.token(), "prepared-attempt")
        )
        assertThrows(IllegalArgumentException::class.java) {
            prepared.copy(physicalResult = MechanicalPhysicalResult.SUCCEEDED)
        }

        val dispatched = applied(
            MechanicalOperationStateMachine.markDispatched(
                prepared,
                requireNotNull(prepared.currentCommandAttempt)
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            dispatched.copy(physicalResult = MechanicalPhysicalResult.RUNNING)
        }

        val confirmed = applied(
            MechanicalOperationStateMachine.confirmResult(
                dispatched,
                requireNotNull(dispatched.currentCommandAttempt),
                MechanicalPhysicalResult.SUCCEEDED
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            confirmed.copy(currentCommandAttempt = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            confirmed.copy(
                lastConfirmedCommandAttempt = requireNotNull(confirmed.lastConfirmedCommandAttempt)
                    .copy(attemptId = "different-confirmed-attempt")
            )
        }
    }

    @Test
    fun `paused operation requires cursor and never owns resources`() {
        assertThrows(IllegalArgumentException::class.java) {
            operation(state = MechanicalOperationState.PAUSED, resumeCursor = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            operation(
                state = MechanicalOperationState.PAUSED,
                heldResources = setOf(MechanicalResource.LIFT),
                resumeCursor = ResumeCursor.ExecuteStep(0)
            )
        }
    }

    @Test
    fun `ledger slots have distinct operation ids and exact states`() {
        assertThrows(IllegalArgumentException::class.java) {
            OperationLedgerSnapshot(
                ledgerRevision = 1,
                admissionGate = openGate(),
                pending = operation(operationId = "same"),
                running = admitted(operationId = "same")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OperationLedgerSnapshot(
                ledgerRevision = 1,
                admissionGate = openGate(),
                paused = operation(operationId = "paused-but-requested")
            )
        }
        val dispatched = dispatched()
        val recoveryRequired = applied(
            MechanicalOperationStateMachine.confirmResult(
                dispatched,
                requireNotNull(dispatched.currentCommandAttempt),
                MechanicalPhysicalResult.UNKNOWN
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            OperationLedgerSnapshot(
                ledgerRevision = 1,
                admissionGate = openGate(),
                running = recoveryRequired
            )
        }
    }

    @Test
    fun `recovery authorization is valid only in matching recovery gate`() {
        val authorization = dispatchAuthorization()
        assertThrows(IllegalArgumentException::class.java) {
            openGate().copy(recoveryAuthorization = authorization)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MechanicalAdmissionGate(
                bootSessionId = "other-boot",
                state = AdmissionGateState.RECOVERY_REQUIRED,
                recoveryAuthorization = authorization
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MechanicalAdmissionGate(
                bootSessionId = BOOT,
                state = AdmissionGateState.STARTUP_RECOVERY_PENDING,
                recoveryAuthorization = authorization
            )
        }
    }

    @Test
    fun `recovery cursor must match running command attempt and recovery gate`() {
        val running = dispatched()
        val cursor = RecoveryObservationCursor(
            commandAttempt = requireNotNull(running.currentCommandAttempt),
            lastAcceptedSampleSequence = 2,
            lastAcceptedSampledAtMs = 20
        )
        assertThrows(IllegalArgumentException::class.java) {
            OperationLedgerSnapshot(
                ledgerRevision = 1,
                admissionGate = openGate(),
                running = running,
                recoveryObservationCursor = cursor
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OperationLedgerSnapshot(
                ledgerRevision = 1,
                admissionGate = recoveryGate(),
                running = running,
                recoveryObservationCursor = cursor.copy(commandAttempt = cursor.commandAttempt.copy(attemptId = "old"))
            )
        }
    }

    private fun step(id: String = "out1") = MechanicalStep(
        stepId = id,
        commandIdempotencyKey = "command-$id",
        effectIdempotencyKey = "effect-$id",
        resources = setOf(MechanicalResource.LIFT)
    )

    private fun operation(
        operationId: String = "operation-1",
        revision: Long = 1,
        kind: MechanicalOperationKind = MechanicalOperationKind.SUPPLEMENT,
        admissionClass: MechanicalAdmissionClass = MechanicalAdmissionClass.SUPPLEMENT,
        state: MechanicalOperationState = MechanicalOperationState.REQUESTED,
        steps: List<MechanicalStep> = listOf(step()),
        heldResources: Set<MechanicalResource> = emptySet(),
        resumeCursor: ResumeCursor? = null,
        currentCommandAttempt: CommandAttemptToken? = null,
        lastConfirmedCommandAttempt: CommandAttemptToken? = null,
        currentStepIndex: Int = 0,
        checkpoint: MechanicalCheckpoint? = null,
        physicalResult: MechanicalPhysicalResult = MechanicalPhysicalResult.NONE,
        effectState: MechanicalEffectState = MechanicalEffectState.NOT_APPLIED,
        recoveryReason: String? = null,
        appliedEffectKeys: Set<String> = emptySet()
    ) = MechanicalOperation(
        operationId = operationId,
        revision = revision,
        kind = kind,
        admissionClass = admissionClass,
        state = state,
        steps = steps,
        currentStepIndex = currentStepIndex,
        heldResources = heldResources,
        checkpoint = checkpoint,
        resumeCursor = resumeCursor,
        currentCommandAttempt = currentCommandAttempt,
        lastConfirmedCommandAttempt = lastConfirmedCommandAttempt,
        physicalResult = physicalResult,
        effectState = effectState,
        recoveryReason = recoveryReason,
        appliedEffectKeys = appliedEffectKeys
    )

    private fun admitted(operationId: String = "running") = operation(
        operationId = operationId,
        state = MechanicalOperationState.ADMITTED,
        heldResources = setOf(MechanicalResource.LIFT)
    )

    private fun dispatched(): MechanicalOperation {
        val attempt = CommandAttemptToken("running", 2, "out1", "attempt-1")
        return operation(
            operationId = "running",
            revision = 3,
            state = MechanicalOperationState.COMMAND_DISPATCHED,
            heldResources = setOf(MechanicalResource.LIFT),
            currentCommandAttempt = attempt
        )
    }

    private fun confirmedSingleStep(): MechanicalOperation {
        var current = operation()
        current = applied(MechanicalOperationStateMachine.admit(current, current.token()))
        current = applied(MechanicalOperationStateMachine.prepareCommand(current, current.token(), "attempt-1"))
        val attempt = requireNotNull(current.currentCommandAttempt)
        current = applied(MechanicalOperationStateMachine.markDispatched(current, attempt))
        return applied(
            MechanicalOperationStateMachine.confirmResult(
                current,
                attempt,
                MechanicalPhysicalResult.SUCCEEDED
            )
        )
    }

    private fun completedFirstStepOfTwo(): MechanicalOperation {
        val steps = listOf(step("out1"), step("out2"))
        var current = operation(steps = steps)
        current = applied(MechanicalOperationStateMachine.admit(current, current.token()))
        current = applied(MechanicalOperationStateMachine.prepareCommand(current, current.token(), "attempt-1"))
        val attempt = requireNotNull(current.currentCommandAttempt)
        current = applied(MechanicalOperationStateMachine.markDispatched(current, attempt))
        current = applied(
            MechanicalOperationStateMachine.confirmResult(
                current,
                attempt,
                MechanicalPhysicalResult.SUCCEEDED
            )
        )
        current = applied(MechanicalOperationStateMachine.markEffectApplied(current, current.token()))
        return applied(MechanicalOperationStateMachine.completeStep(current, current.token()))
    }

    private fun out4CompletedOut5Dispatched(): MechanicalOperation {
        var current = operation(
            kind = MechanicalOperationKind.ORDER_DELIVERY,
            admissionClass = MechanicalAdmissionClass.PAID_ORDER_DELIVERY,
            steps = listOf(step("out4"), step("out5"))
        )
        current = applied(MechanicalOperationStateMachine.admit(current, current.token()))
        current = applied(MechanicalOperationStateMachine.prepareCommand(current, current.token(), "out4-attempt"))
        var attempt = requireNotNull(current.currentCommandAttempt)
        current = applied(MechanicalOperationStateMachine.markDispatched(current, attempt))
        current = applied(
            MechanicalOperationStateMachine.confirmResult(
                current,
                attempt,
                MechanicalPhysicalResult.SUCCEEDED
            )
        )
        current = applied(MechanicalOperationStateMachine.markEffectApplied(current, current.token()))
        current = applied(MechanicalOperationStateMachine.completeStep(current, current.token()))
        current = applied(MechanicalOperationStateMachine.startNextStep(current, current.token()))
        current = applied(MechanicalOperationStateMachine.prepareCommand(current, current.token(), "out5-attempt"))
        attempt = requireNotNull(current.currentCommandAttempt)
        return applied(MechanicalOperationStateMachine.markDispatched(current, attempt))
    }

    private fun applied(result: TransitionResult): MechanicalOperation =
        (result as TransitionResult.Applied).updated

    private fun dispatchAuthorization() = RecoveryAuthorization.DispatchIssued(
        authorizationId = "authorization-1",
        bootSessionId = BOOT,
        proofId = "proof-1",
        commandAttempt = CommandAttemptToken("running", 2, "out1", "attempt-1")
    )

    private fun openGate() = MechanicalAdmissionGate(BOOT, AdmissionGateState.OPEN)
    private fun recoveryGate() = MechanicalAdmissionGate(BOOT, AdmissionGateState.RECOVERY_REQUIRED)

    private companion object {
        const val BOOT = "boot-1"
    }
}
