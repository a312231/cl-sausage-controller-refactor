package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanDeliveryBusyRecoveryPolicyTest {

    @Test
    fun twoCompleteIdleSnapshots_allowRecovery() {
        val decision = OrphanDeliveryBusyRecoveryPolicy.evaluate(2, listOf(idleSnapshot(), idleSnapshot()))

        assertTrue(decision.reason, decision.canRecover)
    }

    @Test
    fun physicalIdleSnapshots_canBeReusedForManualClaimCloseout() {
        val decision = OrphanDeliveryBusyRecoveryPolicy.evaluateIdleSnapshots(
            listOf(idleSnapshot(), idleSnapshot())
        )

        assertTrue(decision.reason, decision.canRecover)
    }

    @Test
    fun completedResult_rejectsRecovery() {
        val decision = OrphanDeliveryBusyRecoveryPolicy.evaluate(
            2,
            listOf(idleSnapshot(), idleSnapshot(resultOverrides = mapOf("out1" to AtomicActionCodes.COMPLETED)))
        )

        assertFalse(decision.canRecover)
    }

    @Test
    fun runningCommand_rejectsRecovery() {
        val decision = OrphanDeliveryBusyRecoveryPolicy.evaluate(
            2,
            listOf(idleSnapshot(), idleSnapshot(commandOverrides = mapOf("in1" to AtomicActionCodes.RUNNING)))
        )

        assertFalse(decision.canRecover)
    }

    @Test
    fun missingRegisterRead_rejectsRecovery() {
        val incomplete = idleSnapshot().copy(resultStates = idleSnapshot().resultStates.dropLast(1))

        val decision = OrphanDeliveryBusyRecoveryPolicy.evaluate(2, listOf(idleSnapshot(), incomplete))

        assertFalse(decision.canRecover)
    }

    @Test
    fun failedRegisterRead_rejectsRecovery() {
        val unreadable = idleSnapshot().copy(
            commandStates = idleSnapshot().commandStates.map {
                if (it.actionName == "in1") it.copy(value = null, errorMessage = "Modbus read failed") else it
            }
        )

        val decision = OrphanDeliveryBusyRecoveryPolicy.evaluate(2, listOf(idleSnapshot(), unreadable))

        assertFalse(decision.canRecover)
    }

    @Test
    fun disconnectedModbus_rejectsRecovery() {
        val disconnected = idleSnapshot().copy(modbusConnected = false)

        val decision = OrphanDeliveryBusyRecoveryPolicy.evaluate(2, listOf(idleSnapshot(), disconnected))

        assertFalse(decision.canRecover)
    }

    @Test
    fun nonDeliveryMoveStatus_rejectsRecovery() {
        val decision = OrphanDeliveryBusyRecoveryPolicy.evaluate(1, listOf(idleSnapshot(), idleSnapshot()))

        assertFalse(decision.canRecover)
    }

    private fun idleSnapshot(
        commandOverrides: Map<String, Int> = emptyMap(),
        resultOverrides: Map<String, Int> = emptyMap()
    ): OrphanDeliveryBusySnapshot {
        val specs = listOf(
            AtomicActions.in1, AtomicActions.in2, AtomicActions.in3, AtomicActions.in4,
            AtomicActions.in5, AtomicActions.in6, AtomicActions.in7, AtomicActions.out1,
            AtomicActions.out2, AtomicActions.out3, AtomicActions.out4, AtomicActions.out5
        )
        return OrphanDeliveryBusySnapshot(
            modbusConnected = true,
            legacyActionStatus201 = AtomicActionCodes.IDLE,
            commandStates = specs.map { spec ->
                OrphanDeliveryBusyRegisterState(spec.name, spec.commandRegister, commandOverrides[spec.name] ?: 0)
            },
            resultStates = specs.map { spec ->
                OrphanDeliveryBusyRegisterState(spec.name, spec.resultRegister, resultOverrides[spec.name] ?: 0)
            }
        )
    }
}
