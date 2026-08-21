package cn.niuyannet.kaochang.mechanical

sealed interface TransitionResult {
    data class Applied(val updated: MechanicalOperation) : TransitionResult
    data class Rejected(val reason: String) : TransitionResult
    data class StaleOperationEvidence(
        val expected: OperationToken,
        val actual: OperationToken
    ) : TransitionResult

    data class StaleCommandEvidence(
        val expected: CommandAttemptToken?,
        val actual: CommandAttemptToken
    ) : TransitionResult

    data object DuplicateEvidence : TransitionResult
}

object MechanicalOperationStateMachine {

    fun admit(current: MechanicalOperation, token: OperationToken): TransitionResult {
        staleOperation(current, token)?.let { return it }
        if (current.state != MechanicalOperationState.REQUESTED) {
            return TransitionResult.Rejected("only REQUESTED operation can be admitted")
        }
        return applied(
            current.copy(
                revision = current.revision + 1,
                state = MechanicalOperationState.ADMITTED,
                heldResources = current.currentStep.resources,
                checkpoint = MechanicalCheckpoint(
                    current.currentStep.stepId,
                    CheckpointSafety.SAFE_TO_PAUSE
                ),
                recoveryReason = null
            )
        )
    }

    fun prepareCommand(
        current: MechanicalOperation,
        token: OperationToken,
        attemptId: String
    ): TransitionResult {
        staleOperation(current, token)?.let { return it }
        if (current.state != MechanicalOperationState.ADMITTED) {
            return TransitionResult.Rejected("command can be prepared only from ADMITTED")
        }
        if (attemptId.isBlank()) {
            return TransitionResult.Rejected("attemptId must not be blank")
        }
        val nextRevision = current.revision + 1
        val attempt = CommandAttemptToken(
            operationId = current.operationId,
            dispatchRevision = nextRevision,
            stepId = current.currentStep.stepId,
            attemptId = attemptId
        )
        return applied(
            current.copy(
                revision = nextRevision,
                state = MechanicalOperationState.COMMAND_PREPARED,
                checkpoint = null,
                currentCommandAttempt = attempt,
                physicalResult = MechanicalPhysicalResult.NONE,
                effectState = MechanicalEffectState.NOT_APPLIED,
                recoveryReason = null
            )
        )
    }

    fun markDispatched(
        current: MechanicalOperation,
        commandAttempt: CommandAttemptToken
    ): TransitionResult {
        if (current.state == MechanicalOperationState.COMMAND_DISPATCHED &&
            current.currentCommandAttempt == commandAttempt
        ) {
            return TransitionResult.DuplicateEvidence
        }
        staleCommand(current, commandAttempt)?.let { return it }
        if (current.state != MechanicalOperationState.COMMAND_PREPARED) {
            return TransitionResult.Rejected("dispatch can be recorded only from COMMAND_PREPARED")
        }
        return applied(
            current.copy(
                revision = current.revision + 1,
                state = MechanicalOperationState.COMMAND_DISPATCHED,
                checkpoint = null,
                physicalResult = MechanicalPhysicalResult.NONE,
                recoveryReason = null
            )
        )
    }

    fun confirmResult(
        current: MechanicalOperation,
        commandAttempt: CommandAttemptToken,
        result: MechanicalPhysicalResult
    ): TransitionResult {
        if (current.lastConfirmedCommandAttempt == commandAttempt) {
            return TransitionResult.DuplicateEvidence
        }
        staleCommand(current, commandAttempt)?.let { return it }
        if (current.state != MechanicalOperationState.COMMAND_DISPATCHED) {
            return TransitionResult.Rejected("result can be confirmed only from COMMAND_DISPATCHED")
        }
        return when (result) {
            MechanicalPhysicalResult.SUCCEEDED -> applied(
                current.copy(
                    revision = current.revision + 1,
                    state = MechanicalOperationState.RESULT_CONFIRMED,
                    lastConfirmedCommandAttempt = commandAttempt,
                    physicalResult = MechanicalPhysicalResult.SUCCEEDED,
                    effectState = MechanicalEffectState.NOT_APPLIED,
                    recoveryReason = null
                )
            )

            MechanicalPhysicalResult.FAILED,
            MechanicalPhysicalResult.UNKNOWN -> applied(
                current.copy(
                    revision = current.revision + 1,
                    state = MechanicalOperationState.RECOVERY_REQUIRED,
                    physicalResult = result,
                    recoveryReason = "physical result is $result"
                )
            )

            MechanicalPhysicalResult.NONE,
            MechanicalPhysicalResult.RUNNING -> TransitionResult.Rejected(
                "non-terminal physical result cannot confirm a step"
            )
        }
    }

    fun markEffectApplied(
        current: MechanicalOperation,
        token: OperationToken
    ): TransitionResult {
        staleOperation(current, token)?.let { return it }
        if (current.state != MechanicalOperationState.RESULT_CONFIRMED) {
            return TransitionResult.Rejected("effect can be applied only from RESULT_CONFIRMED")
        }
        return applied(
            current.copy(
                revision = current.revision + 1,
                state = MechanicalOperationState.EFFECT_APPLIED,
                effectState = MechanicalEffectState.APPLIED,
                appliedEffectKeys = current.appliedEffectKeys + current.currentStep.effectIdempotencyKey,
                recoveryReason = null
            )
        )
    }

    fun completeStep(
        current: MechanicalOperation,
        token: OperationToken
    ): TransitionResult {
        staleOperation(current, token)?.let { return it }
        if (current.state != MechanicalOperationState.EFFECT_APPLIED) {
            return TransitionResult.Rejected("step can be completed only from EFFECT_APPLIED")
        }
        return applied(
            current.copy(
                revision = current.revision + 1,
                state = MechanicalOperationState.STEP_COMPLETED,
                checkpoint = MechanicalCheckpoint(
                    current.currentStep.stepId,
                    CheckpointSafety.SAFE_TO_PAUSE
                ),
                recoveryReason = null
            )
        )
    }

    fun startNextStep(
        current: MechanicalOperation,
        token: OperationToken
    ): TransitionResult {
        staleOperation(current, token)?.let { return it }
        if (current.state != MechanicalOperationState.STEP_COMPLETED) {
            return TransitionResult.Rejected("next step requires STEP_COMPLETED")
        }
        if (current.currentStepIndex == current.steps.lastIndex) {
            return TransitionResult.Rejected("final step must complete the operation")
        }
        val nextIndex = current.currentStepIndex + 1
        val nextStep = current.steps[nextIndex]
        return applied(
            current.copy(
                revision = current.revision + 1,
                state = MechanicalOperationState.ADMITTED,
                currentStepIndex = nextIndex,
                heldResources = nextStep.resources,
                checkpoint = MechanicalCheckpoint(nextStep.stepId, CheckpointSafety.SAFE_TO_PAUSE),
                currentCommandAttempt = null,
                physicalResult = MechanicalPhysicalResult.NONE,
                effectState = MechanicalEffectState.NOT_APPLIED,
                recoveryReason = null
            )
        )
    }

    fun completeOperation(
        current: MechanicalOperation,
        token: OperationToken
    ): TransitionResult {
        staleOperation(current, token)?.let { return it }
        if (current.state != MechanicalOperationState.STEP_COMPLETED) {
            return TransitionResult.Rejected("operation completion requires STEP_COMPLETED")
        }
        if (current.currentStepIndex != current.steps.lastIndex) {
            return TransitionResult.Rejected("operation cannot complete before the final step")
        }
        return applied(
            current.copy(
                revision = current.revision + 1,
                state = MechanicalOperationState.OPERATION_COMPLETED,
                heldResources = emptySet(),
                checkpoint = null,
                recoveryReason = null
            )
        )
    }

    fun requireRecovery(
        current: MechanicalOperation,
        token: OperationToken,
        reason: String
    ): TransitionResult {
        staleOperation(current, token)?.let { return it }
        if (reason.isBlank()) {
            return TransitionResult.Rejected("recovery reason must not be blank")
        }
        if (current.state == MechanicalOperationState.OPERATION_COMPLETED) {
            return TransitionResult.Rejected("completed operation cannot re-enter recovery")
        }
        if (current.state == MechanicalOperationState.RECOVERY_REQUIRED) {
            return TransitionResult.Rejected("operation already requires recovery")
        }
        return applied(
            current.copy(
                revision = current.revision + 1,
                state = MechanicalOperationState.RECOVERY_REQUIRED,
                recoveryReason = reason
            )
        )
    }

    private fun staleOperation(
        current: MechanicalOperation,
        expected: OperationToken
    ): TransitionResult.StaleOperationEvidence? {
        val actual = current.token()
        return if (actual == expected) null else TransitionResult.StaleOperationEvidence(expected, actual)
    }

    private fun staleCommand(
        current: MechanicalOperation,
        actual: CommandAttemptToken
    ): TransitionResult.StaleCommandEvidence? =
        if (current.currentCommandAttempt == actual) null
        else TransitionResult.StaleCommandEvidence(current.currentCommandAttempt, actual)

    private fun applied(updated: MechanicalOperation): TransitionResult =
        TransitionResult.Applied(updated)
}
