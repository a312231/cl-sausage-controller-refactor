package cn.niuyannet.kaochang.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderProcessingStageTest {
    @Test
    fun pausedStageUsesCustomerFriendlyText() {
        assertEquals("补肠动作已暂缓，优先为您出餐", OrderProcessingStage.SUPPLEMENT_PAUSED.customerText)
    }

    @Test
    fun workerPublishIsDispatchedBeforeListenerRuns() {
        val queued = mutableListOf<() -> Unit>()
        val received = mutableListOf<OrderProcessingStage>()
        val dispatcher = OrderProcessingStageDispatcher(
            isDispatchThread = { false },
            dispatch = { queued += it }
        )
        dispatcher.register { received += it }

        dispatcher.publish(OrderProcessingStage.SUPPLEMENT_PAUSED)

        assertTrue(received.isEmpty())
        assertEquals(1, queued.size)
        queued.single().invoke()
        assertEquals(listOf(OrderProcessingStage.SUPPLEMENT_PAUSED), received)
    }

    @Test
    fun dispatchThreadPublishRunsListenerImmediately() {
        val received = mutableListOf<OrderProcessingStage>()
        val dispatcher = OrderProcessingStageDispatcher(
            isDispatchThread = { true },
            dispatch = { error("主线程发布不应再次入队") }
        )
        dispatcher.register { received += it }

        dispatcher.publish(OrderProcessingStage.DELIVERING)

        assertEquals(listOf(OrderProcessingStage.DELIVERING), received)
    }

    @Test
    fun queuedPublishIsDroppedAfterSameListenerIsUnregisteredAndRegisteredAgain() {
        val queued = mutableListOf<() -> Unit>()
        val received = mutableListOf<OrderProcessingStage>()
        val listener: (OrderProcessingStage) -> Unit = { received += it }
        val dispatcher = OrderProcessingStageDispatcher(
            isDispatchThread = { false },
            dispatch = { queued += it }
        )
        dispatcher.register(listener)
        dispatcher.publish(OrderProcessingStage.SUPPLEMENTING)

        dispatcher.unregister(listener)
        dispatcher.register(listener)
        queued.single().invoke()

        assertTrue(received.isEmpty())
    }

    @Test
    fun queuedPublishIsDroppedAfterListenerIsUnregistered() {
        val queued = mutableListOf<() -> Unit>()
        val received = mutableListOf<OrderProcessingStage>()
        val listener: (OrderProcessingStage) -> Unit = { received += it }
        val dispatcher = OrderProcessingStageDispatcher(
            isDispatchThread = { false },
            dispatch = { queued += it }
        )
        dispatcher.register(listener)
        dispatcher.publish(OrderProcessingStage.READY_TO_TAKE)

        dispatcher.unregister(listener)
        queued.single().invoke()

        assertTrue(received.isEmpty())
    }
}
