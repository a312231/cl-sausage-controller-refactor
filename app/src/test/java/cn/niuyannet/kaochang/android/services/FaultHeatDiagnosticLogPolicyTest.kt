package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultHeatDiagnosticLogPolicyTest {

    @Test
    fun stableEpisode_emitsEdgePeriodicSummaryAndRecoveryOnlyOnce() {
        val intervalMs = 30 * 60 * 1000L
        val policy = FaultHeatDiagnosticLogPolicy(summaryIntervalMs = intervalMs)

        val first = policy.evaluate("not_business|rest|normal", nowMs = 1_000L)
        assertEquals(FaultHeatDiagnosticLogPolicy.Kind.EMIT, first?.kind)
        assertTrue(first?.stateChanged == true)

        assertNull(policy.evaluate("not_business|rest|normal", nowMs = 11_000L))
        val summary = policy.evaluate("not_business|rest|normal", nowMs = 1_801_000L)
        assertEquals(FaultHeatDiagnosticLogPolicy.Kind.EMIT, summary?.kind)
        assertTrue(summary?.stateChanged == false)
        assertEquals(2, summary?.suppressedCount)

        val changed = policy.evaluate("fault|maintenance|ACTION_GROUP_FAILED", nowMs = 1_802_000L)
        assertEquals(FaultHeatDiagnosticLogPolicy.Kind.EMIT, changed?.kind)
        assertTrue(changed?.stateChanged == true)

        val recovered = policy.evaluate(state = null, nowMs = 1_803_000L)
        assertEquals(FaultHeatDiagnosticLogPolicy.Kind.RECOVERED, recovered?.kind)
        assertNull(policy.evaluate(state = null, nowMs = 1_804_000L))

        val restarted = policy.evaluate("not_business|rest|normal", nowMs = 1_805_000L)
        assertEquals(FaultHeatDiagnosticLogPolicy.Kind.EMIT, restarted?.kind)
        assertTrue(restarted?.stateChanged == true)
    }
}
