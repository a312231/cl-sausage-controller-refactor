package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionResult
import cn.niuyannet.kaochang.android.services.actiongroup.MechanicalClaimState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementFailureCloseoutPolicyTest {

    @Test
    fun serialHeatIn1ExplicitFailureWithoutPhysicalCommit_shouldBeEligible() {
        assertTrue(
            SupplementFailureCloseoutPolicy.canAutoRelease(
                SupplementFailureCloseoutEvidence(
                    batchType = SupplementBatchType.LOW_HEAT_13_15,
                    actionName = "in1",
                    rawResult = AtomicActionResult.Failed(
                        AtomicActionCodes.IN1_X_RESET_FAILED,
                        "reset failed"
                    ),
                    prefetch = false,
                    mechanicalClaimState = MechanicalClaimState.CLAIMED
                )
            )
        )
    }

    @Test
    fun timeoutDangerousLiftFallbackAndInitialization_shouldStayConservative() {
        val base = SupplementFailureCloseoutEvidence(
            batchType = SupplementBatchType.LOW_HEAT_13_15,
            actionName = "in1",
            rawResult = AtomicActionResult.Failed(
                AtomicActionCodes.IN1_X_RESET_FAILED,
                "reset failed"
            ),
            prefetch = false,
            mechanicalClaimState = MechanicalClaimState.CLAIMED
        )

        assertFalse(SupplementFailureCloseoutPolicy.canAutoRelease(base.copy(rawResult = AtomicActionResult.Timeout("timeout"))))
        assertFalse(
            SupplementFailureCloseoutPolicy.canAutoRelease(
                base.copy(rawResult = AtomicActionResult.Failed(AtomicActionCodes.LIFT_FAILED, "lift"))
            )
        )
        assertFalse(
            SupplementFailureCloseoutPolicy.canAutoRelease(
                base.copy(actionName = "in2", rawResult = AtomicActionResult.Failed(AtomicActionCodes.IR_PICK_FAILED, "pick"))
            )
        )
        assertFalse(
            SupplementFailureCloseoutPolicy.canAutoRelease(
                base.copy(batchType = SupplementBatchType.LOW_INITIALIZATION_1_9)
            )
        )
        assertFalse(SupplementFailureCloseoutPolicy.canAutoRelease(base.copy(prefetch = true)))
    }

    @Test
    fun physicalEvidenceAndIn3AfterIn2Commit_shouldStayConservative() {
        val base = SupplementFailureCloseoutEvidence(
            batchType = SupplementBatchType.LOW_HEAT_13_15,
            actionName = "in1",
            rawResult = AtomicActionResult.Failed(AtomicActionCodes.IN1_X_RESET_FAILED, "reset failed"),
            prefetch = false,
            mechanicalClaimState = MechanicalClaimState.CLAIMED
        )

        assertFalse(SupplementFailureCloseoutPolicy.canAutoRelease(base.copy(physicalBoxDeducted = true)))
        assertFalse(SupplementFailureCloseoutPolicy.canAutoRelease(base.copy(boxDeductionApplied = true)))
        assertFalse(
            SupplementFailureCloseoutPolicy.canAutoRelease(
                base.copy(actionName = "in3", lastConfirmedStage = "in2")
            )
        )
        assertFalse(
            SupplementFailureCloseoutPolicy.canAutoRelease(
                base.copy(mechanicalClaimState = MechanicalClaimState.PREPARED)
            )
        )
    }
}
