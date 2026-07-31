package cn.niuyannet.kaochang.android.fragments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceBoxOperationPolicyTest {
    @Test
    fun inventoryWrite_shouldRequireAlgorithmOffAndMechanicalIdle() {
        assertFalse(MaintenanceBoxOperationPolicy.evaluate(isEnable = 1, moveStatus = 0).allowed)
        assertFalse(MaintenanceBoxOperationPolicy.evaluate(isEnable = 0, moveStatus = 2).allowed)
        assertTrue(MaintenanceBoxOperationPolicy.evaluate(isEnable = 0, moveStatus = 0).allowed)
    }
}
