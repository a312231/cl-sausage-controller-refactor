package cn.niuyannet.kaochang.android.services

object ManualRecoveryCloudConfirmationPolicy {
    data class Decision(
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
            return Decision(false, "云端恢复确认超时")
        }
        if (!ackApplied) {
            return Decision(false, "云端未应用恢复状态")
        }
        if (!localStateStillRecovered) {
            return Decision(false, "恢复确认期间本机状态再次变化")
        }
        return if (remoteOnlineStatus == targetOnlineStatus && remoteErrorStatus == 0) {
            Decision(true, "后台已确认恢复")
        } else {
            Decision(
                false,
                "后台仍未恢复：onlineStatus=$remoteOnlineStatus，errorStatus=$remoteErrorStatus"
            )
        }
    }
}
