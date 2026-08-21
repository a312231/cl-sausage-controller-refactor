package cn.niuyannet.kaochang.mechanical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MechanicalResourcePolicyTest {

    @Test
    fun `admission claims exact current step resources and rejects overlap`() {
        val requested = requested()
        val admitted = applied(
            MechanicalResourcePolicy.claimForAdmission(requested, requested.token(), emptySet())
        )
        assertEquals(requested.currentStep.resources, admitted.heldResources)
        assertEquals(MechanicalOperationState.ADMITTED, admitted.state)

        assertTrue(
            MechanicalResourcePolicy.claimForAdmission(
                requested,
                requested.token(),
                setOf(MechanicalResource.LIFT)
            ) is TransitionResult.Rejected
        )
    }

    @Test
    fun `pause requires exact token and safe checkpoint`() {
        val admitted = admitted()
        assertTrue(
            MechanicalResourcePolicy.pauseAtCheckpoint(
                admitted,
                OperationToken("other", admitted.revision)
            ) is TransitionResult.StaleOperationEvidence
        )
        assertTrue(
            MechanicalResourcePolicy.pauseAtCheckpoint(
                admitted.copy(checkpoint = MechanicalCheckpoint("in1", CheckpointSafety.UNSAFE)),
                admitted.token()
            ) is TransitionResult.Rejected
        )
    }

    @Test
    fun `pause before command releases resources and resumes current step`() {
        val admitted = admitted()
        val paused = applied(MechanicalResourcePolicy.pauseAtCheckpoint(admitted, admitted.token()))
        assertEquals(MechanicalOperationState.PAUSED, paused.state)
        assertTrue(paused.heldResources.isEmpty())
        assertEquals(ResumeCursor.ExecuteStep(0), paused.resumeCursor)

        val resumed = applied(
            MechanicalResourcePolicy.resumeAfterRevalidation(paused, paused.token(), emptySet())
        )
        assertEquals(MechanicalOperationState.ADMITTED, resumed.state)
        assertEquals(0, resumed.currentStepIndex)
        assertEquals(resumed.currentStep.resources, resumed.heldResources)
    }

    @Test
    fun `pause after completed step resumes next step without replay`() {
        val completed = completedStep(stepIndex = 0, stepCount = 2)
        val paused = applied(MechanicalResourcePolicy.pauseAtCheckpoint(completed, completed.token()))
        assertEquals(ResumeCursor.ExecuteStep(1), paused.resumeCursor)

        val resumed = applied(
            MechanicalResourcePolicy.resumeAfterRevalidation(paused, paused.token(), emptySet())
        )
        assertEquals(1, resumed.currentStepIndex)
        assertEquals(MechanicalOperationState.ADMITTED, resumed.state)
        assertEquals(MechanicalPhysicalResult.NONE, resumed.physicalResult)
        assertEquals(MechanicalEffectState.NOT_APPLIED, resumed.effectState)
    }

    @Test
    fun `pause after final step resumes only for operation closeout`() {
        val completed = completedStep(stepIndex = 0, stepCount = 1)
        val paused = applied(MechanicalResourcePolicy.pauseAtCheckpoint(completed, completed.token()))
        assertEquals(ResumeCursor.CompleteOperation, paused.resumeCursor)

        val resumed = applied(
            MechanicalResourcePolicy.resumeAfterRevalidation(paused, paused.token(), emptySet())
        )
        assertEquals(MechanicalOperationState.STEP_COMPLETED, resumed.state)
        assertTrue(resumed.heldResources.isEmpty())
    }

    @Test
    fun `resume rejects conflicting resources`() {
        val admitted = admitted()
        val paused = applied(MechanicalResourcePolicy.pauseAtCheckpoint(admitted, admitted.token()))
        assertTrue(
            MechanicalResourcePolicy.resumeAfterRevalidation(
                paused,
                paused.token(),
                setOf(MechanicalResource.LIFT)
            ) is TransitionResult.Rejected
        )
    }

    @Test
    fun `timeout cancellation and recovery preserve resources`() {
        val dispatched = dispatched()
        listOf("timeout", "cancelled").forEach { reason ->
            val recovered = applied(
                MechanicalResourcePolicy.requireRecovery(dispatched, dispatched.token(), reason)
            )
            assertEquals(MechanicalOperationState.RECOVERY_REQUIRED, recovered.state)
            assertEquals(dispatched.heldResources, recovered.heldResources)
        }
    }

    @Test
    fun `completion releases resources only for exact token`() {
        val completedStep = completedStep(stepIndex = 0, stepCount = 1)
        assertTrue(
            MechanicalResourcePolicy.releaseForCompletion(
                completedStep,
                OperationToken(completedStep.operationId, completedStep.revision - 1)
            ) is TransitionResult.StaleOperationEvidence
        )
        val completed = applied(
            MechanicalResourcePolicy.releaseForCompletion(completedStep, completedStep.token())
        )
        assertEquals(MechanicalOperationState.OPERATION_COMPLETED, completed.state)
        assertTrue(completed.heldResources.isEmpty())
    }

    private fun requested(stepCount: Int = 1): MechanicalOperation {
        val steps = (1..stepCount).map { index ->
            MechanicalStep(
                stepId = "in$index",
                commandIdempotencyKey = "command-in$index",
                effectIdempotencyKey = "effect-in$index",
                resources = if (index == 1) {
                    setOf(MechanicalResource.LIFT)
                } else {
                    setOf(MechanicalResource.ABOVE)
                }
            )
        }
        return MechanicalOperation(
            operationId = "supplement-1",
            revision = 1,
            kind = MechanicalOperationKind.SUPPLEMENT,
            admissionClass = MechanicalAdmissionClass.SUPPLEMENT,
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
    }

    private fun admitted(): MechanicalOperation = applied(
        MechanicalOperationStateMachine.admit(requested(), requested().token())
    )

    private fun dispatched(): MechanicalOperation {
        var current = admitted()
        current = applied(MechanicalOperationStateMachine.prepareCommand(current, current.token(), "attempt-in1"))
        return applied(
            MechanicalOperationStateMachine.markDispatched(
                current,
                requireNotNull(current.currentCommandAttempt)
            )
        )
    }

    private fun completedStep(stepIndex: Int, stepCount: Int): MechanicalOperation {
        var current = requested(stepCount)
        current = applied(MechanicalOperationStateMachine.admit(current, current.token()))
        repeat(stepIndex + 1) { index ->
            current = applied(
                MechanicalOperationStateMachine.prepareCommand(current, current.token(), "attempt-in${index + 1}")
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
            current = applied(MechanicalOperationStateMachine.completeStep(current, current.token()))
            if (index < stepIndex) {
                current = applied(MechanicalOperationStateMachine.startNextStep(current, current.token()))
            }
        }
        return current
    }

    private fun applied(result: TransitionResult): MechanicalOperation =
        (result as TransitionResult.Applied).updated
}
