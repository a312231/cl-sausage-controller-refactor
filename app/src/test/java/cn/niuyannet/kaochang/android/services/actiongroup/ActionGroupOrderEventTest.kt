package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.alibaba.fastjson.JSON

class ActionGroupOrderEventTest {

    @Test
    fun buildPayload_shouldIncludeOrderContextAndTerminalStatus() {
        val payload = ActionGroupOrderEventPayloadBuilder.build(
            ActionGroupOrderContext(
                orderNo = "KC202604280001",
                itemId = 12,
                productId = 34,
                tasteCode = "taste-hot"
            ),
            status = ActionGroupOrderStatus.CUSTOMER_TIMEOUT_DISCARDED
        )

        assertEquals("KC202604280001", payload.getString("order_no"))
        assertEquals(12, payload.getInteger("item_id"))
        assertEquals(34, payload.getInteger("product_id"))
        assertEquals("taste-hot", payload.getString("taste_code"))
        assertEquals(ActionGroupOrderStatus.CUSTOMER_TIMEOUT_DISCARDED, payload.getInteger("status"))
    }

    @Test
    fun buildPayload_shouldNotIncludeLocalOnlyPanNo() {
        val payload = ActionGroupOrderEventPayloadBuilder.build(
            ActionGroupOrderContext(
                orderNo = "KC202604280001",
                itemId = 12,
                productId = 34,
                tasteCode = "taste-hot"
            ),
            status = ActionGroupOrderStatus.ERROR
        )

        assertFalse(payload.containsKey("panNo"))
    }

    @Test
    fun pendingEvent_shouldRoundTripPanNoZeroAsUnknownLocalLogValue() {
        val event = PendingActionGroupOrderEvent(
            orderContext = ActionGroupOrderContext(
                orderNo = "KC202604280001",
                itemId = 12,
                productId = 34,
                tasteCode = "taste-hot"
            ),
            status = ActionGroupOrderStatus.ERROR,
            panNo = 0,
            discarded = false
        )

        val parsed = JSON.parseObject(JSON.toJSONString(event), PendingActionGroupOrderEvent::class.java)

        assertEquals(0, parsed.panNo)
        assertEquals(ActionGroupOrderStatus.ERROR, parsed.status)
        assertEquals("KC202604280001", parsed.orderContext.orderNo)
    }

    @Test
    fun buildPayload_shouldCarryTypedNoDispatchEvidence() {
        val payload = ActionGroupOrderEventPayloadBuilder.build(
            ActionGroupOrderContext("KC-NO-DISPATCH", 1, 2, "hot"),
            status = ActionGroupOrderStatus.ERROR,
            failureReason = OrderFailureReason.NO_SELLABLE_STOCK,
            deliveryEvidence = DeliveryEvidence.NOT_DISPATCHED,
            out1Dispatched = false
        )

        assertEquals("NO_SELLABLE_STOCK", payload.getString("failure_reason"))
        assertEquals("NOT_DISPATCHED", payload.getString("delivery_evidence"))
        assertEquals(false, payload.getBoolean("out1_dispatched"))
    }

    @Test
    fun pendingStore_shouldExposeDurableSaveSuccessAndFailure() {
        fun store(saveResult: Boolean) = object : ActionGroupOrderEventStore {
            override fun load(): PendingActionGroupOrderEvent? = null
            override fun save(event: PendingActionGroupOrderEvent): Boolean = saveResult
            override fun clear() = Unit
        }
        val event = PendingActionGroupOrderEvent(status = ActionGroupOrderStatus.FINISHED)

        assertTrue(store(true).save(event))
        assertFalse(store(false).save(event))
    }

    @Test
    fun deliveredCloseoutUnknownStatus2_shouldAttemptOnceAndSwallowFalseOrThrow() {
        listOf<() -> Boolean>({ false }, { error("mqtt failed") }).forEach { report ->
            var attempts = 0

            val reported = DeliveredCloseoutUnknownOrderEventPolicy.reportFinishedBestEffort {
                attempts++
                report()
            }

            assertFalse(reported)
            assertEquals(1, attempts)
        }
    }

    @Test
    fun v2Payload_shouldCarryDispatchIdentityForAllOrderStatuses() {
        val context = ActionGroupOrderContext(
            orderNo = "ORDER-V2",
            itemId = 1,
            productId = 2,
            tasteCode = "hot",
            dispatchId = "DISPATCH-1",
            protocolVersion = 2
        )

        listOf(1, 2, 3, 5, 6).forEach { status ->
            val payload = ActionGroupOrderEventPayloadBuilder.build(context, status)
            assertEquals("DISPATCH-1", payload.getString("dispatch_id"))
            assertEquals(2, payload.getIntValue("protocol_version"))
        }
    }

    @Test
    fun legacyPayload_shouldRemainWithoutV2DispatchFields() {
        val payload = ActionGroupOrderEventPayloadBuilder.build(
            ActionGroupOrderContext("ORDER-LEGACY", 1, 2, "hot"),
            status = 2
        )

        assertFalse(payload.containsKey("dispatch_id"))
        assertFalse(payload.containsKey("protocol_version"))
    }

    @Test
    fun v2OfflinePendingEvents_shouldRoundTripDispatchIdentityForAllStatuses() {
        listOf(1, 2, 3, 5, 6).forEach { status ->
            val original = PendingActionGroupOrderEvent(
                orderContext = ActionGroupOrderContext(
                    orderNo = "ORDER-OFFLINE",
                    itemId = 1,
                    productId = 2,
                    tasteCode = "hot",
                    dispatchId = "DISPATCH-OFFLINE",
                    protocolVersion = 2
                ),
                status = status
            )
            val restored = JSON.parseObject(
                JSON.toJSONString(original),
                PendingActionGroupOrderEvent::class.java
            )
            val payload = ActionGroupOrderEventPayloadBuilder.build(restored.orderContext, restored.status)

            assertEquals("DISPATCH-OFFLINE", payload.getString("dispatch_id"))
            assertEquals(2, payload.getIntValue("protocol_version"))
        }
    }
}
