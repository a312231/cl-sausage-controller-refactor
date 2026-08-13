package cn.niuyannet.kaochang.recovery

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualRecoveryPoliciesTest {

    @Test
    fun gate_shouldReturnStableCodeForEveryBranch() {
        val cases = listOf(
            Triple(
                ManualRecoveryGateCode.MODBUS_DISCONNECTED,
                "下位机串口未连接",
                gate(modbusConnected = false)
            ),
            Triple(
                ManualRecoveryGateCode.ACTION_GROUP_NOT_READY,
                "300/400动作组恢复检查未通过",
                gate(actionGroupReady = false)
            ),
            Triple(
                ManualRecoveryGateCode.LEGACY_ACTION_NOT_IDLE,
                "201（旧动作状态寄存器）=1（非0=旧协议动作未空闲）",
                gate(legacyActionStatus201 = 1)
            ),
            Triple(
                ManualRecoveryGateCode.PAN_MOVE_NOT_CONFIRMED,
                "moveStatus（机械动作状态）=3（烤盘搬移中），203（烤盘搬移结果寄存器）=1（非0=未确认完成）",
                gate(moveStatus = 3, panMoveResult203 = 1)
            ),
            Triple(
                ManualRecoveryGateCode.ALLOWED,
                "恢复到 onlineStatus（设备营业状态）=1（运营中），允许恢复设备状态，温控保持关闭等待人工开启算法",
                gate()
            ),
            Triple(
                ManualRecoveryGateCode.ALLOWED,
                "恢复到 onlineStatus（设备营业状态）=2（非运营中），允许恢复设备状态，温控保持关闭等待人工开启算法",
                gate(targetOnlineStatus = 2)
            )
        )
        cases.forEach { (code, reason, decision) ->
            assertEquals(code, decision.code)
            assertEquals(reason, decision.reason)
        }
    }

    @Test
    fun gate_shouldExposeDedicatedCodeForAppResidueCloseoutFailure() {
        val decision = ManualRecoveryGatePolicy.Decision(
            code = ManualRecoveryGateCode.ACTION_GROUP_RESIDUE_CLOSEOUT_FAILED,
            canRecoverDeviceStatus = false,
            reason = "residue"
        )
        assertEquals(ManualRecoveryGateCode.ACTION_GROUP_RESIDUE_CLOSEOUT_FAILED, decision.code)
    }

    @Test
    fun cloud_shouldReturnStableCodeForEveryAckBranch() {
        val cases = listOf(
            Triple(ManualRecoveryCloudCode.ACK_TIMEOUT, "云端恢复确认超时", cloud(ackReceived = false)),
            Triple(ManualRecoveryCloudCode.ACK_NOT_APPLIED, "云端未应用恢复状态", cloud(ackApplied = false)),
            Triple(
                ManualRecoveryCloudCode.LOCAL_STATE_CHANGED,
                "恢复确认期间本机状态再次变化",
                cloud(localStateStillRecovered = false)
            ),
            Triple(
                ManualRecoveryCloudCode.REMOTE_STATE_MISMATCH,
                "后台仍未恢复：onlineStatus=3，errorStatus=2",
                cloud(remoteOnlineStatus = 3, remoteErrorStatus = 2)
            ),
            Triple(ManualRecoveryCloudCode.CONFIRMED, "后台已确认恢复", cloud())
        )
        cases.forEach { (code, reason, decision) ->
            assertEquals(code, decision.code)
            assertEquals(reason, decision.reason)
        }
    }

    @Test
    fun cloud_shouldExposeDedicatedHttpVerificationCodeForAppAdapter() {
        val decision = ManualRecoveryCloudConfirmationPolicy.Decision(
            code = ManualRecoveryCloudCode.HTTP_VERIFICATION_FAILED,
            confirmed = false,
            reason = "http"
        )
        assertEquals(ManualRecoveryCloudCode.HTTP_VERIFICATION_FAILED, decision.code)
    }

    @Test
    fun cloudRetry_shouldRequireExactLocallyRecoveredIdleState() {
        assertEquals(
            true,
            ManualRecoveryCloudConfirmationPolicy.canRetryCloudOnly(1, 1, 0, 0, 0, false)
        )
        listOf(
            ManualRecoveryCloudConfirmationPolicy.canRetryCloudOnly(4, 1, 0, 0, 0, false),
            ManualRecoveryCloudConfirmationPolicy.canRetryCloudOnly(1, 1, 1, 0, 0, false),
            ManualRecoveryCloudConfirmationPolicy.canRetryCloudOnly(1, 1, 0, 2, 0, false),
            ManualRecoveryCloudConfirmationPolicy.canRetryCloudOnly(1, 1, 0, 0, 1, false),
            ManualRecoveryCloudConfirmationPolicy.canRetryCloudOnly(1, 1, 0, 0, 0, true)
        ).forEach { assertEquals(false, it) }
    }

    private fun gate(
        modbusConnected: Boolean = true,
        actionGroupReady: Boolean = true,
        legacyActionStatus201: Int = 0,
        moveStatus: Int = 0,
        panMoveResult203: Int? = null,
        targetOnlineStatus: Int = 1
    ) = ManualRecoveryGatePolicy.evaluate(
        modbusConnected = modbusConnected,
        actionGroupReady = actionGroupReady,
        legacyActionStatus201 = legacyActionStatus201,
        moveStatus = moveStatus,
        panMoveResult203 = panMoveResult203,
        targetOnlineStatus = targetOnlineStatus
    )

    private fun cloud(
        ackReceived: Boolean = true,
        ackApplied: Boolean = true,
        remoteOnlineStatus: Int = 1,
        remoteErrorStatus: Int = 0,
        localStateStillRecovered: Boolean = true
    ) = ManualRecoveryCloudConfirmationPolicy.evaluate(
        targetOnlineStatus = 1,
        ackReceived = ackReceived,
        ackApplied = ackApplied,
        remoteOnlineStatus = remoteOnlineStatus,
        remoteErrorStatus = remoteErrorStatus,
        localStateStillRecovered = localStateStillRecovered
    )
}
