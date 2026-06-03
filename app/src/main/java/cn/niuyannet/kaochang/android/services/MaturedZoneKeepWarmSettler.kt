package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan

internal object MaturedZoneKeepWarmSettler {
    data class Settlement(
        val area: Int,
        val positions: List<Int>
    )

    private val physicalZones = listOf(
        1 to (1..9),
        2 to (13..21),
        3 to (25..33)
    )

    fun settleMaturedZones(
        pans: List<KaoPan>,
        config: AppConfigBean,
        nowMs: Long,
        applyKeepWarm: (area: Int) -> Unit
    ): List<Settlement> {
        if (config.pendingDiscardPositions.isNotEmpty()) {
            return emptyList()
        }
        if (FaultHeatPauseManager.shouldSkipBakingCompletion(config)) {
            return emptyList()
        }

        return physicalZones.mapNotNull { (area, positions) ->
            settleZone(
                area = area,
                positions = positions,
                pans = pans,
                config = config,
                nowMs = nowMs,
                applyKeepWarm = applyKeepWarm
            )
        }
    }

    private fun settleZone(
        area: Int,
        positions: IntRange,
        pans: List<KaoPan>,
        config: AppConfigBean,
        nowMs: Long,
        applyKeepWarm: (area: Int) -> Unit
    ): Settlement? {
        val zonePans = pans.filter { it.positionSn in positions }
        val bakingPans = zonePans.filter { it.isHasSausage && it.startTime > 0L }
        if (bakingPans.isEmpty()) {
            return null
        }
        if (!bakingPans.all { isMatured(it, config, nowMs) }) {
            return null
        }

        applyKeepWarm(area)
        bakingPans.forEach { pan ->
            pan.startTime = 0L
            pan.holdingTime = nowMs
            pan.status = 2
        }
        return Settlement(
            area = area,
            positions = bakingPans.map { it.positionSn }
        )
    }

    private fun isMatured(
        pan: KaoPan,
        config: AppConfigBean,
        nowMs: Long
    ): Boolean {
        return pan.bakingTime > 0L &&
            FaultHeatPauseManager.effectiveBakingElapsedMs(pan, config, nowMs) >= pan.bakingTime
    }
}
