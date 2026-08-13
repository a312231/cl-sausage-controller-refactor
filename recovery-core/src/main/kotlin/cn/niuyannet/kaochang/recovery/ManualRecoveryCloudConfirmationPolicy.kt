package cn.niuyannet.kaochang.recovery

enum class ManualRecoveryCloudCode {
    ACK_TIMEOUT,
    ACK_NOT_APPLIED,
    LOCAL_STATE_CHANGED,
    REMOTE_STATE_MISMATCH,
    HTTP_VERIFICATION_FAILED,
    CONFIRMED
}

object ManualRecoveryCloudConfirmationPolicy {
    data class Decision(
        val code: ManualRecoveryCloudCode,
        val confirmed: Boolean,
        val reason: String
    )

    fun canRetryCloudOnly(
        onlineStatus: Int,
        targetOnlineStatus: Int,
        errorStatus: Int,
        moveStatus: Int,
        isEnable: Int,
        faultPauseActive: Boolean
    ): Boolean =
        onlineStatus == targetOnlineStatus &&
            errorStatus == 0 &&
            moveStatus == 0 &&
            isEnable == 0 &&
            !faultPauseActive

    fun evaluate(
        targetOnlineStatus: Int,
        ackReceived: Boolean,
        ackApplied: Boolean,
        remoteOnlineStatus: Int,
        remoteErrorStatus: Int,
        localStateStillRecovered: Boolean
    ): Decision {
        if (!ackReceived) {
            return Decision(ManualRecoveryCloudCode.ACK_TIMEOUT, false, "云端恢复确认超时")
        }
        if (!ackApplied) {
            return Decision(ManualRecoveryCloudCode.ACK_NOT_APPLIED, false, "云端未应用恢复状态")
        }
        if (!localStateStillRecovered) {
            return Decision(ManualRecoveryCloudCode.LOCAL_STATE_CHANGED, false, "恢复确认期间本机状态再次变化")
        }
        return if (remoteOnlineStatus == targetOnlineStatus && remoteErrorStatus == 0) {
            Decision(ManualRecoveryCloudCode.CONFIRMED, true, "后台已确认恢复")
        } else {
            Decision(
                ManualRecoveryCloudCode.REMOTE_STATE_MISMATCH,
                false,
                "后台仍未恢复：onlineStatus=$remoteOnlineStatus，errorStatus=$remoteErrorStatus"
            )
        }
    }
}
