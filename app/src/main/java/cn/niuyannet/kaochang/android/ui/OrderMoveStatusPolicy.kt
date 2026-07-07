package cn.niuyannet.kaochang.android.ui

internal object OrderMoveStatusPolicy {
    const val MOVE_STATUS_IDLE = 0
    const val MOVE_STATUS_SUPPLEMENTING = 1
    const val MOVE_STATUS_DELIVERING = 2
    const val MOVE_STATUS_PAN_MOVING = 3
    const val MOVE_STATUS_DISCARDING = 4
    const val MOVE_STATUS_SELF_CLEANING = 5

    fun canStartSingleDelivery(status: Int): Boolean =
        status == MOVE_STATUS_IDLE || status == MOVE_STATUS_DELIVERING

    fun canAcceptIncomingOrder(
        moveStatus: Int,
        onlineStatus: Int,
        actionGroupBusy: Boolean
    ): Boolean {
        if (onlineStatus == 4) {
            return false
        }
        if (moveStatus == MOVE_STATUS_IDLE) {
            return !actionGroupBusy
        }
        return moveStatus == MOVE_STATUS_SUPPLEMENTING ||
            moveStatus == MOVE_STATUS_PAN_MOVING ||
            moveStatus == MOVE_STATUS_DISCARDING
    }

    fun shouldWaitForBlockingAction(status: Int): Boolean =
        status == MOVE_STATUS_SUPPLEMENTING ||
            status == MOVE_STATUS_PAN_MOVING ||
            status == MOVE_STATUS_DISCARDING ||
            status == MOVE_STATUS_SELF_CLEANING

    fun shouldNormalizeToDelivering(status: Int): Boolean =
        status == MOVE_STATUS_IDLE

    fun waitTimeoutMsForBlockingAction(status: Int): Long? = when (status) {
        MOVE_STATUS_SUPPLEMENTING -> 240_000L
        MOVE_STATUS_PAN_MOVING -> 90_000L
        MOVE_STATUS_DISCARDING -> 150_000L
        MOVE_STATUS_SELF_CLEANING -> 0L
        else -> null
    }

    fun describe(status: Int): String = when (status) {
        MOVE_STATUS_IDLE -> "0（机械臂空闲）"
        MOVE_STATUS_SUPPLEMENTING -> "1（补肠中）"
        MOVE_STATUS_DELIVERING -> "2（取肠/出餐中）"
        MOVE_STATUS_PAN_MOVING -> "3（烤盘搬移中）"
        MOVE_STATUS_DISCARDING -> "4（丢弃中）"
        MOVE_STATUS_SELF_CLEANING -> "5（自清洁中）"
        else -> "$status（未知）"
    }
}
