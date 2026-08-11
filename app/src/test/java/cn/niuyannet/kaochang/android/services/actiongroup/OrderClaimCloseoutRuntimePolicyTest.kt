package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertEquals
import org.junit.Test

class OrderClaimCloseoutRuntimePolicyTest {

    @Test
    fun normalWorkingDevice_shouldReturnToOperating() {
        assertEquals(
            1,
            orderClaimCloseoutOnlineStatus(
                onlineStatus = 4,
                errorStatus = 0,
                inspectionMode = 0,
                isEnable = 1,
                orderFailed = false
            )
        )
    }

    @Test
    fun faultedWorkingDevice_shouldRemainInMaintenance() {
        assertEquals(
            3,
            orderClaimCloseoutOnlineStatus(
                onlineStatus = 4,
                errorStatus = 2,
                inspectionMode = 0,
                isEnable = 1,
                orderFailed = false
            )
        )
    }

    @Test
    fun failedOrder_shouldEnterMaintenanceEvenWhenDeviceHasNoFaultFlag() {
        assertEquals(
            3,
            orderClaimCloseoutOnlineStatus(
                onlineStatus = 4,
                errorStatus = 0,
                inspectionMode = 0,
                isEnable = 1,
                orderFailed = true
            )
        )
        assertEquals(
            3,
            orderClaimCloseoutOnlineStatus(
                onlineStatus = 1,
                errorStatus = 0,
                inspectionMode = 0,
                isEnable = 1,
                orderFailed = true
            )
        )
    }

    @Test
    fun nonWorkingStatus_shouldRemainUnchanged() {
        assertEquals(
            3,
            orderClaimCloseoutOnlineStatus(
                onlineStatus = 3,
                errorStatus = 0,
                inspectionMode = 0,
                isEnable = 0,
                orderFailed = false
            )
        )
    }
}
