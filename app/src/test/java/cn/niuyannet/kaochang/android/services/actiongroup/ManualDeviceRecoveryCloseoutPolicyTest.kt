package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualDeviceRecoveryCloseoutPolicyTest {

    @Test
    fun idleHardwareWithoutProcessingOrder_allowsManualCloseout() {
        val decision = ManualDeviceRecoveryCloseoutPolicy.evaluate(
            evidence = evidence(),
            hasProcessingOrder = false,
            hasPanMoveJournal = false,
            hasDiscardJournal = false,
            durableEvidenceCorrupted = false
        )

        assertTrue(decision.canCloseout)
    }

    @Test
    fun completedActionResults_areAlsoIdleEnoughForManualCloseout() {
        val completed = ActionGroupRecoveryBootstrap.allSpecs.associate { it.name to AtomicActionCodes.COMPLETED }

        val decision = ManualDeviceRecoveryCloseoutPolicy.evaluate(
            evidence = evidence(completed),
            hasProcessingOrder = false,
            hasPanMoveJournal = false,
            hasDiscardJournal = false,
            durableEvidenceCorrupted = false
        )

        assertTrue(decision.canCloseout)
    }

    @Test
    fun busy201_processingOrder_orUnsafeMoveStatus_rejectsManualCloseout() {
        assertFalse(decide(evidence(legacy201 = AtomicActionCodes.RUNNING)).canCloseout)
        assertFalse(decide(evidence(legacy201 = AtomicActionCodes.COMPLETED)).canCloseout)
        assertFalse(decide(evidence(), hasProcessingOrder = true).canCloseout)
        assertFalse(decide(evidence(moveStatus = 3)).canCloseout)
    }

    @Test
    fun incompleteRead_runningErrorAndCorruptedEvidence_rejectManualCloseout() {
        val missing = evidence().copy(resultStates = evidence().resultStates.dropLast(1))
        val readFailed = evidence().copy(
            resultStates = evidence().resultStates.mapIndexed { index, state ->
                if (index == 0) state.copy(value = null, errorMessage = "read failed") else state
            }
        )
        val running = evidence(mapOf("in1" to AtomicActionCodes.RUNNING))
        val error = evidence(mapOf("out3" to AtomicActionCodes.MOVE_TIMEOUT))

        assertFalse(decide(missing).canCloseout)
        assertFalse(decide(readFailed).canCloseout)
        assertFalse(decide(running).canCloseout)
        assertFalse(decide(error).canCloseout)
        assertFalse(decide(evidence(), durableEvidenceCorrupted = true).canCloseout)
    }

    @Test
    fun dedicatedJournals_remainOutsideGenericManualCloseout() {
        assertFalse(decide(evidence(), hasPanMoveJournal = true).canCloseout)
        assertFalse(decide(evidence(), hasDiscardJournal = true).canCloseout)
    }

    private fun decide(
        evidence: ActionGroupRecoveryResidueEvidence,
        hasProcessingOrder: Boolean = false,
        hasPanMoveJournal: Boolean = false,
        hasDiscardJournal: Boolean = false,
        durableEvidenceCorrupted: Boolean = false
    ): ManualDeviceRecoveryCloseoutDecision = ManualDeviceRecoveryCloseoutPolicy.evaluate(
        evidence = evidence,
        hasProcessingOrder = hasProcessingOrder,
        hasPanMoveJournal = hasPanMoveJournal,
        hasDiscardJournal = hasDiscardJournal,
        durableEvidenceCorrupted = durableEvidenceCorrupted
    )

    private fun evidence(
        overrides: Map<String, Int> = emptyMap(),
        legacy201: Int = AtomicActionCodes.IDLE,
        moveStatus: Int = 1
    ) = ActionGroupRecoveryResidueEvidence(
        reason = "test",
        modbusConnected = true,
        legacyActionStatus201 = legacy201,
        moveStatus = moveStatus,
        session = null,
        resultStates = ActionGroupRecoveryBootstrap.allSpecs.map { spec ->
            ActionGroupRegisterState(
                actionName = spec.name,
                resultRegister = spec.resultRegister,
                value = overrides[spec.name] ?: AtomicActionCodes.IDLE
            )
        }
    )
}
