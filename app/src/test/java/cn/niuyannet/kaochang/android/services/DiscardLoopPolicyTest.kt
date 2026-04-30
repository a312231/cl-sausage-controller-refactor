package cn.niuyannet.kaochang.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscardLoopPolicyTest {

    @Test
    fun shouldStopAfterDiscard_shouldContinueAfterSuccessfulDiscardWhenDeviceNormal() {
        assertFalse(
            DiscardLoopPolicy.shouldStopAfterDiscard(
                discardSuccess = true,
                onlineStatus = 1,
                errorStatus = 0
            )
        )
    }

    @Test
    fun shouldStopAfterDiscard_shouldStopWhenDiscardFails() {
        assertTrue(
            DiscardLoopPolicy.shouldStopAfterDiscard(
                discardSuccess = false,
                onlineStatus = 1,
                errorStatus = 0
            )
        )
    }

    @Test
    fun shouldStopAfterDiscard_shouldStopWhenDeviceEnteredMaintenance() {
        assertTrue(
            DiscardLoopPolicy.shouldStopAfterDiscard(
                discardSuccess = true,
                onlineStatus = 3,
                errorStatus = 0
            )
        )
    }

    @Test
    fun shouldStopAfterDiscard_shouldStopWhenDeviceHasError() {
        assertTrue(
            DiscardLoopPolicy.shouldStopAfterDiscard(
                discardSuccess = true,
                onlineStatus = 1,
                errorStatus = 2
            )
        )
    }
}
