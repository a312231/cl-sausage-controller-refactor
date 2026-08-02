package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCleanStartPolicyTest {

    @Test
    fun operatingOrDelivering_shouldRejectSelfClean() {
        assertFalse(SelfCleanStartPolicy.canStart(1, 0, false, 0, 0))
        assertFalse(SelfCleanStartPolicy.canStart(4, 0, false, 0, 0))
    }

    @Test
    fun pendingOrderOrBusyMachine_shouldRejectSelfClean() {
        assertFalse(SelfCleanStartPolicy.canStart(2, 0, true, 0, 0))
        assertFalse(SelfCleanStartPolicy.canStart(2, 1, false, 0, 0))
    }

    @Test
    fun maintenanceWithIdleMachine_shouldAllowSelfClean() {
        assertTrue(SelfCleanStartPolicy.canStart(3, 0, false, 0, 0))
    }
}
