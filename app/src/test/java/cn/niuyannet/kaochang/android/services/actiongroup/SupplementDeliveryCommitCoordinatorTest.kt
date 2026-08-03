package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplementDeliveryCommitCoordinatorTest {

    @Test
    fun orderLinearizedFirst_shouldRejectIn4Commit() {
        val lock = Any()
        var orderPending = false
        var deliveryCommitted = false
        val coordinator = SupplementDeliveryCommitCoordinator(
            transitionLock = lock,
            orderPending = { orderPending }
        ) {
            deliveryCommitted = true
            true
        }

        synchronized(lock) { orderPending = true }

        assertFalse(coordinator.tryCommit())
        assertFalse(deliveryCommitted)
    }

    @Test
    fun deliveryLinearizedFirst_shouldRemainCommittedWhenOrderArrivesLater() {
        val lock = Any()
        var orderPending = false
        var deliveryCommitted = false
        val coordinator = SupplementDeliveryCommitCoordinator(
            transitionLock = lock,
            orderPending = { orderPending }
        ) {
            deliveryCommitted = true
            true
        }

        assertTrue(coordinator.tryCommit())
        synchronized(lock) { orderPending = true }

        assertTrue(deliveryCommitted)
        assertTrue(orderPending)
    }
}
