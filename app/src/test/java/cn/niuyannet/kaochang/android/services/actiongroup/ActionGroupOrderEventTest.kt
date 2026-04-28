package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
