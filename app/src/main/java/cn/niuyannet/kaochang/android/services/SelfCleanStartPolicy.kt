package cn.niuyannet.kaochang.android.services

object SelfCleanStartPolicy {
    fun canStart(
        onlineStatus: Int,
        moveStatus: Int,
        orderPending: Boolean,
        errorStatus: Int,
        inspectionMode: Int
    ): Boolean {
        return onlineStatus != 1 &&
            onlineStatus != 4 &&
            moveStatus == 0 &&
            !orderPending &&
            errorStatus == 0 &&
            inspectionMode == 0
    }
}
