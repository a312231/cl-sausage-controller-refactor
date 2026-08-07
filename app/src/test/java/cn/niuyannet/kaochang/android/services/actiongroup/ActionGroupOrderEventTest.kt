package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
