package cn.niuyannet.kaochang.android.services

object DiscardLoopPolicy {
    fun shouldStopAfterDiscard(
        discardSuccess: Boolean,
        onlineStatus: Int,
        errorStatus: Int
    ): Boolean {
        return !discardSuccess || onlineStatus == 3 || errorStatus != 0
    }

    fun stopReason(
        discardSuccess: Boolean,
        onlineStatus: Int,
        errorStatus: Int
    ): String {
        return when {
            !discardSuccess -> "当前烤盘丢弃失败"
            errorStatus != 0 -> "errorStatus（设备故障状态）=$errorStatus（非0=存在故障）"
            onlineStatus == 3 -> "onlineStatus（设备营业状态）=3（维护中）"
            else -> "无需停止"
        }
    }
}
