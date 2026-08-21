package cn.niuyannet.kaochang.android.services.reset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetOrderEvidenceTest {

    @Test
    fun prepareOrder_shouldPersistMinimalEvidenceWithoutBlockingAdmission() {
        var raw: String? = null
        val store = ResetCloseoutStore({ raw }, { value -> raw = value; true })

        assertEquals(
            PrepareOrderEvidenceResult.NEW,
            store.prepareOrderDurable(
                orderNo = "ORDER-1",
                items = listOf(ResetOrderItem(11L, 2, 0), ResetOrderItem(12L, 1, 0)),
                previousOnlineStatus = 1,
                previousOrderBlockReason = ""
            )
        )

        val record = store.load()!!
        assertEquals(ResetCloseoutPhase.ORDER_ACTIVE, record.phase)
        assertEquals("ORDER-1", record.orderNo)
        assertFalse(ResetCloseoutPolicy.blocksOrders(record))
    }

    @Test
    fun confirmedOut4_shouldPersistBeforeTerminalPublishAndIgnoreUnknownOutcome() {
        var raw: String? = null
        val store = ResetCloseoutStore({ raw }, { value -> raw = value; true })
        store.prepareOrderDurable("ORDER-1", listOf(ResetOrderItem(11L, 2, 0)), 1, "")

        assertEquals(Out4ConfirmResult.ADVANCED_THIS_CALL, store.confirmOut4Durable("ORDER-1", 11L))
        assertEquals(1, store.load()!!.items.single().confirmedOut4)
        assertEquals(Out4ConfirmResult.ADVANCED_THIS_CALL, store.confirmOut4Durable("ORDER-1", 11L))
        assertEquals(2, store.load()!!.items.single().confirmedOut4)
        assertEquals(Out4ConfirmResult.ALREADY_AT_QUANTITY, store.confirmOut4Durable("ORDER-1", 11L))
        assertEquals(2, store.load()!!.items.single().confirmedOut4)
    }

    @Test
    fun confirmedOut4DuringPreparedAndResetWritten_shouldRemainDurable() {
        var raw: String? = null
        val store = ResetCloseoutStore({ raw }, { value -> raw = value; true })
        val active = ResetCloseoutRecord(
            phase = ResetCloseoutPhase.ORDER_ACTIVE,
            orderNo = "ORDER-1",
            items = listOf(ResetOrderItem(11L, 2, 0)),
            previousOnlineStatus = 4
        )
        val prepared = active.copy(
            resetId = "reset-1",
            originProcessId = "process-1",
            kind = ResetKind.NORMAL,
            phase = ResetCloseoutPhase.PREPARED
        )
        assertTrue(store.saveDurable(prepared))

        assertEquals(Out4ConfirmResult.ADVANCED_THIS_CALL, store.confirmOut4Durable("ORDER-1", 11L))
        assertEquals(1, store.load()!!.items.single().confirmedOut4)

        assertTrue(
            store.saveDurable(
                store.load()!!.copy(
                    phase = ResetCloseoutPhase.RESET_WRITTEN,
                    resetWrittenAtMs = 1_000L
                )
            )
        )
        assertEquals(Out4ConfirmResult.ADVANCED_THIS_CALL, store.confirmOut4Durable("ORDER-1", 11L))
        assertEquals(2, store.load()!!.items.single().confirmedOut4)
    }

    @Test
    fun normalTerminalClear_shouldBeOrderScopedAndIdempotent() {
        var raw: String? = null
        val store = ResetCloseoutStore({ raw }, { value -> raw = value; true })
        store.prepareOrderDurable("ORDER-1", listOf(ResetOrderItem(11L, 1, 0)), 1, "")

        assertFalse(store.clearOrderDurable("ORDER-2"))
        assertEquals("ORDER-1", store.load()!!.orderNo)
        assertTrue(store.clearOrderDurable("ORDER-1"))
        assertNull(store.load())
        assertTrue(store.clearOrderDurable("ORDER-1"))
    }

    @Test
    fun saveFailure_shouldLeaveExistingEvidenceUnchanged() {
        var raw: String? = null
        val store = ResetCloseoutStore({ raw }, { value ->
            if (raw == null) {
                raw = value
                true
            } else {
                false
            }
        })
        store.prepareOrderDurable("ORDER-1", listOf(ResetOrderItem(11L, 1, 0)), 1, "")

        assertEquals(Out4ConfirmResult.WRITE_FAILED, store.confirmOut4Durable("ORDER-1", 11L))
        assertEquals(0, store.load()!!.items.single().confirmedOut4)
    }

    @Test
    fun duplicatePrepare_shouldPreserveConfirmedProgress() {
        var raw: String? = null
        val store = ResetCloseoutStore({ raw }, { value -> raw = value; true })
        assertEquals(
            PrepareOrderEvidenceResult.NEW,
            store.prepareOrderDurable("ORDER-1", listOf(ResetOrderItem(11L, 2, 0)), 1, "")
        )
        assertEquals(Out4ConfirmResult.ADVANCED_THIS_CALL, store.confirmOut4Durable("ORDER-1", 11L))

        assertEquals(
            PrepareOrderEvidenceResult.EXISTING,
            store.prepareOrderDurable("ORDER-1", listOf(ResetOrderItem(11L, 2, 0)), 1, "")
        )
        assertEquals(1, store.load()!!.items.single().confirmedOut4)
    }

    @Test
    fun differentActiveOrder_shouldNotBeOverwritten() {
        var raw: String? = null
        val store = ResetCloseoutStore({ raw }, { value -> raw = value; true })
        store.prepareOrderDurable("ORDER-1", listOf(ResetOrderItem(11L, 1, 0)), 1, "")

        assertEquals(
            PrepareOrderEvidenceResult.REJECTED,
            store.prepareOrderDurable("ORDER-2", listOf(ResetOrderItem(12L, 1, 0)), 1, "")
        )
        assertEquals("ORDER-1", store.load()!!.orderNo)
    }

    @Test
    fun confirmOut4_shouldReturnNotPreparedForMissingOrMismatchedEvidence() {
        var raw: String? = null
        val store = ResetCloseoutStore({ raw }, { value -> raw = value; true })

        assertEquals(Out4ConfirmResult.NOT_PREPARED, store.confirmOut4Durable("ORDER-1", 11L))
        store.prepareOrderDurable("ORDER-1", listOf(ResetOrderItem(11L, 1, 0)), 1, "")
        assertEquals(Out4ConfirmResult.NOT_PREPARED, store.confirmOut4Durable("ORDER-2", 11L))
        assertEquals(Out4ConfirmResult.NOT_PREPARED, store.confirmOut4Durable("ORDER-1", 12L))
    }

    @Test
    fun confirmOut4_shouldReturnCorruptedInsteadOfThrowing() {
        val store = ResetCloseoutStore({ "{broken-json" }, { true })

        assertEquals(Out4ConfirmResult.CORRUPTED, store.confirmOut4Durable("ORDER-1", 11L))
    }

}
