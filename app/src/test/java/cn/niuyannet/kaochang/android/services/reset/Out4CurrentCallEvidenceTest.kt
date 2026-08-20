package cn.niuyannet.kaochang.android.services.reset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Out4CurrentCallEvidenceTest {

    @Test
    fun repeatedTelemetry_shouldAdvanceAndSavePendingOnlyOnce() {
        val evidence = Out4CurrentCallEvidence()
        var confirmCalls = 0
        var pendingSaveCalls = 0

        repeat(2) {
            evidence.observeConfirmedOut4(
                confirmDurably = {
                    confirmCalls++
                    Out4ConfirmResult.ADVANCED_THIS_CALL
                },
                savePendingDurably = {
                    pendingSaveCalls++
                    true
                }
            )
        }

        assertEquals(1, confirmCalls)
        assertEquals(1, pendingSaveCalls)
        assertEquals(Out4CurrentCallEvidenceState.CONFIRMED_DURABLE_PENDING, evidence.state)
        assertTrue(evidence.advancedThisCall)
        assertFalse(evidence.pendingSaveFailure)
    }

    @Test
    fun alreadyAtQuantity_shouldNotConfirmCurrentCallOrSavePending() {
        assertTerminalWithoutPending(Out4ConfirmResult.ALREADY_AT_QUANTITY)
    }

    @Test
    fun notPreparedOrCorrupted_shouldNotConfirmCurrentCallOrSavePending() {
        assertTerminalWithoutPending(Out4ConfirmResult.NOT_PREPARED)
        assertTerminalWithoutPending(Out4ConfirmResult.CORRUPTED)
    }

    @Test
    fun writeFailed_shouldBeStickyAndRepeatedTelemetryMustNotRetryStore() {
        val evidence = Out4CurrentCallEvidence()
        var confirmCalls = 0

        repeat(2) {
            evidence.observeConfirmedOut4(
                confirmDurably = {
                    confirmCalls++
                    Out4ConfirmResult.WRITE_FAILED
                },
                savePendingDurably = { error("pending must not be saved") }
            )
        }

        assertEquals(1, confirmCalls)
        assertEquals(Out4CurrentCallEvidenceState.UNKNOWN, evidence.state)
        assertFalse(evidence.advancedThisCall)
        assertFalse(evidence.pendingSaveFailure)
    }

    @Test
    fun pendingSaveFalseOrThrow_shouldPreserveAdvancedEvidenceAndLatchFailure() {
        listOf<() -> Boolean>({ false }, { error("disk failed") }).forEach { savePending ->
            val evidence = Out4CurrentCallEvidence()

            evidence.observeConfirmedOut4(
                confirmDurably = { Out4ConfirmResult.ADVANCED_THIS_CALL },
                savePendingDurably = savePending
            )

            assertEquals(Out4CurrentCallEvidenceState.CONFIRMED_PENDING_MISSING, evidence.state)
            assertTrue(evidence.advancedThisCall)
            assertTrue(evidence.pendingSaveFailure)
        }
    }

    private fun assertTerminalWithoutPending(result: Out4ConfirmResult) {
        val evidence = Out4CurrentCallEvidence()
        var pendingSaveCalls = 0

        evidence.observeConfirmedOut4(
            confirmDurably = { result },
            savePendingDurably = {
                pendingSaveCalls++
                true
            }
        )

        assertEquals(0, pendingSaveCalls)
        assertEquals(Out4CurrentCallEvidenceState.UNKNOWN, evidence.state)
        assertFalse(evidence.advancedThisCall)
    }
}
