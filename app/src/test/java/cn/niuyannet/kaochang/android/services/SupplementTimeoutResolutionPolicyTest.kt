package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import org.junit.Assert.assertEquals
import org.junit.Test

class SupplementTimeoutResolutionPolicyTest {
    @Test fun firstRunningCycle_shouldKeepSessionAndResources() {
        assertEquals(
            SupplementTimeoutResolutionPolicy.Resolution.KEEP_RUNNING,
            SupplementTimeoutResolutionPolicy.resolve(
                resultCode = AtomicActionCodes.RUNNING,
                readsSucceeded = true,
                actionName = "in3",
                isHeatSupplementBatch = true,
                isFinalCycle = false
            )
        )
    }

    @Test fun secondRunningCycle_shouldEnterHardFailure() {
        assertEquals(
            SupplementTimeoutResolutionPolicy.Resolution.HARD_FAILURE,
            SupplementTimeoutResolutionPolicy.resolve(
                resultCode = AtomicActionCodes.RUNNING,
                readsSucceeded = true,
                actionName = "in3",
                isHeatSupplementBatch = true,
                isFinalCycle = true
            )
        )
    }

    @Test fun completed_shouldReconcileAsSuccess() {
        assertEquals(
            SupplementTimeoutResolutionPolicy.Resolution.RECONCILE_COMPLETED,
            SupplementTimeoutResolutionPolicy.resolve(
                AtomicActionCodes.COMPLETED,
                readsSucceeded = true,
                actionName = "in3",
                isHeatSupplementBatch = true,
                isFinalCycle = true
            )
        )
    }

    @Test fun readableLowerTerminalError_shouldIsolateHeatSupplementIn1ToIn5() {
        assertEquals(
            SupplementTimeoutResolutionPolicy.Resolution.ISOLATE_TERMINAL_ERROR,
            SupplementTimeoutResolutionPolicy.resolve(
                AtomicActionCodes.IR_THROW_FAILED,
                readsSucceeded = true,
                actionName = "in4",
                isHeatSupplementBatch = true,
                isFinalCycle = true
            )
        )
    }

    @Test fun readableLowerTerminalError_shouldNotIsolateInitializationOrIn6() {
        assertEquals(
            SupplementTimeoutResolutionPolicy.Resolution.HARD_FAILURE,
            SupplementTimeoutResolutionPolicy.resolve(
                AtomicActionCodes.LIFT_FAILED,
                readsSucceeded = true,
                actionName = "in4",
                isHeatSupplementBatch = false,
                isFinalCycle = true
            )
        )
        assertEquals(
            SupplementTimeoutResolutionPolicy.Resolution.HARD_FAILURE,
            SupplementTimeoutResolutionPolicy.resolve(
                AtomicActionCodes.LIFT_FAILED,
                readsSucceeded = true,
                actionName = "in6",
                isHeatSupplementBatch = true,
                isFinalCycle = true
            )
        )
    }

    @Test fun liftPositionOrMotionErrors_shouldAlwaysEnterHardFailure() {
        listOf(
            AtomicActionCodes.LIFT_FAILED,
            AtomicActionCodes.LIFT_DOWN_LOCKED_ROTOR,
            AtomicActionCodes.LIFT_UP_LOCKED_ROTOR,
            AtomicActionCodes.LIFT_NOT_BOTTOM
        ).forEach { resultCode ->
            assertEquals(
                SupplementTimeoutResolutionPolicy.Resolution.HARD_FAILURE,
                SupplementTimeoutResolutionPolicy.resolve(
                    resultCode,
                    readsSucceeded = true,
                    actionName = "in4",
                    isHeatSupplementBatch = true,
                    isFinalCycle = true
                )
            )
        }
    }

    @Test fun idleErrorAndReadFailure_shouldEnterHardFailure() {
        assertEquals(
            SupplementTimeoutResolutionPolicy.Resolution.HARD_FAILURE,
            SupplementTimeoutResolutionPolicy.resolve(
                AtomicActionCodes.IDLE, true, "in3", true, true
            )
        )
        assertEquals(
            SupplementTimeoutResolutionPolicy.Resolution.HARD_FAILURE,
            SupplementTimeoutResolutionPolicy.resolve(0x7FFF, true, "in3", true, true)
        )
        assertEquals(
            SupplementTimeoutResolutionPolicy.Resolution.HARD_FAILURE,
            SupplementTimeoutResolutionPolicy.resolve(null, false, "in3", true, true)
        )
    }
}
