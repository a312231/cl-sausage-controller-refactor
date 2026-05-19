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

internal data class LowToHighPartialWaitLogSnapshot(
    val oldLowHeatPositions: List<Int>,
    val partialHighHeatPositions: List<Int>,
    val boxSupplyAvailable: Boolean,
    val schedulingEnabled: Boolean,
    val errorStatus: Int
)

internal data class OldLowHeatReturnGroup(
    val start: Int,
    val end: Int,
    val groupName: String,
    val maxStartTime: Long
)

private data class OldLowHeatRange(
    val start: Int,
    val end: Int,
    val groupName: String
)

private val oldLowHeatRanges = listOf(
    OldLowHeatRange(start = 13, end = 15, groupName = "13~15"),
    OldLowHeatRange(start = 16, end = 18, groupName = "16~18")
)

internal fun createLowToHighPartialWaitLogSnapshot(
    oldLowHeatPans: List<KaoPan>,
    partialHighHeatPans: List<KaoPan>,
    boxSupplyAvailable: Boolean,
    schedulingEnabled: Boolean,
    errorStatus: Int
): LowToHighPartialWaitLogSnapshot =
    LowToHighPartialWaitLogSnapshot(
        oldLowHeatPositions = oldLowHeatPans
            .filter { it.isHasSausage }
            .map { it.positionSn }
            .sorted(),
        partialHighHeatPositions = partialHighHeatPans
            .filter { it.isHasSausage }
            .map { it.positionSn }
            .sorted(),
        boxSupplyAvailable = boxSupplyAvailable,
        schedulingEnabled = schedulingEnabled,
        errorStatus = errorStatus
    )

internal fun shouldLogLowToHighPartialWait(
    previous: LowToHighPartialWaitLogSnapshot?,
    current: LowToHighPartialWaitLogSnapshot
): Boolean = previous != current

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
    hasStartedHighHeatSupplyBatchNow: Boolean,
    hasReturnedHighHeatSupplyBatch: Boolean = false
): HighTrafficModeState? {
    val hasAnyHighHeatBatchStarted =
        hasFullHighHeatSupplyBatchNow || hasStartedHighHeatSupplyBatchNow || hasReturnedHighHeatSupplyBatch
    if (hasOldLowHeatAreaSausage || !hasAnyHighHeatBatchStarted || !hasReturnedHighHeatSupplyBatch) {
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

internal fun shouldResumeIncompleteHighHeatBatch(
    pitCount: Int,
    hasIncompleteBatch: Boolean
): Boolean {
    return hasIncompleteBatch && pitCount <= 0
}

internal fun canReturnHighHeatBatchToFront(
    frontEmptyCount: Int,
    batchSize: Int
): Boolean {
    return batchSize > 0 && frontEmptyCount >= batchSize
}

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
    maxFrontSausageCount: Int = 9,
    hasUnreadySausage: Boolean = false
): Boolean {
    return frontSausageCount <= maxFrontSausageCount &&
        zone3SausageCount == 0 &&
        !hasUnreadySausage
}

internal fun hasUnreadySausage(kaoPanList: List<KaoPan>): Boolean {
    return kaoPanList.any { pan ->
        pan.isHasSausage && (pan.startTime > 0L || pan.status == 1)
    }
}

internal fun hasRealOldLowHeatLegacyBatch(
    oldLowHeatPans: List<KaoPan>,
    frontPans: List<KaoPan>
): Boolean {
    return oldLowHeatPans
        .filter { it.positionSn in 13..18 && it.isHasSausage }
        .any { pan ->
            val isBaking = pan.startTime > 0L && pan.status != 2
            isBaking || hasReservedFrontReturnTarget(pan, frontPans)
        }
}

internal fun planOldLowHeatReturnGroups(
    oldLowHeatPans: List<KaoPan>,
    nowMs: Long
): List<OldLowHeatReturnGroup> {
    return oldLowHeatRanges
        .mapNotNull { range ->
            val sourcePans = oldLowHeatPans
                .filter { it.positionSn in range.start..range.end && it.isHasSausage }
            if (sourcePans.isEmpty()) {
                return@mapNotNull null
            }
            val hasUnfinished = sourcePans.any { pan ->
                pan.startTime > 0L &&
                    (nowMs - pan.startTime).coerceAtLeast(0L) < pan.bakingTime
            }
            if (hasUnfinished) {
                return@mapNotNull null
            }
            OldLowHeatReturnGroup(
                start = range.start,
                end = range.end,
                groupName = range.groupName,
                maxStartTime = sourcePans.maxOf { it.startTime }
            )
        }
        .sortedWith(compareBy<OldLowHeatReturnGroup> { it.maxStartTime }.thenBy { it.start })
}

internal fun resolveStaleLowToHighTransitionState(
    currentModeType: Int,
    currentTransitionMode: Int,
    hasRealLowHeatLegacyBatch: Boolean,
    hasHighHeatSupplyInProgress: Boolean
): HighTrafficModeState? {
    if (currentModeType != 2 || currentTransitionMode != 1) {
        return null
    }
    if (hasRealLowHeatLegacyBatch || hasHighHeatSupplyInProgress) {
        return null
    }
    return HighTrafficModeState(
        modeType = 2,
        transitionMode = 0
    )
}

private fun hasReservedFrontReturnTarget(
    sourcePan: KaoPan,
    frontPans: List<KaoPan>
): Boolean {
    val tasteCode = sourcePan.taste?.tasteCode?.takeIf { it.isNotBlank() } ?: return false
    return frontPans.any { pan ->
        pan.positionSn in 1..9 &&
            !pan.isHasSausage &&
            pan.isHasGrilling &&
            pan.taste?.tasteCode == tasteCode
    }
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
