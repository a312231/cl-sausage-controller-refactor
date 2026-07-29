package cn.niuyannet.kaochang.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatedLogGateTest {

    @Test
    fun intervalGate_suppressesSameStateUntilSummaryInterval() {
        val gate = RepeatedLogGate(summaryIntervalMs = 30 * 60 * 1000L)

        gate.markInitial("pause-1", nowMs = 1_000L)

        assertNull(gate.evaluate("pause-1", nowMs = 11_000L))
        val summary = gate.evaluate("pause-1", nowMs = 1_801_000L)
        assertEquals(2, summary?.suppressedCount)
        assertTrue(summary?.stateChanged == false)
    }

    @Test
    fun changeOnlyGate_emitsFirstAndChangedStateButSuppressesExactRepeat() {
        val gate = RepeatedLogGate(summaryIntervalMs = null)

        assertTrue(gate.evaluate("busy", nowMs = 1_000L)?.stateChanged == true)
        assertNull(gate.evaluate("busy", nowMs = 2_000L))
        assertTrue(gate.evaluate("disconnected", nowMs = 3_000L)?.stateChanged == true)
    }

    @Test
    fun intervalGate_restartsSummaryWindowWhenClockMovesBackward() {
        val intervalMs = 30 * 60 * 1000L
        val gate = RepeatedLogGate(summaryIntervalMs = intervalMs)

        gate.markInitial("pause-1", nowMs = 10_000L)

        assertNull(gate.evaluate("pause-1", nowMs = 5_000L))
        val summary = gate.evaluate("pause-1", nowMs = 5_000L + intervalMs)

        assertEquals(2, summary?.suppressedCount)
        assertTrue(summary?.stateChanged == false)
    }
}
