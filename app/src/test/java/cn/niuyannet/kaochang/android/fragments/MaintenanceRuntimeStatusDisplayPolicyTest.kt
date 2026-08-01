package cn.niuyannet.kaochang.android.fragments

import org.junit.Assert.assertEquals
import org.junit.Test

class MaintenanceRuntimeStatusDisplayPolicyTest {

    @Test
    fun build_shouldShowSingleStatusWhenLocalAndCloudAgree() {
        val display = MaintenanceRuntimeStatusDisplayPolicy.build(
            localOnlineStatus = 1,
            localErrorStatus = 0,
            cloudState = MaintenanceRuntimeStatusDisplayPolicy.CloudState.Available(
                onlineStatus = 1,
                errorStatus = 0
            ),
            needsLocalRecovery = false,
            canRetryCloudOnly = true
        )

        assertEquals("故障状态：正常", display.faultText)
        assertEquals("营业状态：运营中", display.onlineText)
        assertEquals("当前无需恢复", display.recoveryButtonText)
        assertEquals(MaintenanceRuntimeStatusDisplayPolicy.Level.NORMAL, display.faultLevel)
        assertEquals(MaintenanceRuntimeStatusDisplayPolicy.Level.NORMAL, display.onlineLevel)
    }

    @Test
    fun build_shouldExposeLocalCloudMismatchAndCloudRetry() {
        val display = MaintenanceRuntimeStatusDisplayPolicy.build(
            localOnlineStatus = 1,
            localErrorStatus = 0,
            cloudState = MaintenanceRuntimeStatusDisplayPolicy.CloudState.Available(
                onlineStatus = 3,
                errorStatus = 0
            ),
            needsLocalRecovery = false,
            canRetryCloudOnly = true
        )

        assertEquals("故障状态：正常", display.faultText)
        assertEquals("营业状态不同步：本机运营中 / 后台维护中", display.onlineText)
        assertEquals("重试同步后台", display.recoveryButtonText)
        assertEquals(MaintenanceRuntimeStatusDisplayPolicy.Level.WARNING, display.onlineLevel)
    }

    @Test
    fun build_shouldExposeFaultMismatchWithoutChangingRecoveryPolicy() {
        val display = MaintenanceRuntimeStatusDisplayPolicy.build(
            localOnlineStatus = 3,
            localErrorStatus = 0,
            cloudState = MaintenanceRuntimeStatusDisplayPolicy.CloudState.Available(
                onlineStatus = 3,
                errorStatus = 2
            ),
            needsLocalRecovery = true,
            canRetryCloudOnly = false
        )

        assertEquals("故障状态不同步：本机正常 / 后台错误", display.faultText)
        assertEquals("营业状态：维护中", display.onlineText)
        assertEquals("检查并恢复设备", display.recoveryButtonText)
        assertEquals(MaintenanceRuntimeStatusDisplayPolicy.Level.ERROR, display.faultLevel)
    }

    @Test
    fun build_shouldMarkCloudReadFailureWithoutPretendingStatesAgree() {
        val display = MaintenanceRuntimeStatusDisplayPolicy.build(
            localOnlineStatus = 2,
            localErrorStatus = 0,
            cloudState = MaintenanceRuntimeStatusDisplayPolicy.CloudState.Unavailable,
            needsLocalRecovery = false,
            canRetryCloudOnly = false
        )

        assertEquals("故障状态：正常（后台状态获取失败）", display.faultText)
        assertEquals("营业状态：本机休息中（后台状态获取失败）", display.onlineText)
        assertEquals(MaintenanceRuntimeStatusDisplayPolicy.Level.WARNING, display.onlineLevel)
    }

    @Test
    fun labels_shouldCoverAllDefinedRuntimeStates() {
        assertEquals("待投运", MaintenanceRuntimeStatusDisplayPolicy.onlineStatusLabel(0))
        assertEquals("运营中", MaintenanceRuntimeStatusDisplayPolicy.onlineStatusLabel(1))
        assertEquals("休息中", MaintenanceRuntimeStatusDisplayPolicy.onlineStatusLabel(2))
        assertEquals("维护中", MaintenanceRuntimeStatusDisplayPolicy.onlineStatusLabel(3))
        assertEquals("工作中", MaintenanceRuntimeStatusDisplayPolicy.onlineStatusLabel(4))
        assertEquals("告警", MaintenanceRuntimeStatusDisplayPolicy.errorStatusLabel(1))
        assertEquals("错误", MaintenanceRuntimeStatusDisplayPolicy.errorStatusLabel(2))
    }
}
