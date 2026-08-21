package cn.niuyannet.kaochang.mechanical

enum class MechanicalPriority {
    EMERGENCY_OR_FOOD_SAFETY,
    CURRENT_ATOMIC_ACTION,
    PAID_ORDER_DELIVERY,
    SAFE_RETURN,
    SUPPLEMENT,
    CLEANING_OR_MAINTENANCE
}

enum class ArbitrationDecision {
    ADMIT_CANDIDATE,
    KEEP_RUNNING,
    PAUSE_RUNNING_THEN_ADMIT,
    RESUME_PAUSED_BEFORE_CANDIDATE,
    QUEUE_CANDIDATE,
    EMERGENCY_STOP_REQUIRED
}

object MechanicalOperationArbiter {

    fun decide(
        running: MechanicalOperation?,
        candidate: MechanicalOperation,
        emergency: Boolean
    ): ArbitrationDecision {
        if (emergency) return ArbitrationDecision.EMERGENCY_STOP_REQUIRED
        if (candidate.state != MechanicalOperationState.REQUESTED) {
            return ArbitrationDecision.QUEUE_CANDIDATE
        }
        if (running == null) return ArbitrationDecision.ADMIT_CANDIDATE

        if (isUninterruptible(running)) {
            return if (candidate.admissionClass == MechanicalAdmissionClass.FOOD_SAFETY) {
                ArbitrationDecision.EMERGENCY_STOP_REQUIRED
            } else {
                ArbitrationDecision.KEEP_RUNNING
            }
        }

        val candidateRank = rank(candidate.admissionClass)
        val runningRank = rank(running.admissionClass)
        if (candidateRank >= runningRank) {
            return ArbitrationDecision.QUEUE_CANDIDATE
        }
        return if (isSafeCheckpoint(running)) {
            ArbitrationDecision.PAUSE_RUNNING_THEN_ADMIT
        } else if (candidate.admissionClass == MechanicalAdmissionClass.FOOD_SAFETY) {
            ArbitrationDecision.EMERGENCY_STOP_REQUIRED
        } else {
            ArbitrationDecision.QUEUE_CANDIDATE
        }
    }

    fun decideWhenNoRunning(
        paused: MechanicalOperation?,
        candidate: MechanicalOperation
    ): ArbitrationDecision {
        if (candidate.state != MechanicalOperationState.REQUESTED) {
            return ArbitrationDecision.QUEUE_CANDIDATE
        }
        if (paused == null) return ArbitrationDecision.ADMIT_CANDIDATE
        return if (rank(candidate.admissionClass) < rank(paused.admissionClass)) {
            ArbitrationDecision.ADMIT_CANDIDATE
        } else {
            ArbitrationDecision.RESUME_PAUSED_BEFORE_CANDIDATE
        }
    }

    private fun isUninterruptible(operation: MechanicalOperation): Boolean =
        operation.state in setOf(
            MechanicalOperationState.COMMAND_PREPARED,
            MechanicalOperationState.COMMAND_DISPATCHED,
            MechanicalOperationState.RESULT_CONFIRMED,
            MechanicalOperationState.EFFECT_APPLIED,
            MechanicalOperationState.RECOVERY_REQUIRED
        )

    private fun isSafeCheckpoint(operation: MechanicalOperation): Boolean =
        operation.state in setOf(
            MechanicalOperationState.ADMITTED,
            MechanicalOperationState.STEP_COMPLETED
        ) && operation.checkpoint?.let { checkpoint ->
            checkpoint.stepId == operation.currentStep.stepId &&
                checkpoint.safety == CheckpointSafety.SAFE_TO_PAUSE
        } == true

    private fun rank(admissionClass: MechanicalAdmissionClass): Int = when (admissionClass) {
        MechanicalAdmissionClass.FOOD_SAFETY -> 0
        MechanicalAdmissionClass.PAID_ORDER_DELIVERY -> 2
        MechanicalAdmissionClass.SAFE_RETURN -> 3
        MechanicalAdmissionClass.SUPPLEMENT -> 4
        MechanicalAdmissionClass.CLEANING_OR_MAINTENANCE -> 5
    }
}
