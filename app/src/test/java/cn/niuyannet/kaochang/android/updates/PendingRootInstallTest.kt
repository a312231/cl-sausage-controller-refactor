package cn.niuyannet.kaochang.android.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRootInstallTest {

    @Test
    fun encodeDecode_roundTripsPendingRootInstall() {
        val pending = PendingRootInstall(
            targetVersionCode = 130,
            targetVersionName = "2.1.35",
            forceUpdate = true,
            triggerId = "trigger-1",
            apkSha256 = "abc123",
            startedAtMs = 1_000L,
            firstDetectedAtMs = 900L
        )

        assertEquals(pending, PendingRootInstallCodec.decode(PendingRootInstallCodec.encode(pending)))
    }

    @Test
    fun isTimedOut_onlyAfterTimeoutWindow() {
        val pending = PendingRootInstall(
            targetVersionCode = 130,
            targetVersionName = "2.1.35",
            forceUpdate = false,
            triggerId = null,
            apkSha256 = "abc123",
            startedAtMs = 1_000L,
            firstDetectedAtMs = 1_000L
        )

        assertFalse(PendingRootInstallPolicy.isTimedOut(pending, nowMs = 1_000L + 59_999L, timeoutMs = 60_000L))
        assertTrue(PendingRootInstallPolicy.isTimedOut(pending, nowMs = 1_000L + 60_000L, timeoutMs = 60_000L))
    }

    @Test
    fun reconcileAction_confirmsSuccessOnlyWhenCurrentVersionReachesTarget() {
        val pending = PendingRootInstall(
            targetVersionCode = 130,
            targetVersionName = "2.1.35",
            forceUpdate = false,
            triggerId = null,
            apkSha256 = "abc123",
            startedAtMs = 1_000L,
            firstDetectedAtMs = 1_000L
        )

        assertEquals(
            PendingRootInstallPolicy.ReconcileAction.Success,
            PendingRootInstallPolicy.reconcileAction(pending, currentVersionCode = 130, nowMs = 2_000L, timeoutMs = 60_000L)
        )
        assertEquals(
            PendingRootInstallPolicy.ReconcileAction.Wait,
            PendingRootInstallPolicy.reconcileAction(pending, currentVersionCode = 129, nowMs = 2_000L, timeoutMs = 60_000L)
        )
        assertEquals(
            PendingRootInstallPolicy.ReconcileAction.Timeout,
            PendingRootInstallPolicy.reconcileAction(pending, currentVersionCode = 129, nowMs = 61_000L, timeoutMs = 60_000L)
        )
    }
}
