package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
import cn.niuyannet.kaochang.android.model.bean.Taste
import cn.niuyannet.kaochang.android.init.AppConfigBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighCurrentTrafficModeStateHelperTest {

    @Test
    fun resolveLowToHighTransitionIdleState_shouldKeepHighModeWhenSchedulingIsDisabled() {
        val resolved = resolveLowToHighTransitionIdleState(
            currentModeType = 2,
            schedulingEnabled = false,
            boxSupplyAvailable = true,
            hasActiveHeatingIn13_18 = false,
            hasStartedHighHeatSupplyNow = false
        )

        assertNotNull(resolved)
        assertEquals(2, resolved!!.modeType)
        assertEquals(0, resolved.transitionMode)
    }

    @Test
    fun resolveLowToHighTransitionIdleState_shouldReturnNullWhenStillWaitingForOldOrNewBatch() {
        val resolved = resolveLowToHighTransitionIdleState(
            currentModeType = 2,
            schedulingEnabled = false,
            boxSupplyAvailable = true,
            hasActiveHeatingIn13_18 = true,
            hasStartedHighHeatSupplyNow = false
        )

        assertEquals(null, resolved)
    }

    @Test
    fun resolveLowToHighTransitionCompletionState_shouldWaitUntilOldLowHeatAreaIsCleared() {
        val resolved = resolveLowToHighTransitionCompletionState(
            currentModeType = 2,
            hasOldLowHeatAreaSausage = true,
            hasFullHighHeatSupplyBatchNow = true,
            hasStartedHighHeatSupplyBatchNow = true,
            hasReturnedHighHeatSupplyBatch = false
        )

        assertEquals(null, resolved)
    }

    @Test
    fun resolveLowToHighTransitionCompletionState_shouldWaitUntilHighHeatBatchStarts() {
        val resolved = resolveLowToHighTransitionCompletionState(
            currentModeType = 2,
            hasOldLowHeatAreaSausage = false,
            hasFullHighHeatSupplyBatchNow = false,
            hasStartedHighHeatSupplyBatchNow = false,
            hasReturnedHighHeatSupplyBatch = false
        )

        assertEquals(null, resolved)
    }

    @Test
    fun resolveLowToHighTransitionCompletionState_shouldWaitUntilHighHeatBatchReturns() {
        val resolved = resolveLowToHighTransitionCompletionState(
            currentModeType = 2,
            hasOldLowHeatAreaSausage = false,
            hasFullHighHeatSupplyBatchNow = true,
            hasStartedHighHeatSupplyBatchNow = true,
            hasReturnedHighHeatSupplyBatch = false
        )

        assertEquals(null, resolved)
    }

    @Test
    fun resolveLowToHighTransitionCompletionState_shouldCompleteAfterHighHeatBatchReturns() {
        val resolved = resolveLowToHighTransitionCompletionState(
            currentModeType = 2,
            hasOldLowHeatAreaSausage = false,
            hasFullHighHeatSupplyBatchNow = false,
            hasStartedHighHeatSupplyBatchNow = true,
            hasReturnedHighHeatSupplyBatch = true
        )

        assertNotNull(resolved)
        assertEquals(2, resolved!!.modeType)
        assertEquals(0, resolved.transitionMode)
    }

    @Test
    fun recordSupplementLaunch_shouldRefreshTrackingWhenTransitionStartsNewBatch() {
        val previous = HighTrafficSupplementTrackingState(
            pitCount = 0,
            lastFillPitTime = 1_000L
        )

        val updated = recordHighTrafficSupplementLaunch(
            previous = previous,
            filledCount = 9,
            now = 20_000L
        )

        assertEquals(9, updated.pitCount)
        assertEquals(20_000L, updated.lastFillPitTime)
    }

    @Test
    fun recordSupplementLaunch_shouldKeepPreviousTrackingWhenNoSausageWasMoved() {
        val previous = HighTrafficSupplementTrackingState(
            pitCount = 3,
            lastFillPitTime = 12_345L
        )

        val updated = recordHighTrafficSupplementLaunch(
            previous = previous,
            filledCount = 0,
            now = 99_999L
        )

        assertEquals(previous, updated)
    }

    @Test
    fun recordSupplementLaunchOutcome_shouldKeepActualBatchSizeWhenPlannedSmallBatchCompletes() {
        val previous = HighTrafficSupplementTrackingState(
            pitCount = 0,
            lastFillPitTime = 1_000L
        )

        val updated = recordHighTrafficSupplementLaunchOutcome(
            previous = previous,
            requestedTargetCount = 2,
            movedCount = 2,
            now = 20_000L
        )

        assertEquals(2, updated.pitCount)
        assertEquals(20_000L, updated.lastFillPitTime)
    }

    @Test
    fun recordSupplementLaunchOutcome_shouldForceResumeWhenLaunchWasInterruptedBeforeRequestedTargetsComplete() {
        val previous = HighTrafficSupplementTrackingState(
            pitCount = 0,
            lastFillPitTime = 1_000L
        )

        val updated = recordHighTrafficSupplementLaunchOutcome(
            previous = previous,
            requestedTargetCount = 9,
            movedCount = 2,
            now = 20_000L
        )

        assertEquals("异常中断残缺批次不能记成 2 根小批次，否则恢复后不会续补 27~33", 0, updated.pitCount)
        assertEquals(20_000L, updated.lastFillPitTime)
        assertTrue(
            shouldResumeIncompleteHighHeatBatch(
                pitCount = updated.pitCount,
                hasIncompleteBatch = true
            )
        )
    }

    @Test
    fun shouldResumeIncompleteHighHeatBatch_shouldTreatRecordedPartialCountAsCurrentBatchSize() {
        assertFalse(
            shouldResumeIncompleteHighHeatBatch(
                pitCount = 5,
                hasIncompleteBatch = true
            )
        )
    }

    @Test
    fun shouldResumeIncompleteHighHeatBatch_shouldAllowRecoveryWhenNoBatchSizeWasRecorded() {
        assertTrue(
            shouldResumeIncompleteHighHeatBatch(
                pitCount = 0,
                hasIncompleteBatch = true
            )
        )
    }

    @Test
    fun shouldSwitchHighHeatAreaToKeepWarm_shouldTreatPartiallyOpenedFrontAreaAsReady() {
        val nowMs = 31L * 60L * 1000L
        val pans = (10..21).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = position <= 15
                status = if (position <= 15) 1 else 0
                startTime = if (position <= 15) 1_000L else 0L
                bakingTime = 30L * 60L * 1000L
            }
        }

        assertTrue(
            shouldSwitchHighHeatAreaToKeepWarm(
                pans = pans,
                area = 2,
                config = AppConfigBean(),
                nowMs = nowMs + 1_000L
            )
        )
    }

    @Test
    fun shouldSwitchHighHeatAreaToKeepWarm_shouldPauseEffectiveBakingTimeDuringFault() {
        val config = AppConfigBean().apply {
            faultHeatPauseActive = 1
            faultHeatPauseStartedAtMs = 20L * 60L * 1000L + 1_000L
        }
        val pan = KaoPan().apply {
            positionSn = 1
            isHasSausage = true
            status = 1
            startTime = 1_000L
            bakingTime = 30L * 60L * 1000L
        }

        assertFalse(
            shouldSwitchHighHeatAreaToKeepWarm(
                pans = listOf(pan),
                area = 1,
                config = config,
                nowMs = 60L * 60L * 1000L + 1_000L
            )
        )
    }

    @Test
    fun resolveAutomaticTrafficTransition_shouldOnlyMoveToNextStateInOneWayCycle() {
        assertEquals(
            HighTrafficModeState(modeType = 2, transitionMode = 2),
            resolveAutomaticTrafficTransition(
                currentModeType = 2,
                currentTransitionMode = 0,
                requestedTransitionMode = 2
            )
        )
        assertEquals(
            HighTrafficModeState(modeType = 2, transitionMode = 1),
            resolveAutomaticTrafficTransition(
                currentModeType = 1,
                currentTransitionMode = 0,
                requestedTransitionMode = 1
            )
        )
        assertEquals(
            null,
            resolveAutomaticTrafficTransition(
                currentModeType = 2,
                currentTransitionMode = 0,
                requestedTransitionMode = 1
            )
        )
        assertEquals(
            null,
            resolveAutomaticTrafficTransition(
                currentModeType = 1,
                currentTransitionMode = 0,
                requestedTransitionMode = 2
            )
        )
    }

    @Test
    fun canReturnHighHeatBatchToFront_shouldUseActualBatchSize() {
        assertTrue(
            canReturnHighHeatBatchToFront(
                frontEmptyCount = 5,
                batchSize = 5
            )
        )
        assertFalse(
            canReturnHighHeatBatchToFront(
                frontEmptyCount = 4,
                batchSize = 5
            )
        )
    }

    @Test
    fun shouldSwitchHighToLowByTimeout_shouldReturnFalseWhenThresholdIsNonPositive() {
        assertFalse(
            shouldSwitchHighToLowByTimeout(
                lastFillPitTime = 10_000L,
                now = 20_000L,
                timeThresholdLowMinutes = 0
            )
        )
    }

    @Test
    fun shouldSwitchHighToLowByTimeout_shouldReturnTrueWhenElapsedTimeExceedsThreshold() {
        assertTrue(
            shouldSwitchHighToLowByTimeout(
                lastFillPitTime = 10_000L,
                now = 80_001L,
                timeThresholdLowMinutes = 1
            )
        )
    }

    @Test
    fun shouldSwitchHighToLowByTimeout_shouldReturnFalseAtExactThreshold() {
        val lastFillPitTime = 10_000L
        val thresholdMs = 40 * 60 * 1000L

        assertFalse(
            shouldSwitchHighToLowByTimeout(
                lastFillPitTime = lastFillPitTime,
                now = lastFillPitTime + thresholdMs,
                timeThresholdLowMinutes = 40
            )
        )
    }

    @Test
    fun shouldSwitchHighToLowByTimeout_shouldReturnTrueAfterFortyMinuteThreshold() {
        val lastFillPitTime = 10_000L
        val thresholdMs = 40 * 60 * 1000L

        assertTrue(
            shouldSwitchHighToLowByTimeout(
                lastFillPitTime = lastFillPitTime,
                now = lastFillPitTime + thresholdMs + 1,
                timeThresholdLowMinutes = 40
            )
        )
    }

    @Test
    fun canFinishHighToLowDrain_shouldAllowCloseoutWhenFrontHasNineAndZone3IsEmpty() {
        assertTrue(
            canFinishHighToLowDrain(
                frontSausageCount = 9,
                zone3SausageCount = 0
            )
        )
    }

    @Test
    fun canFinishHighToLowDrain_shouldWaitWhenFrontHasMoreThanNineOrZone3HasSausage() {
        assertFalse(
            canFinishHighToLowDrain(
                frontSausageCount = 10,
                zone3SausageCount = 0,
                hasUnreadySausage = false
            )
        )
        assertFalse(
            canFinishHighToLowDrain(
                frontSausageCount = 9,
                zone3SausageCount = 1,
                hasUnreadySausage = false
            )
        )
        assertFalse(
            canFinishHighToLowDrain(
                frontSausageCount = 9,
                zone3SausageCount = 0,
                hasUnreadySausage = true
            )
        )
    }

    @Test
    fun resolveStaleLowToHighTransitionState_shouldNormalizeHighModeBackToStable() {
        val resolved = resolveStaleLowToHighTransitionState(
            currentModeType = 2,
            currentTransitionMode = 1,
            hasRealLowHeatLegacyBatch = false,
            hasHighHeatSupplyInProgress = false
        )

        assertNotNull(resolved)
        assertEquals(2, resolved!!.modeType)
        assertEquals(0, resolved.transitionMode)
    }

    @Test
    fun resolveStaleLowToHighTransitionState_shouldKeepRealLowToHighTransition() {
        val resolved = resolveStaleLowToHighTransitionState(
            currentModeType = 2,
            currentTransitionMode = 1,
            hasRealLowHeatLegacyBatch = true,
            hasHighHeatSupplyInProgress = false
        )

        assertEquals(null, resolved)
    }

    @Test
    fun resolveStaleLowToHighTransitionState_shouldKeepWhenHighHeatBatchIsInProgress() {
        val resolved = resolveStaleLowToHighTransitionState(
            currentModeType = 2,
            currentTransitionMode = 1,
            hasRealLowHeatLegacyBatch = false,
            hasHighHeatSupplyInProgress = true
        )

        assertEquals(null, resolved)
    }

    @Test
    fun hasRealOldLowHeatLegacyBatch_shouldKeepMatureOldLowHeatWithReservedFrontTarget() {
        val oldLowHeatPans = listOf(
            oldLowHeatPan(position = 13, tasteCode = "A", startTime = 0L, holdingTime = 10_000L, status = 2)
        )
        val frontPans = frontPansWithReservedTarget(position = 1, tasteCode = "A")

        assertTrue(hasRealOldLowHeatLegacyBatch(oldLowHeatPans, frontPans))
        val resolved = resolveStaleLowToHighTransitionState(
            currentModeType = 2,
            currentTransitionMode = 1,
            hasRealLowHeatLegacyBatch = hasRealOldLowHeatLegacyBatch(oldLowHeatPans, frontPans),
            hasHighHeatSupplyInProgress = false
        )

        assertEquals(null, resolved)
    }

    @Test
    fun hasRealOldLowHeatLegacyBatch_shouldAllowSelfHealWhenOldAndHighHeatBatchesAreAbsent() {
        val oldLowHeatPans = (13..18).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = false
            }
        }
        val frontPans = frontPansWithReservedTarget(position = 1, tasteCode = "A")

        assertFalse(hasRealOldLowHeatLegacyBatch(oldLowHeatPans, frontPans))
        val resolved = resolveStaleLowToHighTransitionState(
            currentModeType = 2,
            currentTransitionMode = 1,
            hasRealLowHeatLegacyBatch = hasRealOldLowHeatLegacyBatch(oldLowHeatPans, frontPans),
            hasHighHeatSupplyInProgress = false
        )

        assertNotNull(resolved)
        assertEquals(2, resolved!!.modeType)
        assertEquals(0, resolved.transitionMode)
    }

    @Test
    fun hasRealOldLowHeatLegacyBatch_shouldKeepActiveOldLowHeatWithoutReservedFrontTarget() {
        val oldLowHeatPans = listOf(
            oldLowHeatPan(position = 13, tasteCode = "A", startTime = 10_000L, holdingTime = 0L, status = 1)
        )
        val frontPans = frontPansWithReservedTarget(position = 1, tasteCode = "B")

        assertTrue(hasRealOldLowHeatLegacyBatch(oldLowHeatPans, frontPans))
    }

    @Test
    fun hasHighHeatReturnStartedAndUncleared_shouldDetectPartialReturnByRecordedBatchSize() {
        assertTrue(
            hasHighHeatReturnStartedAndUncleared(
                recordedBatchSize = 9,
                highHeatPans = highHeatPans(occupiedPositions = setOf(33))
            )
        )
        assertFalse(
            hasHighHeatReturnStartedAndUncleared(
                recordedBatchSize = 9,
                highHeatPans = highHeatPans(occupiedPositions = (25..33).toSet())
            )
        )
        assertFalse(
            hasHighHeatReturnStartedAndUncleared(
                recordedBatchSize = 9,
                highHeatPans = highHeatPans(occupiedPositions = emptySet())
            )
        )
    }

    @Test
    fun hasOldLowHeatAreaSausageForLowToHighTransition_shouldIgnoreKeepWarmOverlapAfterHighHeatReturnStarts() {
        val oldLowHeatPans = listOf(
            oldLowHeatPan(position = 13, tasteCode = "A", startTime = 0L, holdingTime = 10_000L, status = 2),
            oldLowHeatPan(position = 14, tasteCode = "A", startTime = 0L, holdingTime = 10_000L, status = 2)
        )

        assertFalse(
            hasOldLowHeatAreaSausageForLowToHighTransition(
                oldLowHeatPans = oldLowHeatPans,
                highHeatPans = highHeatPans(occupiedPositions = setOf(33)),
                recordedHighHeatBatchSize = 9
            )
        )
    }

    @Test
    fun hasOldLowHeatAreaSausageForLowToHighTransition_shouldKeepOldLowHeatBeforeHighHeatReturnStarts() {
        val oldLowHeatPans = listOf(
            oldLowHeatPan(position = 13, tasteCode = "A", startTime = 0L, holdingTime = 10_000L, status = 2)
        )

        assertTrue(
            hasOldLowHeatAreaSausageForLowToHighTransition(
                oldLowHeatPans = oldLowHeatPans,
                highHeatPans = highHeatPans(occupiedPositions = (25..33).toSet()),
                recordedHighHeatBatchSize = 9
            )
        )
    }

    @Test
    fun hasOldLowHeatAreaSausageForLowToHighTransition_shouldKeepActiveOldLowHeatAfterHighHeatReturnStarts() {
        val oldLowHeatPans = listOf(
            oldLowHeatPan(position = 13, tasteCode = "A", startTime = 10_000L, holdingTime = 0L, status = 1)
        )

        assertTrue(
            hasOldLowHeatAreaSausageForLowToHighTransition(
                oldLowHeatPans = oldLowHeatPans,
                highHeatPans = highHeatPans(occupiedPositions = setOf(33)),
                recordedHighHeatBatchSize = 9
            )
        )
    }

    @Test
    fun hasOldLowHeatAreaSausageForLowToHighTransition_shouldKeepWaitingReturnOldLowHeatAfterHighHeatReturnStarts() {
        val oldLowHeatPans = listOf(
            oldLowHeatPan(position = 13, tasteCode = "A", startTime = 0L, holdingTime = 10_000L, status = 3)
        )

        assertTrue(
            hasOldLowHeatAreaSausageForLowToHighTransition(
                oldLowHeatPans = oldLowHeatPans,
                highHeatPans = highHeatPans(occupiedPositions = setOf(33)),
                recordedHighHeatBatchSize = 9
            )
        )
    }

    @Test
    fun collectProtectedOldLowHeatReturnPositions_shouldUseActiveIntentReturnPositionsAndRestoreFrontReservation() {
        val frontPans = (1..9).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = false
                isHasGrilling = false
                taste = null
            }
        }
        val oldLowHeatPans = listOf(
            oldLowHeatPan(position = 13, tasteCode = "A", startTime = 1_000L, holdingTime = 0L, status = 1)
        )
        val intent = SupplementBatchIntent(
            type = SupplementBatchType.LOW_HEAT_13_15,
            targets = mutableListOf(
                SupplementBatchIntentTarget(positionSn = 13, tasteCode = "A", returnPositionSn = 2, completed = true)
            )
        )

        val protected = collectProtectedOldLowHeatReturnPositions(
            frontPans = frontPans,
            oldLowHeatPans = oldLowHeatPans,
            activeIntent = intent
        )

        val protectedPan = frontPans.first { it.positionSn == 2 }
        assertEquals(setOf(2), protected)
        assertTrue(protectedPan.isHasGrilling)
        assertEquals("A", protectedPan.taste.tasteCode)
    }

    @Test
    fun collectProtectedOldLowHeatReturnPositions_shouldProtectActiveIntentTargetBeforeSourceIsPlaced() {
        val frontPans = (1..9).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = false
                isHasGrilling = false
                taste = null
            }
        }
        val oldLowHeatPans = (13..18).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = false
            }
        }
        val intent = SupplementBatchIntent(
            type = SupplementBatchType.LOW_HEAT_13_15,
            targets = mutableListOf(
                SupplementBatchIntentTarget(positionSn = 13, tasteCode = "A", returnPositionSn = 2, completed = false)
            )
        )

        val protected = collectProtectedOldLowHeatReturnPositions(
            frontPans = frontPans,
            oldLowHeatPans = oldLowHeatPans,
            activeIntent = intent
        )

        val protectedPan = frontPans.first { it.positionSn == 2 }
        assertEquals(setOf(2), protected)
        assertTrue(protectedPan.isHasGrilling)
        assertEquals("A", protectedPan.taste.tasteCode)
    }

    @Test
    fun collectProtectedOldLowHeatReturnPositions_shouldNotInferBindingFromPhysicalReservationOnly() {
        val frontPans = frontPansWithReservedTarget(position = 3, tasteCode = "A")
        val oldLowHeatPans = listOf(
            oldLowHeatPan(position = 13, tasteCode = "A", startTime = 1_000L, holdingTime = 0L, status = 1)
        )

        val protected = collectProtectedOldLowHeatReturnPositions(
            frontPans = frontPans,
            oldLowHeatPans = oldLowHeatPans,
            activeIntent = null
        )

        assertEquals(emptySet<Int>(), protected)
    }

    @Test
    fun selectOldLowHeatReturnTarget_shouldPreferIntentSpecifiedEmptyFrontPanAndRestoreReservation() {
        val frontPans = (1..9).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = false
                isHasGrilling = position == 1
                taste = if (position == 1) Taste("A") else null
            }
        }
        val sourcePan = oldLowHeatPan(position = 13, tasteCode = "A", startTime = 1_000L, holdingTime = 0L, status = 1)
        val intent = SupplementBatchIntent(
            type = SupplementBatchType.LOW_HEAT_13_15,
            targets = mutableListOf(
                SupplementBatchIntentTarget(positionSn = 13, tasteCode = "A", returnPositionSn = 4, completed = true)
            )
        )

        val selected = selectOldLowHeatReturnTarget(
            sourcePan = sourcePan,
            frontPans = frontPans,
            activeIntent = intent
        )

        assertEquals(4, selected?.positionSn)
        assertTrue(selected!!.isHasGrilling)
        assertEquals("A", selected.taste.tasteCode)
    }

    @Test
    fun normalizeOldLowHeatWaitingSourcesForReturn_shouldConvertLegacyKeepWarmStateBackToNonSellableWaiting() {
        val legacyPan = oldLowHeatPan(
            position = 13,
            tasteCode = "A",
            startTime = 0L,
            holdingTime = 9_000L,
            status = 2
        ).apply {
            bakingTime = 8_000L
        }

        val normalized = normalizeOldLowHeatWaitingSourcesForReturn(listOf(legacyPan))

        assertEquals(listOf(13), normalized)
        assertEquals(3, legacyPan.status)
        assertEquals(0L, legacyPan.startTime)
        assertEquals(9_000L, legacyPan.holdingTime)
    }

    @Test
    fun clearReturnedOldLowHeatSourceMetadata_shouldMakeReturnedSourceAvailableAsBlankHighFrontSlot() {
        val sourcePan = oldLowHeatPan(
            position = 13,
            tasteCode = "A",
            startTime = 0L,
            holdingTime = 0L,
            status = 0
        ).apply {
            isHasSausage = false
            isHasGrilling = true
        }

        clearReturnedOldLowHeatSourceMetadata(sourcePan)

        assertEquals(null, sourcePan.taste)
        assertFalse(sourcePan.isHasGrilling)
    }

    @Test
    fun shouldDiscardWaitingOldLowHeatSource_shouldTriggerAfterBakingAndCloseTimeElapsed() {
        val pan = oldLowHeatPan(
            position = 13,
            tasteCode = "A",
            startTime = 1_000L,
            holdingTime = 0L,
            status = 1
        ).apply {
            bakingTime = 8_000L
            closeTime = 2_000L
        }

        assertFalse(
            shouldDiscardWaitingOldLowHeatSource(
                pan = pan,
                config = AppConfigBean(),
                nowMs = 10_999L
            )
        )
        assertTrue(
            shouldDiscardWaitingOldLowHeatSource(
                pan = pan,
                config = AppConfigBean(),
                nowMs = 11_000L
            )
        )
    }

    @Test
    fun shouldDiscardWaitingOldLowHeatSource_shouldUseHoldingTimeForWaitingReturnKeepWarm() {
        val pan = oldLowHeatPan(
            position = 13,
            tasteCode = "A",
            startTime = 0L,
            holdingTime = 9_000L,
            status = 3
        ).apply {
            closeTime = 2_000L
        }

        assertFalse(
            shouldDiscardWaitingOldLowHeatSource(
                pan = pan,
                config = AppConfigBean(),
                nowMs = 10_999L
            )
        )
        assertTrue(
            shouldDiscardWaitingOldLowHeatSource(
                pan = pan,
                config = AppConfigBean(),
                nowMs = 11_000L
            )
        )
    }

    @Test
    fun planOldLowHeatReturnGroups_shouldPrioritizeEarlierMaxStartTime() {
        val oldLowHeatPans =
            oldLowHeatBatch(13, 15, listOf(2_000L, 2_500L, 3_000L)) +
                oldLowHeatBatch(16, 18, listOf(1_000L, 1_200L, 1_500L))

        val plannedGroups = planOldLowHeatReturnGroups(
            oldLowHeatPans = oldLowHeatPans,
            nowMs = 10_000L
        )

        assertEquals(listOf(16, 13), plannedGroups.map { it.start })
        assertEquals(listOf("16~18", "13~15"), plannedGroups.map { it.groupName })
    }

    @Test
    fun shouldLogLowToHighPartialWait_shouldLogFirstSnapshotAndSuppressSameSnapshot() {
        val snapshot = createLowToHighPartialWaitLogSnapshot(
            oldLowHeatPans = occupiedPans(13, 14),
            partialHighHeatPans = occupiedPans(25, 26, 27, 28, 29),
            boxSupplyAvailable = false,
            schedulingEnabled = true,
            errorStatus = 0
        )

        assertTrue(shouldLogLowToHighPartialWait(previous = null, current = snapshot))
        assertFalse(shouldLogLowToHighPartialWait(previous = snapshot, current = snapshot))
    }

    @Test
    fun shouldLogLowToHighPartialWait_shouldLogWhenPanPositionsChange() {
        val previous = createLowToHighPartialWaitLogSnapshot(
            oldLowHeatPans = occupiedPans(13, 14),
            partialHighHeatPans = occupiedPans(25, 26, 27, 28, 29),
            boxSupplyAvailable = false,
            schedulingEnabled = true,
            errorStatus = 0
        )
        val oldLowHeatChanged = createLowToHighPartialWaitLogSnapshot(
            oldLowHeatPans = occupiedPans(14),
            partialHighHeatPans = occupiedPans(25, 26, 27, 28, 29),
            boxSupplyAvailable = false,
            schedulingEnabled = true,
            errorStatus = 0
        )
        val partialHighHeatChanged = createLowToHighPartialWaitLogSnapshot(
            oldLowHeatPans = occupiedPans(14),
            partialHighHeatPans = occupiedPans(25, 26, 27, 28, 29, 30),
            boxSupplyAvailable = false,
            schedulingEnabled = true,
            errorStatus = 0
        )

        assertTrue(shouldLogLowToHighPartialWait(previous, oldLowHeatChanged))
        assertTrue(shouldLogLowToHighPartialWait(oldLowHeatChanged, partialHighHeatChanged))
    }

    @Test
    fun shouldLogLowToHighPartialWait_shouldLogWhenSchedulingStockOrErrorChanges() {
        val previous = createLowToHighPartialWaitLogSnapshot(
            oldLowHeatPans = occupiedPans(13, 14),
            partialHighHeatPans = occupiedPans(25, 26, 27, 28, 29),
            boxSupplyAvailable = false,
            schedulingEnabled = true,
            errorStatus = 0
        )

        assertTrue(shouldLogLowToHighPartialWait(previous, previous.copy(boxSupplyAvailable = true)))
        assertTrue(shouldLogLowToHighPartialWait(previous, previous.copy(schedulingEnabled = false)))
        assertTrue(shouldLogLowToHighPartialWait(previous, previous.copy(errorStatus = 1)))
    }

    @Test
    fun shouldLogLowToHighPartialWait_shouldLogAgainAfterSnapshotReset() {
        val snapshot = createLowToHighPartialWaitLogSnapshot(
            oldLowHeatPans = occupiedPans(13, 14),
            partialHighHeatPans = occupiedPans(25, 26, 27, 28, 29),
            boxSupplyAvailable = false,
            schedulingEnabled = true,
            errorStatus = 0
        )

        assertFalse(shouldLogLowToHighPartialWait(previous = snapshot, current = snapshot))
        assertTrue(shouldLogLowToHighPartialWait(previous = null, current = snapshot))
    }

    @Test
    fun clearEmptyHighConcurrencyResidualMetadata_shouldClearEmptyDirtyHighAreaPans() {
        val pans = emptyPans().map { pan ->
            when (pan.positionSn) {
                13 -> pan.apply {
                    isHasGrilling = true
                    startTime = 1_000L
                    holdingTime = 2_000L
                    status = 2
                    temperature = 60
                }
                25 -> pan.apply {
                    holdingTime = 3_000L
                    status = 2
                    temperature = 60
                }
                else -> pan
            }
        }

        val clearedPositions = clearEmptyHighConcurrencyResidualMetadata(pans)

        assertEquals(listOf(13, 25), clearedPositions)
        assertEquals(0, HighConcurrencyResidualDrainEvaluator.evaluate(1, pans, includeLowHeatOverlapArea = true))
    }

    @Test
    fun clearEmptyHighConcurrencyResidualMetadata_shouldKeepRealSausageResidual() {
        val pans = emptyPans().map { pan ->
            when (pan.positionSn) {
                10 -> pan.apply {
                    isHasSausage = true
                    holdingTime = 2_000L
                    status = 2
                }
                25 -> pan.apply {
                    holdingTime = 3_000L
                    status = 2
                }
                else -> pan
            }
        }

        val clearedPositions = clearEmptyHighConcurrencyResidualMetadata(pans)

        assertEquals(listOf(25), clearedPositions)
        assertTrue(pans.first { it.positionSn == 10 }.isHasSausage)
        assertEquals(1, HighConcurrencyResidualDrainEvaluator.evaluate(1, pans))
    }

    @Test
    fun collectStableHighSuspiciousOldLowHeatSources_shouldUseActiveLowHeatIntentAsStrongSignal() {
        val oldLowHeatPans = listOf(
            oldLowHeatPan(position = 13, tasteCode = "A", startTime = 0L, holdingTime = 9_000L, status = 2),
            oldLowHeatPan(position = 19, tasteCode = "A", startTime = 0L, holdingTime = 9_000L, status = 2)
        )
        val intent = SupplementBatchIntent(
            type = SupplementBatchType.LOW_HEAT_13_15,
            targets = mutableListOf(
                SupplementBatchIntentTarget(positionSn = 13, tasteCode = "A", returnPositionSn = 1)
            )
        )

        assertEquals(
            listOf(13),
            collectStableHighSuspiciousOldLowHeatSources(
                modeType = 2,
                transitionMode = 0,
                oldLowHeatPans = oldLowHeatPans,
                activeIntent = intent
            )
        )
    }

    @Test
    fun collectStableHighSuspiciousOldLowHeatSources_shouldNotFlagNormalStableHighSellableFront() {
        val oldLowHeatPans = listOf(
            oldLowHeatPan(position = 13, tasteCode = "A", startTime = 0L, holdingTime = 9_000L, status = 2)
        )

        assertEquals(
            emptyList<Int>(),
            collectStableHighSuspiciousOldLowHeatSources(
                modeType = 2,
                transitionMode = 0,
                oldLowHeatPans = oldLowHeatPans,
                activeIntent = null
            )
        )
    }

    @Test
    fun collectStableHighSuspiciousOldLowHeatSources_shouldIgnoreCancelledIntentTargets() {
        val oldLowHeatPans = listOf(
            oldLowHeatPan(position = 13, tasteCode = "A", startTime = 0L, holdingTime = 9_000L, status = 2)
        )
        val intent = SupplementBatchIntent(
            type = SupplementBatchType.LOW_HEAT_13_15,
            targets = mutableListOf(
                SupplementBatchIntentTarget(
                    positionSn = 13,
                    tasteCode = "A",
                    returnPositionSn = 1,
                    cancelledByFaultTimeout = true
                )
            )
        )

        assertEquals(
            emptyList<Int>(),
            collectStableHighSuspiciousOldLowHeatSources(
                modeType = 2,
                transitionMode = 0,
                oldLowHeatPans = oldLowHeatPans,
                activeIntent = intent
            )
        )
    }

    @Test
    fun selectOldLowHeatReturnTarget_shouldUseRetainedLowBindingInsideHighHeatIntent() {
        val frontPans = (1..9).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = position !in setOf(1, 3)
                isHasGrilling = position in setOf(1, 3)
                taste = Taste("A")
            }
        }
        val sourcePan = oldLowHeatPan(position = 18, tasteCode = "A", startTime = 0L, holdingTime = 9_000L, status = 3)
        val intent = SupplementBatchIntent(
            batchId = "mixed",
            type = SupplementBatchType.HIGH_HEAT_25_33,
            targets = mutableListOf(
                SupplementBatchIntentTarget(positionSn = 18, tasteCode = "A", returnPositionSn = 3, completed = true),
                SupplementBatchIntentTarget(positionSn = 25, tasteCode = "A", returnPositionSn = 10)
            )
        )

        val target = selectOldLowHeatReturnTarget(sourcePan, frontPans, intent)

        assertEquals(3, target?.positionSn)
    }

    @Test
    fun collectOldLowHeatReturnReservations_shouldUseRetainedLowBindingInsideHighHeatIntent() {
        val frontPans = (1..9).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = position != 3
                taste = Taste("A")
            }
        }
        val oldLowHeatPans = listOf(
            oldLowHeatPan(position = 18, tasteCode = "A", startTime = 0L, holdingTime = 9_000L, status = 3)
        )
        val intent = SupplementBatchIntent(
            batchId = "mixed",
            type = SupplementBatchType.HIGH_HEAT_25_33,
            targets = mutableListOf(
                SupplementBatchIntentTarget(positionSn = 18, tasteCode = "A", returnPositionSn = 3, completed = true),
                SupplementBatchIntentTarget(positionSn = 25, tasteCode = "A", returnPositionSn = 10)
            )
        )

        val reservations = collectOldLowHeatReturnReservations(frontPans, oldLowHeatPans, intent)

        assertEquals(listOf(3), reservations.map { it.positionSn })
    }

    @Test
    fun collectCommittedOldLowHeatSourcePositions_shouldUseRetainedLowBindingInsideHighHeatIntent() {
        val frontPans = (1..9).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = position != 3
            }
        }
        val oldLowHeatPans = listOf(
            oldLowHeatPan(position = 18, tasteCode = "A", startTime = 0L, holdingTime = 9_000L, status = 3)
        )
        val intent = SupplementBatchIntent(
            batchId = "mixed",
            type = SupplementBatchType.HIGH_HEAT_25_33,
            targets = mutableListOf(
                SupplementBatchIntentTarget(positionSn = 18, tasteCode = "A", returnPositionSn = 3, completed = true),
                SupplementBatchIntentTarget(positionSn = 25, tasteCode = "A", returnPositionSn = 10)
            )
        )

        val committedSources = collectCommittedOldLowHeatSourcePositionsForHighHeatReturn(
            frontPans = frontPans,
            oldLowHeatPans = oldLowHeatPans,
            activeIntent = intent
        )

        assertEquals(setOf(18), committedSources)
    }

    private fun occupiedPans(vararg positions: Int): List<KaoPan> =
        positions.map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = true
            }
        }

    private fun emptyPans(): List<KaoPan> =
        (1..33).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = false
                isHasGrilling = false
                startTime = 0L
                holdingTime = 0L
                status = 0
                temperature = 0
            }
        }

    private fun oldLowHeatPan(
        position: Int,
        tasteCode: String,
        startTime: Long,
        holdingTime: Long,
        status: Int
    ): KaoPan =
        KaoPan().apply {
            positionSn = position
            isHasSausage = true
            this.startTime = startTime
            this.holdingTime = holdingTime
            this.status = status
            taste = Taste(tasteCode)
            bakingTime = 1_000L
        }

    private fun oldLowHeatBatch(
        start: Int,
        end: Int,
        startTimes: List<Long>
    ): List<KaoPan> =
        (start..end).zip(startTimes).map { (position, startTime) ->
            oldLowHeatPan(
                position = position,
                tasteCode = "A",
                startTime = startTime,
                holdingTime = 0L,
                status = 1
            )
        }

    private fun frontPansWithReservedTarget(position: Int, tasteCode: String): List<KaoPan> =
        (1..9).map { index ->
            KaoPan().apply {
                positionSn = index
                isHasSausage = index != position
                isHasGrilling = index == position
                taste = Taste(tasteCode)
            }
        }

    private fun highHeatPans(occupiedPositions: Set<Int>): List<KaoPan> =
        (25..33).map { position ->
            KaoPan().apply {
                positionSn = position
                isHasSausage = position in occupiedPositions
                taste = if (position in occupiedPositions) Taste("A") else null
                holdingTime = if (position in occupiedPositions) 10_000L else 0L
                status = if (position in occupiedPositions) 2 else 0
            }
        }
}
