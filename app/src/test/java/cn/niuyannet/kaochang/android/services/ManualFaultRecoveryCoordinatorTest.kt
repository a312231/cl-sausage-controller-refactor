package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualFaultRecoveryCoordinatorTest {

    @Test
    fun recover_shouldRestoreHeatingBeforeClearingFaultAndKeepAlgorithmDisabled() {
        val events = mutableListOf<String>()
        val store = FakeStateStore(events)
        val coordinator = ManualFaultRecoveryCoordinator(Any(), store)

        val result = coordinator.recover(
            targetOnlineStatus = 1,
            canRecover = { true },
            forceAlgorithmDisabled = { events += "algorithm-off" },
            restoreHeating = {
                events += "heating-restored"
                true
            },
            stopHeating = { events += "heating-stopped" }
        )

        assertTrue(result is ManualFaultRecoveryResult.Success)
        assertEquals(
            listOf("algorithm-off", "heating-restored", "state-recovered"),
            events
        )
        assertEquals(0, store.state.errorStatus)
        assertEquals(1, store.state.onlineStatus)
        assertEquals(0, store.state.isEnable)
    }

    @Test
    fun recover_shouldKeepFaultAndStopHeatingWhenAnyTemperatureWriteFails() {
        val events = mutableListOf<String>()
        val store = FakeStateStore(events)
        val coordinator = ManualFaultRecoveryCoordinator(Any(), store)

        val result = coordinator.recover(
            targetOnlineStatus = 1,
            canRecover = { true },
            forceAlgorithmDisabled = { events += "algorithm-off" },
            restoreHeating = {
                events += "heating-failed"
                false
            },
            stopHeating = { events += "heating-stopped" }
        )

        assertTrue(result is ManualFaultRecoveryResult.Failed)
        assertEquals(
            listOf("algorithm-off", "heating-failed", "state-restored", "heating-stopped"),
            events
        )
        assertEquals(1, store.state.errorStatus)
        assertEquals(3, store.state.onlineStatus)
        assertEquals(0, store.state.isEnable)
    }

    @Test
    fun recover_shouldRollbackAndStopHeatingWhenDurableStateSaveFails() {
        val events = mutableListOf<String>()
        val store = FakeStateStore(events, failRecoveredSave = true)
        val coordinator = ManualFaultRecoveryCoordinator(Any(), store)

        val result = coordinator.recover(
            targetOnlineStatus = 1,
            canRecover = { true },
            forceAlgorithmDisabled = { events += "algorithm-off" },
            restoreHeating = {
                events += "heating-restored"
                true
            },
            stopHeating = { events += "heating-stopped" }
        )

        assertTrue(result is ManualFaultRecoveryResult.Failed)
        assertEquals(
            listOf(
                "algorithm-off",
                "heating-restored",
                "state-recovered-failed",
                "state-restored",
                "heating-stopped"
            ),
            events
        )
        assertEquals(1, store.state.errorStatus)
        assertEquals(3, store.state.onlineStatus)
        assertEquals(0, store.state.isEnable)
    }

    @Test
    fun recover_shouldRecheckGateBeforeCommittingRecoveredState() {
        val events = mutableListOf<String>()
        val store = FakeStateStore(events)
        val coordinator = ManualFaultRecoveryCoordinator(Any(), store)
        var checks = 0

        val result = coordinator.recover(
            targetOnlineStatus = 1,
            canRecover = { ++checks == 1 },
            forceAlgorithmDisabled = { events += "algorithm-off" },
            restoreHeating = {
                events += "heating-restored"
                true
            },
            stopHeating = { events += "heating-stopped" }
        )

        assertTrue(result is ManualFaultRecoveryResult.Rejected)
        assertEquals(
            listOf("algorithm-off", "heating-restored", "state-restored", "heating-stopped"),
            events
        )
        assertEquals(1, store.state.errorStatus)
        assertEquals(3, store.state.onlineStatus)
    }

    private class FakeStateStore(
        private val events: MutableList<String>,
        private val failRecoveredSave: Boolean = false
    ) : ManualFaultRecoveryStateStore {
        var state = ManualFaultRecoveryState(
            onlineStatus = 3,
            errorStatus = 1,
            errorCode = "E",
            errorCategory = "fault",
            errorReason = "test",
            isEnable = 0
        )

        override fun snapshot(): ManualFaultRecoveryState = state.copy()

        override fun saveRecovered(targetOnlineStatus: Int) {
            if (failRecoveredSave) {
                events += "state-recovered-failed"
                throw IllegalStateException("durable save failed")
            }
            events += "state-recovered"
            state = state.copy(
                onlineStatus = targetOnlineStatus,
                errorStatus = 0,
                errorCode = null,
                errorCategory = null,
                errorReason = null,
                isEnable = 0
            )
        }

        override fun restoreFault(snapshot: ManualFaultRecoveryState) {
            events += "state-restored"
            state = snapshot.copy(isEnable = 0)
        }
    }
}
