package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRecoveryCloudConfirmationPolicyTest {

    @Test
    fun canRetryCloudOnly_shouldRequireExactLocallyRecoveredIdleState() {
        assertTrue(
            ManualRecoveryCloudConfirmationPolicy.canRetryCloudOnly(
                onlineStatus = 1,
                targetOnlineStatus = 1,
                errorStatus = 0,
                moveStatus = 0,
                isEnable = 0,
                faultPauseActive = false
            )
        )
        assertFalse(
            ManualRecoveryCloudConfirmationPolicy.canRetryCloudOnly(
                onlineStatus = 4,
                targetOnlineStatus = 1,
                errorStatus = 0,
                moveStatus = 2,
                isEnable = 1,
                faultPauseActive = false
            )
        )
    }

    @Test
    fun evaluate_shouldConfirmOnlyWhenAckAndLocalStateBothSucceed() {
        val decision = ManualRecoveryCloudConfirmationPolicy.evaluate(
            targetOnlineStatus = 1,
            ackReceived = true,
            ackApplied = true,
            remoteOnlineStatus = 1,
            remoteErrorStatus = 0,
            localStateStillRecovered = true
        )

        assertTrue(decision.confirmed)
    }

    @Test
    fun evaluate_shouldRejectWhenCloudStillKeepsFaultLatched() {
        val decision = ManualRecoveryCloudConfirmationPolicy.evaluate(
            targetOnlineStatus = 1,
            ackReceived = true,
            ackApplied = true,
            remoteOnlineStatus = 3,
            remoteErrorStatus = 2,
            localStateStillRecovered = true
        )

        assertFalse(decision.confirmed)
        assertTrue(decision.reason.contains("onlineStatus=3"))
        assertTrue(decision.reason.contains("errorStatus=2"))
    }

    @Test
    fun evaluate_shouldRejectWhenRecoveryAckTimesOut() {
        val decision = ManualRecoveryCloudConfirmationPolicy.evaluate(
            targetOnlineStatus = 1,
            ackReceived = false,
            ackApplied = false,
            remoteOnlineStatus = 1,
            remoteErrorStatus = 0,
            localStateStillRecovered = true
        )

        assertFalse(decision.confirmed)
        assertTrue(decision.reason.contains("确认超时"))
    }

    @Test
    fun evaluate_shouldRejectWhenServerDoesNotApplyRecoveryState() {
        val decision = ManualRecoveryCloudConfirmationPolicy.evaluate(
            targetOnlineStatus = 1,
            ackReceived = true,
            ackApplied = false,
            remoteOnlineStatus = 1,
            remoteErrorStatus = 0,
            localStateStillRecovered = true
        )

        assertFalse(decision.confirmed)
        assertTrue(decision.reason.contains("未应用"))
    }

    @Test
    fun evaluate_shouldRejectWhenLocalFaultReturnsBeforeAck() {
        val decision = ManualRecoveryCloudConfirmationPolicy.evaluate(
            targetOnlineStatus = 1,
            ackReceived = true,
            ackApplied = true,
            remoteOnlineStatus = 1,
            remoteErrorStatus = 0,
            localStateStillRecovered = false
        )

        assertFalse(decision.confirmed)
        assertTrue(decision.reason.contains("本机状态再次变化"))
    }
}
