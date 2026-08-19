package cn.niuyannet.kaochang.android.services.reset

import cn.niuyannet.kaochang.android.services.actiongroup.ActionGroupTelemetry
import cn.niuyannet.kaochang.android.services.actiongroup.AtomicActionCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResetCloseoutPolicyTest {

    @Test
    fun onlyPreparedAndResetWritten_shouldBlockOrders() {
        assertFalse(ResetCloseoutPolicy.blocksOrders(record(ResetCloseoutPhase.ORDER_ACTIVE)))
        assertTrue(ResetCloseoutPolicy.blocksOrders(record(ResetCloseoutPhase.PREPARED)))
        assertTrue(ResetCloseoutPolicy.blocksOrders(record(ResetCloseoutPhase.RESET_WRITTEN)))
        assertFalse(ResetCloseoutPolicy.blocksOrders(record(ResetCloseoutPhase.SERVER_CLOSED)))
        assertFalse(ResetCloseoutPolicy.blocksOrders(null))
    }

    @Test
    fun confirmedOut4_shouldIncrementOnlyTheMatchingItemAndClampAtQuantity() {
        val original = record(ResetCloseoutPhase.ORDER_ACTIVE).copy(
            items = listOf(
                ResetOrderItem(itemId = 11L, quantity = 2, confirmedOut4 = 1),
                ResetOrderItem(itemId = 12L, quantity = 1, confirmedOut4 = 0)
            )
        )

        val first = ResetCloseoutPolicy.confirmOut4(original, 11L)
        val repeated = ResetCloseoutPolicy.confirmOut4(first, 11L)

        assertEquals(2, first.items.first().confirmedOut4)
        assertEquals(0, first.items.last().confirmedOut4)
        assertEquals(first, repeated)
        assertEquals(0, ResetCloseoutPolicy.remainingCount(repeated.items.first()))
        assertEquals(1, ResetCloseoutPolicy.remainingCount(repeated.items.last()))
    }

    @Test
    fun onlyFinishedSuccessfulOut4Telemetry_shouldConfirmDelivery() {
        assertFalse(ResetCloseoutPolicy.isConfirmedOut4Delivery(out4Telemetry("started", null, null)))
        assertFalse(
            ResetCloseoutPolicy.isConfirmedOut4Delivery(
                out4Telemetry("finished", AtomicActionCodes.OUT_EXIT_SAUSAGE_NOT_DETECTED, AtomicActionCodes.OUT_EXIT_SAUSAGE_NOT_DETECTED)
            )
        )
        assertTrue(
            ResetCloseoutPolicy.isConfirmedOut4Delivery(
                out4Telemetry("finished", AtomicActionCodes.COMPLETED, null)
            )
        )
    }

    @Test
    fun serverBlockConfirmation_shouldRequireSuccessfulBusinessResponse() {
        assertTrue(ResetCloseoutPolicy.isServerBlockConfirmed(0, "{\"code\":200}"))
        assertFalse(ResetCloseoutPolicy.isServerBlockConfirmed(0, "{\"code\":500}"))
        assertFalse(ResetCloseoutPolicy.isServerBlockConfirmed(1, "{\"code\":200}"))
        assertFalse(ResetCloseoutPolicy.isServerBlockConfirmed(0, null))
        assertFalse(ResetCloseoutPolicy.isServerBlockConfirmed(0, "not-json"))
    }

    @Test
    fun stableOnlineStatus_shouldPreserveNormalStateAndCloseTransientWorkingState() {
        assertEquals(1, ResetCloseoutPolicy.resolveStableOnlineStatus(ResetKind.NORMAL, 1, 0))
        assertEquals(2, ResetCloseoutPolicy.resolveStableOnlineStatus(ResetKind.NORMAL, 2, 0))
        assertEquals(3, ResetCloseoutPolicy.resolveStableOnlineStatus(ResetKind.NORMAL, 3, 2))
        assertEquals(1, ResetCloseoutPolicy.resolveStableOnlineStatus(ResetKind.NORMAL, 4, 0))
        assertEquals(3, ResetCloseoutPolicy.resolveStableOnlineStatus(ResetKind.NORMAL, 4, 2))
        assertEquals(3, ResetCloseoutPolicy.resolveStableOnlineStatus(ResetKind.EMERGENCY, 1, 0))
    }

    @Test
    fun recordValidation_shouldRejectImpossibleEvidenceAndIncompleteResetPhase() {
        expectIllegalState {
            record(ResetCloseoutPhase.ORDER_ACTIVE).copy(
                items = listOf(ResetOrderItem(11L, 1, 2))
            ).requireValid()
        }
        expectIllegalState {
            record(ResetCloseoutPhase.PREPARED).copy(resetId = null).requireValid()
        }
        expectIllegalState {
            record(ResetCloseoutPhase.RESET_WRITTEN).copy(resetWrittenAtMs = null).requireValid()
        }
    }

    @Test
    fun store_shouldRoundTripAndFailClosedForCorruption() {
        var raw: String? = null
        val store = ResetCloseoutStore(
            readRaw = { raw },
            writeRawDurably = { value -> raw = value; true }
        )
        val expected = record(ResetCloseoutPhase.RESET_WRITTEN)

        store.saveDurable(expected)
        assertEquals(expected, store.load())
        store.clearDurable()
        assertNull(store.load())

        raw = "{broken-json"
        expectIllegalState { store.load() }
    }

    @Test
    fun store_shouldExposeDurableWriteFailure() {
        val store = ResetCloseoutStore(
            readRaw = { null },
            writeRawDurably = { false }
        )

        assertFalse(store.saveDurable(record(ResetCloseoutPhase.ORDER_ACTIVE)))
        assertFalse(store.clearDurable())
    }

    private fun record(phase: ResetCloseoutPhase): ResetCloseoutRecord = ResetCloseoutRecord(
        resetId = if (phase == ResetCloseoutPhase.ORDER_ACTIVE) null else "reset-1",
        originProcessId = if (phase == ResetCloseoutPhase.ORDER_ACTIVE) null else "process-1",
        kind = if (phase == ResetCloseoutPhase.ORDER_ACTIVE) null else ResetKind.NORMAL,
        phase = phase,
        orderNo = "ORDER-1",
        items = listOf(ResetOrderItem(itemId = 11L, quantity = 2, confirmedOut4 = 0)),
        previousOnlineStatus = 1,
        previousOrderBlockReason = "LOWER_PLATFORM_RECOVERY_REQUIRED",
        resetWrittenAtMs = if (phase == ResetCloseoutPhase.RESET_WRITTEN || phase == ResetCloseoutPhase.SERVER_CLOSED) 1_000L else null
    ).requireValid()

    private fun expectIllegalState(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    private fun out4Telemetry(eventPhase: String, resultCode: Int?, errorCode: Int?): ActionGroupTelemetry =
        ActionGroupTelemetry(
            eventPhase = eventPhase,
            sessionId = "session-1",
            actionName = "out4",
            registerAddress = 0,
            resultRegister = 0,
            resultCode = resultCode,
            errorCode = errorCode,
            errorMessage = null,
            boxNo = null,
            panNo = 1,
            orderId = "ORDER-1",
            resourceLocks = null,
            elapsedMs = 1L
        )
}
