package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSessionType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualOutOrderRecoveryPolicyTest {

    @Test
    fun staleManualOutSession_shouldDeferOrderToRecovery() {
        assertTrue(
            ManualOutOrderRecoveryPolicy.shouldRecoverBeforeOrder(
                moveStatus = 2,
                onlineStatus = 1,
                errorStatus = 0,
                inspectionMode = 0,
                sessionType = ActionGroupSessionType.OUT_MANUAL,
                manualTakeTaskActive = false
            )
        )
    }

    @Test
    fun activeManualTake_shouldKeepOrderBlocked() {
        assertFalse(
            ManualOutOrderRecoveryPolicy.shouldRecoverBeforeOrder(
                moveStatus = 2,
                onlineStatus = 1,
                errorStatus = 0,
                inspectionMode = 0,
                sessionType = ActionGroupSessionType.OUT_MANUAL,
                manualTakeTaskActive = true
            )
        )
    }

    @Test
    fun nonManualOrUnsafeState_shouldKeepOrderBlocked() {
        assertFalse(
            ManualOutOrderRecoveryPolicy.shouldRecoverBeforeOrder(
                moveStatus = 2,
                onlineStatus = 1,
                errorStatus = 0,
                inspectionMode = 0,
                sessionType = ActionGroupSessionType.OUT_SELL,
                manualTakeTaskActive = false
            )
        )
        assertFalse(
            ManualOutOrderRecoveryPolicy.shouldRecoverBeforeOrder(
                moveStatus = 2,
                onlineStatus = 3,
                errorStatus = 0,
                inspectionMode = 0,
                sessionType = ActionGroupSessionType.OUT_MANUAL,
                manualTakeTaskActive = false
            )
        )
        assertFalse(
            ManualOutOrderRecoveryPolicy.shouldRecoverBeforeOrder(
                moveStatus = 2,
                onlineStatus = 4,
                errorStatus = 0,
                inspectionMode = 0,
                sessionType = ActionGroupSessionType.OUT_MANUAL,
                manualTakeTaskActive = false
            )
        )
        assertTrue(
            ManualOutOrderRecoveryPolicy.shouldRecoverBeforeOrder(
                moveStatus = 2,
                onlineStatus = 4,
                errorStatus = 0,
                inspectionMode = 0,
                sessionType = ActionGroupSessionType.OUT_MANUAL,
                manualTakeTaskActive = false,
                recoveryReservedForOrder = true
            )
        )
        assertFalse(
            ManualOutOrderRecoveryPolicy.shouldRecoverBeforeOrder(
                moveStatus = 2,
                onlineStatus = 3,
                errorStatus = 0,
                inspectionMode = 0,
                sessionType = ActionGroupSessionType.OUT_MANUAL,
                manualTakeTaskActive = false,
                recoveryReservedForOrder = true
            )
        )
    }
}
