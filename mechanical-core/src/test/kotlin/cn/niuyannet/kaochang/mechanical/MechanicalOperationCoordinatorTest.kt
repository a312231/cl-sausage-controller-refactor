package cn.niuyannet.kaochang.mechanical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MechanicalOperationCoordinatorTest {

    @Test
    fun `startup gate blocks work until absent ledger is proven safely idle`() {
        val ledger = FakeLedger(OperationLedgerRead.Absent)
        val coordinator = coordinator(ledger)
        assertTrue(coordinator.request(requestedOrder()) is CoordinatorResult.AdmissionBlocked)

        assertTrue(
            coordinator.recoverStartup(true, stableIdle(), null) is CoordinatorResult.StartupGateOpened
        )
        assertTrue(coordinator.request(requestedOrder()) is CoordinatorResult.Requested)
        assertEquals(BOOT, ledger.present().admissionGate.bootSessionId)
        assertEquals(AdmissionGateState.OPEN, ledger.present().admissionGate.state)
    }

    @Test
    fun `old boot and recovery gate block ordinary side effect methods before validation`() {
        val oldBootLedger = FakeLedger(present(admittedSupplement(), gate = openGate("old-boot")))
        val oldBootCoordinator = coordinator(oldBootLedger)
        val admitted = oldBootLedger.present().running!!
        assertTrue(
            oldBootCoordinator.prepareCommand(admitted.token(), "attempt")
                is CoordinatorResult.AdmissionBlocked
        )
        assertEquals(MechanicalOperationState.ADMITTED, oldBootLedger.present().running!!.state)

        val effect = FakeEffectPort()
        val confirmed = confirmedOrder()
        val recoveryLedger = FakeLedger(present(confirmed, gate = recoveryGate()))
        val recoveryCoordinator = coordinator(recoveryLedger, effect)
        assertTrue(
            recoveryCoordinator.applyEffect(confirmed.effectToken())
                is CoordinatorResult.AdmissionBlocked
        )
        assertEquals(0, effect.realApplyCount)
    }

    @Test
    fun `safe supplement handoff to paid order is one ledger CAS`() {
        val ledger = FakeLedger(present(admittedSupplement(), gate = openGate()))
        val coordinator = coordinator(ledger)
        val order = requestedOrder()
        assertTrue(coordinator.request(order) is CoordinatorResult.Requested)
        val before = ledger.present()

        ledger.failNextCompareAndSet = true
        assertTrue(coordinator.admitPending(order.operationId) is CoordinatorResult.LedgerConflict)
        assertEquals(before, ledger.present())

        assertTrue(coordinator.admitPending(order.operationId) is CoordinatorResult.HandoffAdmitted)
        assertEquals(order.operationId, ledger.present().running!!.operationId)
        assertEquals(MechanicalOperationState.PAUSED, ledger.present().paused!!.state)
        assertTrue(ledger.present().paused!!.heldResources.isEmpty())
        assertNull(ledger.present().pending)
    }

    @Test
    fun `dispatch permission exists only after command intent CAS succeeds`() {
        val ledger = FakeLedger(present(admittedOrder(), gate = openGate()))
        val coordinator = coordinator(ledger)
        val token = ledger.present().running!!.token()
        ledger.failNextCompareAndSet = true
        assertTrue(
            coordinator.prepareCommand(token, "attempt-1") is CoordinatorResult.LedgerConflict
        )
        assertEquals(MechanicalOperationState.ADMITTED, ledger.present().running!!.state)

        val allowed = coordinator.prepareCommand(token, "attempt-1") as CoordinatorResult.DispatchAllowed
        assertEquals("attempt-1", allowed.commandAttempt.attemptId)
        assertEquals(MechanicalOperationState.COMMAND_PREPARED, ledger.present().running!!.state)
    }

    @Test
    fun `normal operation lifecycle advances only through coordinator`() {
        val ledger = FakeLedger(present(admittedOrder(), gate = openGate()))
        val coordinator = coordinator(ledger)

        val prepared = coordinator.prepareCommand(
            ledger.present().running!!.token(),
            "attempt-normal"
        ) as CoordinatorResult.DispatchAllowed
        assertTrue(coordinator.markDispatched(prepared.commandAttempt) is CoordinatorResult.Applied)
        assertTrue(
            coordinator.confirmResult(
                prepared.commandAttempt,
                MechanicalPhysicalResult.SUCCEEDED
            ) is CoordinatorResult.Applied
        )
        val confirmed = requireNotNull(ledger.present().running)
        assertTrue(coordinator.applyEffect(confirmed.effectToken()) is CoordinatorResult.Applied)
        assertTrue(
            coordinator.completeStep(ledger.present().running!!.token())
                is CoordinatorResult.Applied
        )
        assertTrue(
            coordinator.completeOperation(ledger.present().running!!.token())
                is CoordinatorResult.Applied
        )

        assertNull(ledger.present().running)
        assertEquals(MechanicalOperationState.OPERATION_COMPLETED, ledger.present().terminal!!.state)
    }

    @Test
    fun `completed non-final step starts next step only through coordinator`() {
        var operation = requested(
            operationId = "order-two-step",
            kind = MechanicalOperationKind.ORDER_DELIVERY,
            admissionClass = MechanicalAdmissionClass.PAID_ORDER_DELIVERY,
            steps = listOf(step("out1"), step("out2"))
        )
        operation = admitted(operation)
        operation = applied(
            MechanicalOperationStateMachine.prepareCommand(operation, operation.token(), "attempt-out1")
        )
        val attempt = requireNotNull(operation.currentCommandAttempt)
        operation = applied(MechanicalOperationStateMachine.markDispatched(operation, attempt))
        operation = applied(
            MechanicalOperationStateMachine.confirmResult(
                operation,
                attempt,
                MechanicalPhysicalResult.SUCCEEDED
            )
        )
        operation = applied(MechanicalOperationStateMachine.markEffectApplied(operation, operation.token()))
        operation = applied(MechanicalOperationStateMachine.completeStep(operation, operation.token()))
        val ledger = FakeLedger(present(operation, gate = openGate()))
        val coordinator = coordinator(ledger)

        assertTrue(coordinator.startNextStep(operation.token()) is CoordinatorResult.Applied)
        assertEquals(MechanicalOperationState.ADMITTED, ledger.present().running!!.state)
        assertEquals(1, ledger.present().running!!.currentStepIndex)
        assertTrue("effect-out1" in ledger.present().running!!.appliedEffectKeys)
    }

    @Test
    fun `safe pause and resume move ownership atomically through coordinator`() {
        val running = admittedSupplement()
        val ledger = FakeLedger(present(running, gate = openGate()))
        val coordinator = coordinator(ledger)

        assertTrue(coordinator.pauseAtSafeCheckpoint(running.token()) is CoordinatorResult.Applied)
        assertNull(ledger.present().running)
        val paused = requireNotNull(ledger.present().paused)
        assertTrue(paused.heldResources.isEmpty())

        assertTrue(coordinator.resumePaused(paused.token()) is CoordinatorResult.Applied)
        assertNull(ledger.present().paused)
        assertEquals(MechanicalOperationState.ADMITTED, ledger.present().running!!.state)
        assertEquals(running.currentStep.resources, ledger.present().running!!.heldResources)
    }

    @Test
    fun `timeout never releases resources and closes admission gate`() {
        val dispatched = dispatchedOrder()
        val ledger = FakeLedger(present(dispatched, gate = openGate()))
        val coordinator = coordinator(ledger)

        assertTrue(
            coordinator.markTimedOut(dispatched.token(), "modbus timeout")
                is CoordinatorResult.RecoveryRequired
        )
        assertEquals(MechanicalOperationState.RECOVERY_REQUIRED, ledger.present().running!!.state)
        assertEquals(dispatched.heldResources, ledger.present().running!!.heldResources)
        assertEquals(AdmissionGateState.RECOVERY_REQUIRED, ledger.present().admissionGate.state)
    }

    @Test
    fun `normal lifecycle CAS failures preserve the complete ledger`() {
        fun assertRunningFailure(
            operation: MechanicalOperation,
            action: (MechanicalOperationCoordinator, MechanicalOperation) -> CoordinatorResult
        ) {
            val ledger = FakeLedger(present(operation, gate = openGate()))
            val before = ledger.present()
            ledger.failNextCompareAndSet = true
            assertTrue(action(coordinator(ledger), operation) is CoordinatorResult.LedgerConflict)
            assertEquals(before, ledger.present())
        }

        val prepared = preparedOrder()
        assertRunningFailure(prepared) { coordinator, operation ->
            coordinator.markDispatched(requireNotNull(operation.currentCommandAttempt))
        }
        val dispatched = dispatchedOrder()
        assertRunningFailure(dispatched) { coordinator, operation ->
            coordinator.confirmResult(
                requireNotNull(operation.currentCommandAttempt),
                MechanicalPhysicalResult.UNKNOWN
            )
        }
        val confirmed = confirmedOrder()
        val effectApplied = applied(
            MechanicalOperationStateMachine.markEffectApplied(confirmed, confirmed.token())
        )
        assertRunningFailure(effectApplied) { coordinator, operation ->
            coordinator.completeStep(operation.token())
        }
        val completedFirst = completedFirstStepOfTwo()
        assertRunningFailure(completedFirst) { coordinator, operation ->
            coordinator.startNextStep(operation.token())
        }
        assertRunningFailure(finalStepCompletedOrder()) { coordinator, operation ->
            coordinator.completeOperation(operation.token())
        }
        assertRunningFailure(admittedSupplement()) { coordinator, operation ->
            coordinator.pauseAtSafeCheckpoint(operation.token())
        }
        assertRunningFailure(dispatched) { coordinator, operation ->
            coordinator.markTimedOut(operation.token(), "timeout")
        }

        val paused = pausedSupplement()
        val resumeLedger = FakeLedger(
            OperationLedgerRead.Present(
                OperationLedgerSnapshot(
                    ledgerRevision = 1,
                    admissionGate = openGate(),
                    paused = paused
                )
            )
        )
        val beforeResume = resumeLedger.present()
        resumeLedger.failNextCompareAndSet = true
        assertTrue(
            coordinator(resumeLedger).resumePaused(paused.token())
                is CoordinatorResult.LedgerConflict
        )
        assertEquals(beforeResume, resumeLedger.present())
    }

    @Test
    fun `late callback cannot alter replacement command and duplicate is idempotent`() {
        val dispatched = dispatchedOrder()
        val ledger = FakeLedger(present(dispatched, gate = openGate()))
        val coordinator = coordinator(ledger)
        val currentAttempt = requireNotNull(dispatched.currentCommandAttempt)
        val before = ledger.present()
        assertTrue(
            coordinator.confirmResult(
                currentAttempt.copy(attemptId = "old"),
                MechanicalPhysicalResult.SUCCEEDED
            ) is CoordinatorResult.StaleCommandEvidence
        )
        assertEquals(before, ledger.present())

        assertTrue(
            coordinator.confirmResult(currentAttempt, MechanicalPhysicalResult.SUCCEEDED)
                is CoordinatorResult.Applied
        )
        val confirmed = ledger.present()
        assertTrue(
            coordinator.confirmResult(currentAttempt, MechanicalPhysicalResult.SUCCEEDED)
                is CoordinatorResult.DuplicateEvidence
        )
        assertEquals(confirmed, ledger.present())
    }

    @Test
    fun `stale operation revision is typed evidence and never writes ledger`() {
        val admitted = admittedOrder()
        val ledger = FakeLedger(present(admitted, gate = openGate()))
        val before = ledger.present()
        val callsBefore = ledger.compareAndSetCallCount
        val stale = admitted.token().copy(revision = admitted.revision - 1)

        val result = coordinator(ledger).prepareCommand(stale, "stale-attempt")
            as CoordinatorResult.StaleOperationEvidence
        assertEquals(stale, result.expected)
        assertEquals(admitted.token(), result.actual)
        assertEquals(callsBefore, ledger.compareAndSetCallCount)
        assertEquals(before, ledger.present())
    }

    @Test
    fun `failed or unknown result closes gate and retains resources`() {
        listOf(MechanicalPhysicalResult.FAILED, MechanicalPhysicalResult.UNKNOWN).forEach { result ->
            val dispatched = dispatchedOrder()
            val ledger = FakeLedger(present(dispatched, gate = openGate()))
            val coordinator = coordinator(ledger)

            assertTrue(
                coordinator.confirmResult(
                    requireNotNull(dispatched.currentCommandAttempt),
                    result
                ) is CoordinatorResult.RecoveryRequired
            )
            assertEquals(MechanicalOperationState.RECOVERY_REQUIRED, ledger.present().running!!.state)
            assertEquals(dispatched.heldResources, ledger.present().running!!.heldResources)
            assertEquals(AdmissionGateState.RECOVERY_REQUIRED, ledger.present().admissionGate.state)
            assertTrue(coordinator.request(requestedCleaning("blocked-$result")) is CoordinatorResult.AdmissionBlocked)
        }
    }

    @Test
    fun `effect is idempotent when effect succeeds before ledger CAS`() {
        val effect = FakeEffectPort()
        val confirmed = confirmedOrder()
        val ledger = FakeLedger(present(confirmed, gate = openGate()))
        val coordinator = coordinator(ledger, effect)
        ledger.failNextCompareAndSet = true
        assertTrue(coordinator.applyEffect(confirmed.effectToken()) is CoordinatorResult.LedgerConflict)
        assertEquals(1, effect.realApplyCount)
        assertEquals(MechanicalOperationState.RESULT_CONFIRMED, ledger.present().running!!.state)

        assertTrue(coordinator.applyEffect(confirmed.effectToken()) is CoordinatorResult.Applied)
        assertEquals(1, effect.realApplyCount)
        assertEquals(MechanicalOperationState.EFFECT_APPLIED, ledger.present().running!!.state)
    }

    @Test
    fun `effect failure never calls ledger CAS`() {
        val effect = FakeEffectPort().apply { failNext = true }
        val confirmed = confirmedOrder()
        val ledger = FakeLedger(present(confirmed, gate = openGate()))
        val coordinator = coordinator(ledger, effect)
        val before = ledger.present()
        val callsBefore = ledger.compareAndSetCallCount

        assertTrue(coordinator.applyEffect(confirmed.effectToken()) is CoordinatorResult.Rejected)
        assertEquals(before, ledger.present())
        assertEquals(callsBefore, ledger.compareAndSetCallCount)
    }

    @Test
    fun `premature normal and recovery effect never call effect port or ledger CAS`() {
        listOf(admittedOrder(), preparedOrder(), dispatchedOrder()).forEach { operation ->
            val effect = FakeEffectPort()
            val ledger = FakeLedger(present(operation, gate = openGate()))
            val callsBefore = ledger.compareAndSetCallCount

            assertTrue(
                coordinator(ledger, effect).applyEffect(operation.effectToken())
                    is CoordinatorResult.Rejected
            )
            assertEquals(0, effect.realApplyCount)
            assertEquals(callsBefore, ledger.compareAndSetCallCount)
        }

        val dispatched = dispatchedOrder()
        val authorization = RecoveryAuthorization.EffectIssued(
            authorizationId = "premature-effect",
            bootSessionId = BOOT,
            effectToken = dispatched.effectToken()
        )
        val recoveryLedger = FakeLedger(
            present(
                dispatched,
                gate = MechanicalAdmissionGate(
                    bootSessionId = BOOT,
                    state = AdmissionGateState.RECOVERY_REQUIRED,
                    recoveryAuthorization = authorization
                )
            )
        )
        val recoveryEffect = FakeEffectPort()
        val callsBefore = recoveryLedger.compareAndSetCallCount

        assertTrue(
            coordinator(recoveryLedger, recoveryEffect).applyRecoveredEffect(
                RecoveryAuthorizationToken(BOOT, authorization.authorizationId)
            ) is CoordinatorResult.Rejected
        )
        assertEquals(0, recoveryEffect.realApplyCount)
        assertEquals(callsBefore, recoveryLedger.compareAndSetCallCount)
    }

    @Test
    fun `cancellation after dispatch enters recovery and retains resources`() {
        val dispatched = dispatchedOrder()
        val ledger = FakeLedger(present(dispatched, gate = openGate()))
        val coordinator = coordinator(ledger)
        assertTrue(
            coordinator.markControlFlowCancelled(dispatched.token(), "job cancelled")
                is CoordinatorResult.RecoveryRequired
        )
        assertEquals(MechanicalOperationState.RECOVERY_REQUIRED, ledger.present().running!!.state)
        assertEquals(dispatched.heldResources, ledger.present().running!!.heldResources)
        assertEquals(AdmissionGateState.RECOVERY_REQUIRED, ledger.present().admissionGate.state)
    }

    @Test
    fun `completion frees running and preserves paused pending while replacing terminal`() {
        val running = finalStepCompletedOrder()
        val paused = pausedSupplement()
        val pending = requestedCleaning("pending-clean")
        val oldTerminal = terminalCleaning("old-terminal")
        val ledger = FakeLedger(
            OperationLedgerRead.Present(
                OperationLedgerSnapshot(
                    ledgerRevision = 1,
                    admissionGate = openGate(),
                    pending = pending,
                    running = running,
                    paused = paused,
                    terminal = oldTerminal
                )
            )
        )
        val coordinator = coordinator(ledger)

        assertTrue(coordinator.completeOperation(running.token()) is CoordinatorResult.Applied)
        assertNull(ledger.present().running)
        assertEquals(running.operationId, ledger.present().terminal!!.operationId)
        assertEquals(paused, ledger.present().paused)
        assertEquals(pending, ledger.present().pending)
    }

    @Test
    fun `recovery dispatch proof is consumed before one permit`() {
        val prepared = preparedOrder()
        val ledger = FakeLedger(present(prepared, gate = pendingGate()))
        val coordinator = coordinator(ledger)
        val proof = notDispatchedProof(requireNotNull(prepared.currentCommandAttempt))

        val allowed = coordinator.recoverStartup(true, stableIdle(), proof)
            as CoordinatorResult.RecoveryDispatchAllowed
        assertTrue(ledger.present().admissionGate.recoveryAuthorization is RecoveryAuthorization.DispatchIssued)
        assertTrue(
            coordinator.recoverStartup(true, stableIdle(), proof)
                is CoordinatorResult.StartupRecoveryBlocked
        )
        assertTrue(coordinator.markRecoveredDispatched(allowed.authorization) is CoordinatorResult.Applied)
        assertEquals(MechanicalOperationState.COMMAND_DISPATCHED, ledger.present().running!!.state)
        assertNull(ledger.present().admissionGate.recoveryAuthorization)
    }

    @Test
    fun `new boot may replace abandoned dispatch permit only with fresh not-dispatched proof`() {
        val prepared = preparedOrder()
        val attempt = requireNotNull(prepared.currentCommandAttempt)
        val abandoned = RecoveryAuthorization.DispatchIssued(
            authorizationId = "old-auth",
            bootSessionId = "old-boot",
            proofId = "proof-old",
            commandAttempt = attempt
        )
        val ledger = FakeLedger(
            present(
                prepared,
                gate = MechanicalAdmissionGate(
                    bootSessionId = "old-boot",
                    state = AdmissionGateState.RECOVERY_REQUIRED,
                    recoveryAuthorization = abandoned
                )
            )
        )
        val coordinator = coordinator(ledger)

        assertTrue(
            coordinator.recoverStartup(
                true,
                stableIdle(),
                DispatchRecoveryProof(
                    commandAttempt = attempt,
                    proofId = "proof-old",
                    outcome = DispatchProofOutcome.CONFIRMED_NOT_DISPATCHED
                )
            ) is CoordinatorResult.StartupRecoveryBlocked
        )
        val allowed = coordinator.recoverStartup(
            true,
            stableIdle(),
            DispatchRecoveryProof(
                commandAttempt = attempt,
                proofId = "proof-fresh",
                outcome = DispatchProofOutcome.CONFIRMED_NOT_DISPATCHED
            )
        ) as CoordinatorResult.RecoveryDispatchAllowed
        assertEquals(BOOT, allowed.authorization.bootSessionId)
        val issued = ledger.present().admissionGate.recoveryAuthorization
            as RecoveryAuthorization.DispatchIssued
        assertEquals("proof-fresh", issued.proofId)
    }

    @Test
    fun `recovery authorization cannot cross operation ownership`() {
        val preparedOne = preparedOrder("order-auth-1")
        val dispatchLedgerOne = FakeLedger(present(preparedOne, gate = pendingGate()))
        val oldDispatch = coordinator(dispatchLedgerOne).recoverStartup(
            true,
            stableIdle(),
            DispatchRecoveryProof(
                commandAttempt = requireNotNull(preparedOne.currentCommandAttempt),
                proofId = "same-proof",
                outcome = DispatchProofOutcome.CONFIRMED_NOT_DISPATCHED
            )
        ) as CoordinatorResult.RecoveryDispatchAllowed

        val preparedTwo = preparedOrder("order-auth-2")
        val dispatchLedgerTwo = FakeLedger(present(preparedTwo, gate = pendingGate()))
        coordinator(dispatchLedgerTwo).recoverStartup(
            true,
            stableIdle(),
            DispatchRecoveryProof(
                commandAttempt = requireNotNull(preparedTwo.currentCommandAttempt),
                proofId = "same-proof",
                outcome = DispatchProofOutcome.CONFIRMED_NOT_DISPATCHED
            )
        ) as CoordinatorResult.RecoveryDispatchAllowed
        val beforeDispatch = dispatchLedgerTwo.present()
        assertTrue(
            coordinator(dispatchLedgerTwo).markRecoveredDispatched(oldDispatch.authorization)
                is CoordinatorResult.AdmissionBlocked
        )
        assertEquals(beforeDispatch, dispatchLedgerTwo.present())

        val confirmedOne = confirmedOrder("order-effect-1")
        val effectLedgerOne = FakeLedger(present(confirmedOne, gate = pendingGate()))
        val oldEffect = coordinator(effectLedgerOne).recoverStartup(
            true,
            stable(
                requireNotNull(confirmedOne.lastConfirmedCommandAttempt),
                RegisterActivity.COMPLETED,
                2,
                sequenceStart = 1
            ),
            null
        ) as CoordinatorResult.RecoveryEffectReady

        val confirmedTwo = confirmedOrder("order-effect-2")
        val effectLedgerTwo = FakeLedger(present(confirmedTwo, gate = pendingGate()))
        coordinator(effectLedgerTwo).recoverStartup(
            true,
            stable(
                requireNotNull(confirmedTwo.lastConfirmedCommandAttempt),
                RegisterActivity.COMPLETED,
                2,
                sequenceStart = 1
            ),
            null
        ) as CoordinatorResult.RecoveryEffectReady
        val effectPort = FakeEffectPort()
        val beforeEffect = effectLedgerTwo.present()
        val casBefore = effectLedgerTwo.compareAndSetCallCount
        assertTrue(
            coordinator(effectLedgerTwo, effectPort).applyRecoveredEffect(oldEffect.authorization)
                is CoordinatorResult.AdmissionBlocked
        )
        assertEquals(0, effectPort.realApplyCount)
        assertEquals(casBefore, effectLedgerTwo.compareAndSetCallCount)
        assertEquals(beforeEffect, effectLedgerTwo.present())
    }

    @Test
    fun `colon bearing identities cannot collide recovery authorization`() {
        val dispatchOne = preparedCustom("x", "y:z")
        val dispatchLedgerOne = FakeLedger(present(dispatchOne, gate = pendingGate()))
        val oldDispatch = coordinator(dispatchLedgerOne).recoverStartup(
            true,
            stableIdle(),
            DispatchRecoveryProof(
                requireNotNull(dispatchOne.currentCommandAttempt),
                "same-proof",
                DispatchProofOutcome.CONFIRMED_NOT_DISPATCHED
            )
        ) as CoordinatorResult.RecoveryDispatchAllowed
        val dispatchTwo = preparedCustom("x:y", "z")
        val dispatchLedgerTwo = FakeLedger(present(dispatchTwo, gate = pendingGate()))
        coordinator(dispatchLedgerTwo).recoverStartup(
            true,
            stableIdle(),
            DispatchRecoveryProof(
                requireNotNull(dispatchTwo.currentCommandAttempt),
                "same-proof",
                DispatchProofOutcome.CONFIRMED_NOT_DISPATCHED
            )
        ) as CoordinatorResult.RecoveryDispatchAllowed
        val beforeDispatch = dispatchLedgerTwo.present()
        assertTrue(
            coordinator(dispatchLedgerTwo).markRecoveredDispatched(oldDispatch.authorization)
                is CoordinatorResult.AdmissionBlocked
        )
        assertEquals(beforeDispatch, dispatchLedgerTwo.present())

        val effectOne = confirmedCustom("x", "y:4:z")
        val effectLedgerOne = FakeLedger(present(effectOne, gate = pendingGate()))
        val oldEffect = coordinator(effectLedgerOne).recoverStartup(
            true,
            stable(
                requireNotNull(effectOne.lastConfirmedCommandAttempt),
                RegisterActivity.COMPLETED,
                2,
                1
            ),
            null
        ) as CoordinatorResult.RecoveryEffectReady
        val effectTwo = confirmedCustom("x:4:y", "z")
        val effectLedgerTwo = FakeLedger(present(effectTwo, gate = pendingGate()))
        coordinator(effectLedgerTwo).recoverStartup(
            true,
            stable(
                requireNotNull(effectTwo.lastConfirmedCommandAttempt),
                RegisterActivity.COMPLETED,
                2,
                1
            ),
            null
        ) as CoordinatorResult.RecoveryEffectReady
        val effectPort = FakeEffectPort()
        val beforeEffect = effectLedgerTwo.present()
        val casBefore = effectLedgerTwo.compareAndSetCallCount
        assertTrue(
            coordinator(effectLedgerTwo, effectPort).applyRecoveredEffect(oldEffect.authorization)
                is CoordinatorResult.AdmissionBlocked
        )
        assertEquals(0, effectPort.realApplyCount)
        assertEquals(casBefore, effectLedgerTwo.compareAndSetCallCount)
        assertEquals(beforeEffect, effectLedgerTwo.present())
    }

    @Test
    fun `running recovery observation advances cursor and rejects older completed evidence`() {
        val dispatched = dispatchedOrder()
        val attempt = requireNotNull(dispatched.currentCommandAttempt)
        val ledger = FakeLedger(present(dispatched, gate = pendingGate()))
        val coordinator = coordinator(ledger)
        val runningEvidence = stable(attempt, RegisterActivity.RUNNING, 0, sequenceStart = 100)

        val progressed = coordinator.recoverStartup(true, runningEvidence, null)
            as CoordinatorResult.RecoveryProgressed
        assertEquals(RecoveryDecision.WAIT_FOR_RUNNING, progressed.decision)
        assertEquals(101, ledger.present().recoveryObservationCursor!!.lastAcceptedSampleSequence)
        val afterRunning = ledger.present()
        assertTrue(
            coordinator.recoverStartup(
                true,
                stable(attempt, RegisterActivity.COMPLETED, 2, sequenceStart = 3),
                null
            ) is CoordinatorResult.StartupRecoveryBlocked
        )
        assertEquals(afterRunning, ledger.present())

        val conflictLedger = FakeLedger(present(dispatched, gate = pendingGate()))
        val beforeConflict = conflictLedger.present()
        conflictLedger.failNextCompareAndSet = true
        assertTrue(
            coordinator(conflictLedger).recoverStartup(true, runningEvidence, null)
                is CoordinatorResult.LedgerConflict
        )
        assertEquals(beforeConflict, conflictLedger.present())
    }

    @Test
    fun `emergency and unpreservable food safety return typed stop without ledger changes`() {
        val emergencyOrder = requestedOrder()
        val emergencyLedger = FakeLedger(
            OperationLedgerRead.Present(
                OperationLedgerSnapshot(
                    ledgerRevision = 1,
                    admissionGate = openGate(),
                    pending = emergencyOrder
                )
            )
        )
        val beforeEmergency = emergencyLedger.present()
        assertTrue(
            coordinator(emergencyLedger).admitPending(emergencyOrder.operationId, emergency = true)
                is CoordinatorResult.EmergencyStopRequired
        )
        assertEquals(beforeEmergency, emergencyLedger.present())

        val foodSafety = requestedFoodSafety("food-safety")
        val atomicLedger = FakeLedger(
            OperationLedgerRead.Present(
                OperationLedgerSnapshot(
                    ledgerRevision = 1,
                    admissionGate = openGate(),
                    pending = foodSafety,
                    running = dispatchedOrder()
                )
            )
        )
        val beforeAtomic = atomicLedger.present()
        assertTrue(
            coordinator(atomicLedger).admitPending(foodSafety.operationId)
                is CoordinatorResult.EmergencyStopRequired
        )
        assertEquals(beforeAtomic, atomicLedger.present())

        val safeRunning = admittedSupplement()
        val paused = pausedCleaning("paused-cleaning")
        val occupiedPausedLedger = FakeLedger(
            OperationLedgerRead.Present(
                OperationLedgerSnapshot(
                    ledgerRevision = 1,
                    admissionGate = openGate(),
                    pending = foodSafety,
                    running = safeRunning,
                    paused = paused
                )
            )
        )
        val beforeOccupied = occupiedPausedLedger.present()
        assertTrue(
            coordinator(occupiedPausedLedger).admitPending(foodSafety.operationId)
                is CoordinatorResult.EmergencyStopRequired
        )
        assertEquals(beforeOccupied, occupiedPausedLedger.present())
    }

    @Test
    fun `cross boot completed evidence advances through cursor without replay or redispatch`() {
        val prepared = preparedOrder()
        val attempt = requireNotNull(prepared.currentCommandAttempt)
        val oldAuthorization = RecoveryAuthorization.DispatchIssued(
            authorizationId = "old-auth",
            bootSessionId = "old-boot",
            proofId = "proof-old",
            commandAttempt = attempt
        )
        val ledger = FakeLedger(
            present(
                prepared,
                gate = MechanicalAdmissionGate(
                    "old-boot",
                    AdmissionGateState.RECOVERY_REQUIRED,
                    recoveryAuthorization = oldAuthorization
                )
            )
        )
        val effect = FakeEffectPort()
        val coordinator = coordinator(ledger, effect)
        val firstGroup = stable(attempt, RegisterActivity.COMPLETED, 2, sequenceStart = 1)

        val progressed = coordinator.recoverStartup(true, firstGroup, null)
            as CoordinatorResult.RecoveryProgressed
        assertEquals(RecoveryDecision.RECORD_OBSERVED_DISPATCHED, progressed.decision)
        assertEquals(MechanicalOperationState.COMMAND_DISPATCHED, ledger.present().running!!.state)
        assertEquals(2, ledger.present().recoveryObservationCursor!!.lastAcceptedSampleSequence)
        val afterFirst = ledger.present()
        assertTrue(
            coordinator.recoverStartup(true, firstGroup, null)
                is CoordinatorResult.StartupRecoveryBlocked
        )
        assertEquals(afterFirst, ledger.present())

        val effectReady = coordinator.recoverStartup(
            true,
            stable(attempt, RegisterActivity.COMPLETED, 2, sequenceStart = 3),
            null
        ) as CoordinatorResult.RecoveryEffectReady
        assertEquals(MechanicalOperationState.RESULT_CONFIRMED, ledger.present().running!!.state)
        assertEquals(4, ledger.present().recoveryObservationCursor!!.lastAcceptedSampleSequence)
        assertTrue(coordinator.applyRecoveredEffect(effectReady.authorization) is CoordinatorResult.Applied)
        assertEquals(MechanicalOperationState.EFFECT_APPLIED, ledger.present().running!!.state)
        assertNull(ledger.present().recoveryObservationCursor)

        val stepCompleted = coordinator.continueRecoveredOperation(ledger.present().running!!.token())
            as CoordinatorResult.RecoveryProgressed
        assertEquals(MechanicalOperationState.STEP_COMPLETED, ledger.present().running!!.state)
        assertTrue(coordinator.continueRecoveredOperation(stepCompleted.token) is CoordinatorResult.RecoveryProgressed)
        assertNull(ledger.present().running)
        assertEquals(MechanicalOperationState.OPERATION_COMPLETED, ledger.present().terminal!!.state)
        assertEquals(AdmissionGateState.OPEN, ledger.present().admissionGate.state)
    }

    private fun coordinator(
        ledger: FakeLedger,
        effect: FakeEffectPort = FakeEffectPort()
    ) = MechanicalOperationCoordinator(ledger, effect, BOOT)

    private fun present(
        running: MechanicalOperation,
        gate: MechanicalAdmissionGate,
        cursor: RecoveryObservationCursor? = null
    ) = OperationLedgerRead.Present(
        OperationLedgerSnapshot(
            ledgerRevision = 1,
            admissionGate = gate,
            running = running,
            recoveryObservationCursor = cursor
        )
    )

    private fun requestedOrder(operationId: String = "order-1") = requested(
        operationId = operationId,
        kind = MechanicalOperationKind.ORDER_DELIVERY,
        admissionClass = MechanicalAdmissionClass.PAID_ORDER_DELIVERY,
        steps = listOf(step("out1"))
    )

    private fun requestedCleaning(id: String) = requested(
        operationId = id,
        kind = MechanicalOperationKind.CLEANING,
        admissionClass = MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE,
        steps = listOf(step("clean"))
    )

    private fun requestedFoodSafety(id: String) = requested(
        operationId = id,
        kind = MechanicalOperationKind.DISCARD,
        admissionClass = MechanicalAdmissionClass.FOOD_SAFETY,
        steps = listOf(step("discard"))
    )

    private fun requested(
        operationId: String,
        kind: MechanicalOperationKind,
        admissionClass: MechanicalAdmissionClass,
        steps: List<MechanicalStep>
    ) = MechanicalOperation(
        operationId = operationId,
        revision = 1,
        kind = kind,
        admissionClass = admissionClass,
        state = MechanicalOperationState.REQUESTED,
        steps = steps,
        currentStepIndex = 0,
        heldResources = emptySet(),
        checkpoint = null,
        resumeCursor = null,
        currentCommandAttempt = null,
        lastConfirmedCommandAttempt = null,
        physicalResult = MechanicalPhysicalResult.NONE,
        effectState = MechanicalEffectState.NOT_APPLIED
    )

    private fun admittedOrder(operationId: String = "order-1"): MechanicalOperation =
        admitted(requestedOrder(operationId))

    private fun admittedSupplement(): MechanicalOperation = admitted(
        requested(
            operationId = "supplement-1",
            kind = MechanicalOperationKind.SUPPLEMENT,
            admissionClass = MechanicalAdmissionClass.SUPPLEMENT,
            steps = listOf(step("in1"))
        )
    )

    private fun admitted(requested: MechanicalOperation): MechanicalOperation = applied(
        MechanicalOperationStateMachine.admit(requested, requested.token())
    )

    private fun preparedOrder(operationId: String = "order-1"): MechanicalOperation {
        val admitted = admittedOrder(operationId)
        return applied(MechanicalOperationStateMachine.prepareCommand(admitted, admitted.token(), "attempt-out1"))
    }

    private fun dispatchedOrder(operationId: String = "order-1"): MechanicalOperation {
        val prepared = preparedOrder(operationId)
        return applied(
            MechanicalOperationStateMachine.markDispatched(
                prepared,
                requireNotNull(prepared.currentCommandAttempt)
            )
        )
    }

    private fun confirmedOrder(operationId: String = "order-1"): MechanicalOperation {
        val dispatched = dispatchedOrder(operationId)
        return applied(
            MechanicalOperationStateMachine.confirmResult(
                dispatched,
                requireNotNull(dispatched.currentCommandAttempt),
                MechanicalPhysicalResult.SUCCEEDED
            )
        )
    }

    private fun preparedCustom(operationId: String, stepId: String): MechanicalOperation {
        var operation = requested(
            operationId = operationId,
            kind = MechanicalOperationKind.ORDER_DELIVERY,
            admissionClass = MechanicalAdmissionClass.PAID_ORDER_DELIVERY,
            steps = listOf(identityStep(stepId))
        )
        operation = admitted(operation)
        return applied(
            MechanicalOperationStateMachine.prepareCommand(
                operation,
                operation.token(),
                "same-attempt"
            )
        )
    }

    private fun confirmedCustom(operationId: String, stepId: String): MechanicalOperation {
        var operation = preparedCustom(operationId, stepId)
        val attempt = requireNotNull(operation.currentCommandAttempt)
        operation = applied(MechanicalOperationStateMachine.markDispatched(operation, attempt))
        return applied(
            MechanicalOperationStateMachine.confirmResult(
                operation,
                attempt,
                MechanicalPhysicalResult.SUCCEEDED
            )
        )
    }

    private fun finalStepCompletedOrder(): MechanicalOperation {
        var current = confirmedOrder()
        current = applied(MechanicalOperationStateMachine.markEffectApplied(current, current.token()))
        return applied(MechanicalOperationStateMachine.completeStep(current, current.token()))
    }

    private fun completedFirstStepOfTwo(): MechanicalOperation {
        var current = requested(
            operationId = "order-two-step-cas",
            kind = MechanicalOperationKind.ORDER_DELIVERY,
            admissionClass = MechanicalAdmissionClass.PAID_ORDER_DELIVERY,
            steps = listOf(step("out1"), step("out2"))
        )
        current = admitted(current)
        current = applied(
            MechanicalOperationStateMachine.prepareCommand(current, current.token(), "attempt-out1")
        )
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

    private fun pausedSupplement(): MechanicalOperation {
        val admitted = admittedSupplement()
        return applied(MechanicalResourcePolicy.pauseAtCheckpoint(admitted, admitted.token()))
    }

    private fun pausedCleaning(id: String): MechanicalOperation {
        val admitted = admitted(requestedCleaning(id))
        return applied(MechanicalResourcePolicy.pauseAtCheckpoint(admitted, admitted.token()))
    }

    private fun terminalCleaning(id: String): MechanicalOperation {
        var current = requestedCleaning(id)
        current = admitted(current)
        current = applied(MechanicalOperationStateMachine.prepareCommand(current, current.token(), "attempt-clean"))
        val attempt = requireNotNull(current.currentCommandAttempt)
        current = applied(MechanicalOperationStateMachine.markDispatched(current, attempt))
        current = applied(
            MechanicalOperationStateMachine.confirmResult(current, attempt, MechanicalPhysicalResult.SUCCEEDED)
        )
        current = applied(MechanicalOperationStateMachine.markEffectApplied(current, current.token()))
        current = applied(MechanicalOperationStateMachine.completeStep(current, current.token()))
        return applied(MechanicalOperationStateMachine.completeOperation(current, current.token()))
    }

    private fun step(id: String) = MechanicalStep(
        stepId = id,
        commandIdempotencyKey = "command-$id",
        effectIdempotencyKey = "effect-$id",
        resources = setOf(MechanicalResource.LIFT)
    )

    private fun identityStep(id: String) = MechanicalStep(
        stepId = id,
        commandIdempotencyKey = "shared-command",
        effectIdempotencyKey = "shared-effect",
        resources = setOf(MechanicalResource.LIFT)
    )

    private fun openGate(boot: String = BOOT) = MechanicalAdmissionGate(boot, AdmissionGateState.OPEN)
    private fun pendingGate() = MechanicalAdmissionGate(BOOT, AdmissionGateState.STARTUP_RECOVERY_PENDING)
    private fun recoveryGate() = MechanicalAdmissionGate(BOOT, AdmissionGateState.RECOVERY_REQUIRED)

    private fun stableIdle() = listOf(
        MechanicalRegisterSnapshot(1, 10, null, SnapshotCommandCorrelation.UNAVAILABLE, RegisterActivity.IDLE, 0),
        MechanicalRegisterSnapshot(2, 20, null, SnapshotCommandCorrelation.UNAVAILABLE, RegisterActivity.IDLE, 0)
    )

    private fun stable(
        attempt: CommandAttemptToken,
        activity: RegisterActivity,
        resultCode: Int,
        sequenceStart: Long
    ) = listOf(
        MechanicalRegisterSnapshot(
            sequenceStart,
            sequenceStart * 10,
            attempt,
            SnapshotCommandCorrelation.MATCHED_CURRENT_ATTEMPT,
            activity,
            resultCode
        ),
        MechanicalRegisterSnapshot(
            sequenceStart + 1,
            (sequenceStart + 1) * 10,
            attempt,
            SnapshotCommandCorrelation.MATCHED_CURRENT_ATTEMPT,
            activity,
            resultCode
        )
    )

    private fun notDispatchedProof(attempt: CommandAttemptToken) = DispatchRecoveryProof(
        commandAttempt = attempt,
        proofId = "proof-not-dispatched",
        outcome = DispatchProofOutcome.CONFIRMED_NOT_DISPATCHED
    )

    private fun applied(result: TransitionResult): MechanicalOperation =
        (result as TransitionResult.Applied).updated

    private class FakeLedger(initial: OperationLedgerRead) : OperationLedgerPort {
        private var current: OperationLedgerRead = initial
        var failNextCompareAndSet: Boolean = false
        var compareAndSetCallCount: Int = 0
            private set

        override fun load(): OperationLedgerRead = current

        override fun compareAndSet(
            expectedLedgerRevision: Long?,
            next: OperationLedgerSnapshot
        ): Boolean {
            compareAndSetCallCount++
            if (failNextCompareAndSet) {
                failNextCompareAndSet = false
                return false
            }
            val matches = when (val before = current) {
                OperationLedgerRead.Absent -> expectedLedgerRevision == null
                is OperationLedgerRead.Present -> expectedLedgerRevision == before.snapshot.ledgerRevision
                is OperationLedgerRead.Corrupt -> false
            }
            if (!matches) return false
            current = OperationLedgerRead.Present(next)
            return true
        }

        fun present(): OperationLedgerSnapshot = (current as OperationLedgerRead.Present).snapshot
    }

    private class FakeEffectPort : MechanicalEffectPort {
        private val applied = mutableSetOf<EffectToken>()
        var failNext = false
        var realApplyCount = 0
            private set

        override fun applyOnce(token: EffectToken): EffectApplyResult {
            if (failNext) {
                failNext = false
                return EffectApplyResult.FAILED
            }
            return if (applied.add(token)) {
                realApplyCount++
                EffectApplyResult.APPLIED
            } else {
                EffectApplyResult.ALREADY_APPLIED
            }
        }
    }

    private companion object {
        const val BOOT = "boot-new"
    }
}
