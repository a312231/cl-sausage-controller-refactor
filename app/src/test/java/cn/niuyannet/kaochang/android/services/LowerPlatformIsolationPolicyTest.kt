package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowerPlatformIsolationPolicyTest {

    @Test
    fun supplementActionFailure_shouldClassifyLowerPlatformFailuresWithoutHidingIn5ResidueRisk() {
        assertTrue(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in1",
            failed(AtomicActionCodes.IN1_X_RESET_FAILED),
            hasPrefetchedNext = false
        ))
        assertTrue(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in2",
            failed(AtomicActionCodes.X_RESET_FAILED),
            hasPrefetchedNext = false
        ))
        assertTrue(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in3",
            failed(AtomicActionCodes.IN3_Y_RESET_FAILED),
            hasPrefetchedNext = false
        ))
        assertTrue(LowerPlatformIsolationPolicy.requiresManualConfirmAfterIsolation(
            "in3",
            failed(AtomicActionCodes.IN3_X_RESET_FAILED)
        ))
        assertTrue(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in4",
            failed(AtomicActionCodes.LIFT_NOT_BOTTOM),
            hasPrefetchedNext = false
        ))
        assertTrue(LowerPlatformIsolationPolicy.requiresManualConfirmAfterIsolation(
            "in4",
            failed(AtomicActionCodes.LIFT_NOT_BOTTOM)
        ))
        assertTrue(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in5",
            failed(AtomicActionCodes.IR_THROW_FAILED),
            hasPrefetchedNext = false
        ))
        assertTrue(LowerPlatformIsolationPolicy.requiresManualConfirmAfterIsolation(
            "in5",
            failed(AtomicActionCodes.IR_THROW_FAILED)
        ))

        assertFalse(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in2",
            failed(AtomicActionCodes.IR_PICK_FAILED),
            hasPrefetchedNext = false
        ))
    }

    @Test
    fun supplementActionFailure_shouldKeepHardFailureForUpperPlatformRiskAndUntrustedResults() {
        assertFalse(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in6",
            failed(AtomicActionCodes.LIFT_UP_LOCKED_ROTOR),
            hasPrefetchedNext = false
        ))
        assertFalse(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in7",
            failed(AtomicActionCodes.IN7_SAU_ADD_BUSY),
            hasPrefetchedNext = false
        ))
        assertFalse(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "out1",
            failed(AtomicActionCodes.OUT_SLOT_PARAM),
            hasPrefetchedNext = false
        ))
        assertTrue(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in5",
            failed(AtomicActionCodes.IR_THROW_FAILED),
            hasPrefetchedNext = true
        ))
        assertTrue(LowerPlatformIsolationPolicy.requiresManualConfirmAfterIsolation(
            "in5",
            failed(AtomicActionCodes.IR_THROW_FAILED)
        ))
        assertFalse(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in5",
            AtomicActionResult.Timeout("超时"),
            hasPrefetchedNext = false
        ))
        assertFalse(LowerPlatformIsolationPolicy.isSupplementActionIsolatable(
            "in5",
            AtomicActionResult.ModbusError("通信失败"),
            hasPrefetchedNext = false
        ))
        assertFalse(LowerPlatformIsolationPolicy.requiresManualConfirmAfterIsolation(
            "in1",
            failed(AtomicActionCodes.IN1_X_RESET_FAILED)
        ))
    }

    @Test
    fun buildReason_shouldDescribeIsolationWithoutMaintenanceWording() {
        val reason = LowerPlatformIsolationPolicy.buildIsolationReason(
            actionName = "in5",
            result = failed(AtomicActionCodes.IR_THROW_FAILED),
            targetPanNo = 9,
            sourceBoxNo = 12,
            hasPrefetchedNext = false
        )

        assertTrue(reason.contains("下平台隔离"))
        assertTrue(reason.contains("action=in5"))
        assertTrue(reason.contains("targetPan=9"))
        assertTrue(reason.contains("sourceBox=12"))
        assertFalse(reason.contains("维护"))
    }

    private fun failed(code: Int): AtomicActionResult.Failed =
        AtomicActionResult.Failed(code, "failure")
}
