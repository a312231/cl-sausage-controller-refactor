package cn.niuyannet.kaochang.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartInitIdentityTest {

    @Test
    fun isActivationCode_shouldAcceptGeneratedActivationCode() {
        assertTrue(StartInitIdentity.isActivationCode("KC-3APZ-VSBX-KQC3-WMMY"))
    }

    @Test
    fun isActivationCode_shouldRejectDeviceCode() {
        assertFalse(StartInitIdentity.isActivationCode("41e8b5898e359459"))
    }
}
