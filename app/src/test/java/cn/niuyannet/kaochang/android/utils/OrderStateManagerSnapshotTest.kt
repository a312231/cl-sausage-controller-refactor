package cn.niuyannet.kaochang.android.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderStateManagerSnapshotTest {

    @Test
    fun snapshotOnlyObservesProcessingWithoutApplyingTtlCleanup() {
        val expiredProcessing = OrderStateManager.OrderState(
            orderNo = "old-order",
            state = OrderStateManager.STATE_ACCEPTED_PROCESSING,
            timestamp = 1L
        )
        val finished = OrderStateManager.OrderState(
            orderNo = "finished",
            state = OrderStateManager.STATE_FINISHED,
            timestamp = 1L
        )

        assertTrue(hasProcessingOrderSnapshot(listOf(expiredProcessing, finished)))
        assertFalse(hasProcessingOrderSnapshot(listOf(finished)))
    }
}
