package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.model.bean.KaoPan
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
            hasStartedHighHeatSupplyBatchNow = true
        )

        assertEquals(null, resolved)
    }

    @Test
    fun resolveLowToHighTransitionCompletionState_shouldWaitUntilHighHeatBatchStarts() {
        val resolved = resolveLowToHighTransitionCompletionState(
            currentModeType = 2,
            hasOldLowHeatAreaSausage = false,
            hasFullHighHeatSupplyBatchNow = false,
            hasStartedHighHeatSupplyBatchNow = false
        )

        assertEquals(null, resolved)
    }

    @Test
    fun resolveLowToHighTransitionCompletionState_shouldCompleteAfterOldLowHeatAreaIsCleared() {
        val resolved = resolveLowToHighTransitionCompletionState(
            currentModeType = 2,
            hasOldLowHeatAreaSausage = false,
            hasFullHighHeatSupplyBatchNow = true,
            hasStartedHighHeatSupplyBatchNow = true
        )

        assertNotNull(resolved)
        assertEquals(2, resolved!!.modeType)
        assertEquals(0, resolved.transitionMode)
    }

    @Test
    fun resolveLowToHighTransitionCompletionState_shouldCompleteWhenPartialHighHeatBatchStartedAndOldAreaCleared() {
        val resolved = resolveLowToHighTransitionCompletionState(
            currentModeType = 2,
            hasOldLowHeatAreaSausage = false,
            hasFullHighHeatSupplyBatchNow = false,
            hasStartedHighHeatSupplyBatchNow = true
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
                zone3SausageCount = 0
            )
        )
        assertFalse(
            canFinishHighToLowDrain(
                frontSausageCount = 9,
                zone3SausageCount = 1
            )
        )
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
}
