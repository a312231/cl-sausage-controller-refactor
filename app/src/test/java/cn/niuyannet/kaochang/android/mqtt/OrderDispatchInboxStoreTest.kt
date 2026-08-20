package cn.niuyannet.kaochang.android.mqtt

import com.alibaba.fastjson.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderDispatchInboxStoreTest {

    @Test
    fun rawPayloadHash_shouldUseExactUtf8Bytes() {
        val raw = "{\"order_no\":\"订单-1\",\"goods_items\":[] }"

        assertEquals(
            "beb145dc91598e0fe14e6516667f8cacb350a55b09116d2f7b3d8963508bb475",
            OrderDispatchPayloadHash.sha256Utf8(raw)
        )
    }

    @Test
    fun sameDispatchShouldBeIdempotentButHashDriftAndDifferentActiveDispatchMustReject() {
        val fixture = storeFixture()
        val first = record("D-1", "H-1")

        assertEquals(OrderDispatchOfferStoreResult.ACCEPTED, fixture.store.acceptOfferDurably(first))
        assertEquals(OrderDispatchOfferStoreResult.ALREADY_ACCEPTED, fixture.store.acceptOfferDurably(first))
        assertEquals(
            OrderDispatchOfferStoreResult.HASH_MISMATCH,
            fixture.store.acceptOfferDurably(first.copy(payloadHash = "H-DRIFT"))
        )
        assertEquals(
            OrderDispatchOfferStoreResult.ACTIVE_DISPATCH_CONFLICT,
            fixture.store.acceptOfferDurably(record("D-2", "H-2"))
        )
        assertEquals("D-1", fixture.store.load()!!.dispatchId)
        assertEquals("H-1", fixture.store.load()!!.payloadHash)
    }

    @Test
    fun commitShouldAuthorizeAndStartAtMostOnceAcrossRestart() {
        val fixture = storeFixture()
        val original = record("D-1", "H-1")
        assertEquals(OrderDispatchOfferStoreResult.ACCEPTED, fixture.store.acceptOfferDurably(original))
        assertEquals(
            OrderDispatchCommitStoreResult.AUTHORIZED,
            fixture.store.authorizeCommitDurably(commit("D-1", "H-1"))
        )
        assertEquals(
            OrderDispatchCommitStoreResult.RESUME_AUTHORIZED,
            fixture.store.authorizeCommitDurably(commit("D-1", "H-1"))
        )
        assertTrue(fixture.store.markExecutionStartedDurably("D-1"))
        assertFalse(fixture.store.markExecutionStartedDurably("D-1"))

        val restarted = OrderDispatchInboxStore({ fixture.raw }, { value -> fixture.raw = value; true }, { NOW })
        assertEquals(OrderDispatchInboxState.EXECUTION_STARTED, restarted.load()!!.state)
        assertEquals(
            OrderDispatchCommitStoreResult.ALREADY_STARTED,
            restarted.authorizeCommitDurably(commit("D-1", "H-1"))
        )
        assertTrue(restarted.canPublishOrderEvent("D-1", "ORDER-1"))
        assertTrue(restarted.markTerminalDurably("D-1"))
        assertTrue(restarted.canPublishOrderEvent("D-1", "ORDER-1"))
    }

    @Test
    fun rejectedAndAllRestartStates_shouldRemainFailClosed() {
        val states = listOf(
            OrderDispatchInboxState.ACCEPTED,
            OrderDispatchInboxState.COMMIT_AUTHORIZED,
            OrderDispatchInboxState.EXECUTION_STARTED,
            OrderDispatchInboxState.REJECTED_NO_EXECUTION,
            OrderDispatchInboxState.TERMINAL
        )
        states.forEach { state ->
            val fixture = storeFixture()
            val current = record("D-$state", "H")
            if (state == OrderDispatchInboxState.REJECTED_NO_EXECUTION) {
                assertEquals(
                    OrderDispatchOfferStoreResult.REJECTED_DURABLY,
                    fixture.store.rejectNoExecutionDurably(current, "TEST_REJECT")
                )
            } else {
                assertEquals(OrderDispatchOfferStoreResult.ACCEPTED, fixture.store.acceptOfferDurably(current))
                if (state != OrderDispatchInboxState.ACCEPTED) {
                    assertEquals(
                        OrderDispatchCommitStoreResult.AUTHORIZED,
                        fixture.store.authorizeCommitDurably(commit("D-$state", "H"))
                    )
                }
                if (state == OrderDispatchInboxState.EXECUTION_STARTED || state == OrderDispatchInboxState.TERMINAL) {
                    assertTrue(fixture.store.markExecutionStartedDurably("D-$state"))
                }
                if (state == OrderDispatchInboxState.TERMINAL) {
                    assertTrue(fixture.store.markTerminalDurably("D-$state"))
                }
            }
            val restarted = OrderDispatchInboxStore({ fixture.raw }, { value -> fixture.raw = value; true }, { NOW })

            assertEquals(state, restarted.load()!!.state)
            assertEquals(
                state == OrderDispatchInboxState.EXECUTION_STARTED || state == OrderDispatchInboxState.TERMINAL,
                restarted.canPublishOrderEvent("D-$state", "ORDER-1")
            )
        }
    }

    @Test
    fun offerDurableFailure_shouldNotAckAndBusyOrExpiredShouldPersistTombstone() {
        listOf(false, true).forEach { busy ->
            var raw: String? = null
            var ackCalls = 0
            val store = OrderDispatchInboxStore({ raw }, { value -> raw = value; true }, { NOW })
            val handler = handler(
                store = store,
                canAccept = { !busy },
                publishAck = { ackCalls++; true }
            )

            val result = handler.handleAction2(offerJson(
                dispatchId = if (busy) "D-BUSY" else "D-EXPIRED",
                ackExpiresAtMs = if (busy) NOW + 1_000 else NOW - 1,
                commitExpiresAtMs = NOW + 5_000
            ))

            assertEquals(OrderDispatchHandleResult.OFFER_REJECTED, result)
            assertEquals(1, ackCalls)
            assertEquals(OrderDispatchInboxState.REJECTED_NO_EXECUTION, store.load()!!.state)
        }

        var ackCalls = 0
        val failedStore = OrderDispatchInboxStore({ null }, { false }, { NOW })
        val failed = handler(failedStore, publishAck = { ackCalls++; true })
            .handleAction2(offerJson("D-WRITE-FAIL"))
        assertEquals(OrderDispatchHandleResult.DURABLE_WRITE_FAILED, failed)
        assertEquals(0, ackCalls)
    }

    @Test
    fun offerMustHaveNoAdmissionOrExecutionSideEffectsAndCommitRunsOnlyOnce() {
        val fixture = storeFixture()
        var admissionCalls = 0
        var executionCalls = 0
        var rejectedCalls = 0
        val handler = handler(
            store = fixture.store,
            admit = { _, beforeAccept -> admissionCalls++; beforeAccept() },
            execute = { executionCalls++ },
            publishCommitRejected = { rejectedCalls++; true }
        )

        assertEquals(OrderDispatchHandleResult.OFFER_ACCEPTED, handler.handleAction2(offerJson("D-1")))
        assertEquals(0, admissionCalls)
        assertEquals(0, executionCalls)
        assertEquals(OrderDispatchInboxState.ACCEPTED, fixture.store.load()!!.state)

        assertEquals(OrderDispatchHandleResult.COMMIT_STARTED, handler.handleAction2(commitJson("D-1")))
        assertEquals(1, admissionCalls)
        assertEquals(1, executionCalls)
        assertEquals(OrderDispatchInboxState.EXECUTION_STARTED, fixture.store.load()!!.state)

        assertEquals(OrderDispatchHandleResult.ALREADY_STARTED, handler.handleAction2(commitJson("D-1")))
        assertEquals(1, admissionCalls)
        assertEquals(1, executionCalls)
        assertEquals(0, rejectedCalls)
    }

    @Test
    fun admissionFailureAfterExecutionBoundary_shouldRemainUnknownWithoutRefundSignal() {
        val fixture = storeFixture()
        var rejectedCalls = 0
        var executionCalls = 0
        val handler = handler(
            fixture.store,
            admit = { _, beforeAccept -> beforeAccept(); false },
            execute = { executionCalls++ },
            publishCommitRejected = { rejectedCalls++; true }
        )

        handler.handleAction2(offerJson("D-1"))
        assertEquals(OrderDispatchHandleResult.START_FAILED_UNKNOWN, handler.handleAction2(commitJson("D-1")))
        assertEquals(0, executionCalls)
        assertEquals(0, rejectedCalls)
        assertEquals(OrderDispatchInboxState.EXECUTION_STARTED, fixture.store.load()!!.state)
    }

    @Test
    fun commitBusyBeforeBeforeAccept_shouldPersistRejectedNoExecutionTombstone() {
        val fixture = storeFixture()
        var rejectedCalls = 0
        val handler = handler(
            fixture.store,
            admit = { _, _ -> false },
            publishCommitRejected = { rejectedCalls++; true }
        )

        handler.handleAction2(offerJson("D-BUSY-COMMIT"))
        assertEquals(
            OrderDispatchHandleResult.COMMIT_REJECTED,
            handler.handleAction2(commitJson("D-BUSY-COMMIT"))
        )
        assertEquals(1, rejectedCalls)
        assertEquals(OrderDispatchInboxState.REJECTED_NO_EXECUTION, fixture.store.load()!!.state)
    }

    @Test
    fun duplicateCommitWhileCommitAuthorized_shouldRetryAdmissionWithoutPrematureRejection() {
        val fixture = storeFixture()
        val accepted = record("D-RESUME", OrderDispatchPayloadHash.sha256Utf8(RAW_ORDER))
        assertEquals(OrderDispatchOfferStoreResult.ACCEPTED, fixture.store.acceptOfferDurably(accepted))
        assertEquals(
            OrderDispatchCommitStoreResult.AUTHORIZED,
            fixture.store.authorizeCommitDurably(commit("D-RESUME", accepted.payloadHash))
        )
        var admissionCalls = 0
        var executionCalls = 0
        var rejectedCalls = 0
        val handler = handler(
            fixture.store,
            admit = { _, beforeAccept -> admissionCalls++; beforeAccept() },
            execute = { executionCalls++ },
            publishCommitRejected = { rejectedCalls++; true }
        )

        assertEquals(OrderDispatchHandleResult.COMMIT_STARTED, handler.handleAction2(commitJson("D-RESUME")))
        assertEquals(1, admissionCalls)
        assertEquals(1, executionCalls)
        assertEquals(0, rejectedCalls)
        assertEquals(OrderDispatchInboxState.EXECUTION_STARTED, fixture.store.load()!!.state)
    }

    @Test
    fun duplicateCommitAfterExecutionStartedOrTerminal_shouldBeIgnoredWithoutRefundSignal() {
        listOf(false, true).forEach { terminal ->
            val fixture = storeFixture()
            val dispatchId = if (terminal) "D-TERMINAL" else "D-STARTED"
            val accepted = record(dispatchId, OrderDispatchPayloadHash.sha256Utf8(RAW_ORDER))
            fixture.store.acceptOfferDurably(accepted)
            fixture.store.authorizeCommitDurably(commit(dispatchId, accepted.payloadHash))
            fixture.store.markExecutionStartedDurably(dispatchId)
            if (terminal) fixture.store.markTerminalDurably(dispatchId)
            var executionCalls = 0
            var rejectedCalls = 0
            val handler = handler(
                fixture.store,
                execute = { executionCalls++ },
                publishCommitRejected = { rejectedCalls++; true }
            )

            val result = handler.handleAction2(commitJson(dispatchId))

            assertEquals(
                if (terminal) OrderDispatchHandleResult.TERMINAL_IGNORED
                else OrderDispatchHandleResult.ALREADY_STARTED,
                result
            )
            assertEquals(0, executionCalls)
            assertEquals(0, rejectedCalls)
        }
    }

    @Test
    fun executeThrowAfterExecutionStarted_shouldReturnUnknownWithoutNoExecutionRejection() {
        val fixture = storeFixture()
        var rejectedCalls = 0
        val handler = handler(
            fixture.store,
            execute = { error("UI start failed") },
            publishCommitRejected = { rejectedCalls++; true }
        )

        handler.handleAction2(offerJson("D-EXEC-THROW"))
        val result = handler.handleAction2(commitJson("D-EXEC-THROW"))

        assertEquals(OrderDispatchHandleResult.START_FAILED_UNKNOWN, result)
        assertEquals(0, rejectedCalls)
        assertEquals(OrderDispatchInboxState.EXECUTION_STARTED, fixture.store.load()!!.state)
    }

    @Test
    fun duplicateCommitForRejectedTombstone_shouldOnlyReplayStoredCommitRejection() {
        val fixture = storeFixture()
        val dispatchId = "D-REJECTED"
        val accepted = record(dispatchId, OrderDispatchPayloadHash.sha256Utf8(RAW_ORDER))
        assertEquals(
            OrderDispatchOfferStoreResult.REJECTED_DURABLY,
            fixture.store.rejectNoExecutionDurably(accepted, "DEVICE_BUSY")
        )
        val reasons = mutableListOf<String>()
        var executionCalls = 0
        val handler = handler(
            fixture.store,
            execute = { executionCalls++ },
            publishCommitRejected = { rejected -> reasons += rejected.reason; true }
        )

        assertEquals(OrderDispatchHandleResult.COMMIT_REJECTED, handler.handleAction2(commitJson(dispatchId)))
        assertEquals(listOf("DEVICE_BUSY"), reasons)
        assertEquals(0, executionCalls)
        assertEquals(OrderDispatchInboxState.REJECTED_NO_EXECUTION, fixture.store.load()!!.state)
    }

    @Test
    fun backendOfferWithoutCommitDeadline_shouldAcceptThenPersistDeadlineFromFirstCommit() {
        val fixture = storeFixture()
        val handler = handler(fixture.store)

        assertEquals(
            OrderDispatchHandleResult.OFFER_ACCEPTED,
            handler.handleAction2(offerJson("D-BACKEND", includeCommitDeadline = false))
        )
        assertEquals(0L, fixture.store.load()!!.commitExpiresAtMs)

        val deadline = NOW + 5_000
        assertEquals(
            OrderDispatchHandleResult.COMMIT_STARTED,
            handler.handleAction2(commitJson("D-BACKEND", commitExpiresAtMs = deadline))
        )
        assertEquals(deadline, fixture.store.load()!!.commitExpiresAtMs)
    }

    @Test
    fun expiredAcceptedSlot_shouldAllowNewDispatchAndLateOldCommitMustNeverExecute() {
        val fixture = storeFixture()
        val old = record("D-OLD", OrderDispatchPayloadHash.sha256Utf8(RAW_ORDER)).copy(
            ackExpiresAtMs = NOW + 100,
            commitExpiresAtMs = 0L
        )
        assertEquals(OrderDispatchOfferStoreResult.ACCEPTED, fixture.store.acceptOfferDurably(old))
        fixture.now = NOW + 101

        val replacement = record("D-NEW", OrderDispatchPayloadHash.sha256Utf8(RAW_ORDER)).copy(
            ackExpiresAtMs = fixture.now + 1_000,
            commitExpiresAtMs = 0L
        )
        assertEquals(OrderDispatchOfferStoreResult.ACCEPTED, fixture.store.acceptOfferDurably(replacement))
        assertEquals("D-NEW", fixture.store.load()!!.dispatchId)
        assertEquals(
            OrderDispatchCommitStoreResult.IDENTITY_MISMATCH,
            fixture.store.authorizeCommitDurably(commit("D-OLD", old.payloadHash))
        )

        var executionCalls = 0
        var rejectedCalls = 0
        val handler = handler(
            fixture.store,
            nowMs = { fixture.now },
            execute = { executionCalls++ },
            publishCommitRejected = { rejectedCalls++; true }
        )
        assertEquals(OrderDispatchHandleResult.COMMIT_REJECTED, handler.handleAction2(commitJson("D-OLD")))
        assertEquals(0, executionCalls)
        assertEquals(1, rejectedCalls)
        assertEquals("D-NEW", fixture.store.load()!!.dispatchId)
    }

    @Test
    fun sameExpiredAcceptedOffer_shouldBecomeRejectedTombstone() {
        val fixture = storeFixture()
        val dispatchId = "D-EXPIRED-SAME"
        val accepted = record(dispatchId, OrderDispatchPayloadHash.sha256Utf8(RAW_ORDER)).copy(
            ackExpiresAtMs = NOW + 100,
            commitExpiresAtMs = 0L
        )
        assertEquals(OrderDispatchOfferStoreResult.ACCEPTED, fixture.store.acceptOfferDurably(accepted))
        fixture.now = NOW + 101
        val handler = handler(fixture.store, nowMs = { fixture.now })

        assertEquals(
            OrderDispatchHandleResult.OFFER_REJECTED,
            handler.handleAction2(
                offerJson(
                    dispatchId = dispatchId,
                    ackExpiresAtMs = NOW + 100,
                    includeCommitDeadline = false
                )
            )
        )
        assertEquals(OrderDispatchInboxState.REJECTED_NO_EXECUTION, fixture.store.load()!!.state)
        assertEquals("OFFER_EXPIRED", fixture.store.load()!!.rejectionReason)
    }

    @Test
    fun expiredAckMustNotReplaceCommitAuthorizedOrExecutionStarted() {
        listOf(false, true).forEach { executionStarted ->
            val fixture = storeFixture()
            val dispatchId = if (executionStarted) "D-RUNNING" else "D-AUTHORIZED"
            val accepted = record(dispatchId, OrderDispatchPayloadHash.sha256Utf8(RAW_ORDER)).copy(
                ackExpiresAtMs = NOW + 100,
                commitExpiresAtMs = 0L
            )
            fixture.store.acceptOfferDurably(accepted)
            fixture.store.authorizeCommitDurably(commit(dispatchId, accepted.payloadHash))
            if (executionStarted) fixture.store.markExecutionStartedDurably(dispatchId)
            fixture.now = NOW + 10_000

            assertEquals(
                OrderDispatchOfferStoreResult.ACTIVE_DISPATCH_CONFLICT,
                fixture.store.acceptOfferDurably(
                    record("D-OTHER-$executionStarted", OrderDispatchPayloadHash.sha256Utf8(RAW_ORDER)).copy(
                        ackExpiresAtMs = fixture.now + 1_000,
                        commitExpiresAtMs = 0L
                    )
                )
            )
            assertEquals(dispatchId, fixture.store.load()!!.dispatchId)
        }
    }

    @Test
    fun duplicateAcceptedOfferBeforeExpiry_shouldReAckAcceptedEvenIfDeviceBecameBusy() {
        val fixture = storeFixture()
        var busy = false
        val acceptedFlags = mutableListOf<Boolean>()
        val handler = handler(
            fixture.store,
            canAccept = { !busy },
            publishAck = { ack -> acceptedFlags += ack.accepted; true },
            nowMs = { fixture.now }
        )
        val offer = offerJson(
            dispatchId = "D-REACK",
            ackExpiresAtMs = NOW + 1_000,
            includeCommitDeadline = false
        )

        assertEquals(OrderDispatchHandleResult.OFFER_ACCEPTED, handler.handleAction2(offer))
        busy = true
        assertEquals(OrderDispatchHandleResult.OFFER_ACCEPTED, handler.handleAction2(offer))
        assertEquals(listOf(true, true), acceptedFlags)
        assertEquals(OrderDispatchInboxState.ACCEPTED, fixture.store.load()!!.state)
        assertEquals(null, fixture.store.load()!!.rejectionReason)
    }

    private fun handler(
        store: OrderDispatchInboxStore,
        canAccept: (OrderDispatchInboxRecord) -> Boolean = { true },
        publishAck: (OrderDispatchOfferAckV2) -> Boolean = { true },
        admit: (OrderDispatchInboxRecord, () -> Boolean) -> Boolean = { _, beforeAccept -> beforeAccept() },
        execute: (OrderDispatchInboxRecord) -> Unit = {},
        publishCommitRejected: (OrderDispatchCommitRejectedV2) -> Boolean = { true },
        nowMs: () -> Long = { NOW }
    ) = OrderDispatchV2Handler(
        store = store,
        expectedDeviceId = DEVICE_ID,
        nowMs = nowMs,
        canAcceptOffer = canAccept,
        publishOfferAck = publishAck,
        admitCommit = admit,
        executeAcceptedCommit = execute,
        publishCommitRejected = publishCommitRejected
    )

    private fun offerJson(
        dispatchId: String,
        ackExpiresAtMs: Long = NOW + 1_000,
        commitExpiresAtMs: Long = NOW + 5_000,
        includeCommitDeadline: Boolean = true
    ): String {
        val raw = RAW_ORDER
        return JSONObject().apply {
            put("messageType", MqttProtocol.TYPE_ORDER_DISPATCH_OFFER_V2)
            put("protocolVersion", MqttProtocol.ORDER_DISPATCH_PROTOCOL_VERSION)
            put("dispatch_id", dispatchId)
            put("order_no", "ORDER-1")
            put("device_id", DEVICE_ID)
            put("payload_hash", OrderDispatchPayloadHash.sha256Utf8(raw))
            put("raw_order_payload", raw)
            put("ack_expires_at_ms", ackExpiresAtMs)
            if (includeCommitDeadline) {
                put("commit_expires_at_ms", commitExpiresAtMs)
            }
        }.toJSONString()
    }

    private fun commitJson(
        dispatchId: String,
        commitExpiresAtMs: Long = NOW + 5_000
    ): String = JSONObject().apply {
        put("messageType", MqttProtocol.TYPE_ORDER_DISPATCH_COMMIT_V2)
        put("protocolVersion", MqttProtocol.ORDER_DISPATCH_PROTOCOL_VERSION)
        put("dispatch_id", dispatchId)
        put("order_no", "ORDER-1")
        put("device_id", DEVICE_ID)
        put("payload_hash", OrderDispatchPayloadHash.sha256Utf8(RAW_ORDER))
        put("commit_expires_at_ms", commitExpiresAtMs)
    }.toJSONString()

    private fun record(dispatchId: String, hash: String): OrderDispatchInboxRecord = OrderDispatchInboxRecord(
        dispatchId = dispatchId,
        orderNo = "ORDER-1",
        deviceId = DEVICE_ID,
        payloadHash = hash,
        rawPayload = RAW_ORDER,
        ackExpiresAtMs = NOW + 1_000,
        commitExpiresAtMs = NOW + 5_000
    )

    private fun commit(dispatchId: String, hash: String) = OrderDispatchCommitV2(
        dispatchId = dispatchId,
        orderNo = "ORDER-1",
        deviceId = DEVICE_ID,
        payloadHash = hash,
        commitExpiresAtMs = NOW + 5_000
    )

    private fun storeFixture(): Fixture = Fixture()

    private class Fixture {
        var raw: String? = null
        var now: Long = NOW
        val store = OrderDispatchInboxStore({ raw }, { value -> raw = value; true }, { now })
    }

    companion object {
        private const val NOW = 1_000_000L
        private const val DEVICE_ID = "149"
        private const val RAW_ORDER = "{\"order_no\":\"ORDER-1\",\"goods_items\":[{\"id\":1,\"productId\":2,\"tasteCode\":\"hot\",\"quantity\":1}]}"
    }
}
