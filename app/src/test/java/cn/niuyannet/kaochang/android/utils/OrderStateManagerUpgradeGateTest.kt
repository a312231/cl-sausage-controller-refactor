package cn.niuyannet.kaochang.android.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class OrderStateManagerUpgradeGateTest {

    @Before
    fun setUp() {
        memoryStates().clear()
    }

    @Test
    fun hasProcessingOrder_shouldOnlyTreatAcceptedProcessingAsProcessing() {
        val orderNo = "upgrade-processing-${System.nanoTime()}"

        assertFalse(OrderStateManager.hasProcessingOrder())
        memoryStates()[orderNo] = OrderStateManager.OrderState(
            orderNo = orderNo,
            state = OrderStateManager.STATE_ACCEPTED_PROCESSING,
            timestamp = System.currentTimeMillis()
        )
        assertTrue(OrderStateManager.hasProcessingOrder())

        memoryStates().remove(orderNo)
        assertFalse(OrderStateManager.hasProcessingOrder())
    }

    @Test
    fun hasRecentBusyOrder_shouldTreatBusyStateAsShortDebounceOnly() {
        val orderNo = "upgrade-busy-${System.nanoTime()}"

        memoryStates()[orderNo] = OrderStateManager.OrderState(
            orderNo = orderNo,
            state = OrderStateManager.STATE_RECEIVED_BUT_BUSY,
            timestamp = System.currentTimeMillis()
        )
        assertTrue(OrderStateManager.hasRecentBusyOrder(windowMs = 5_000L))
        assertFalse(OrderStateManager.hasRecentBusyOrder(windowMs = 0L))
    }

    @Suppress("UNCHECKED_CAST")
    private fun memoryStates(): ConcurrentHashMap<String, OrderStateManager.OrderState> {
        val field = OrderStateManager::class.java.getDeclaredField("memoryStates")
        field.isAccessible = true
        return field.get(OrderStateManager) as ConcurrentHashMap<String, OrderStateManager.OrderState>
    }
}
