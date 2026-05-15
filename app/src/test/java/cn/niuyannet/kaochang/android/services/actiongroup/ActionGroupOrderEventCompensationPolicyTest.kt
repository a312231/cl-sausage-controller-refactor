package cn.niuyannet.kaochang.android.services.actiongroup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGroupOrderEventCompensationPolicyTest {

    @Test
    fun shouldPersistPendingEventOnlyWhenErrorStatusMqttFails() {
        assertTrue(ActionGroupOrderEventCompensationPolicy.shouldPersistPendingEvent(status = 5, publishOk = false))

        assertFalse(ActionGroupOrderEventCompensationPolicy.shouldPersistPendingEvent(status = 5, publishOk = true))
        assertFalse(ActionGroupOrderEventCompensationPolicy.shouldPersistPendingEvent(status = 2, publishOk = false))
        assertFalse(ActionGroupOrderEventCompensationPolicy.shouldPersistPendingEvent(status = 6, publishOk = false))
    }
}
