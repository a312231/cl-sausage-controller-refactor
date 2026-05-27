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

    @Test
    fun shouldResetStaleChecking_onlyAfterCheckInProgressTimeout() {
        val startedAtMs = 1_000L

        assertFalse(
            AppUpdateManager.shouldResetStaleChecking(
                isChecking = false,
                checkingStartedAtMs = startedAtMs,
                nowMs = startedAtMs + AppUpdatePolicy.CHECK_IN_PROGRESS_TIMEOUT_MS + 1L
            )
        )
        assertFalse(
            AppUpdateManager.shouldResetStaleChecking(
                isChecking = true,
                checkingStartedAtMs = 0L,
                nowMs = startedAtMs + AppUpdatePolicy.CHECK_IN_PROGRESS_TIMEOUT_MS + 1L
            )
        )
        assertFalse(
            AppUpdateManager.shouldResetStaleChecking(
                isChecking = true,
                checkingStartedAtMs = startedAtMs,
                nowMs = startedAtMs + AppUpdatePolicy.CHECK_IN_PROGRESS_TIMEOUT_MS
            )
        )
        assertTrue(
            AppUpdateManager.shouldResetStaleChecking(
                isChecking = true,
                checkingStartedAtMs = startedAtMs,
                nowMs = startedAtMs + AppUpdatePolicy.CHECK_IN_PROGRESS_TIMEOUT_MS + 1L
            )
        )
    }

    @Test
    fun validateDownloadedApkMetadata_rejectsWrongPackageOrVersion() {
        assertEquals(
            null,
            AppUpdateManager.validateDownloadedApkMetadata(
                apkPackageName = "cn.niuyannet.kaochang.android",
                apkVersionCode = 93L,
                currentPackageName = "cn.niuyannet.kaochang.android",
                currentVersionCode = 92,
                targetVersionCode = 93
            )
        )
        assertEquals(
            "更新包包名不一致：actual=wrong.package，expected=cn.niuyannet.kaochang.android",
            AppUpdateManager.validateDownloadedApkMetadata(
                apkPackageName = "wrong.package",
                apkVersionCode = 93L,
                currentPackageName = "cn.niuyannet.kaochang.android",
                currentVersionCode = 92,
                targetVersionCode = 93
            )
        )
        assertEquals(
            "更新包版本号不一致：actual=94，expected=93",
            AppUpdateManager.validateDownloadedApkMetadata(
                apkPackageName = "cn.niuyannet.kaochang.android",
                apkVersionCode = 94L,
                currentPackageName = "cn.niuyannet.kaochang.android",
                currentVersionCode = 92,
                targetVersionCode = 93
            )
        )
        assertEquals(
            "更新包版本未高于当前版本：apkVersionCode=92，currentVersionCode=92",
            AppUpdateManager.validateDownloadedApkMetadata(
                apkPackageName = "cn.niuyannet.kaochang.android",
                apkVersionCode = 92L,
                currentPackageName = "cn.niuyannet.kaochang.android",
                currentVersionCode = 92,
                targetVersionCode = 92
            )
        )
    }

    @Test
    fun reportVersionRetryDelayMs_usesThreeStepBackoff() {
        assertEquals(5_000L, AppUpdateManager.reportVersionRetryDelayMs(0))
        assertEquals(15_000L, AppUpdateManager.reportVersionRetryDelayMs(1))
        assertEquals(60_000L, AppUpdateManager.reportVersionRetryDelayMs(2))
        assertEquals(null, AppUpdateManager.reportVersionRetryDelayMs(3))
    }
}
