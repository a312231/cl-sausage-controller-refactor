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

        val zones = when {
            config.modeType == 2 &&
                (config.transitionMode == 0 || config.transitionMode == 2) -> listOf(
                1 to (1..9),
                2 to (10..21),
                3 to (25..33)
            )
            config.modeType == 2 && config.transitionMode == 1 -> listOf(
                1 to (1..9),
                2 to (13..18),
                2 to (19..21),
                3 to (25..33)
            )
            else -> physicalZones
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

    fun settleMaturedZoneOne(
        pans: List<KaoPan>,
        config: AppConfigBean,
        nowMs: Long,
        applyKeepWarm: (area: Int) -> Boolean
    ): Settlement? {
        if (config.pendingDiscardPositions.isNotEmpty()) return null
        if (FaultHeatPauseManager.shouldSkipBakingCompletion(config)) return null
        val bakingPans = pans.filter { it.positionSn in 1..9 && it.isHasSausage && it.startTime > 0L }
        if (bakingPans.isEmpty() || !bakingPans.all { isMatured(it, config, nowMs) }) return null
        if (!applyKeepWarm(1)) return null
        settlePansAsKeepWarm(bakingPans, config, nowMs)
        return Settlement(area = 1, positions = bakingPans.map { it.positionSn })
    }

    fun settlePreparedZoneOne(
        pans: List<KaoPan>,
        config: AppConfigBean,
        nowMs: Long,
        preparedPositions: List<Int>,
        applyKeepWarm: (area: Int) -> Boolean
    ): Settlement? {
        if (config.pendingDiscardPositions.isNotEmpty()) return null
        if (FaultHeatPauseManager.shouldSkipBakingCompletion(config)) return null
        val normalizedPrepared = preparedPositions.filter { it in 1..9 }.distinct().sorted()
        if (normalizedPrepared.isEmpty()) return null
        val occupiedFront = pans.filter { it.positionSn in 1..9 && it.isHasSausage }
        if (occupiedFront.map { it.positionSn }.sorted() != normalizedPrepared) return null
        if (occupiedFront.any {
                it.status != 1 || it.startTime <= 0L || it.holdingTime > 0L
            }
        ) return null

        if (!applyKeepWarm(1)) return null
        settlePansAsKeepWarm(occupiedFront, config, nowMs)
        return Settlement(area = 1, positions = normalizedPrepared)
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

    private fun settlePansAsKeepWarm(
        pans: List<KaoPan>,
        config: AppConfigBean,
        nowMs: Long
    ) {
        pans.forEach { pan ->
            pan.startTime = 0L
            pan.holdingTime = nowMs
            pan.status = maturedKeepWarmStatus(pan.positionSn, config)
        }
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
