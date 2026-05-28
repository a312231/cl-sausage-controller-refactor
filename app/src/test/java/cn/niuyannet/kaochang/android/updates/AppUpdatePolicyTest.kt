package cn.niuyannet.kaochang.android.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePolicyTest {

    @Test
    fun shouldUpdate_onlyWhenRemoteVersionCodeIsNewerAndHasUpdate() {
        assertTrue(
            AppUpdatePolicy.shouldUpdate(
                currentVersionCode = 91,
                remoteVersionCode = 92,
                hasUpdate = true
            )
        )
        assertFalse(
            AppUpdatePolicy.shouldUpdate(
                currentVersionCode = 91,
                remoteVersionCode = 91,
                hasUpdate = true
            )
        )
        assertFalse(
            AppUpdatePolicy.shouldUpdate(
                currentVersionCode = 91,
                remoteVersionCode = 92,
                hasUpdate = false
            )
        )
    }

    @Test
    fun shouldForceUpdate_whenBackendForcesOrCurrentBelowMinSupportedVersion() {
        assertTrue(
            AppUpdatePolicy.shouldForceUpdate(
                currentVersionCode = 91,
                forceUpdate = true,
                minSupportedVersionCode = null
            )
        )
        assertTrue(
            AppUpdatePolicy.shouldForceUpdate(
                currentVersionCode = 89,
                forceUpdate = false,
                minSupportedVersionCode = 90
            )
        )
        assertFalse(
            AppUpdatePolicy.shouldForceUpdate(
                currentVersionCode = 91,
                forceUpdate = false,
                minSupportedVersionCode = 90
            )
        )
    }

    @Test
    fun updateStartAction_shouldAutoDownloadOnlyForForceUpdate() {
        assertEquals(AppUpdateManager.UpdateStartAction.DownloadImmediately, AppUpdateManager.updateStartAction(force = true))
        assertEquals(AppUpdateManager.UpdateStartAction.ShowDialog, AppUpdateManager.updateStartAction(force = false))
    }

    @Test
    fun constants_shouldMatchFinalUpgradePlan() {
        assertEquals(30_000L, AppUpdatePolicy.MQTT_DRAIN_TIMEOUT_MS)
        assertEquals(5 * 60_000L, AppUpdatePolicy.MQTT_DRAIN_RETRY_DELAY_MS)
        assertEquals(24 * 60 * 60_000L, AppUpdatePolicy.FORCE_UPDATE_MINIMAL_GATE_AFTER_MS)
        assertEquals(listOf(30_000L, 2 * 60_000L, 10 * 60_000L), AppUpdatePolicy.DOWNLOAD_RETRY_DELAYS_MS)
    }
}
