package cn.niuyannet.kaochang.android.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTimingTest {

    @Test
    fun nextInstallWaitAction_shouldPollBeforeMqttDrainTimeout() {
        val action = AppUpdateManager.nextInstallWaitAction(
            readinessOk = false,
            waitStartedAtMs = 1_000L,
            nowMs = 1_000L + AppUpdatePolicy.MQTT_DRAIN_TIMEOUT_MS
        )

        assertEquals(AppUpdateManager.InstallWaitAction.PollAgain(1_000L), action)
    }

    @Test
    fun nextInstallWaitAction_shouldRetryFiveMinutesAfterMqttDrainTimeout() {
        val action = AppUpdateManager.nextInstallWaitAction(
            readinessOk = false,
            waitStartedAtMs = 1_000L,
            nowMs = 1_001L + AppUpdatePolicy.MQTT_DRAIN_TIMEOUT_MS
        )

        assertEquals(
            AppUpdateManager.InstallWaitAction.RetryLater(AppUpdatePolicy.MQTT_DRAIN_RETRY_DELAY_MS),
            action
        )
    }

    @Test
    fun nextInstallWaitAction_shouldInstallImmediatelyWhenReadinessOk() {
        val action = AppUpdateManager.nextInstallWaitAction(
            readinessOk = true,
            waitStartedAtMs = 1_000L,
            nowMs = 1_001L + AppUpdatePolicy.MQTT_DRAIN_TIMEOUT_MS
        )

        assertEquals(AppUpdateManager.InstallWaitAction.InstallNow, action)
    }

    @Test
    fun shouldUseMinimalGate_onlyAfterForceUpdateDelayedForTwentyFourHours() {
        val firstDetectedAtMs = 1_000L

        assertFalse(
            AppUpdateManager.shouldUseMinimalGate(
                force = true,
                firstDetectedAtMs = firstDetectedAtMs,
                nowMs = firstDetectedAtMs + AppUpdatePolicy.FORCE_UPDATE_MINIMAL_GATE_AFTER_MS - 1L
            )
        )
        assertTrue(
            AppUpdateManager.shouldUseMinimalGate(
                force = true,
                firstDetectedAtMs = firstDetectedAtMs,
                nowMs = firstDetectedAtMs + AppUpdatePolicy.FORCE_UPDATE_MINIMAL_GATE_AFTER_MS
            )
        )
        assertFalse(
            AppUpdateManager.shouldUseMinimalGate(
                force = false,
                firstDetectedAtMs = firstDetectedAtMs,
                nowMs = firstDetectedAtMs + AppUpdatePolicy.FORCE_UPDATE_MINIMAL_GATE_AFTER_MS
            )
        )
    }
}
