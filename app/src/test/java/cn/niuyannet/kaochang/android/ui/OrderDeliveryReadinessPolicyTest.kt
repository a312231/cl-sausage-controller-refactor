package cn.niuyannet.kaochang.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderDeliveryReadinessPolicyTest {

    @Test
    fun evaluate_shouldAllowNormalOperatingDevice() {
        val result = OrderDeliveryReadinessPolicy.evaluate(
            onlineStatus = 1,
            errorStatus = 0,
            inspectionMode = 0
        )

        assertTrue(result.canStartDelivery)
    }

    @Test
    fun evaluate_shouldBlockMaintenanceDeviceBeforeOut1() {
        val result = OrderDeliveryReadinessPolicy.evaluate(
            onlineStatus = 3,
            errorStatus = 0,
            inspectionMode = 0
        )

        assertFalse(result.canStartDelivery)
        assertTrue(result.reason.contains("维护中"))
    }

    @Test
    fun evaluate_shouldBlockDeviceWithErrorBeforeOut1() {
        val result = OrderDeliveryReadinessPolicy.evaluate(
            onlineStatus = 1,
            errorStatus = 2,
            inspectionMode = 0
        )

        assertFalse(result.canStartDelivery)
        assertTrue(result.reason.contains("设备故障"))
    }

    @Test
    fun evaluate_shouldBlockInspectionModeBeforeOut1() {
        val result = OrderDeliveryReadinessPolicy.evaluate(
            onlineStatus = 1,
            errorStatus = 0,
            inspectionMode = 1
        )

        assertFalse(result.canStartDelivery)
        assertTrue(result.reason.contains("检修模式"))
    }
}
