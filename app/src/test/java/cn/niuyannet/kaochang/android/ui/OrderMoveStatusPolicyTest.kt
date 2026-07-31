package cn.niuyannet.kaochang.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class OrderMoveStatusPolicyTest {
    @Test
    fun idle_shouldNormalizeAndAllowSingleDelivery() {
        val status = OrderMoveStatusPolicy.MOVE_STATUS_IDLE

        assertTrue(OrderMoveStatusPolicy.canStartSingleDelivery(status))
        assertTrue(OrderMoveStatusPolicy.shouldNormalizeToDelivering(status))
        assertFalse(OrderMoveStatusPolicy.shouldWaitForBlockingAction(status))
    }

    @Test
    fun delivering_shouldAllowSingleDeliveryWithoutNormalize() {
        val status = OrderMoveStatusPolicy.MOVE_STATUS_DELIVERING

        assertTrue(OrderMoveStatusPolicy.canStartSingleDelivery(status))
        assertFalse(OrderMoveStatusPolicy.shouldNormalizeToDelivering(status))
        assertFalse(OrderMoveStatusPolicy.shouldWaitForBlockingAction(status))
    }

    @Test
    fun conflictingStatuses_shouldWaitAndNotAllowDirectDelivery() {
        val statuses = listOf(
            OrderMoveStatusPolicy.MOVE_STATUS_SUPPLEMENTING,
            OrderMoveStatusPolicy.MOVE_STATUS_PAN_MOVING,
            OrderMoveStatusPolicy.MOVE_STATUS_DISCARDING,
            OrderMoveStatusPolicy.MOVE_STATUS_SELF_CLEANING
        )

        statuses.forEach { status ->
            assertFalse("status=$status should not allow direct delivery", OrderMoveStatusPolicy.canStartSingleDelivery(status))
            assertFalse("status=$status should not normalize", OrderMoveStatusPolicy.shouldNormalizeToDelivering(status))
            assertTrue("status=$status should wait", OrderMoveStatusPolicy.shouldWaitForBlockingAction(status))
        }
    }

    @Test
    fun unknownStatus_shouldNotAllowDeliveryOrWaitAsKnownConflict() {
        val status = 99

        assertFalse(OrderMoveStatusPolicy.canStartSingleDelivery(status))
        assertFalse(OrderMoveStatusPolicy.shouldNormalizeToDelivering(status))
        assertFalse(OrderMoveStatusPolicy.shouldWaitForBlockingAction(status))
    }

    @Test
    fun waitTimeout_shouldMatchSellPrioritySafeCloseoutBudget() {
        assertEquals(240_000L, OrderMoveStatusPolicy.waitTimeoutMsForBlockingAction(OrderMoveStatusPolicy.MOVE_STATUS_SUPPLEMENTING))
        assertEquals(90_000L, OrderMoveStatusPolicy.waitTimeoutMsForBlockingAction(OrderMoveStatusPolicy.MOVE_STATUS_PAN_MOVING))
        assertEquals(150_000L, OrderMoveStatusPolicy.waitTimeoutMsForBlockingAction(OrderMoveStatusPolicy.MOVE_STATUS_DISCARDING))
        assertEquals(0L, OrderMoveStatusPolicy.waitTimeoutMsForBlockingAction(OrderMoveStatusPolicy.MOVE_STATUS_SELF_CLEANING))
        assertNull(OrderMoveStatusPolicy.waitTimeoutMsForBlockingAction(OrderMoveStatusPolicy.MOVE_STATUS_IDLE))
    }

    @Test
    fun incomingOrder_shouldRejectWhenActionGroupBusyEvenIfMoveStatusIdle() {
        assertFalse(
            OrderMoveStatusPolicy.canAcceptIncomingOrder(
                moveStatus = OrderMoveStatusPolicy.MOVE_STATUS_IDLE,
                onlineStatus = 1,
                actionGroupBusy = true
            )
        )
    }

    @Test
    fun incomingOrder_shouldRejectWhenNotOperatingOrInFaultOrInspection() {
        assertFalse(
            OrderMoveStatusPolicy.canAcceptIncomingOrder(
                moveStatus = 0,
                onlineStatus = 3,
                actionGroupBusy = false
            )
        )
        assertFalse(
            OrderMoveStatusPolicy.canAcceptIncomingOrder(
                moveStatus = 0,
                onlineStatus = 1,
                actionGroupBusy = false,
                errorStatus = 2
            )
        )
        assertFalse(
            OrderMoveStatusPolicy.canAcceptIncomingOrder(
                moveStatus = 0,
                onlineStatus = 1,
                actionGroupBusy = false,
                inspectionMode = 1
            )
        )
    }

    @Test
    fun incomingOrder_shouldAcceptIdleOrWaitableSafeCloseoutStates() {
        assertTrue(
            OrderMoveStatusPolicy.canAcceptIncomingOrder(
                moveStatus = OrderMoveStatusPolicy.MOVE_STATUS_IDLE,
                onlineStatus = 1,
                actionGroupBusy = false
            )
        )
        assertTrue(
            OrderMoveStatusPolicy.canAcceptIncomingOrder(
                moveStatus = OrderMoveStatusPolicy.MOVE_STATUS_SUPPLEMENTING,
                onlineStatus = 1,
                actionGroupBusy = true
            )
        )
        assertTrue(
            OrderMoveStatusPolicy.canAcceptIncomingOrder(
                moveStatus = OrderMoveStatusPolicy.MOVE_STATUS_PAN_MOVING,
                onlineStatus = 1,
                actionGroupBusy = true
            )
        )
        assertTrue(
            OrderMoveStatusPolicy.canAcceptIncomingOrder(
                moveStatus = OrderMoveStatusPolicy.MOVE_STATUS_DISCARDING,
                onlineStatus = 1,
                actionGroupBusy = true
            )
        )
        assertFalse(
            OrderMoveStatusPolicy.canAcceptIncomingOrder(
                moveStatus = OrderMoveStatusPolicy.MOVE_STATUS_DELIVERING,
                onlineStatus = 1,
                actionGroupBusy = false
            )
        )
        assertFalse(
            OrderMoveStatusPolicy.canAcceptIncomingOrder(
                moveStatus = OrderMoveStatusPolicy.MOVE_STATUS_SELF_CLEANING,
                onlineStatus = 1,
                actionGroupBusy = true
            )
        )
        assertFalse(
            OrderMoveStatusPolicy.canAcceptIncomingOrder(
                moveStatus = OrderMoveStatusPolicy.MOVE_STATUS_IDLE,
                onlineStatus = 4,
                actionGroupBusy = false
            )
        )
    }
}
