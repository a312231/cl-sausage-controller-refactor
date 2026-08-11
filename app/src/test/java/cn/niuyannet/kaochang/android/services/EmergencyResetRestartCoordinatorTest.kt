package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyResetRestartCoordinatorTest {

    @Test
    fun scheduledRestart_shouldTerminateCurrentProcess() {
        var terminated = false
        val coordinator = EmergencyResetRestartCoordinator(
            scheduleRestart = { true },
            terminateCurrentProcess = { terminated = true }
        )

        assertTrue(coordinator.scheduleAndTerminate())
        assertTrue(terminated)
    }

    @Test
    fun failedSchedule_shouldKeepCurrentProcessAlive() {
        var terminated = false
        val coordinator = EmergencyResetRestartCoordinator(
            scheduleRestart = { false },
            terminateCurrentProcess = { terminated = true }
        )

        assertFalse(coordinator.scheduleAndTerminate())
        assertFalse(terminated)
    }

    @Test
    fun scheduleException_shouldKeepCurrentProcessAlive() {
        var terminated = false
        val coordinator = EmergencyResetRestartCoordinator(
            scheduleRestart = { error("alarm unavailable") },
            terminateCurrentProcess = { terminated = true }
        )

        assertFalse(coordinator.scheduleAndTerminate())
        assertFalse(terminated)
    }
}
