package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.KaoPanHelper
import cn.niuyannet.kaochang.android.model.bean.KaoPan

object SellablePanPhysicalStateProjection {
    fun sellablePositions(
        pans: List<KaoPan>,
        config: AppConfigBean,
        nowMs: Long = System.currentTimeMillis()
    ): List<Int> {
        val sellEnd = TrafficModeHelper.purchaseSellEnd(
            config.modeType,
            config.transitionMode,
            config.highConcurrencyResidualDrain
        )
        return pans.asSequence()
            .filter { it.positionSn in 1..sellEnd }
            .filter { KaoPanHelper.isSellable(it, config.pendingDiscardPositions, nowMs) }
            .map { it.positionSn }
            .distinct()
            .sorted()
            .toList()
    }

    fun rulesSignature(config: AppConfigBean): String {
        val pendingDiscardPositions = config.pendingDiscardPositions.toSortedSet().joinToString(",")
        return "${config.modeType}|${config.transitionMode}|${config.highConcurrencyResidualDrain}|$pendingDiscardPositions"
    }
}
