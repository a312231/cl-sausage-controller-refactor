package cn.niuyannet.kaochang.android.modbus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyResetMechanicalWriteGateTest {

    @Test
    fun emergencyReset_shouldBlockMechanicalCommandsBeforeWriting999() {
        val gate = EmergencyResetMechanicalWriteGate()
        var blockedInsideResetWrite = false

        val result = gate.blockAndExecuteEmergencyReset {
            blockedInsideResetWrite = gate.isMechanicalCommandBlocked()
            "reset-written"
        }

        assertEquals("reset-written", result)
        assertTrue(blockedInsideResetWrite)
        assertTrue(gate.isMechanicalCommandBlocked())
    }

    @Test
    fun pendingEmergencyReset_shouldRejectActionAndMoveCommandsButAllowHeatingWrites() {
        val gate = EmergencyResetMechanicalWriteGate()
        gate.blockAndExecuteEmergencyReset { Unit }
        var writes = 0

        val action = gate.executeWrite(200, rejected = { "blocked" }) {
            writes += 1
            "written"
        }
        val move = gate.executeWrite(202, rejected = { "blocked" }) {
            writes += 1
            "written"
        }
        val heating = gate.executeWrite(503, rejected = { "blocked" }) {
            writes += 1
            "written"
        }

        assertEquals("blocked", action)
        assertEquals("blocked", move)
        assertEquals("written", heating)
        assertEquals(1, writes)
    }

    @Test
    fun pendingEmergencyReset_shouldRejectAll300And400ActionCommandRegisters() {
        val gate = EmergencyResetMechanicalWriteGate()
        gate.blockMechanicalCommands()
        val actionCommands = listOf(310, 320, 330, 340, 350, 360, 370, 410, 420, 430, 440, 450)

        actionCommands.forEach { address ->
            assertFalse(
                "紧急复位后必须拦截动作命令寄存器 $address",
                gate.executeWrite(address, rejected = { false }) { true }
            )
        }
    }

    @Test
    fun recoveryCloseout_shouldReopenMechanicalCommandWrites() {
        val gate = EmergencyResetMechanicalWriteGate()
        gate.blockAndExecuteEmergencyReset { Unit }
        gate.clearAfterRecovery()

        val result = gate.executeWrite(200, rejected = { false }) { true }

        assertTrue(result)
        assertFalse(gate.isMechanicalCommandBlocked())
    }

    @Test
    fun coldStartWithPendingFence_shouldRestoreMechanicalCommandBlockWithoutWriting999() {
        val gate = EmergencyResetMechanicalWriteGate()

        gate.blockMechanicalCommands()

        assertTrue(gate.isMechanicalCommandBlocked())
        assertFalse(gate.executeWrite(200, rejected = { false }) { true })
    }
}
