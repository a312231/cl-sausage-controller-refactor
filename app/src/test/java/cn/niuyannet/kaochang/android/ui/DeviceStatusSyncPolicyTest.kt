package cn.niuyannet.kaochang.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStatusSyncPolicyTest {

    @Test
    fun shouldMirrorOnlineStatusToHttp_shouldMirrorOperatingAndDeliveringStates() {
        assertTrue(DeviceStatusSyncPolicy.shouldMirrorOnlineStatusToHttp(1))
        assertTrue(DeviceStatusSyncPolicy.shouldMirrorOnlineStatusToHttp(4))
    }

    @Test
    fun shouldMirrorOnlineStatusToHttp_shouldIgnoreUnknownState() {
        assertFalse(DeviceStatusSyncPolicy.shouldMirrorOnlineStatusToHttp(99))
    }
}
