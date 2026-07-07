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

        val zones = if (config.modeType == 2 && config.transitionMode == 1) {
            listOf(
                1 to (1..9),
                2 to (13..18),
                2 to (19..21),
                3 to (25..33)
            )
        } else {
            physicalZones
        }

        val appliedPhysicalAreas = mutableSetOf<Int>()
        return zones.mapNotNull { (area, positions) ->
            settleZone(
                area = area,
                positions = positions,
                pans = pans,
                config = config,
                nowMs = nowMs,
                applyKeepWarm = { settledArea ->
                    if (
                        appliedPhysicalAreas.add(settledArea) &&
                        canApplyKeepWarmToPhysicalArea(settledArea, pans, config, nowMs)
                    ) {
                        applyKeepWarm(settledArea)
                    }
                }
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
            pan.status = maturedKeepWarmStatus(pan.positionSn, config)
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

    private fun canApplyKeepWarmToPhysicalArea(
        area: Int,
        pans: List<KaoPan>,
        config: AppConfigBean,
        nowMs: Long
    ): Boolean {
        if (!(area == 2 && config.modeType == 2 && config.transitionMode == 1)) {
            return true
        }
        val physicalZoneBakingPans = pans.filter {
            it.positionSn in 13..21 &&
                it.isHasSausage &&
                it.startTime > 0L
        }
        return physicalZoneBakingPans.all { isMatured(it, config, nowMs) }
    }

    private fun maturedKeepWarmStatus(positionSn: Int, config: AppConfigBean): Int {
        val isLowModeSupplementSource =
            positionSn in 13..18 &&
                (
                    config.modeType != 2 &&
                        config.transitionMode == 0 &&
                        config.highConcurrencyResidualDrain != 1 ||
                        config.modeType == 2 &&
                        config.transitionMode == 1
                    )
        return if (isLowModeSupplementSource || positionSn in 25..33) {
            KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
        } else {
            KaoPan.STATUS_SELLABLE_KEEP_WARM
        }
    }
}
