package cn.niuyannet.kaochang.android.services

import cn.niuyannet.kaochang.android.init.AppConfigBean
import cn.niuyannet.kaochang.android.model.bean.KaoPan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerLossRecoveryGuardTest {

    @Test
    fun recoverPansAfterColdStart_shouldInitializeHeartbeatWhenNoHistory() {
        val config = config().apply {
            lastRuntimeAliveAtMs = 0L
        }
        val bakingPan = bakingPan(positionSn = 1, startTime = 10_000L)

        val result = PowerLossRecoveryGuard.recoverPansAfterColdStart(
            pans = listOf(bakingPan),
            config = config,
            nowMs = 600_000L,
            reason = "unit-test",
            emitLogs = false
        )

        assertFalse(result.adjusted)
        assertEquals(10_000L, bakingPan.startTime)
        assertEquals(600_000L, config.lastRuntimeAliveAtMs)
        assertTrue(config.pendingDiscardPositions.isEmpty())
    }

    @Test
    fun recoverPansAfterColdStart_shouldShiftBakingStartTimeByPowerLossGap() {
        val config = config().apply {
            lastRuntimeAliveAtMs = 120_000L
        }
        val bakingPan = bakingPan(positionSn = 2, startTime = 60_000L)

        val result = PowerLossRecoveryGuard.recoverPansAfterColdStart(
            pans = listOf(bakingPan),
            config = config,
            nowMs = 720_000L,
            reason = "unit-test",
            emitLogs = false
        )

        assertTrue(result.adjusted)
        assertEquals(660_000L, bakingPan.startTime)
        assertEquals(720_000L, config.lastRuntimeAliveAtMs)
        assertEquals(listOf(2), result.pauseResult.shiftedPositions)
        assertTrue(config.pendingDiscardPositions.isEmpty())
    }

    @Test
    fun recoverPansAfterColdStart_shouldNotDoubleAdjustActiveFaultPause() {
        val config = config().apply {
            lastRuntimeAliveAtMs = 120_000L
            faultHeatPauseActive = 1
            faultHeatPauseStartedAtMs = 100_000L
        }
        val bakingPan = bakingPan(positionSn = 2, startTime = 60_000L)

        val result = PowerLossRecoveryGuard.recoverPansAfterColdStart(
            pans = listOf(bakingPan),
            config = config,
            nowMs = 720_000L,
            reason = "unit-test",
            emitLogs = false
        )

        assertFalse(result.adjusted)
        assertEquals(60_000L, bakingPan.startTime)
        assertEquals(720_000L, config.lastRuntimeAliveAtMs)
        assertFalse(result.pauseResult.active)
        assertTrue(FaultHeatPauseManager.shouldSkipBakingCompletion(config))
    }

    @Test
    fun recoverPansAfterColdStart_shouldMarkLongPausedBakingPanPendingDiscard() {
        val config = config().apply {
            lastRuntimeAliveAtMs = 1_000L
        }
        val bakingPan = bakingPan(positionSn = 25, startTime = 1_000L)

        val result = PowerLossRecoveryGuard.recoverPansAfterColdStart(
            pans = listOf(bakingPan),
            config = config,
            nowMs = 3 * 60 * 60 * 1000L,
            reason = "unit-test",
            emitLogs = false
        )

        assertEquals(1_000L, bakingPan.startTime)
        assertEquals(listOf(25), config.pendingDiscardPositions)
        assertEquals(listOf(25), result.pauseResult.pendingDiscardPositions)
    }

    @Test
    fun recoverPansAfterColdStart_shouldMarkExpiredHoldingPanPendingDiscard() {
        val config = config().apply {
            lastRuntimeAliveAtMs = 10_000L
        }
        val holdingPan = holdingPan(positionSn = 3, holdingTime = 1L).apply {
            closeTime = 6 * 60 * 60 * 1000L
        }

        PowerLossRecoveryGuard.recoverPansAfterColdStart(
            pans = listOf(holdingPan),
            config = config,
            nowMs = 7 * 60 * 60 * 1000L,
            reason = "unit-test",
            emitLogs = false
        )

        assertEquals(listOf(3), config.pendingDiscardPositions)
    }

    @Test
    fun recoverPansAfterColdStart_shouldTreatClockRegressionAsPendingDiscard() {
        val config = config().apply {
            lastRuntimeAliveAtMs = 720_000L
        }
        val bakingPan = bakingPan(positionSn = 4, startTime = 60_000L)

        val result = PowerLossRecoveryGuard.recoverPansAfterColdStart(
            pans = listOf(bakingPan),
            config = config,
            nowMs = 120_000L,
            reason = "unit-test",
            emitLogs = false
        )

        assertEquals(60_000L, bakingPan.startTime)
        assertEquals(listOf(4), config.pendingDiscardPositions)
        assertEquals(FaultHeatPauseManager.ALERT_CLOCK_REGRESSED, result.pauseResult.alertCode)
    }

    @Test
    fun recoverPansAfterColdStart_shouldNormalizeStaleBakingHoldingTimeBeforeAdjusting() {
        val config = config().apply {
            lastRuntimeAliveAtMs = 2_000_000L
            pendingDiscardPositions.add(14)
        }
        val stalePan = bakingPan(positionSn = 14, startTime = 2_100_000L).apply {
            status = 2
            holdingTime = 1_000L
            closeTime = 1L
        }

        val result = PowerLossRecoveryGuard.recoverPansAfterColdStart(
            pans = listOf(stalePan),
            config = config,
            nowMs = 2_060_000L,
            reason = "unit-test",
            emitLogs = false
        )

        assertEquals(0L, stalePan.holdingTime)
        assertEquals(1, stalePan.status)
        assertEquals(listOf(14), result.normalizedPositions)
        assertTrue(config.pendingDiscardPositions.isEmpty())
    }

    @Test
    fun markAliveIfDue_shouldThrottleHeartbeatToSixtySeconds() {
        val config = config().apply {
            lastRuntimeAliveAtMs = 1_000L
        }

        assertFalse(PowerLossRecoveryGuard.markAliveIfDue(config, nowMs = 10_000L))
        assertEquals(1_000L, config.lastRuntimeAliveAtMs)

        assertTrue(PowerLossRecoveryGuard.markAliveIfDue(config, nowMs = 61_000L))
        assertEquals(61_000L, config.lastRuntimeAliveAtMs)
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
