package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfig
import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan

internal data class AvailableHeatBatch(
    val name: String,
    val remainHeatTimeMs: Long
)

private enum class AvailableProjectionMode {
    LOW,
    LOW_TO_HIGH,
    HIGH
}

internal fun findNextAvailableHeatBatch(
    config: AppConfigBean,
    kaoPanList: List<KaoPan>,
    now: Long
): AvailableHeatBatch? {
    val projectionMode = resolveAvailableProjectionMode(config)
    val frontPans = when (projectionMode) {
        AvailableProjectionMode.LOW,
        AvailableProjectionMode.LOW_TO_HIGH ->
            kaoPanList.filter { it.positionSn in 1..9 }.sortedBy { it.positionSn }
        AvailableProjectionMode.HIGH ->
            kaoPanList.filter { it.positionSn in 1..21 }.sortedBy { it.positionSn }
    }

    val openingBatch = findNextOpeningSellBatch(
        isLowOrTransition = projectionMode != AvailableProjectionMode.HIGH,
        sellArea = frontPans,
        now = now,
        config = config
    )
    if (openingBatch != null) {
        return openingBatch
    }

    val oldLowHeatPans = kaoPanList.filter { it.positionSn in 13..18 }.sortedBy { it.positionSn }
    val oldLowHeatStillPresent = oldLowHeatPans.any { it.isHasSausage }
    val lowHeatBatch = if (projectionMode == AvailableProjectionMode.LOW ||
        projectionMode == AvailableProjectionMode.LOW_TO_HIGH
    ) {
        findNextSupplementHeatBatch(
            isLowOrTransition = true,
            heatArea = oldLowHeatPans,
            now = now,
            config = config
        )
    } else {
        null
    }
    if (projectionMode == AvailableProjectionMode.LOW_TO_HIGH && oldLowHeatStillPresent) {
        return lowHeatBatch
    }

    val highHeatBatch = if (projectionMode == AvailableProjectionMode.HIGH ||
        projectionMode == AvailableProjectionMode.LOW_TO_HIGH
    ) {
        findNextSupplementHeatBatch(
            isLowOrTransition = false,
            heatArea = kaoPanList.filter { it.positionSn in 25..33 }.sortedBy { it.positionSn },
            now = now,
            config = config
        )
    } else {
        null
    }

    return listOfNotNull(lowHeatBatch, highHeatBatch)
        .minByOrNull { it.remainHeatTimeMs }
}

internal fun findAvailableProjectionSellArea(
    config: AppConfigBean,
    kaoPanList: List<KaoPan>
): List<KaoPan> {
    val projectionMode = resolveAvailableProjectionMode(config)
    val range = when (projectionMode) {
        AvailableProjectionMode.LOW,
        AvailableProjectionMode.LOW_TO_HIGH -> 1..9
        AvailableProjectionMode.HIGH -> 1..21
    }
    return kaoPanList.filter { it.positionSn in range }.sortedBy { it.positionSn }
}

internal fun findAvailableProjectionTrackedArea(
    config: AppConfigBean,
    kaoPanList: List<KaoPan>
): List<KaoPan> {
    val projectionMode = resolveAvailableProjectionMode(config)
    val trackedRanges = when (projectionMode) {
        AvailableProjectionMode.LOW -> listOf(1..9, 13..18)
        AvailableProjectionMode.LOW_TO_HIGH -> listOf(1..9, 13..18, 25..33)
        AvailableProjectionMode.HIGH -> listOf(1..21, 25..33)
    }
    return kaoPanList
        .filter { pan -> trackedRanges.any { pan.positionSn in it } }
        .sortedBy { it.positionSn }
}

internal fun findAvailableProjectionHeatArea(
    config: AppConfigBean,
    kaoPanList: List<KaoPan>
): List<KaoPan> {
    val projectionMode = resolveAvailableProjectionMode(config)
    val ranges = when (projectionMode) {
        AvailableProjectionMode.LOW -> listOf(13..18)
        AvailableProjectionMode.LOW_TO_HIGH -> listOf(13..18, 25..33)
        AvailableProjectionMode.HIGH -> listOf(25..33)
    }
    return kaoPanList
        .filter { pan -> ranges.any { pan.positionSn in it } }
        .sortedBy { it.positionSn }
}

private fun resolveAvailableProjectionMode(config: AppConfigBean): AvailableProjectionMode {
    if (config.transitionMode == 1) {
        return AvailableProjectionMode.LOW_TO_HIGH
    }
    return if (TrafficModeHelper.shouldUseExpandedSellRangeForPurchase(
            config.modeType,
            config.transitionMode,
            config.highConcurrencyResidualDrain
        )
    ) {
        AvailableProjectionMode.HIGH
    } else {
        AvailableProjectionMode.LOW
    }
}

internal fun findNextOpeningSellBatch(
    isLowOrTransition: Boolean,
    sellArea: List<KaoPan>,
    now: Long,
    config: AppConfigBean = AppConfig.getAppConfig()
): AvailableHeatBatch? {
    val openingBatches = if (isLowOrTransition) {
        listOf("1~9开盘批次" to sellArea)
    } else {
        listOf(
            "1~9开盘批次" to sellArea.filter { it.positionSn in 1..9 },
            "10~21开盘批次" to sellArea.filter { it.positionSn in 10..21 }
        )
    }
    return openingBatches
        .mapNotNull { (name, pans) ->
            calcAvailableBatchRemainHeatTime(pans, now, config)?.let { remain ->
                AvailableHeatBatch(name = name, remainHeatTimeMs = remain)
            }
        }
        .minByOrNull { it.remainHeatTimeMs }
}

internal fun findNextSupplementHeatBatch(
    isLowOrTransition: Boolean,
    heatArea: List<KaoPan>,
    now: Long,
    config: AppConfigBean = AppConfig.getAppConfig()
): AvailableHeatBatch? {
    val supplementBatches = if (isLowOrTransition) {
        listOf(
            "13~15补肠批次" to heatArea.filter { it.positionSn in 13..15 },
            "16~18补肠批次" to heatArea.filter { it.positionSn in 16..18 }
        )
    } else {
        listOf("25~33补肠批次" to heatArea.filter { it.positionSn in 25..33 })
    }
    return supplementBatches
        .mapNotNull { (name, pans) ->
            calcAvailableBatchRemainHeatTime(pans, now, config)?.let { remain ->
                AvailableHeatBatch(name = name, remainHeatTimeMs = remain)
            }
        }
        .minByOrNull { it.remainHeatTimeMs }
}

private fun calcAvailableBatchRemainHeatTime(
    pans: List<KaoPan>,
    now: Long,
    config: AppConfigBean
): Long? {
    return pans
        .asSequence()
        .filter { it.isHasSausage && it.startTime > 0L && it.holdingTime <= 0L }
        .mapNotNull { pan ->
            val remain = pan.bakingTime - FaultHeatPauseManager.effectiveBakingElapsedMs(pan, config, now)
            remain.takeIf { it > 0L }
        }
        .maxOrNull()
}
