package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
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
