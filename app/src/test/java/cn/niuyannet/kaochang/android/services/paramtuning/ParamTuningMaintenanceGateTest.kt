package cn.niuyannet.kaochang.android.services.paramtuning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamTuningMaintenanceGateTest {

    private fun allIdle() = ParamTuningMaintenanceGate.check(
        onlineStatus = 3,
        errorStatus = 0,
        inspectionMode = 0,
        moveStatus = 0,
        status201 = 0,
        moveResult203 = 0,
        atomicResultValues = emptyMap()
    )

    @Test
    fun allIdle_shouldAllow() {
        val decision = allIdle()
        assertTrue("all idle should allow", decision.allowed)
    }

    @Test
    fun onlineStatusNot3_shouldReject() {
        for (status in listOf(0, 1, 2, 4)) {
            val decision = ParamTuningMaintenanceGate.check(
                onlineStatus = status,
                errorStatus = 0,
                inspectionMode = 0,
                moveStatus = 0,
                status201 = 0,
                moveResult203 = 0,
                atomicResultValues = emptyMap()
            )
            assertFalse("onlineStatus=$status should reject", decision.allowed)
            assertTrue(
                "reason should mention maintenance, got: ${decision.reason}",
                decision.reason.contains("维护中")
            )
        }
    }

    @Test
    fun onlineStatusZero_shouldSayPendingDeployment() {
        val decision = ParamTuningMaintenanceGate.check(
            onlineStatus = 0,
            errorStatus = 0,
            inspectionMode = 0,
            moveStatus = 0,
            status201 = 0,
            moveResult203 = 0,
            atomicResultValues = emptyMap()
        )

        assertFalse(decision.allowed)
        assertTrue(
            "reason should say pending deployment, got: ${decision.reason}",
            decision.reason.contains("待投运")
        )
        assertFalse(
            "onlineStatus=0 is not connectivity offline, got: ${decision.reason}",
            decision.reason.contains("离线")
        )
    }

    @Test
    fun errorStatusNonZero_shouldReject() {
        val decision = ParamTuningMaintenanceGate.check(
            onlineStatus = 3,
            errorStatus = 1,
            inspectionMode = 0,
            moveStatus = 0,
            status201 = 0,
            moveResult203 = 0,
            atomicResultValues = emptyMap()
        )
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("故障"))
    }

    @Test
    fun inspectionModeActive_shouldReject() {
        val decision = ParamTuningMaintenanceGate.check(
            onlineStatus = 3,
            errorStatus = 0,
            inspectionMode = 1,
            moveStatus = 0,
            status201 = 0,
            moveResult203 = 0,
            atomicResultValues = emptyMap()
        )
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("检修模式"))
    }

    @Test
    fun moveStatusNonZero_shouldReject() {
        val decision = ParamTuningMaintenanceGate.check(
            onlineStatus = 3,
            errorStatus = 0,
            inspectionMode = 0,
            moveStatus = 1,
            status201 = 0,
            moveResult203 = 0,
            atomicResultValues = emptyMap()
        )
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("moveStatus"))
    }

    @Test
    fun status201Running_shouldReject() {
        val decision = ParamTuningMaintenanceGate.check(
            onlineStatus = 3,
            errorStatus = 0,
            inspectionMode = 0,
            moveStatus = 0,
            status201 = 1,
            moveResult203 = 0,
            atomicResultValues = emptyMap()
        )
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("201"))
    }

    @Test
    fun moveResult203Running_shouldReject() {
        val decision = ParamTuningMaintenanceGate.check(
            onlineStatus = 3,
            errorStatus = 0,
            inspectionMode = 0,
            moveStatus = 0,
            status201 = 0,
            moveResult203 = 1,
            atomicResultValues = emptyMap()
        )
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("203"))
    }

    @Test
    fun atomicResultRegisterRunning_shouldReject() {
        val decision = ParamTuningMaintenanceGate.check(
            onlineStatus = 3,
            errorStatus = 0,
            inspectionMode = 0,
            moveStatus = 0,
            status201 = 0,
            moveResult203 = 0,
            atomicResultValues = mapOf(311 to 1)
        )
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("311"))
    }
}
