package cn.niuyannet.kaochang.mechanical

import org.junit.Assert.assertEquals
import org.junit.Test

class MechanicalOperationArbiterTest {

    @Test
    fun `paid order pauses supplement only at safe checkpoint`() {
        assertEquals(
            ArbitrationDecision.PAUSE_RUNNING_THEN_ADMIT,
            MechanicalOperationArbiter.decide(
                safeRunning(MechanicalAdmissionClass.SUPPLEMENT),
                requested(MechanicalAdmissionClass.PAID_ORDER_DELIVERY),
                emergency = false
            )
        )
        assertEquals(
            ArbitrationDecision.KEEP_RUNNING,
            MechanicalOperationArbiter.decide(
                dispatchedRunning(MechanicalAdmissionClass.SUPPLEMENT),
                requested(MechanicalAdmissionClass.PAID_ORDER_DELIVERY),
                emergency = false
            )
        )
    }

    @Test
    fun `prepared or dispatched attempt cannot be silently preempted`() {
        listOf(
            preparedRunning(MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE),
            dispatchedRunning(MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE)
        ).forEach { atomic ->
            listOf(
                MechanicalAdmissionClass.PAID_ORDER_DELIVERY,
                MechanicalAdmissionClass.SAFE_RETURN,
                MechanicalAdmissionClass.SUPPLEMENT
            ).forEach { candidateClass ->
                assertEquals(
                    ArbitrationDecision.KEEP_RUNNING,
                    MechanicalOperationArbiter.decide(
                        atomic,
                        requested(candidateClass),
                        emergency = false
                    )
                )
            }
            assertEquals(
                ArbitrationDecision.EMERGENCY_STOP_REQUIRED,
                MechanicalOperationArbiter.decide(
                    atomic,
                    requested(MechanicalAdmissionClass.FOOD_SAFETY),
                    emergency = false
                )
            )
        }
    }

    @Test
    fun `external emergency never transfers or releases ownership`() {
        assertEquals(
            ArbitrationDecision.EMERGENCY_STOP_REQUIRED,
            MechanicalOperationArbiter.decide(
                dispatchedRunning(MechanicalAdmissionClass.SUPPLEMENT),
                requested(MechanicalAdmissionClass.PAID_ORDER_DELIVERY),
                emergency = true
            )
        )
    }

    @Test
    fun `complete fixed admission matrix is not runtime configurable`() {
        assertEquals(
            listOf(
                MechanicalPriority.EMERGENCY_OR_FOOD_SAFETY,
                MechanicalPriority.CURRENT_ATOMIC_ACTION,
                MechanicalPriority.PAID_ORDER_DELIVERY,
                MechanicalPriority.SAFE_RETURN,
                MechanicalPriority.SUPPLEMENT,
                MechanicalPriority.CLEANING_OR_MAINTENANCE
            ),
            MechanicalPriority.values().toList()
        )

        val classes = listOf(
            MechanicalAdmissionClass.FOOD_SAFETY,
            MechanicalAdmissionClass.PAID_ORDER_DELIVERY,
            MechanicalAdmissionClass.SAFE_RETURN,
            MechanicalAdmissionClass.SUPPLEMENT,
            MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE
        )
        classes.indices.forEach { higher ->
            (higher + 1 until classes.size).forEach { lower ->
                assertEquals(
                    ArbitrationDecision.QUEUE_CANDIDATE,
                    MechanicalOperationArbiter.decide(
                        safeRunning(classes[higher]),
                        requested(classes[lower]),
                        emergency = false
                    )
                )
                assertEquals(
                    ArbitrationDecision.PAUSE_RUNNING_THEN_ADMIT,
                    MechanicalOperationArbiter.decide(
                        safeRunning(classes[lower]),
                        requested(classes[higher]),
                        emergency = false
                    )
                )
            }
        }
    }

    @Test
    fun `without running operation paused work precedes equal or lower pending work`() {
        val paused = paused(MechanicalAdmissionClass.SUPPLEMENT)
        assertEquals(
            ArbitrationDecision.RESUME_PAUSED_BEFORE_CANDIDATE,
            MechanicalOperationArbiter.decideWhenNoRunning(
                paused,
                requested(MechanicalAdmissionClass.SUPPLEMENT)
            )
        )
        assertEquals(
            ArbitrationDecision.RESUME_PAUSED_BEFORE_CANDIDATE,
            MechanicalOperationArbiter.decideWhenNoRunning(
                paused,
                requested(MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE)
            )
        )
        assertEquals(
            ArbitrationDecision.ADMIT_CANDIDATE,
            MechanicalOperationArbiter.decideWhenNoRunning(
                paused,
                requested(MechanicalAdmissionClass.PAID_ORDER_DELIVERY)
            )
        )
        assertEquals(
            ArbitrationDecision.ADMIT_CANDIDATE,
            MechanicalOperationArbiter.decideWhenNoRunning(
                null,
                requested(MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE)
            )
        )
    }

    private fun requested(admissionClass: MechanicalAdmissionClass): MechanicalOperation {
        val kind = when (admissionClass) {
            MechanicalAdmissionClass.FOOD_SAFETY -> MechanicalOperationKind.DISCARD
            MechanicalAdmissionClass.PAID_ORDER_DELIVERY -> MechanicalOperationKind.ORDER_DELIVERY
            MechanicalAdmissionClass.SAFE_RETURN -> MechanicalOperationKind.PAN_MOVE
            MechanicalAdmissionClass.SUPPLEMENT -> MechanicalOperationKind.SUPPLEMENT
            MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE -> MechanicalOperationKind.CLEANING
        }
        return MechanicalOperation(
            operationId = "operation-${admissionClass.name}",
            revision = 1,
            kind = kind,
            admissionClass = admissionClass,
            state = MechanicalOperationState.REQUESTED,
            steps = listOf(
                MechanicalStep(
                    stepId = "step",
                    commandIdempotencyKey = "command-${admissionClass.name}",
                    effectIdempotencyKey = "effect-${admissionClass.name}",
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

    private fun safeRunning(admissionClass: MechanicalAdmissionClass): MechanicalOperation {
        val requested = requested(admissionClass)
        return applied(MechanicalOperationStateMachine.admit(requested, requested.token()))
    }

    private fun preparedRunning(admissionClass: MechanicalAdmissionClass): MechanicalOperation {
        val admitted = safeRunning(admissionClass)
        return applied(
            MechanicalOperationStateMachine.prepareCommand(admitted, admitted.token(), "attempt")
        )
    }

    private fun dispatchedRunning(admissionClass: MechanicalAdmissionClass): MechanicalOperation {
        val prepared = preparedRunning(admissionClass)
        return applied(
            MechanicalOperationStateMachine.markDispatched(
                prepared,
                requireNotNull(prepared.currentCommandAttempt)
            )
        )
    }

    private fun paused(admissionClass: MechanicalAdmissionClass): MechanicalOperation {
        val running = safeRunning(admissionClass)
        return applied(MechanicalResourcePolicy.pauseAtCheckpoint(running, running.token()))
    }

    private fun applied(result: TransitionResult): MechanicalOperation =
        (result as TransitionResult.Applied).updated
}
