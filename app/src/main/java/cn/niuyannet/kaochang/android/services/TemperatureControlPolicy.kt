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

    data class ActiveBatchTemperatureDemand(
        val pendingSupplementPositions: Set<Int> = emptySet(),
        val pendingReturnPositions: Set<Int> = emptySet()
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
        pendingDiscardPositions: Collection<Int> = emptyList(),
        pendingSupplementPositions: Collection<Int> = emptyList(),
        pendingReturnPositions: Collection<Int> = emptyList()
    ): List<ZoneTemperatureDecision> {
        return zoneDefinitions.map { (area, positions) ->
            val zonePans = pans.filter {
                it.positionSn in positions && it.positionSn !in pendingDiscardPositions
            }
            val mode = when {
                pendingSupplementPositions.any { it in positions } -> ZoneMode.HEATING
                zonePans.any { it.isHasSausage && it.status == 1 } -> ZoneMode.HEATING
                pendingReturnPositions.any { it in positions } -> ZoneMode.KEEP_WARM
                zonePans.any {
                    it.isHasSausage &&
                        (it.status == KaoPan.STATUS_SELLABLE_KEEP_WARM || it.holdingTime > 0L)
                } -> ZoneMode.KEEP_WARM
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

    fun projectBatchIntent(intent: SupplementBatchIntent?): ActiveBatchTemperatureDemand {
        if (intent == null) {
            return ActiveBatchTemperatureDemand()
        }
        val retainedReturnTargets = intent.retainedCompletedHeatReturnTargets()
        return ActiveBatchTemperatureDemand(
            pendingSupplementPositions = intent.remainingTargets()
                .map { it.positionSn }
                .filterTo(mutableSetOf(), ::isPhysicalZonePosition),
            pendingReturnPositions = retainedReturnTargets.flatMapTo(mutableSetOf()) { target ->
                listOfNotNull(target.positionSn, target.returnPositionSn)
            }.filterTo(mutableSetOf(), ::isPhysicalZonePosition)
        )
    }

    fun hasAnySausageInPhysicalPans(pans: List<KaoPan>): Boolean {
        return pans.any { it.positionSn in 1..33 && it.isHasSausage }
    }

    private fun isPhysicalZonePosition(position: Int): Boolean =
        zoneDefinitions.any { (_, positions) -> position in positions }

    fun decideEmptyPanStop(
        hasAnySausage: Boolean,
        hasPendingSupplementTarget: Boolean = false,
        previousHasAnySausage: Boolean?,
        force: Boolean
    ): EmptyPanStopDecision {
        if (hasAnySausage) {
            return EmptyPanStopDecision(
                shouldStopAllHeating = false,
                nextHasAnySausage = true
            )
        }
        if (force) {
            return EmptyPanStopDecision(
                shouldStopAllHeating = true,
                nextHasAnySausage = false
            )
        }
        if (hasPendingSupplementTarget) {
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
