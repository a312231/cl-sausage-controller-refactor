package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticMechanicalCommandAdmissionCoordinatorTest {

    @Test
    fun orderPauseLinearizedFirst_shouldRejectAutomaticCommandIntent() {
        val lock = Any()
        var orderPending = false
        var checkpointSaved = false
        val coordinator = coordinator(
            lock = lock,
            orderPending = { orderPending },
            saveIntent = {
                checkpointSaved = true
                true
            }
        )

        synchronized(lock) { orderPending = true }
        val result = coordinator.admit("move:1", expectedMoveStatus = 3, automatic = true)

        assertTrue(result is AutomaticMechanicalCommandAdmissionResult.Rejected)
        assertFalse(checkpointSaved)
    }

    @Test
    fun commandIntentLinearizedFirst_shouldRemainAdmittedWhenOrderArrivesLater() {
        val lock = Any()
        var orderPending = false
        var checkpointSaved = false
        val coordinator = coordinator(
            lock = lock,
            orderPending = { orderPending },
            saveIntent = {
                checkpointSaved = true
                true
            }
        )

        val result = coordinator.admit("move:1", expectedMoveStatus = 3, automatic = true)
        synchronized(lock) { orderPending = true }

        assertEquals(AutomaticMechanicalCommandAdmissionResult.Admitted, result)
        assertTrue(checkpointSaved)
        assertTrue(orderPending)
    }

    @Test
    fun ownerMoveStatusOrJournalFailure_shouldNotAdmitCommand() {
        val ownerMismatch = coordinator(currentOwner = { "other" })
            .admit("move:1", expectedMoveStatus = 3, automatic = true)
        val moveStatusMismatch = coordinator(currentMoveStatus = { 2 })
            .admit("move:1", expectedMoveStatus = 3, automatic = true)
        val journalFailure = coordinator(saveIntent = { false })
            .admit("move:1", expectedMoveStatus = 3, automatic = true)

        assertTrue(ownerMismatch is AutomaticMechanicalCommandAdmissionResult.Rejected)
        assertTrue(moveStatusMismatch is AutomaticMechanicalCommandAdmissionResult.Rejected)
        assertTrue(journalFailure is AutomaticMechanicalCommandAdmissionResult.Failed)
    }

    @Test
    fun journalException_shouldBecomeFailedAdmission() {
        val result = coordinator(saveIntent = { error("disk failure") })
            .admit("move:1", expectedMoveStatus = 3, automatic = true)

        assertTrue(result is AutomaticMechanicalCommandAdmissionResult.Failed)
    }

    @Test
    fun manualCommand_shouldIgnoreOrderButStillValidateOwnershipAndPersistIntent() {
        var checkpointSaved = false
        val result = coordinator(
            orderPending = { true },
            saveIntent = {
                checkpointSaved = true
                true
            }
        ).admit("move:1", expectedMoveStatus = 3, automatic = false)

        assertEquals(AutomaticMechanicalCommandAdmissionResult.Admitted, result)
        assertTrue(checkpointSaved)
    }

    @Test
    fun emergencyResetFence_shouldRejectAutomaticAndManualCommandIntent() {
        var checkpointSaved = false
        val coordinator = coordinator(
            emergencyResetPending = { true },
            saveIntent = {
                checkpointSaved = true
                true
            }
        )

        val automatic = coordinator.admit("move:1", expectedMoveStatus = 3, automatic = true)
        val manual = coordinator.admit("move:1", expectedMoveStatus = 3, automatic = false)

        assertTrue(automatic is AutomaticMechanicalCommandAdmissionResult.Rejected)
        assertTrue(manual is AutomaticMechanicalCommandAdmissionResult.Rejected)
        assertFalse(checkpointSaved)
    }

    private fun coordinator(
        lock: Any = Any(),
        orderPending: () -> Boolean = { false },
        currentOwner: () -> String? = { "move:1" },
        currentMoveStatus: () -> Int = { 3 },
        emergencyResetPending: () -> Boolean = { false },
        saveIntent: () -> Boolean = { true }
    ) = AutomaticMechanicalCommandAdmissionCoordinator(
        transitionLock = lock,
        orderPending = orderPending,
        currentOwner = currentOwner,
        currentMoveStatus = currentMoveStatus,
        automaticCommandAllowed = { true },
        emergencyResetRecoveryPending = emergencyResetPending,
        saveCommandIntent = saveIntent
    )
}
