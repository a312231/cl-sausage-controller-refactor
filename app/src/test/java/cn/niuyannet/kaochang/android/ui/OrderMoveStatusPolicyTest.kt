package cn.niuyannet.kaochang.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
