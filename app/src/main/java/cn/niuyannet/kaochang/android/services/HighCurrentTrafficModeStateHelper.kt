package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan

internal data class HighTrafficSupplementTrackingState(
    val pitCount: Int,
    val lastFillPitTime: Long
)

internal data class HighTrafficModeState(
    val modeType: Int,
    val transitionMode: Int
)

internal fun resolveLowToHighTransitionIdleState(
    currentModeType: Int,
    schedulingEnabled: Boolean,
    boxSupplyAvailable: Boolean,
    hasActiveHeatingIn13_18: Boolean,
    hasStartedHighHeatSupplyNow: Boolean
): HighTrafficModeState? {
    val shouldExitTransitionWithoutNewBatch =
        (!boxSupplyAvailable || !schedulingEnabled) &&
            !hasActiveHeatingIn13_18 &&
            !hasStartedHighHeatSupplyNow
    if (!shouldExitTransitionWithoutNewBatch) {
        return null
    }
    return HighTrafficModeState(
        modeType = currentModeType.takeIf { it == 2 } ?: 2,
        transitionMode = 0
    )
}

internal fun resolveLowToHighTransitionCompletionState(
    currentModeType: Int,
    hasOldLowHeatAreaSausage: Boolean,
    hasFullHighHeatSupplyBatchNow: Boolean,
    hasStartedHighHeatSupplyBatchNow: Boolean
): HighTrafficModeState? {
    val hasAnyHighHeatBatchStarted =
        hasFullHighHeatSupplyBatchNow || hasStartedHighHeatSupplyBatchNow
    if (hasOldLowHeatAreaSausage || !hasAnyHighHeatBatchStarted) {
        return null
    }
    return HighTrafficModeState(
        modeType = currentModeType.takeIf { it == 2 } ?: 2,
        transitionMode = 0
    )
}

internal fun recordHighTrafficSupplementLaunch(
    previous: HighTrafficSupplementTrackingState,
    filledCount: Int,
    now: Long
): HighTrafficSupplementTrackingState {
    if (filledCount <= 0) {
        return previous
    }
    return HighTrafficSupplementTrackingState(
        pitCount = filledCount,
        lastFillPitTime = now
    )
}

internal fun resetHighTrafficSupplementTracking(): HighTrafficSupplementTrackingState =
    HighTrafficSupplementTrackingState(
        pitCount = 0,
        lastFillPitTime = 0L
    )

internal fun shouldSwitchHighToLowByTimeout(
    lastFillPitTime: Long,
    now: Long,
    timeThresholdLowMinutes: Int
): Boolean {
    if (lastFillPitTime <= 0L || timeThresholdLowMinutes <= 0) {
        return false
    }
    val thresholdMs = timeThresholdLowMinutes * 60 * 1000L
    return now - lastFillPitTime > thresholdMs
}

internal fun canFinishHighToLowDrain(
    frontSausageCount: Int,
    zone3SausageCount: Int,
    maxFrontSausageCount: Int = 9
): Boolean {
    return frontSausageCount <= maxFrontSausageCount && zone3SausageCount == 0
}

internal fun clearEmptyHighConcurrencyResidualMetadata(
    kaoPanList: List<KaoPan>
): List<Int> {
    val clearedPositions = mutableListOf<Int>()
    kaoPanList
        .filter { pan ->
            (pan.positionSn in 10..21 || pan.positionSn in 25..33) &&
                !pan.isHasSausage &&
                (pan.isHasGrilling ||
                    pan.startTime > 0L ||
                    pan.holdingTime > 0L ||
                    pan.status != 0 ||
                    pan.temperature != 0)
        }
        .forEach { pan ->
            pan.isHasGrilling = false
            pan.startTime = 0L
            pan.holdingTime = 0L
            pan.status = 0
            pan.temperature = 0
            clearedPositions += pan.positionSn
        }
    return clearedPositions.sorted()
}
