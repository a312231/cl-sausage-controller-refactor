package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste

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

internal data class OldLowHeatReturnReservation(
    val positionSn: Int,
    val tasteCode: String?
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
    filledCount: Int
): HighTrafficSupplementTrackingState {
    if (filledCount <= 0) {
        return previous
    }
    return HighTrafficSupplementTrackingState(
        pitCount = filledCount,
        lastFillPitTime = previous.lastFillPitTime
    )
}

internal fun recordHighTrafficSupplementLaunchOutcome(
    previous: HighTrafficSupplementTrackingState,
    requestedTargetCount: Int,
    movedCount: Int
): HighTrafficSupplementTrackingState {
    if (movedCount <= 0) {
        return previous
    }
    if (requestedTargetCount > 0 && movedCount < requestedTargetCount) {
        return HighTrafficSupplementTrackingState(
            pitCount = 0,
            lastFillPitTime = previous.lastFillPitTime
        )
    }
    return HighTrafficSupplementTrackingState(
        pitCount = movedCount,
        lastFillPitTime = previous.lastFillPitTime
    )
}

internal fun resetHighTrafficSupplementTracking(): HighTrafficSupplementTrackingState =
    HighTrafficSupplementTrackingState(
        pitCount = 0,
        lastFillPitTime = 0L
    )

internal fun shouldResumeIncompleteHighHeatBatch(
    pitCount: Int,
    hasIncompleteBatch: Boolean,
    persistedBatchState: PersistedHighHeatBatchState
): Boolean {
    return hasIncompleteBatch &&
        pitCount <= 0 &&
        persistedBatchState == PersistedHighHeatBatchState.REMAINING
}

internal enum class PersistedHighHeatBatchState {
    COMPLETED_OR_TERMINAL,
    REMAINING,
    UNTRUSTED
}

internal fun shouldBlockPendingHighHeatRecovery(
    activeIntent: SupplementBatchIntent?,
    persistedBatchState: PersistedHighHeatBatchState
): Boolean {
    val remainingTargets = activeIntent?.remainingTargets().orEmpty()
    val hasPendingHighHeatBatch = remainingTargets.isNotEmpty() &&
        (
            activeIntent?.type == SupplementBatchType.HIGH_HEAT_25_33 ||
                remainingTargets.any {
                    it.resolvedOriginBatchType() == SupplementBatchType.HIGH_HEAT_25_33
                }
            )
    return hasPendingHighHeatBatch && persistedBatchState == PersistedHighHeatBatchState.UNTRUSTED
}

internal fun classifyPersistedHighHeatBatch(
    activeIntent: SupplementBatchIntent?,
    highHeatPans: List<KaoPan>
): PersistedHighHeatBatchState {
    val occupiedSourcePositions = highHeatPans
        .filter { it.positionSn in 25..33 && it.isHasSausage }
        .map { it.positionSn }
        .toSet()
    val intent = activeIntent ?: return PersistedHighHeatBatchState.UNTRUSTED

    val highHeatTargets = intent.targets
        .filter {
            it.resolvedOriginBatchType() == SupplementBatchType.HIGH_HEAT_25_33
        }
    if (highHeatTargets.isEmpty()) {
        return PersistedHighHeatBatchState.UNTRUSTED
    }
    if (
        intent.type == SupplementBatchType.HIGH_HEAT_25_33 &&
        intent.remainingTargets().any {
            it.resolvedOriginBatchType() != SupplementBatchType.HIGH_HEAT_25_33
        }
    ) {
        return PersistedHighHeatBatchState.UNTRUSTED
    }

    val targetPositions = highHeatTargets.map { it.positionSn }
    val returnPositions = highHeatTargets.map { it.returnPositionSn }
    val hasConflictingTerminalState = highHeatTargets.any {
        (it.completed && (it.skippedBySupplyDegrade || it.cancelledByFaultTimeout)) ||
            (it.skippedBySupplyDegrade && it.cancelledByFaultTimeout)
    }
    val activeHeatReturnPositions = intent.targets
        .filter {
            !it.skippedBySupplyDegrade &&
                !it.cancelledByFaultTimeout &&
                it.returnPositionSn != null &&
                SupplementBatchIntentRepository.isHeatSupplementBatch(
                    it.resolvedOriginBatchType() ?: SupplementBatchType.UNKNOWN
                )
        }
        .mapNotNull { it.returnPositionSn }
    if (
        targetPositions.any { it !in 25..33 } ||
        targetPositions.toSet().size != targetPositions.size ||
        returnPositions.any { it == null || it !in 1..21 } ||
        returnPositions.filterNotNull().toSet().size != returnPositions.size ||
        activeHeatReturnPositions.toSet().size != activeHeatReturnPositions.size ||
        hasConflictingTerminalState
    ) {
        return PersistedHighHeatBatchState.UNTRUSTED
    }

    val completedSourcePositions = highHeatTargets
        .filter { it.completed }
        .map { it.positionSn }
        .toSet()
    val remainingSourcePositions = highHeatTargets
        .filter { !it.completed && !it.skippedBySupplyDegrade && !it.cancelledByFaultTimeout }
        .map { it.positionSn }
        .toSet()
    val unrecordedOccupiedPositions = occupiedSourcePositions - completedSourcePositions
    if (
        !occupiedSourcePositions.containsAll(completedSourcePositions) ||
        !remainingSourcePositions.containsAll(unrecordedOccupiedPositions)
    ) {
        return PersistedHighHeatBatchState.UNTRUSTED
    }

    return if (remainingSourcePositions.isNotEmpty()) {
        PersistedHighHeatBatchState.REMAINING
    } else {
        PersistedHighHeatBatchState.COMPLETED_OR_TERMINAL
    }
}

internal fun shouldSwitchHighHeatAreaToKeepWarm(
    pans: List<KaoPan>,
    area: Int,
    config: AppConfigBean,
    nowMs: Long,
    requireCompleteZone3Batch: Boolean = true
): Boolean {
    if (area == 3 && requireCompleteZone3Batch && HighHeatSupplementResumePlanner.hasIncompleteBatch(pans)) {
        return false
    }
    var heatingCount = 0
    pans.forEach { pan ->
        if (pan.isHasSausage && pan.startTime > 0L) {
            heatingCount += 1
            if (FaultHeatPauseManager.effectiveBakingElapsedMs(pan, config, nowMs) < pan.bakingTime) {
                return false
            }
        }
    }
    return heatingCount > 0
}

internal fun resolveAutomaticTrafficTransition(
    currentModeType: Int,
    currentTransitionMode: Int,
    requestedTransitionMode: Int
): HighTrafficModeState? {
    return when (requestedTransitionMode) {
        1 -> {
            if (currentModeType == 1 && currentTransitionMode == 0) {
                HighTrafficModeState(modeType = 2, transitionMode = 1)
            } else {
                null
            }
        }
        2 -> {
            if (currentModeType == 2 && currentTransitionMode == 0) {
                HighTrafficModeState(modeType = 2, transitionMode = 2)
            } else {
                null
            }
        }
        else -> null
    }
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

internal fun canArmHighToLowTimer(
    modeType: Int,
    transitionMode: Int,
    highHeatZoneClear: Boolean,
    hasPendingHighHeatEvidence: Boolean,
    recoveryBlocked: Boolean
): Boolean = modeType == 2 &&
    transitionMode == 0 &&
    highHeatZoneClear &&
    !hasPendingHighHeatEvidence &&
    !recoveryBlocked

internal fun shouldReuseHighToLowTimerAnchor(
    timerStateRestoredInProcess: Boolean,
    anchorAtMs: Long,
    recoveryBlocked: Boolean
): Boolean = timerStateRestoredInProcess && anchorAtMs > 0L && !recoveryBlocked

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
            val waitingReturn = pan.status == KaoPan.STATUS_WAITING_RETURN_KEEP_WARM &&
                pan.holdingTime > 0L &&
                pan.startTime <= 0L
            val isBaking = pan.startTime > 0L && pan.status != KaoPan.STATUS_SELLABLE_KEEP_WARM
            waitingReturn || isBaking || hasReservedFrontReturnTarget(pan, frontPans)
        }
}

internal fun collectProtectedOldLowHeatReturnPositions(
    frontPans: List<KaoPan>,
    oldLowHeatPans: List<KaoPan>,
    activeIntent: SupplementBatchIntent?
): Set<Int> {
    val protectedPositions = linkedSetOf<Int>()
    collectOldLowHeatReturnReservations(
        frontPans = frontPans,
        oldLowHeatPans = oldLowHeatPans,
        activeIntent = activeIntent
    ).forEach { reservation ->
        val frontPan = frontPans.firstOrNull {
            it.positionSn == reservation.positionSn &&
                it.positionSn in 1..9 &&
                !it.isHasSausage
        } ?: return@forEach
        restoreOldLowHeatReturnReservation(frontPan, reservation.tasteCode)
        protectedPositions += frontPan.positionSn
    }
    return protectedPositions
}

internal fun collectOldLowHeatReturnReservations(
    frontPans: List<KaoPan>,
    oldLowHeatPans: List<KaoPan>,
    activeIntent: SupplementBatchIntent?
): List<OldLowHeatReturnReservation> {
    val reservations = linkedMapOf<Int, OldLowHeatReturnReservation>()
    val activeOldLowHeatByPosition = oldLowHeatPans
        .filter { it.positionSn in 13..18 && it.isHasSausage }
        .associateBy { it.positionSn }

    activeIntent
        ?.targets
        ?.filter { target ->
            isOldLowHeatIntentTarget(activeIntent.type, target) &&
                !target.cancelledByFaultTimeout &&
                !target.skippedBySupplyDegrade &&
                target.returnPositionSn in 1..9
        }
        ?.forEach { target ->
            val tasteCode = target.tasteCode?.takeIf { it.isNotBlank() }
                ?: activeOldLowHeatByPosition[target.positionSn]?.taste?.tasteCode?.takeIf { it.isNotBlank() }
            val frontPan = frontPans.firstOrNull {
                it.positionSn == target.returnPositionSn &&
                    it.positionSn in 1..9 &&
                    !it.isHasSausage
            } ?: return@forEach
            reservations[frontPan.positionSn] = OldLowHeatReturnReservation(
                positionSn = frontPan.positionSn,
                tasteCode = tasteCode
            )
        }

    return reservations.values.toList()
}

internal fun collectCommittedOldLowHeatSourcePositionsForHighHeatReturn(
    frontPans: List<KaoPan>,
    oldLowHeatPans: List<KaoPan>,
    activeIntent: SupplementBatchIntent?
): Set<Int> {
    val sourcePans = oldLowHeatPans
        .filter { it.positionSn in 13..18 && it.isHasSausage }
        .sortedBy { it.positionSn }
    if (sourcePans.isEmpty()) {
        return emptySet()
    }

    val sourceByPosition = sourcePans.associateBy { it.positionSn }
    val committedSources = linkedSetOf<Int>()
    activeIntent
        ?.targets
        ?.filter {
            isOldLowHeatIntentTarget(activeIntent.type, it) &&
                !it.cancelledByFaultTimeout &&
                !it.skippedBySupplyDegrade
        }
        ?.forEach { target ->
            val sourcePan = sourceByPosition[target.positionSn] ?: return@forEach
            val returnPosition = target.returnPositionSn ?: return@forEach
            val hasEmptyReturnTarget = frontPans.any {
                it.positionSn == returnPosition &&
                    it.positionSn in 1..9 &&
                    !it.isHasSausage
            }
            if (hasEmptyReturnTarget) {
                committedSources += sourcePan.positionSn
            }
        }

    return committedSources
}

internal fun normalizeOldLowHeatWaitingSourcesForReturn(oldLowHeatPans: List<KaoPan>): List<Int> {
    val normalizedPositions = mutableListOf<Int>()
    oldLowHeatPans
        .filter { pan ->
            pan.positionSn in 13..18 &&
                pan.isHasSausage &&
                pan.status == KaoPan.STATUS_SELLABLE_KEEP_WARM &&
                pan.holdingTime > 0L &&
                pan.startTime <= 0L
        }
        .forEach { pan ->
            pan.startTime = 0L
            pan.status = KaoPan.STATUS_WAITING_RETURN_KEEP_WARM
            normalizedPositions += pan.positionSn
        }
    return normalizedPositions.sorted()
}

internal fun collectStableHighSuspiciousOldLowHeatSources(
    modeType: Int,
    transitionMode: Int,
    oldLowHeatPans: List<KaoPan>,
    activeIntent: SupplementBatchIntent?
): List<Int> {
    if (modeType != 2 || transitionMode != 0) {
        return emptyList()
    }
    val activeLowHeatTargetPositions = activeIntent
        ?.targets
        ?.filter {
            isOldLowHeatIntentTarget(activeIntent.type, it) &&
                !it.cancelledByFaultTimeout &&
                !it.skippedBySupplyDegrade
        }
        ?.map { it.positionSn }
        ?.toSet()
        ?: emptySet()
    if (activeLowHeatTargetPositions.isEmpty()) {
        return emptyList()
    }
    return oldLowHeatPans
        .filter { pan ->
            pan.positionSn in 13..18 &&
                pan.positionSn in activeLowHeatTargetPositions &&
                pan.isHasSausage &&
                pan.status == KaoPan.STATUS_SELLABLE_KEEP_WARM &&
                pan.holdingTime > 0L &&
                pan.startTime <= 0L
        }
        .map { it.positionSn }
        .sorted()
}

internal fun clearReturnedOldLowHeatSourceMetadata(sourcePan: KaoPan) {
    if (sourcePan.positionSn !in 13..18 || sourcePan.isHasSausage) {
        return
    }
    sourcePan.isHasGrilling = false
    sourcePan.taste = null
}

internal fun selectOldLowHeatReturnTarget(
    sourcePan: KaoPan,
    frontPans: List<KaoPan>,
    activeIntent: SupplementBatchIntent?
): KaoPan? {
    val intentTarget = activeIntent?.let { intent ->
        intent.targets.firstOrNull {
            it.positionSn == sourcePan.positionSn &&
            isOldLowHeatIntentTarget(intent.type, it) &&
                it.returnPositionSn in 1..9 &&
                !it.cancelledByFaultTimeout &&
                !it.skippedBySupplyDegrade
        }
    }
    val tasteCode = sourcePan.taste?.tasteCode?.takeIf { it.isNotBlank() }
        ?: intentTarget?.tasteCode?.takeIf { it.isNotBlank() }
        ?: return null
    val specifiedTarget = intentTarget?.returnPositionSn?.let { returnPosition ->
        frontPans.firstOrNull {
            it.positionSn == returnPosition &&
                it.positionSn in 1..9 &&
                !it.isHasSausage
        }
    }
    if (specifiedTarget != null) {
        restoreOldLowHeatReturnReservation(
            specifiedTarget,
            intentTarget.tasteCode?.takeIf { it.isNotBlank() } ?: tasteCode
        )
        return specifiedTarget
    }
    return null
}

private fun isOldLowHeatIntentTarget(
    intentType: SupplementBatchType,
    target: SupplementBatchIntentTarget
): Boolean {
    val originType = target.originBatchType
    return if (originType != null) {
        originType in setOf(SupplementBatchType.LOW_HEAT_13_15, SupplementBatchType.LOW_HEAT_16_18)
    } else {
        intentType in setOf(SupplementBatchType.LOW_HEAT_13_15, SupplementBatchType.LOW_HEAT_16_18) ||
            SupplementBatchIntentRepository.positionMatchesType(target.positionSn, SupplementBatchType.LOW_HEAT_13_15) ||
            SupplementBatchIntentRepository.positionMatchesType(target.positionSn, SupplementBatchType.LOW_HEAT_16_18)
    }
}

internal fun shouldDiscardWaitingOldLowHeatSource(
    pan: KaoPan,
    config: AppConfigBean,
    nowMs: Long
): Boolean {
    if (!pan.isHasSausage || pan.positionSn !in 13..18) {
        return false
    }
    if (pan.status == KaoPan.STATUS_WAITING_RETURN_KEEP_WARM && pan.startTime <= 0L) {
        return pan.holdingTime > 0L &&
            pan.closeTime > 0L &&
            nowMs - pan.holdingTime >= pan.closeTime
    }
    if (pan.startTime <= 0L) {
        return false
    }
    if (pan.bakingTime <= 0L || pan.closeTime <= 0L) {
        return false
    }
    val elapsed = FaultHeatPauseManager.effectiveBakingElapsedMs(pan, config, nowMs)
    return elapsed >= pan.bakingTime + pan.closeTime
}

private fun restoreOldLowHeatReturnReservation(
    frontPan: KaoPan,
    tasteCode: String?
) {
    frontPan.isHasGrilling = true
    val normalizedTasteCode = tasteCode?.takeIf { it.isNotBlank() } ?: return
    if (frontPan.taste?.tasteCode != normalizedTasteCode) {
        frontPan.taste = Taste(normalizedTasteCode)
    }
}

internal fun hasHighHeatReturnStartedAndUncleared(
    recordedBatchSize: Int,
    highHeatPans: List<KaoPan>
): Boolean {
    val remainingCount = highHeatPans.count { it.positionSn in 25..33 && it.isHasSausage }
    return recordedBatchSize > 0 && remainingCount in 1 until recordedBatchSize
}

internal fun hasOldLowHeatAreaSausageForLowToHighTransition(
    oldLowHeatPans: List<KaoPan>,
    highHeatPans: List<KaoPan>,
    recordedHighHeatBatchSize: Int
): Boolean {
    val highHeatReturnStarted = hasHighHeatReturnStartedAndUncleared(
        recordedBatchSize = recordedHighHeatBatchSize,
        highHeatPans = highHeatPans
    )
    return oldLowHeatPans
        .filter { it.positionSn in 13..18 && it.isHasSausage }
        .any { pan ->
            if (!highHeatReturnStarted) {
                true
            } else {
                pan.status == KaoPan.STATUS_WAITING_RETURN_KEEP_WARM ||
                    (pan.startTime > 0L && pan.status != KaoPan.STATUS_SELLABLE_KEEP_WARM)
            }
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
