package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan

internal data class HighToLowEmptyResidualDecision(
    val canClear: Boolean,
    val isHighToLowTransition: Boolean,
    val hasSupplyBoxesAvailable: Boolean,
    val hasFrontSellableSausage: Boolean,
    val highConcurrencyAreaIdle: Boolean,
    val moveStatusIdle: Boolean,
    val notDelivering: Boolean,
    val runtimeStateReliable: Boolean
)

internal object HighToLowEmptyResidualHelper {
    private const val TRANSITION_HIGH_TO_LOW = 2
    private const val ONLINE_STATUS_DELIVERING = 4
    private const val ONLINE_STATUS_MAINTENANCE = 3
    private const val MOVE_STATUS_IDLE = 0

    fun evaluate(
        transitionMode: Int,
        onlineStatus: Int,
        errorStatus: Int,
        moveStatus: Int,
        hasSupplyBoxesAvailable: Boolean,
        kaoPanList: List<KaoPan>
    ): HighToLowEmptyResidualDecision {
        val isHighToLowTransition = transitionMode == TRANSITION_HIGH_TO_LOW
        val hasFrontSellableSausage = kaoPanList
            .filter { it.positionSn in 1..21 }
            .any { pan -> pan.isHasSausage && (pan.holdingTime > 0L || pan.status == 2) }
        val highConcurrencyAreaIdle = kaoPanList
            .filter { it.positionSn in 10..21 || it.positionSn in 25..33 }
            .none { pan ->
                pan.isHasSausage ||
                    pan.isHasGrilling ||
                    pan.startTime > 0L ||
                    pan.holdingTime > 0L ||
                    pan.status != 0
            }
        val moveStatusIdle = moveStatus == MOVE_STATUS_IDLE
        val notDelivering = onlineStatus != ONLINE_STATUS_DELIVERING
        val runtimeStateReliable =
            onlineStatus != ONLINE_STATUS_MAINTENANCE &&
                errorStatus == AppConfigBean.ERROR_STATUS_NORMAL
        val canClear =
            isHighToLowTransition &&
                !hasSupplyBoxesAvailable &&
                !hasFrontSellableSausage &&
                highConcurrencyAreaIdle &&
                moveStatusIdle &&
                notDelivering &&
                runtimeStateReliable

        return HighToLowEmptyResidualDecision(
            canClear = canClear,
            isHighToLowTransition = isHighToLowTransition,
            hasSupplyBoxesAvailable = hasSupplyBoxesAvailable,
            hasFrontSellableSausage = hasFrontSellableSausage,
            highConcurrencyAreaIdle = highConcurrencyAreaIdle,
            moveStatusIdle = moveStatusIdle,
            notDelivering = notDelivering,
            runtimeStateReliable = runtimeStateReliable
        )
    }
}
