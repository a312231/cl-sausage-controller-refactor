package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionGroupOrderRecoveryGatePolicyTest {

    @Test
    fun evaluate_shouldUseFastPathWhenOrderDispatchStateIsClean() {
        val decision = ActionGroupOrderRecoveryGatePolicy.evaluate(
            modbusConnected = true,
            recoveryVerified = true,
            hasPendingSession = false,
            resourceLocksIdle = true
        )

        assertEquals(ActionGroupOrderRecoveryDecision.FAST_PATH, decision)
    }

    @Test
    fun evaluate_shouldUseFullRecoveryWhenRecoveryHasNotBeenVerified() {
        val decision = ActionGroupOrderRecoveryGatePolicy.evaluate(
            modbusConnected = true,
            recoveryVerified = false,
            hasPendingSession = false,
            resourceLocksIdle = true
        )

        assertEquals(ActionGroupOrderRecoveryDecision.FULL_RECOVERY, decision)
    }

    @Test
    fun evaluate_shouldUseFullRecoveryWhenPendingSessionExists() {
        val decision = ActionGroupOrderRecoveryGatePolicy.evaluate(
            modbusConnected = true,
            recoveryVerified = true,
            hasPendingSession = true,
            resourceLocksIdle = true
        )

        assertEquals(ActionGroupOrderRecoveryDecision.FULL_RECOVERY, decision)
    }

    @Test
    fun evaluate_shouldUseFullRecoveryWhenResourceLocksAreBusy() {
        val decision = ActionGroupOrderRecoveryGatePolicy.evaluate(
            modbusConnected = true,
            recoveryVerified = true,
            hasPendingSession = false,
            resourceLocksIdle = false
        )

        assertEquals(ActionGroupOrderRecoveryDecision.FULL_RECOVERY, decision)
    }

    @Test
    fun evaluate_shouldUseFullRecoveryWhenModbusIsDisconnected() {
        val decision = ActionGroupOrderRecoveryGatePolicy.evaluate(
            modbusConnected = false,
            recoveryVerified = true,
            hasPendingSession = false,
            resourceLocksIdle = true
        )

        assertEquals(ActionGroupOrderRecoveryDecision.FULL_RECOVERY, decision)
    }
}
