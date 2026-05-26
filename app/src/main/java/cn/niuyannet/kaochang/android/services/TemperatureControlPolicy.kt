package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan

object TemperatureControlPolicy {
    enum class ZoneMode {
        HEATING,
        KEEP_WARM,
        STOP
    }

    data class ZoneTemperatureDecision(
        val area: Int,
        val positions: IntRange,
        val mode: ZoneMode,
        val targetTemperature: Int
    )

    data class EmptyPanStopDecision(
        val shouldStopAllHeating: Boolean,
        val nextHasAnySausage: Boolean
    )

    private val zoneDefinitions = listOf(
        1 to (1..9),
        2 to (13..21),
        3 to (25..33)
    )

    fun decideZoneTemperatures(
        pans: List<KaoPan>,
        keepWarmTemperature: Int,
        heatingTemperature: Int,
        heatingOffsetsByArea: Map<Int, Int> = emptyMap(),
        pendingDiscardPositions: Collection<Int> = emptyList()
    ): List<ZoneTemperatureDecision> {
        return zoneDefinitions.map { (area, positions) ->
            val zonePans = pans.filter {
                it.positionSn in positions && it.positionSn !in pendingDiscardPositions
            }
            val mode = when {
                zonePans.any { it.isHasSausage && it.status == 1 } -> ZoneMode.HEATING
                zonePans.any { it.isHasSausage && (it.status == 2 || it.holdingTime > 0L) } -> ZoneMode.KEEP_WARM
                else -> ZoneMode.STOP
            }
            ZoneTemperatureDecision(
                area = area,
                positions = positions,
                mode = mode,
                targetTemperature = when (mode) {
                    ZoneMode.HEATING -> FourFTemperaturePolicy.clampTargetTemperature(
                        heatingTemperature + (heatingOffsetsByArea[area] ?: 0)
                    )
                    ZoneMode.KEEP_WARM -> FourFTemperaturePolicy.clampTargetTemperature(keepWarmTemperature)
                    ZoneMode.STOP -> 0
                }
            )
        }
    }

    fun hasAnySausageInPhysicalPans(pans: List<KaoPan>): Boolean {
        return pans.any { it.positionSn in 1..33 && it.isHasSausage }
    }

    fun decideEmptyPanStop(
        hasAnySausage: Boolean,
        previousHasAnySausage: Boolean?,
        force: Boolean
    ): EmptyPanStopDecision {
        if (hasAnySausage) {
            return EmptyPanStopDecision(
                shouldStopAllHeating = false,
                nextHasAnySausage = true
            )
        }

        return EmptyPanStopDecision(
            shouldStopAllHeating = force || previousHasAnySausage != false,
            nextHasAnySausage = false
        )
    }

    fun shouldRestoreHeatingAfterStartup(
        hasRestored: Boolean,
        modbusConnected: Boolean
    ): Boolean {
        return !hasRestored && modbusConnected
    }
}
