package cn.niuyannet.kaochang.android.utils

import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class OrderStateManagerEmergencyResetTest {

    @Test
    fun emergencyReset_shouldReleaseAllInMemoryProcessingWithoutCreatingDurableTerminalState() {
        val first = "emergency-reset-processing-1"
        val second = "emergency-reset-processing-2"
        val states = memoryStates()
        states[first] = OrderStateManager.OrderState(
            first,
            OrderStateManager.STATE_ACCEPTED_PROCESSING,
            System.currentTimeMillis()
        )
        states[second] = OrderStateManager.OrderState(
            second,
            OrderStateManager.STATE_ACCEPTED_PROCESSING,
            System.currentTimeMillis()
        )

        OrderStateManager.releaseAllProcessingForEmergencyReset()

        assertFalse(states.containsKey(first))
        assertFalse(states.containsKey(second))
    }

    @Suppress("UNCHECKED_CAST")
    private fun memoryStates(): ConcurrentHashMap<String, OrderStateManager.OrderState> {
        val field = OrderStateManager::class.java.getDeclaredField("memoryStates")
        field.isAccessible = true
        return field.get(OrderStateManager) as ConcurrentHashMap<String, OrderStateManager.OrderState>
    }
}
