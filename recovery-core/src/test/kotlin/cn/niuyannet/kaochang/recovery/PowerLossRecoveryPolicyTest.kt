package cn.niuyannet.kaochang.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerLossRecoveryPolicyTest {

    @Test
    fun coldStartAction_shouldInitializeWhenHeartbeatHistoryIsMissing() {
        listOf(Long.MIN_VALUE, -1L, 0L).forEach { lastAliveAtMs ->
            assertEquals(
                PowerLossColdStartAction.INITIALIZE_HEARTBEAT,
                action(lastAliveAtMs = lastAliveAtMs)
            )
        }
    }

    @Test
    fun coldStartAction_shouldInitializeBeforeConsideringActiveFaultPause() {
        assertEquals(
            PowerLossColdStartAction.INITIALIZE_HEARTBEAT,
            action(lastAliveAtMs = 0L, faultHeatPauseActive = 1, faultHeatPauseStartedAtMs = 1L)
        )
    }

    @Test
    fun coldStartAction_shouldSkipOnlyForFullyActiveFaultPause() {
        assertEquals(
            PowerLossColdStartAction.SKIP_ACTIVE_FAULT_PAUSE,
            action(lastAliveAtMs = 1L, faultHeatPauseActive = 1, faultHeatPauseStartedAtMs = 1L)
        )
        listOf(
            action(lastAliveAtMs = 1L, faultHeatPauseActive = 0, faultHeatPauseStartedAtMs = 1L),
            action(lastAliveAtMs = 1L, faultHeatPauseActive = 2, faultHeatPauseStartedAtMs = 1L),
            action(lastAliveAtMs = 1L, faultHeatPauseActive = 1, faultHeatPauseStartedAtMs = 0L),
            action(lastAliveAtMs = 1L, faultHeatPauseActive = 1, faultHeatPauseStartedAtMs = -1L)
        ).forEach { result ->
            assertEquals(PowerLossColdStartAction.APPLY_POWER_LOSS_GAP, result)
        }
    }

    @Test
    fun shouldMarkAlive_shouldMarkImmediatelyWithoutPositiveHistory() {
        listOf(Long.MIN_VALUE, -1L, 0L).forEach { lastAliveAtMs ->
            assertTrue(PowerLossRecoveryPolicy.shouldMarkAlive(lastAliveAtMs, nowMs = 1L))
        }
    }

    @Test
    fun shouldMarkAlive_shouldKeepExactSixtySecondBoundary() {
        assertFalse(PowerLossRecoveryPolicy.shouldMarkAlive(1L, nowMs = 60_000L))
        assertTrue(PowerLossRecoveryPolicy.shouldMarkAlive(1L, nowMs = 60_001L))
        assertTrue(PowerLossRecoveryPolicy.shouldMarkAlive(1L, nowMs = 60_002L))
    }

    @Test
    fun shouldMarkAlive_shouldKeepOrdinaryClockRollbackBehavior() {
        assertFalse(PowerLossRecoveryPolicy.shouldMarkAlive(100_000L, nowMs = 99_999L))
    }

    @Test
    fun shouldMarkAlive_shouldKeepCurrentLongOverflowBehavior() {
        assertTrue(PowerLossRecoveryPolicy.shouldMarkAlive(1L, nowMs = Long.MIN_VALUE))
        assertFalse(PowerLossRecoveryPolicy.shouldMarkAlive(Long.MAX_VALUE, nowMs = Long.MIN_VALUE))
    }

    @Test
    fun shouldMarkAlive_shouldRejectNonPositiveInterval() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { intervalMs ->
            val error = runCatching {
                PowerLossRecoveryPolicy.shouldMarkAlive(1L, 2L, intervalMs)
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
    }

    private fun action(
        lastAliveAtMs: Long,
        faultHeatPauseActive: Int = 0,
        faultHeatPauseStartedAtMs: Long = 0L
    ): PowerLossColdStartAction = PowerLossRecoveryPolicy.coldStartAction(
        lastAliveAtMs = lastAliveAtMs,
        faultHeatPauseActive = faultHeatPauseActive,
        faultHeatPauseStartedAtMs = faultHeatPauseStartedAtMs
    )
}
