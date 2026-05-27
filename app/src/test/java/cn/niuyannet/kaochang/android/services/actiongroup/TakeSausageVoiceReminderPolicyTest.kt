package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeSausageVoiceReminderPolicyTest {

    @Test
    fun shouldPlayForFirstDeliveryOfValidOrder() {
        val orderContext = ActionGroupOrderContext(
            orderNo = "ORDER-1",
            itemId = 10,
            productId = 20,
            tasteCode = "A"
        )

        assertTrue(TakeSausageVoiceReminderPolicy.shouldPlay(orderContext, deliveryIndex = 1))
    }

    @Test
    fun shouldPlayForLaterDeliveriesOfValidOrder() {
        val orderContext = ActionGroupOrderContext(
            orderNo = "ORDER-1",
            itemId = 10,
            productId = 20,
            tasteCode = "A"
        )

        assertTrue(TakeSausageVoiceReminderPolicy.shouldPlay(orderContext, deliveryIndex = 2))
        assertTrue(TakeSausageVoiceReminderPolicy.shouldPlay(orderContext, deliveryIndex = 3))
        assertTrue(TakeSausageVoiceReminderPolicy.shouldPlay(orderContext, deliveryIndex = 4))
    }

    @Test
    fun shouldSkipWhenOrderContextMissing() {
        assertFalse(TakeSausageVoiceReminderPolicy.shouldPlay(orderContext = null, deliveryIndex = 1))
    }
}
