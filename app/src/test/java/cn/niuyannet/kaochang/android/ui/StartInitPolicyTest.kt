package cn.niuyannet.kaochang.android.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class StartInitPolicyTest {

    @Test
    fun canInitializeDevice_shouldNotRequireArucoCornersWhenVisionIsDisabled() {
        val canInitialize = StartInitPolicy.canInitializeDevice(
            forceVisionSuccess = 0,
            hasArucoCorners = false
        )

        assertTrue(canInitialize)
    }
}
