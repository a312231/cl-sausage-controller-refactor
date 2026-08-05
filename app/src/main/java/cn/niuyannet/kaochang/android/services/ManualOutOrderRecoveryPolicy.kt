package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupSessionType

/** 仅允许无正在执行任务的持久化手动取肠会话进入订单前恢复。 */
internal object ManualOutOrderRecoveryPolicy {
    fun shouldRecoverBeforeOrder(
        moveStatus: Int,
        onlineStatus: Int,
        errorStatus: Int,
        inspectionMode: Int,
        sessionType: ActionGroupSessionType?,
        manualTakeTaskActive: Boolean,
        recoveryReservedForOrder: Boolean = false
    ): Boolean {
        return moveStatus == 2 &&
            (onlineStatus == 1 || (onlineStatus == 4 && recoveryReservedForOrder)) &&
            errorStatus == 0 &&
            inspectionMode == 0 &&
            sessionType == ActionGroupSessionType.OUT_MANUAL &&
            !manualTakeTaskActive
    }
}
