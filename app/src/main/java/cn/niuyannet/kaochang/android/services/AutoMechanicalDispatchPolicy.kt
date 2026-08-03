package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean

data class AutoMechanicalDispatchDecision(
    val canStart: Boolean,
    val reason: String
)

/**
 * 自动机械调度门控。
 *
 * 订单出餐期间，自动补肠、烤盘搬移、过保丢弃、自清洁都必须暂停，
 * 避免旧协议搬盘或补肠动作插入 out1~out5 出餐链路。
 */
object AutoMechanicalDispatchPolicy {
    private const val ONLINE_STATUS_OPERATING = 1
    private const val ONLINE_STATUS_DELIVERING = 4
    private const val MOVE_STATUS_IDLE = 0
    private const val MOVE_STATUS_DELIVERING = 2

    @Suppress("UNUSED_PARAMETER")
    fun evaluate(
        onlineStatus: Int,
        errorStatus: Int,
        inspectionMode: Int,
        moveStatus: Int,
        lowerPlatformStatus: String = AppConfigBean.LOWER_PLATFORM_STATUS_NORMAL,
        lowerPlatformIsolationActionName: String? = "",
        orderPending: Boolean = false
    ): AutoMechanicalDispatchDecision {
        return when {
            orderPending -> AutoMechanicalDispatchDecision(
                canStart = false,
                reason = "订单待出餐，暂停自动补肠/搬盘/丢弃/自清洁"
            )
            onlineStatus == ONLINE_STATUS_DELIVERING -> AutoMechanicalDispatchDecision(
                canStart = false,
                reason = "订单出餐中，暂停自动补肠/搬盘/丢弃/自清洁"
            )
            onlineStatus != ONLINE_STATUS_OPERATING -> AutoMechanicalDispatchDecision(
                canStart = false,
                reason = "设备非运营中，暂停自动机械调度：onlineStatus（设备营业状态）=$onlineStatus"
            )
            errorStatus != AppConfigBean.ERROR_STATUS_NORMAL -> AutoMechanicalDispatchDecision(
                canStart = false,
                reason = "设备存在故障，暂停自动机械调度：errorStatus（设备故障状态）=$errorStatus"
            )
            inspectionMode != AppConfigBean.INSPECTION_MODE_NORMAL -> AutoMechanicalDispatchDecision(
                canStart = false,
                reason = "设备处于检修模式，暂停自动机械调度：inspectionMode（检修模式）=$inspectionMode"
            )
            moveStatus != MOVE_STATUS_IDLE -> AutoMechanicalDispatchDecision(
                canStart = false,
                reason = "机械动作未空闲，暂停自动机械调度：moveStatus（机械动作状态）=$moveStatus"
            )
            else -> AutoMechanicalDispatchDecision(
                canStart = true,
                reason = "设备运营中且机械空闲，允许自动机械调度"
            )
        }
    }

    fun shouldPreserveDeliveringMoveStatusDuringRecovery(
        onlineStatus: Int,
        moveStatus: Int
    ): Boolean {
        return onlineStatus == ONLINE_STATUS_DELIVERING && moveStatus == MOVE_STATUS_DELIVERING
    }

    fun canIssueAutomaticCommandAfterClaim(
        onlineStatus: Int,
        errorStatus: Int,
        inspectionMode: Int,
        isEnable: Int,
        faultHeatPauseActive: Int,
        orderPending: Boolean,
        lowerPlatformStatus: String?,
        lowerPlatformIsolationActionName: String?
    ): Boolean {
        return !orderPending &&
            onlineStatus == ONLINE_STATUS_OPERATING &&
            errorStatus == AppConfigBean.ERROR_STATUS_NORMAL &&
            inspectionMode == AppConfigBean.INSPECTION_MODE_NORMAL &&
            isEnable == 1 &&
            faultHeatPauseActive == 0 &&
            !shouldBlockAutoDispatchForLowerPlatformIsolation(
                lowerPlatformStatus,
                lowerPlatformIsolationActionName
            )
    }

    fun shouldBlockAutoDispatchForLowerPlatformIsolation(
        lowerPlatformStatus: String?,
        lowerPlatformIsolationActionName: String?
    ): Boolean {
        if (lowerPlatformStatus != AppConfigBean.LOWER_PLATFORM_STATUS_ISOLATED) {
            return false
        }
        return lowerPlatformIsolationActionName in setOf("in4", "in5")
    }
}
