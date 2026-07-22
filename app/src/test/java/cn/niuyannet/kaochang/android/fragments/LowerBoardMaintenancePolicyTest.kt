package cn.niuyannet.kaochang.android.fragments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowerBoardMaintenancePolicyTest {

    @Test
    fun actionResultRegisters_shouldMatch300And400ProtocolResultRegisters() {
        assertEquals(
            listOf(311, 321, 331, 341, 351, 361, 371, 411, 421, 431, 441, 451),
            LowerBoardMaintenancePolicy.actionResultRegisters
        )
    }

    @Test
    fun canRunMaintenance_shouldRejectWhenLocalMoveStatusIsNotIdle() {
        val decision = LowerBoardMaintenancePolicy.canRunMaintenance(
            moveStatus = 2,
            status201 = 0,
            moveResult203 = 0,
            atomicResultValues = mapOf(311 to 0, 411 to 0)
        )

        assertFalse(decision.allowed)
        assertEquals("上位机机械动作状态 moveStatus=2，非空闲，不能执行下位机维护操作", decision.reason)
    }

    @Test
    fun canRunMaintenance_shouldRejectWhenLegacyStatus201IsRunning() {
        val decision = LowerBoardMaintenancePolicy.canRunMaintenance(
            moveStatus = 0,
            status201 = 1,
            moveResult203 = 0,
            atomicResultValues = emptyMap()
        )

        assertFalse(decision.allowed)
        assertEquals("下位机动作执行中：201=1", decision.reason)
    }

    @Test
    fun canRunMaintenance_shouldRejectWhenPanMoveResult203IsRunning() {
        val decision = LowerBoardMaintenancePolicy.canRunMaintenance(
            moveStatus = 0,
            status201 = 0,
            moveResult203 = 1,
            atomicResultValues = emptyMap()
        )

        assertFalse(decision.allowed)
        assertEquals("下位机搬盘执行中：203=1", decision.reason)
    }

    @Test
    fun canRunMaintenance_shouldRejectWhenAnyAtomicResultIsRunning() {
        val decision = LowerBoardMaintenancePolicy.canRunMaintenance(
            moveStatus = 0,
            status201 = 0,
            moveResult203 = 0,
            atomicResultValues = mapOf(311 to 0, 421 to 1, 451 to 0)
        )

        assertFalse(decision.allowed)
        assertEquals("下位机原子动作执行中：421=1", decision.reason)
    }

    @Test
    fun canRunMaintenance_shouldAllowWhenAllStatusesAreIdleOrFinished() {
        val decision = LowerBoardMaintenancePolicy.canRunMaintenance(
            moveStatus = 0,
            status201 = 0,
            moveResult203 = 2,
            atomicResultValues = mapOf(311 to 0, 421 to 2, 451 to 0x00E1)
        )

        assertTrue(decision.allowed)
        assertEquals("允许执行", decision.reason)
    }

    @Test
    fun canRunMaintenance_shouldAllowTerminalActionErrorResidueWhenLocalMoveStatusIsIdle() {
        val decision = LowerBoardMaintenancePolicy.canRunMaintenance(
            moveStatus = 0,
            status201 = 0,
            moveResult203 = 0,
            atomicResultValues = mapOf(311 to 0x00EF, 421 to 0)
        )

        assertTrue(decision.allowed)
        assertEquals("允许执行", decision.reason)
    }

    @Test
    fun canRunEmergencyReset_shouldBypassIdleGate() {
        val decision = LowerBoardMaintenancePolicy.canRunEmergencyReset()

        assertTrue(decision.allowed)
        assertEquals("允许紧急复位", decision.reason)
    }

    @Test
    fun formatMaintenanceRegisterValue_shouldDescribe998And999() {
        assertEquals(
            "0（0x0000，清空 300/400 原子动作寄存器触发寄存器）",
            LowerBoardMaintenancePolicy.formatMaintenanceRegisterValue(998, 0)
        )
        assertEquals(
            "1（0x0001，下位机系统软重启触发寄存器）",
            LowerBoardMaintenancePolicy.formatMaintenanceRegisterValue(999, 1)
        )
    }
}
