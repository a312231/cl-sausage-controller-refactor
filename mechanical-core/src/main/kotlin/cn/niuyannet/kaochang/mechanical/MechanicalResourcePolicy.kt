package cn.niuyannet.kaochang.mechanical

object MechanicalResourcePolicy {

    fun conflicts(
        left: Set<MechanicalResource>,
        right: Set<MechanicalResource>
    ): Boolean = left.any(right::contains)

    fun claimForAdmission(
        requested: MechanicalOperation,
        token: OperationToken,
        occupiedByRunning: Set<MechanicalResource>
    ): TransitionResult {
        staleOperation(requested, token)?.let { return it }
        if (requested.state != MechanicalOperationState.REQUESTED) {
            return TransitionResult.Rejected("resource admission requires REQUESTED operation")
        }
        if (conflicts(requested.currentStep.resources, occupiedByRunning)) {
            return TransitionResult.Rejected("requested resources conflict with running operation")
        }
        return MechanicalOperationStateMachine.admit(requested, token)
    }

    fun pauseAtCheckpoint(
        current: MechanicalOperation,
        token: OperationToken = current.token()
    ): TransitionResult {
        staleOperation(current, token)?.let { return it }
        if (current.state != MechanicalOperationState.ADMITTED &&
            current.state != MechanicalOperationState.STEP_COMPLETED
        ) {
            return TransitionResult.Rejected("pause requires ADMITTED or STEP_COMPLETED")
        }
        val checkpoint = current.checkpoint
            ?: return TransitionResult.Rejected("pause requires a checkpoint")
        if (checkpoint.stepId != current.currentStep.stepId ||
            checkpoint.safety != CheckpointSafety.SAFE_TO_PAUSE
        ) {
            return TransitionResult.Rejected("checkpoint is not safe to pause")
        }
        val cursor = when (current.state) {
            MechanicalOperationState.ADMITTED -> ResumeCursor.ExecuteStep(current.currentStepIndex)
            MechanicalOperationState.STEP_COMPLETED -> if (current.currentStepIndex == current.steps.lastIndex) {
                ResumeCursor.CompleteOperation
            } else {
                ResumeCursor.ExecuteStep(current.currentStepIndex + 1)
            }
            else -> error("state checked above")
        }
        return TransitionResult.Applied(
            current.copy(
                revision = current.revision + 1,
                state = MechanicalOperationState.PAUSED,
                heldResources = emptySet(),
                resumeCursor = cursor
            )
        )
    }

    fun resumeAfterRevalidation(
        paused: MechanicalOperation,
        token: OperationToken,
        occupiedByRunning: Set<MechanicalResource>
    ): TransitionResult {
        staleOperation(paused, token)?.let { return it }
        if (paused.state != MechanicalOperationState.PAUSED) {
            return TransitionResult.Rejected("resume requires PAUSED operation")
        }
        return when (val cursor = paused.resumeCursor) {
            is ResumeCursor.ExecuteStep -> {
                val targetStep = paused.steps[cursor.stepIndex]
                if (conflicts(targetStep.resources, occupiedByRunning)) {
                    TransitionResult.Rejected("resume resources conflict with running operation")
                } else {
                    TransitionResult.Applied(
                        paused.copy(
                            revision = paused.revision + 1,
                            state = MechanicalOperationState.ADMITTED,
                            currentStepIndex = cursor.stepIndex,
                            heldResources = targetStep.resources,
                            checkpoint = MechanicalCheckpoint(
                                targetStep.stepId,
                                CheckpointSafety.SAFE_TO_PAUSE
                            ),
                            resumeCursor = null,
                            currentCommandAttempt = null,
                            physicalResult = MechanicalPhysicalResult.NONE,
                            effectState = MechanicalEffectState.NOT_APPLIED,
                            recoveryReason = null
                        )
                    )
                }
            }

            ResumeCursor.CompleteOperation -> TransitionResult.Applied(
                paused.copy(
                    revision = paused.revision + 1,
                    state = MechanicalOperationState.STEP_COMPLETED,
                    heldResources = emptySet(),
                    resumeCursor = null,
                    checkpoint = MechanicalCheckpoint(
                        paused.currentStep.stepId,
                        CheckpointSafety.SAFE_TO_PAUSE
                    )
                )
            )

            null -> TransitionResult.Rejected("paused operation has no resume cursor")
        }
    }

    fun requireRecovery(
        current: MechanicalOperation,
        token: OperationToken,
        reason: String
    ): TransitionResult = MechanicalOperationStateMachine.requireRecovery(current, token, reason)

    fun releaseForCompletion(
        current: MechanicalOperation,
        token: OperationToken
    ): TransitionResult = MechanicalOperationStateMachine.completeOperation(current, token)

    private fun staleOperation(
        current: MechanicalOperation,
        expected: OperationToken
    ): TransitionResult.StaleOperationEvidence? {
        val actual = current.token()
        return if (actual == expected) null else TransitionResult.StaleOperationEvidence(expected, actual)
    }
}
