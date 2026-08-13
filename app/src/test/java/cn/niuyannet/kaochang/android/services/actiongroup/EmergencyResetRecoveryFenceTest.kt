package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EmergencyResetRecoveryFenceTest {

    @Test
    fun fence_shouldRequireARealProcessBoundaryWithoutBindingOldCoordinationIdentity() {
        val prepared = EmergencyResetRecoveryFence.prepared(
            resetId = "reset-1",
            originProcessInstanceId = "process-a",
            createdAtMs = 100L
        )

        assertEquals(EmergencyResetRecoveryPhase.PREPARED, prepared.phase)
        assertTrue(prepared.matchesReset("reset-1"))
        assertFalse(prepared.matchesReset("reset-2"))
        assertFalse(prepared.hasCrossedProcessBoundary("process-a"))
        assertTrue(prepared.hasCrossedProcessBoundary("process-b"))
        assertEquals(
            EmergencyResetRecoveryPhase.RESET_WRITTEN,
            prepared.markResetWritten().phase
        )
    }

    @Test
    fun store_shouldRoundTripReplaceAndClearFenceDurably() {
        var raw: String? = null
        val store = EmergencyResetRecoveryFenceStore(
            readRaw = { raw },
            writeRawDurably = {
                raw = it
                true
            }
        )
        val first = EmergencyResetRecoveryFence.prepared("reset-1", "process-a", 100L)
        val second = EmergencyResetRecoveryFence.prepared("reset-2", "process-b", 200L)
            .markResetWritten()

        store.save(first)
        assertEquals(first, store.load())
        store.save(second)
        assertEquals(second, store.load())
        store.clear()
        assertNull(store.load())
        assertEquals("", raw)
    }

    @Test
    fun store_shouldFailClosedForCorruptionAndDurableWriteFailure() {
        val corrupted = EmergencyResetRecoveryFenceStore(
            readRaw = { "{broken-json" },
            writeRawDurably = { true }
        )
        expectIllegalState { corrupted.load() }

        val failed = EmergencyResetRecoveryFenceStore(
            readRaw = { null },
            writeRawDurably = { false }
        )
        expectIllegalState {
            failed.save(EmergencyResetRecoveryFence.prepared("reset-1", "process-a", 100L))
        }
        expectIllegalState { failed.clear() }
    }

    @Test
    fun store_shouldAllowAConfirmedRepeatResetToReplaceResetWrittenFence() {
        var raw: String? = null
        val store = EmergencyResetRecoveryFenceStore(
            readRaw = { raw },
            writeRawDurably = { value -> raw = value; true }
        )
        val first = EmergencyResetRecoveryFence.prepared("reset-1", "process-a", 100L)
            .markResetWritten()
        val repeated = EmergencyResetRecoveryFence.prepared("reset-2", "process-b", 200L)

        store.save(first)
        store.save(repeated)

        assertEquals(repeated, store.load())
    }

    @Test(expected = AssertionError::class)
    fun store_shouldNotWrapFatalDecoderErrors() {
        EmergencyResetRecoveryFenceStore(
            readRaw = { "raw" },
            writeRawDurably = { true },
            decodeRaw = { throw AssertionError("fatal") }
        ).load()
    }

    @Test
    fun forceCloseoutPolicy_shouldRequireConfirmationTwoIdleSnapshotsAndColdRestart() {
        val idle = ManualDeviceRecoveryCloseoutDecision(true, "下位机已空闲")
        val running = ManualDeviceRecoveryCloseoutDecision(false, "动作仍在运行")

        assertTrue(
            EmergencyResetForceCloseoutPolicy.evaluate(
                administratorConfirmed = true,
                maintenanceLocked = true,
                resetFenceMatches = true,
                processBoundaryCrossed = true,
                snapshotDecisions = listOf(idle, idle),
                panMoveResults203 = listOf(0, 0)
            ).allowed
        )
        assertFalse(
            EmergencyResetForceCloseoutPolicy.evaluate(false, true, true, true, listOf(idle, idle), listOf(0, 0)).allowed
        )
        assertFalse(
            EmergencyResetForceCloseoutPolicy.evaluate(true, false, true, true, listOf(idle, idle), listOf(0, 0)).allowed
        )
        assertFalse(
            EmergencyResetForceCloseoutPolicy.evaluate(true, true, false, true, listOf(idle, idle), listOf(0, 0)).allowed
        )
        assertFalse(
            EmergencyResetForceCloseoutPolicy.evaluate(true, true, true, false, listOf(idle, idle), listOf(0, 0)).allowed
        )
        assertFalse(
            EmergencyResetForceCloseoutPolicy.evaluate(true, true, true, true, listOf(idle, running), listOf(0, 0)).allowed
        )
        assertFalse(
            EmergencyResetForceCloseoutPolicy.evaluate(true, true, true, true, listOf(idle, idle), listOf(0, 1)).allowed
        )
        assertFalse(
            EmergencyResetForceCloseoutPolicy.evaluate(true, true, true, true, listOf(idle, idle), listOf(0, null)).allowed
        )
    }

    private fun expectIllegalState(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}
