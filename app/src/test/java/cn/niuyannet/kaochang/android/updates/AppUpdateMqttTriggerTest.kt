package cn.niuyannet.kaochang.android.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateMqttTriggerTest {

    @Test
    fun shouldAcceptUpdateTrigger_dedupesSameTriggerIdWithin30Seconds() {
        val triggerId = "trigger-${System.nanoTime()}"

        assertTrue(AppUpdateManager.shouldAcceptUpdateTrigger(triggerId, nowMs = 1_000L))
        assertFalse(AppUpdateManager.shouldAcceptUpdateTrigger(triggerId, nowMs = 30_999L))
        assertTrue(AppUpdateManager.shouldAcceptUpdateTrigger(triggerId, nowMs = 31_000L))
    }

    @Test
    fun isPendingInstallStillMatched_requiresSameTargetVersionAndHasUpdate() {
        assertTrue(
            AppUpdateManager.isPendingInstallStillMatched(
                hasUpdate = true,
                remoteVersionCode = 92,
                pendingVersionCode = 92
            )
        )
        assertFalse(
            AppUpdateManager.isPendingInstallStillMatched(
                hasUpdate = false,
                remoteVersionCode = 92,
                pendingVersionCode = 92
            )
        )
        assertFalse(
            AppUpdateManager.isPendingInstallStillMatched(
                hasUpdate = true,
                remoteVersionCode = 93,
                pendingVersionCode = 92
            )
        )
    }
}
