package cn.niuyannet.kaochang.mechanical

sealed interface CoordinatorResult {
    data class Requested(val token: OperationToken) : CoordinatorResult
    data class HandoffAdmitted(
        val running: OperationToken,
        val paused: OperationToken
    ) : CoordinatorResult
    data class StartupGateOpened(val bootSessionId: String) : CoordinatorResult
    data class AdmissionBlocked(val reason: String) : CoordinatorResult
    data class StartupRecoveryBlocked(
        val decision: RecoveryDecision,
        val reason: String
    ) : CoordinatorResult
    data class RecoveryProgressed(
        val decision: RecoveryDecision,
        val token: OperationToken
    ) : CoordinatorResult
    data class RecoveryDispatchAllowed(
        val authorization: RecoveryAuthorizationToken,
        val commandAttempt: CommandAttemptToken
    ) : CoordinatorResult
    data class RecoveryEffectReady(
        val authorization: RecoveryAuthorizationToken,
        val effectToken: EffectToken
    ) : CoordinatorResult
    data class DispatchAllowed(val commandAttempt: CommandAttemptToken) : CoordinatorResult
    data class Applied(val token: OperationToken) : CoordinatorResult
    data class RecoveryRequired(
        val token: OperationToken,
        val reason: String
    ) : CoordinatorResult
    data class EmergencyStopRequired(val reason: String) : CoordinatorResult
    data class Rejected(val reason: String) : CoordinatorResult
    data class LedgerConflict(val reason: String) : CoordinatorResult
    data class StaleCommandEvidence(
        val expected: CommandAttemptToken?,
        val actual: CommandAttemptToken
    ) : CoordinatorResult
    data class StaleOperationEvidence(
        val expected: OperationToken,
        val actual: OperationToken
    ) : CoordinatorResult
    data object DuplicateEvidence : CoordinatorResult
}

class MechanicalOperationCoordinator(
    private val ledger: OperationLedgerPort,
    private val effectPort: MechanicalEffectPort,
    private val bootSessionId: String,
    private val transitionLock: Any = Any()
) {
    init {
        require(bootSessionId.isNotBlank()) { "bootSessionId must not be blank" }
    }

    fun recoverStartup(
        modbusConnected: Boolean,
        snapshots: List<MechanicalRegisterSnapshot>,
        dispatchProof: DispatchRecoveryProof?
    ): CoordinatorResult = synchronized(transitionLock) {
        val read = ledger.load()
        val decision = MechanicalRecoveryPolicy.decide(
            ledger = read,
            modbusConnected = modbusConnected,
            snapshots = snapshots,
            dispatchProof = dispatchProof
        )
        if (read is OperationLedgerRead.Absent) {
            if (decision != RecoveryDecision.SAFE_IDLE) {
                return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                    decision,
                    "startup evidence is not safe idle"
                )
            }
            val initialized = OperationLedgerSnapshot(
                ledgerRevision = 1L,
                admissionGate = openGate()
            )
            return@synchronized if (ledger.compareAndSet(null, initialized)) {
                CoordinatorResult.StartupGateOpened(bootSessionId)
            } else {
                CoordinatorResult.LedgerConflict("failed to initialize operation ledger")
            }
        }
        val before = (read as? OperationLedgerRead.Present)?.snapshot
            ?: return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                RecoveryDecision.RECOVERY_REQUIRED,
                "operation ledger is corrupt"
            )
        when (decision) {
            RecoveryDecision.SAFE_IDLE,
            RecoveryDecision.CLOSE_SAFE_TERMINAL -> {
                val next = before.copy(
                    admissionGate = openGate(),
                    recoveryObservationCursor = null
                )
                if (!persist(before, next)) {
                    CoordinatorResult.LedgerConflict("failed to open startup gate")
                } else {
                    CoordinatorResult.StartupGateOpened(bootSessionId)
                }
            }

            RecoveryDecision.DISPATCH_CONFIRMED_NOT_SENT -> {
                val operation = before.running
                    ?: return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                        decision,
                        "prepared operation is missing"
                    )
                val attempt = operation.currentCommandAttempt
                    ?: return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                        decision,
                        "prepared command attempt is missing"
                    )
                val proofId = dispatchProof?.proofId
                    ?: return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                        decision,
                        "not-dispatched proof is missing"
                    )
                val existing = before.admissionGate.recoveryAuthorization
                if (existing != null) {
                    val replaceableAbandonedPermit = existing is RecoveryAuthorization.DispatchIssued &&
                        existing.bootSessionId != bootSessionId &&
                        existing.commandAttempt == attempt &&
                        existing.proofId != proofId
                    if (!replaceableAbandonedPermit) {
                        return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                            decision,
                            "recovery dispatch authorization already issued"
                        )
                    }
                }
                val authorization = RecoveryAuthorization.DispatchIssued(
                    authorizationId = dispatchAuthorizationId(attempt, proofId),
                    bootSessionId = bootSessionId,
                    proofId = proofId,
                    commandAttempt = attempt
                )
                val next = before.copy(admissionGate = recoveryGate(authorization))
                if (!persist(before, next)) {
                    CoordinatorResult.LedgerConflict("failed to persist recovery dispatch authorization")
                } else {
                    CoordinatorResult.RecoveryDispatchAllowed(authorization.token(), attempt)
                }
            }

            RecoveryDecision.RECORD_OBSERVED_DISPATCHED -> {
                val operation = before.running
                    ?: return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                        decision,
                        "prepared operation is missing"
                    )
                val attempt = operation.currentCommandAttempt
                    ?: return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                        decision,
                        "prepared command attempt is missing"
                    )
                val transitioned = appliedOperation(
                    MechanicalOperationStateMachine.markDispatched(operation, attempt)
                ) ?: return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                    decision,
                    "prepared operation cannot record observed dispatch"
                )
                val next = before.copy(
                    running = transitioned,
                    admissionGate = recoveryGate(),
                    recoveryObservationCursor = cursorFrom(snapshots, attempt)
                )
                if (!persist(before, next)) {
                    CoordinatorResult.LedgerConflict("failed to persist observed dispatch")
                } else {
                    CoordinatorResult.RecoveryProgressed(decision, transitioned.token())
                }
            }

            RecoveryDecision.CONFIRM_RESULT_AND_APPLY_EFFECT -> {
                val operation = before.running
                    ?: return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                        decision,
                        "dispatched operation is missing"
                    )
                val attempt = operation.currentCommandAttempt
                    ?: return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                        decision,
                        "dispatched command attempt is missing"
                    )
                val confirmed = appliedOperation(
                    MechanicalOperationStateMachine.confirmResult(
                        operation,
                        attempt,
                        MechanicalPhysicalResult.SUCCEEDED
                    )
                ) ?: return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                    decision,
                    "completed evidence cannot confirm result"
                )
                val effectToken = confirmed.effectToken()
                val authorization = RecoveryAuthorization.EffectIssued(
                    authorizationId = effectAuthorizationId(confirmed, effectToken),
                    bootSessionId = bootSessionId,
                    effectToken = effectToken
                )
                val next = before.copy(
                    running = confirmed,
                    admissionGate = recoveryGate(authorization),
                    recoveryObservationCursor = cursorFrom(snapshots, attempt)
                )
                if (!persist(before, next)) {
                    CoordinatorResult.LedgerConflict("failed to persist confirmed recovery result")
                } else {
                    CoordinatorResult.RecoveryEffectReady(authorization.token(), effectToken)
                }
            }

            RecoveryDecision.APPLY_EFFECT_ONLY -> {
                val operation = before.running
                    ?: return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                        decision,
                        "confirmed operation is missing"
                    )
                val effectToken = operation.effectToken()
                val authorization = RecoveryAuthorization.EffectIssued(
                    authorizationId = effectAuthorizationId(operation, effectToken),
                    bootSessionId = bootSessionId,
                    effectToken = effectToken
                )
                val next = before.copy(admissionGate = recoveryGate(authorization))
                if (!persist(before, next)) {
                    CoordinatorResult.LedgerConflict("failed to persist recovery effect authorization")
                } else {
                    CoordinatorResult.RecoveryEffectReady(authorization.token(), effectToken)
                }
            }

            RecoveryDecision.CONTINUE_OR_COMPLETE -> {
                val operation = before.running
                    ?: return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                        decision,
                        "operation is missing"
                    )
                if (operation.state == MechanicalOperationState.ADMITTED) {
                    val next = before.copy(
                        admissionGate = openGate(),
                        recoveryObservationCursor = null
                    )
                    if (!persist(before, next)) {
                        CoordinatorResult.LedgerConflict("failed to reopen admitted operation")
                    } else {
                        CoordinatorResult.StartupGateOpened(bootSessionId)
                    }
                } else {
                    val next = before.copy(admissionGate = recoveryGate())
                    if (!persist(before, next)) {
                        CoordinatorResult.LedgerConflict("failed to persist recovery closeout gate")
                    } else {
                        CoordinatorResult.RecoveryProgressed(decision, operation.token())
                    }
                }
            }

            RecoveryDecision.WAIT_FOR_RUNNING -> {
                val operation = before.running
                    ?: return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                        decision,
                        "running operation is missing"
                    )
                val attempt = operation.currentCommandAttempt
                    ?: return@synchronized CoordinatorResult.StartupRecoveryBlocked(
                        decision,
                        "running command attempt is missing"
                    )
                val next = before.copy(
                    admissionGate = recoveryGate(reason = "command is still running"),
                    recoveryObservationCursor = cursorFrom(snapshots, attempt)
                )
                if (!persist(before, next)) {
                    CoordinatorResult.LedgerConflict("failed to persist running recovery evidence")
                } else {
                    CoordinatorResult.RecoveryProgressed(decision, operation.token())
                }
            }

            RecoveryDecision.BLOCK_NEW_OPERATIONS,
            RecoveryDecision.RECOVERY_REQUIRED -> CoordinatorResult.StartupRecoveryBlocked(
                decision,
                "startup recovery is not at a safe terminal"
            )
        }
    }

    fun request(operation: MechanicalOperation): CoordinatorResult = synchronized(transitionLock) {
        val before = normalSnapshot() ?: return@synchronized blocked()
        if (operation.state != MechanicalOperationState.REQUESTED) {
            return@synchronized CoordinatorResult.Rejected("only REQUESTED operation can enter pending")
        }
        if (before.pending != null) {
            return@synchronized CoordinatorResult.Rejected("pending slot is occupied")
        }
        val next = runCatching { before.copy(pending = operation) }
            .getOrElse { return@synchronized CoordinatorResult.Rejected(it.message ?: "invalid operation") }
        if (!persist(before, next)) {
            CoordinatorResult.LedgerConflict("failed to persist pending operation")
        } else {
            CoordinatorResult.Requested(operation.token())
        }
    }

    fun admitPending(operationId: String, emergency: Boolean = false): CoordinatorResult =
        synchronized(transitionLock) {
            val before = normalSnapshot() ?: return@synchronized blocked()
            val pending = before.pending
                ?.takeIf { it.operationId == operationId }
                ?: return@synchronized CoordinatorResult.Rejected("pending operation does not match")
            if (emergency) {
                return@synchronized CoordinatorResult.EmergencyStopRequired(
                    "emergency stop is required"
                )
            }
            val running = before.running
            if (running == null) {
                return@synchronized when (
                    MechanicalOperationArbiter.decideWhenNoRunning(before.paused, pending)
                ) {
                    ArbitrationDecision.ADMIT_CANDIDATE -> {
                        val admitted = appliedOperation(
                            MechanicalResourcePolicy.claimForAdmission(pending, pending.token(), emptySet())
                        ) ?: return@synchronized CoordinatorResult.Rejected("pending operation cannot be admitted")
                        val next = before.copy(pending = null, running = admitted)
                        if (!persist(before, next)) {
                            CoordinatorResult.LedgerConflict("failed to admit pending operation")
                        } else {
                            CoordinatorResult.Applied(admitted.token())
                        }
                    }
                    ArbitrationDecision.RESUME_PAUSED_BEFORE_CANDIDATE ->
                        CoordinatorResult.Rejected("paused operation must resume first")
                    else -> CoordinatorResult.Rejected("pending operation is not admissible")
                }
            }
            return@synchronized when (
                MechanicalOperationArbiter.decide(running, pending, emergency)
            ) {
                ArbitrationDecision.PAUSE_RUNNING_THEN_ADMIT -> {
                    if (before.paused != null) {
                        return@synchronized if (
                            pending.admissionClass == MechanicalAdmissionClass.FOOD_SAFETY
                        ) {
                            CoordinatorResult.EmergencyStopRequired(
                                "food safety cannot preserve a second paused owner"
                            )
                        } else {
                            CoordinatorResult.Rejected("paused slot is occupied")
                        }
                    }
                    val paused = appliedOperation(
                        MechanicalResourcePolicy.pauseAtCheckpoint(running, running.token())
                    ) ?: return@synchronized CoordinatorResult.Rejected("running operation cannot pause")
                    val admitted = appliedOperation(
                        MechanicalResourcePolicy.claimForAdmission(pending, pending.token(), emptySet())
                    ) ?: return@synchronized CoordinatorResult.Rejected("pending operation cannot be admitted")
                    val next = before.copy(pending = null, running = admitted, paused = paused)
                    if (!persist(before, next)) {
                        CoordinatorResult.LedgerConflict("failed to persist atomic handoff")
                    } else {
                        CoordinatorResult.HandoffAdmitted(admitted.token(), paused.token())
                    }
                }
                ArbitrationDecision.EMERGENCY_STOP_REQUIRED ->
                    CoordinatorResult.EmergencyStopRequired("emergency stop is required")
                ArbitrationDecision.KEEP_RUNNING,
                ArbitrationDecision.QUEUE_CANDIDATE,
                ArbitrationDecision.RESUME_PAUSED_BEFORE_CANDIDATE,
                ArbitrationDecision.ADMIT_CANDIDATE ->
                    CoordinatorResult.Rejected("running operation keeps ownership")
            }
        }

    fun prepareCommand(token: OperationToken, attemptId: String): CoordinatorResult =
        synchronized(transitionLock) {
            val before = normalSnapshot() ?: return@synchronized blocked()
            val operation = before.running
                ?: return@synchronized CoordinatorResult.Rejected("running operation is missing")
            val prepared = appliedOperation(
                MechanicalOperationStateMachine.prepareCommand(operation, token, attemptId)
            ) ?: return@synchronized staleOrRejectedOperation(operation, token)
            val next = before.copy(running = prepared)
            if (!persist(before, next)) {
                CoordinatorResult.LedgerConflict("failed to persist command intent")
            } else {
                CoordinatorResult.DispatchAllowed(requireNotNull(prepared.currentCommandAttempt))
            }
        }

    fun markDispatched(commandAttempt: CommandAttemptToken): CoordinatorResult =
        synchronized(transitionLock) {
            val before = normalSnapshot() ?: return@synchronized blocked()
            val operation = before.running
                ?: return@synchronized CoordinatorResult.Rejected("running operation is missing")
            when (val transition = MechanicalOperationStateMachine.markDispatched(
                operation,
                commandAttempt
            )) {
                is TransitionResult.Applied -> persistRunning(
                    before,
                    transition.updated,
                    "failed to persist command dispatch"
                )
                is TransitionResult.StaleCommandEvidence -> CoordinatorResult.StaleCommandEvidence(
                    transition.expected,
                    transition.actual
                )
                TransitionResult.DuplicateEvidence -> CoordinatorResult.DuplicateEvidence
                is TransitionResult.Rejected -> CoordinatorResult.Rejected(transition.reason)
                is TransitionResult.StaleOperationEvidence ->
                    CoordinatorResult.Rejected("stale operation evidence")
            }
        }

    fun confirmResult(
        commandAttempt: CommandAttemptToken,
        result: MechanicalPhysicalResult
    ): CoordinatorResult = synchronized(transitionLock) {
        val before = normalSnapshot() ?: return@synchronized blocked()
        val operation = before.running
            ?: return@synchronized CoordinatorResult.Rejected("running operation is missing")
        when (val transition = MechanicalOperationStateMachine.confirmResult(
            operation,
            commandAttempt,
            result
        )) {
            is TransitionResult.Applied -> {
                val requiresRecovery = transition.updated.state == MechanicalOperationState.RECOVERY_REQUIRED
                val reason = transition.updated.recoveryReason ?: "physical result requires recovery"
                val next = before.copy(
                    running = transition.updated,
                    admissionGate = if (requiresRecovery) recoveryGate(reason = reason) else before.admissionGate
                )
                if (!persist(before, next)) {
                    CoordinatorResult.LedgerConflict("failed to persist command result")
                } else if (requiresRecovery) {
                    CoordinatorResult.RecoveryRequired(transition.updated.token(), reason)
                } else {
                    CoordinatorResult.Applied(transition.updated.token())
                }
            }
            is TransitionResult.StaleCommandEvidence -> CoordinatorResult.StaleCommandEvidence(
                transition.expected,
                transition.actual
            )
            TransitionResult.DuplicateEvidence -> CoordinatorResult.DuplicateEvidence
            is TransitionResult.Rejected -> CoordinatorResult.Rejected(transition.reason)
            is TransitionResult.StaleOperationEvidence -> CoordinatorResult.Rejected("stale operation evidence")
        }
    }

    fun applyEffect(effectToken: EffectToken): CoordinatorResult = synchronized(transitionLock) {
        val before = normalSnapshot() ?: return@synchronized blocked()
        applyEffectLocked(before, effectToken, recoveryAuthorization = null)
    }

    fun completeStep(token: OperationToken): CoordinatorResult = synchronized(transitionLock) {
        applyRunningTransition(
            token = token,
            conflictReason = "failed to persist step completion",
            transition = MechanicalOperationStateMachine::completeStep
        )
    }

    fun startNextStep(token: OperationToken): CoordinatorResult = synchronized(transitionLock) {
        applyRunningTransition(
            token = token,
            conflictReason = "failed to persist next step",
            transition = MechanicalOperationStateMachine::startNextStep
        )
    }

    fun pauseAtSafeCheckpoint(token: OperationToken): CoordinatorResult = synchronized(transitionLock) {
        val before = normalSnapshot() ?: return@synchronized blocked()
        if (before.paused != null) {
            return@synchronized CoordinatorResult.Rejected("paused slot is occupied")
        }
        val operation = before.running
            ?: return@synchronized CoordinatorResult.Rejected("running operation is missing")
        val paused = appliedOperation(
            MechanicalResourcePolicy.pauseAtCheckpoint(operation, token)
        ) ?: return@synchronized staleOrRejectedOperation(operation, token)
        val next = before.copy(running = null, paused = paused)
        if (!persist(before, next)) {
            CoordinatorResult.LedgerConflict("failed to persist safe pause")
        } else {
            CoordinatorResult.Applied(paused.token())
        }
    }

    fun resumePaused(token: OperationToken): CoordinatorResult = synchronized(transitionLock) {
        val before = normalSnapshot() ?: return@synchronized blocked()
        if (before.running != null) {
            return@synchronized CoordinatorResult.Rejected("running slot is occupied")
        }
        if (before.pending != null) {
            return@synchronized CoordinatorResult.Rejected("pending operation must be arbitrated first")
        }
        val paused = before.paused
            ?: return@synchronized CoordinatorResult.Rejected("paused operation is missing")
        val resumed = appliedOperation(
            MechanicalResourcePolicy.resumeAfterRevalidation(paused, token, emptySet())
        ) ?: return@synchronized staleOrRejectedOperation(paused, token)
        val next = before.copy(running = resumed, paused = null)
        if (!persist(before, next)) {
            CoordinatorResult.LedgerConflict("failed to persist paused operation resume")
        } else {
            CoordinatorResult.Applied(resumed.token())
        }
    }

    fun completeOperation(token: OperationToken): CoordinatorResult = synchronized(transitionLock) {
        val before = normalSnapshot() ?: return@synchronized blocked()
        val operation = before.running
            ?: return@synchronized CoordinatorResult.Rejected("running operation is missing")
        val completed = appliedOperation(
            MechanicalResourcePolicy.releaseForCompletion(operation, token)
        ) ?: return@synchronized staleOrRejectedOperation(operation, token)
        val next = before.copy(
            running = null,
            terminal = completed,
            recoveryObservationCursor = null
        )
        if (!persist(before, next)) {
            CoordinatorResult.LedgerConflict("failed to complete operation")
        } else {
            CoordinatorResult.Applied(completed.token())
        }
    }

    fun markControlFlowCancelled(token: OperationToken, reason: String): CoordinatorResult =
        synchronized(transitionLock) {
            val before = normalSnapshot() ?: return@synchronized blocked()
            val operation = before.running
                ?: return@synchronized CoordinatorResult.Rejected("running operation is missing")
            val recovered = appliedOperation(
                MechanicalResourcePolicy.requireRecovery(operation, token, reason)
            ) ?: return@synchronized staleOrRejectedOperation(operation, token)
            val next = before.copy(
                running = recovered,
                admissionGate = recoveryGate(reason = reason)
            )
            if (!persist(before, next)) {
                CoordinatorResult.LedgerConflict("failed to persist cancellation recovery")
            } else {
                CoordinatorResult.RecoveryRequired(recovered.token(), reason)
            }
        }

    fun markTimedOut(token: OperationToken, reason: String): CoordinatorResult =
        markControlFlowCancelled(token, reason)

    fun markRecoveredDispatched(
        authorization: RecoveryAuthorizationToken
    ): CoordinatorResult = synchronized(transitionLock) {
        val before = recoverySnapshot(authorization) ?: return@synchronized blocked()
        val issued = before.admissionGate.recoveryAuthorization as? RecoveryAuthorization.DispatchIssued
            ?: return@synchronized blocked()
        val operation = before.running ?: return@synchronized blocked()
        val dispatched = appliedOperation(
            MechanicalOperationStateMachine.markDispatched(operation, issued.commandAttempt)
        ) ?: return@synchronized CoordinatorResult.Rejected("recovered dispatch cannot be recorded")
        val next = before.copy(
            running = dispatched,
            admissionGate = recoveryGate()
        )
        if (!persist(before, next)) {
            CoordinatorResult.LedgerConflict("failed to record recovered dispatch")
        } else {
            CoordinatorResult.Applied(dispatched.token())
        }
    }

    fun applyRecoveredEffect(
        authorization: RecoveryAuthorizationToken
    ): CoordinatorResult = synchronized(transitionLock) {
        val before = recoverySnapshot(authorization) ?: return@synchronized blocked()
        val issued = before.admissionGate.recoveryAuthorization as? RecoveryAuthorization.EffectIssued
            ?: return@synchronized blocked()
        applyEffectLocked(before, issued.effectToken, authorization)
    }

    fun continueRecoveredOperation(token: OperationToken): CoordinatorResult =
        synchronized(transitionLock) {
            val before = ledger.load().let { (it as? OperationLedgerRead.Present)?.snapshot }
                ?: return@synchronized blocked()
            if (before.admissionGate.bootSessionId != bootSessionId ||
                before.admissionGate.state != AdmissionGateState.RECOVERY_REQUIRED ||
                before.admissionGate.recoveryAuthorization != null ||
                before.recoveryObservationCursor != null
            ) {
                return@synchronized blocked()
            }
            val operation = before.running
                ?: return@synchronized CoordinatorResult.Rejected("recovery operation is missing")
            if (operation.token() != token) {
                return@synchronized CoordinatorResult.Rejected("operation token is stale")
            }
            when (operation.state) {
                MechanicalOperationState.EFFECT_APPLIED -> {
                    val completedStep = appliedOperation(
                        MechanicalOperationStateMachine.completeStep(operation, token)
                    ) ?: return@synchronized CoordinatorResult.Rejected("effect cannot complete step")
                    val next = before.copy(running = completedStep)
                    if (!persist(before, next)) {
                        CoordinatorResult.LedgerConflict("failed to persist recovered step completion")
                    } else {
                        CoordinatorResult.RecoveryProgressed(
                            RecoveryDecision.CONTINUE_OR_COMPLETE,
                            completedStep.token()
                        )
                    }
                }
                MechanicalOperationState.STEP_COMPLETED -> {
                    val finalStep = operation.currentStepIndex == operation.steps.lastIndex
                    val transitioned = if (finalStep) {
                        appliedOperation(
                            MechanicalResourcePolicy.releaseForCompletion(operation, token)
                        )
                    } else {
                        appliedOperation(
                            MechanicalOperationStateMachine.startNextStep(operation, token)
                        )
                    } ?: return@synchronized CoordinatorResult.Rejected("recovered operation cannot continue")
                    val next = if (finalStep) {
                        before.copy(
                            running = null,
                            terminal = transitioned,
                            admissionGate = openGate()
                        )
                    } else {
                        before.copy(running = transitioned, admissionGate = openGate())
                    }
                    if (!persist(before, next)) {
                        CoordinatorResult.LedgerConflict("failed to persist recovered closeout")
                    } else {
                        CoordinatorResult.RecoveryProgressed(
                            RecoveryDecision.CONTINUE_OR_COMPLETE,
                            transitioned.token()
                        )
                    }
                }
                else -> CoordinatorResult.Rejected("recovery closeout state is not eligible")
            }
        }

    private fun normalSnapshot(): OperationLedgerSnapshot? {
        val snapshot = (ledger.load() as? OperationLedgerRead.Present)?.snapshot ?: return null
        return snapshot.takeIf {
            it.admissionGate.bootSessionId == bootSessionId &&
                it.admissionGate.state == AdmissionGateState.OPEN &&
                it.admissionGate.recoveryAuthorization == null
        }
    }

    private fun recoverySnapshot(
        authorization: RecoveryAuthorizationToken
    ): OperationLedgerSnapshot? {
        val snapshot = (ledger.load() as? OperationLedgerRead.Present)?.snapshot ?: return null
        val current = snapshot.admissionGate.recoveryAuthorization ?: return null
        return snapshot.takeIf {
            it.admissionGate.bootSessionId == bootSessionId &&
                it.admissionGate.state == AdmissionGateState.RECOVERY_REQUIRED &&
                authorization.bootSessionId == bootSessionId &&
                authorization.authorizationId == current.authorizationId
        }
    }

    private fun applyEffectLocked(
        before: OperationLedgerSnapshot,
        effectToken: EffectToken,
        recoveryAuthorization: RecoveryAuthorizationToken?
    ): CoordinatorResult {
        val operation = before.running
            ?: return CoordinatorResult.Rejected("running operation is missing")
        if (operation.effectToken() != effectToken) {
            return CoordinatorResult.Rejected("effect token does not match current step")
        }
        if (operation.state != MechanicalOperationState.RESULT_CONFIRMED ||
            operation.physicalResult != MechanicalPhysicalResult.SUCCEEDED ||
            operation.effectState != MechanicalEffectState.NOT_APPLIED ||
            effectToken.effectIdempotencyKey in operation.appliedEffectKeys
        ) {
            return CoordinatorResult.Rejected("effect is not ready to apply")
        }
        return when (effectPort.applyOnce(effectToken)) {
            EffectApplyResult.FAILED -> CoordinatorResult.Rejected("effect application failed")
            EffectApplyResult.APPLIED,
            EffectApplyResult.ALREADY_APPLIED -> {
                val applied = appliedOperation(
                    MechanicalOperationStateMachine.markEffectApplied(operation, operation.token())
                ) ?: return CoordinatorResult.Rejected("effect state cannot advance")
                val next = before.copy(
                    running = applied,
                    admissionGate = if (recoveryAuthorization == null) before.admissionGate else recoveryGate(),
                    recoveryObservationCursor = if (recoveryAuthorization == null) {
                        before.recoveryObservationCursor
                    } else {
                        null
                    }
                )
                if (!persist(before, next)) {
                    CoordinatorResult.LedgerConflict("effect applied but ledger CAS failed")
                } else {
                    CoordinatorResult.Applied(applied.token())
                }
            }
        }
    }

    private fun applyRunningTransition(
        token: OperationToken,
        conflictReason: String,
        transition: (MechanicalOperation, OperationToken) -> TransitionResult
    ): CoordinatorResult {
        val before = normalSnapshot() ?: return blocked()
        val operation = before.running
            ?: return CoordinatorResult.Rejected("running operation is missing")
        val updated = appliedOperation(transition(operation, token))
            ?: return staleOrRejectedOperation(operation, token)
        return persistRunning(before, updated, conflictReason)
    }

    private fun persistRunning(
        before: OperationLedgerSnapshot,
        operation: MechanicalOperation,
        conflictReason: String
    ): CoordinatorResult {
        val next = before.copy(running = operation)
        return if (!persist(before, next)) {
            CoordinatorResult.LedgerConflict(conflictReason)
        } else {
            CoordinatorResult.Applied(operation.token())
        }
    }

    private fun persist(
        before: OperationLedgerSnapshot,
        proposed: OperationLedgerSnapshot
    ): Boolean = runCatching {
        ledger.compareAndSet(
            before.ledgerRevision,
            proposed.copy(ledgerRevision = before.ledgerRevision + 1L)
        )
    }.getOrDefault(false)

    private fun openGate(): MechanicalAdmissionGate =
        MechanicalAdmissionGate(bootSessionId, AdmissionGateState.OPEN)

    private fun recoveryGate(
        authorization: RecoveryAuthorization? = null,
        reason: String? = null
    ): MechanicalAdmissionGate = MechanicalAdmissionGate(
        bootSessionId = bootSessionId,
        state = AdmissionGateState.RECOVERY_REQUIRED,
        reason = reason,
        recoveryAuthorization = authorization
    )

    private fun RecoveryAuthorization.token(): RecoveryAuthorizationToken =
        RecoveryAuthorizationToken(bootSessionId, authorizationId)

    private fun dispatchAuthorizationId(
        attempt: CommandAttemptToken,
        proofId: String
    ): String = authorizationId(
        bootSessionId,
        "dispatch",
        attempt.operationId,
        attempt.stepId,
        attempt.dispatchRevision,
        attempt.attemptId,
        proofId
    )

    private fun effectAuthorizationId(
        operation: MechanicalOperation,
        effectToken: EffectToken
    ): String = authorizationId(
        bootSessionId,
        "effect",
        operation.operationId,
        operation.revision,
        effectToken.stepId,
        effectToken.effectIdempotencyKey
    )

    private fun authorizationId(vararg parts: Any): String = parts.joinToString("|") { part ->
        val value = part.toString()
        "${value.length}:$value"
    }

    private fun cursorFrom(
        snapshots: List<MechanicalRegisterSnapshot>,
        attempt: CommandAttemptToken
    ): RecoveryObservationCursor {
        val last = snapshots.last()
        return RecoveryObservationCursor(
            commandAttempt = attempt,
            lastAcceptedSampleSequence = last.sampleSequence,
            lastAcceptedSampledAtMs = last.sampledAtMs
        )
    }

    private fun appliedOperation(result: TransitionResult): MechanicalOperation? =
        (result as? TransitionResult.Applied)?.updated

    private fun blocked(): CoordinatorResult =
        CoordinatorResult.AdmissionBlocked("startup recovery gate is not open")

    private fun staleOrRejectedOperation(
        operation: MechanicalOperation,
        token: OperationToken
    ): CoordinatorResult = if (operation.token() != token) {
        CoordinatorResult.StaleOperationEvidence(token, operation.token())
    } else {
        CoordinatorResult.Rejected("operation state transition is rejected")
    }
}
