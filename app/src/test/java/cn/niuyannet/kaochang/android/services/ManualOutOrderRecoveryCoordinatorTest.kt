package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualOutOrderRecoveryCoordinatorTest {

    @Test
    fun reserve_shouldAllowOnlyOneDifferentOrderUntilReleased() {
        val coordinator = ManualOutOrderRecoveryCoordinator()

        assertTrue(coordinator.tryReserve("order-1"))
        assertTrue(coordinator.isReservedFor("order-1"))
        assertTrue(coordinator.tryReserve("order-1"))
        assertFalse(coordinator.tryReserve("order-2"))

        coordinator.release("order-1")

        assertFalse(coordinator.isReservedFor("order-1"))
        assertTrue(coordinator.tryReserve("order-2"))
    }

    @Test
    fun release_shouldNotClearReservationOwnedByAnotherOrder() {
        val coordinator = ManualOutOrderRecoveryCoordinator()

        assertTrue(coordinator.tryReserve("order-1"))
        coordinator.release("order-2")

        assertTrue(coordinator.isReservedFor("order-1"))
        assertFalse(coordinator.tryReserve("order-2"))
    }

    @Test
    fun emergencyReset_shouldClearAnyPreResetReservation() {
        val coordinator = ManualOutOrderRecoveryCoordinator()
        assertTrue(coordinator.tryReserve("order-1"))

        coordinator.clearForEmergencyReset()

        assertFalse(coordinator.isReservedFor("order-1"))
        assertTrue(coordinator.tryReserve("order-2"))
    }
}
