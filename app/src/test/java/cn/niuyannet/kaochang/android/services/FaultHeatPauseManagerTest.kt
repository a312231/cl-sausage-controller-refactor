package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultHeatPauseManagerTest {

    @Test
    fun adjustPansForPause_shouldShiftBakingStartTimeAndKeepHoldingTime() {
        val config = config()
        val bakingPan = bakingPan(positionSn = 1, startTime = 10_000L)
        val holdingPan = holdingPan(positionSn = 2, holdingTime = 20_000L)

        val result = FaultHeatPauseManager.adjustPansForPause(
            pans = listOf(bakingPan, holdingPan),
            config = config,
            nowMs = 3_700_000L,
            pauseStartedAtMs = 100_000L,
            reason = "单测",
            emitLogs = false
        )

        assertEquals(3_610_000L, bakingPan.startTime)
        assertEquals(20_000L, holdingPan.holdingTime)
        assertTrue(result.shiftedPositions.contains(1))
        assertTrue(config.pendingDiscardPositions.isEmpty())
    }

    @Test
    fun adjustPansForPause_shouldMarkLongPausedBakingPanPendingDiscard() {
        val config = config()
        val bakingPan = bakingPan(positionSn = 25, startTime = 10_000L)

        val result = FaultHeatPauseManager.adjustPansForPause(
            pans = listOf(bakingPan),
            config = config,
            nowMs = 3 * 60 * 60 * 1000L,
            pauseStartedAtMs = 0L,
            reason = "单测",
            emitLogs = false
        )

        assertEquals(10_000L, bakingPan.startTime)
        assertEquals(listOf(25), config.pendingDiscardPositions)
        assertTrue(result.pendingDiscardPositions.contains(25))
    }

    @Test
    fun adjustPansForPause_shouldMarkExpiredHoldingPanPendingDiscard() {
        val config = config()
        val holdingPan = holdingPan(positionSn = 3, holdingTime = 0L).apply {
            holdingTime = 1L
            closeTime = 6 * 60 * 60 * 1000L
        }

        FaultHeatPauseManager.adjustPansForPause(
            pans = listOf(holdingPan),
            config = config,
            nowMs = 7 * 60 * 60 * 1000L,
            pauseStartedAtMs = 6 * 60 * 60 * 1000L,
            reason = "单测",
            emitLogs = false
        )

        assertEquals(listOf(3), config.pendingDiscardPositions)
    }

    @Test
    fun adjustPansForPause_shouldTreatSupplementedPanWithZeroHoldingTimeAsBakingPan() {
        val config = config()
        val supplementedPan = bakingPan(positionSn = 13, startTime = 10_000L).apply {
            holdingTime = 0L
            closeTime = 1L
        }

        val result = FaultHeatPauseManager.adjustPansForPause(
            pans = listOf(supplementedPan),
            config = config,
            nowMs = 70_000L,
            pauseStartedAtMs = 60_000L,
            reason = "单测",
            emitLogs = false
        )

        assertEquals(listOf(13), result.shiftedPositions)
        assertTrue(result.pendingDiscardPositions.isEmpty())
        assertTrue(config.pendingDiscardPositions.isEmpty())
    }

    @Test
    fun adjustPansForPause_shouldNormalizeStaleHoldingTimeBeforeClassification() {
        val config = config().apply {
            pendingDiscardPositions.add(14)
        }
        val staleSupplementedPan = bakingPan(positionSn = 14, startTime = 2_000_000L).apply {
            holdingTime = 1_000L
            closeTime = 1L
            status = 2
        }

        FaultHeatPauseManager.normalizeStaleBakingHoldingTimes(listOf(staleSupplementedPan), config)
        val result = FaultHeatPauseManager.adjustPansForPause(
            pans = listOf(staleSupplementedPan),
            config = config,
            nowMs = 2_100_000L,
            pauseStartedAtMs = 2_000_000L,
            reason = "单测",
            emitLogs = false
        )

        assertEquals(0L, staleSupplementedPan.holdingTime)
        assertEquals(1, staleSupplementedPan.status)
        assertEquals(listOf(14), result.shiftedPositions)
        assertTrue(result.pendingDiscardPositions.isEmpty())
        assertTrue(config.pendingDiscardPositions.isEmpty())
    }

    @Test
    fun effectiveBakingElapsedMs_shouldFreezeAtFaultPauseStart() {
        val config = config().apply {
            faultHeatPauseActive = 1
            faultHeatPauseStartedAtMs = 8 * 60 * 1000L
        }
        val bakingPan = bakingPan(positionSn = 1, startTime = 3 * 60 * 1000L)

        val elapsedMs = FaultHeatPauseManager.effectiveBakingElapsedMs(
            pan = bakingPan,
            config = config,
            nowMs = 12 * 60 * 1000L
        )

        assertEquals(5 * 60 * 1000L, elapsedMs)
    }

    @Test
    fun effectiveBakingElapsedMs_shouldClampNegativeElapsedDuringFaultPause() {
        val config = config().apply {
            faultHeatPauseActive = 1
            faultHeatPauseStartedAtMs = 1_000L
        }
        val bakingPan = bakingPan(positionSn = 1, startTime = 2_000L)

        val elapsedMs = FaultHeatPauseManager.effectiveBakingElapsedMs(
            pan = bakingPan,
            config = config,
            nowMs = 10_000L
        )

        assertEquals(0L, elapsedMs)
    }

    @Test
    fun shouldSkipBakingCompletion_shouldRemainTrueAfterRestartedPauseStateLoaded() {
        val config = config().apply {
            faultHeatPauseActive = 1
            faultHeatPauseStartedAtMs = 8 * 60 * 1000L
        }

        assertTrue(FaultHeatPauseManager.shouldSkipBakingCompletion(config))
    }

    @Test
    fun syncRecoveredPhysicalState_shouldReportChangedPans() {
        val result = FaultHeatPauseManager.EndPauseResult(
            active = true,
            pauseDurationMs = 60_000L,
            shiftedPositions = listOf(17, 18),
            holdingPositions = emptyList(),
            pendingDiscardPositions = listOf(25),
            alertCode = null
        )
        var capturedChangedPositions: List<Int>? = null
        var capturedDeltaReason: String? = null
        var capturedDeltaSource: String? = null

        FaultHeatPauseManager.withSyncDependenciesForTest(
            reportPhysicalPansChanged = { changedPositions, reason, source ->
                capturedChangedPositions = changedPositions
                capturedDeltaReason = reason
                capturedDeltaSource = source
            }
        ) {
            FaultHeatPauseManager.syncRecoveredPhysicalState(result, "恢复温度")
        }

        assertEquals(listOf(17, 18, 25), capturedChangedPositions)
        assertEquals("fault_heat_pause_recovered:恢复温度", capturedDeltaReason)
        assertEquals("fault_heat_pause_recovered", capturedDeltaSource)
    }

    @Test
    fun syncRecoveredPhysicalState_shouldSkipInactiveOrUnchangedResult() {
        var reported = false

        FaultHeatPauseManager.withSyncDependenciesForTest(
            reportPhysicalPansChanged = { _, _, _ -> reported = true }
        ) {
            FaultHeatPauseManager.syncRecoveredPhysicalState(FaultHeatPauseManager.EndPauseResult.NotActive, "无暂停")
        }

        assertFalse(reported)
    }

    private fun config(): AppConfigBean =
        AppConfigBean().apply {
            faultBakingPauseAbortMs = 2 * 60 * 60 * 1000L
            faultPauseAnomalyMs = 4 * 60 * 60 * 1000L
            pendingDiscardPositions.clear()
        }

    private fun bakingPan(positionSn: Int, startTime: Long): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = true
            this.status = 1
            this.startTime = startTime
            this.holdingTime = 0L
            this.bakingTime = 40 * 60 * 1000L
        }

    private fun holdingPan(positionSn: Int, holdingTime: Long): KaoPan =
        KaoPan().apply {
            this.positionSn = positionSn
            this.isHasSausage = true
            this.status = 2
            this.startTime = 0L
            this.holdingTime = holdingTime
            this.closeTime = 6 * 60 * 60 * 1000L
        }
}
